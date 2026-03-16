# Contrail - Real-Time Flight Tracking Engine

A backend system inspired by FlightRadar24, built to explore what it takes to track thousands of aircraft simultaneously in real time. Continuously ingests live ADS-B transponder data, maintains a live state store per aircraft, detects anomalous flight patterns, and pushes alerts to subscribers over WebSocket, all while serving low-latency on-demand radius searches.

```
GET  /api/v1/aircraft?lat=40.6&lng=-73.8&radius=200    ->  live aircraft sorted by distance, p95: 7ms
GET  /api/v1/aircraft/a1b2c3                            ->  current position from Redis, sub-ms
GET  /api/v1/aircraft/a1b2c3/trail?minutes=30           ->  position trail from PostGIS
GET  /api/v1/aircraft/congestion?lat=40.6&lng=-73.8&radius=200  ->  congestion: now vs last hour
GET  /api/v1/alerts?type=GO_AROUND                      ->  filtered anomaly history
GET  /api/v1/stats                                      ->  tracked count, alerts/hr
WS   /topic/alerts                                      ->  HOLDING_PATTERN detected: UAL447 over JFK
```

---

## The Problem

FlightRadar24 tracks 180,000+ flights per day across a global network of ADS-B receivers. The core engineering challenge isn't fetching aircraft positions - it's doing something useful with a continuous stream of position updates at scale:

- Maintaining live state for every tracked aircraft without hitting the data source on every read
- Detecting patterns (holding, go-around, diversion) in real time as positions arrive
- Pushing alerts to thousands of concurrent subscribers with sub-second latency
- Surviving upstream API failures and Redis outages without cascading to users

This project tackles each of those problems with production-grade patterns on a single-node setup designed to scale horizontally.

---

## Architecture

```
OpenSky Network API  (ADS-B transponder feed, polled every 30s, OAuth2 authenticated)
         |
         v
  IngestionService   (@Scheduled background worker)
         |
         +-- Redis HASH   aircraft:state:{icao24}     live position per aircraft (TTL 5 min)
         +-- Redis LIST   aircraft:history:{icao24}   last 30 positions per aircraft (TTL 10 min)
         +-- PostgreSQL   position_history             full trail with PostGIS geometry index
         |
         +-- Pattern Detection Engine
                  +-- HoldingPatternDetector    heading rotation >= 330 degrees in tight radius
                  +-- GoAroundDetector          descent below 1000m then sudden climb > 2 m/s
                  +-- DiversionDetector         consistent heading then sustained change > 45 degrees
                           |
                           v
                   AlertBroadcaster  ->  WebSocket /topic/alerts   (live push)
                           |
                           +-- PostgreSQL flight_alerts           (persisted history)

On-Demand REST (runs in parallel):
Client -> RateLimitFilter -> AircraftController -> AircraftService
                                                      +-- L1: Caffeine   ~2ms
                                                      +-- L2: Redis      ~8ms
                                                      +-- OpenSky API   ~400ms
```

---

## Performance

Load tested with K6 (50 concurrent users, 2-minute ramp-up across 5 global regions):

| Metric | Result | Target |
|--------|--------|--------|
| p95 latency | **7ms** | < 2000ms |
| p99 latency | **10ms** | |
| Throughput | **70 req/s** | |
| Error rate | **0.00%** | < 10% |
| Cache hit latency | **2-8ms** | |
| OpenSky miss latency | **~500ms** | |

Cache hit rate under sustained load approaches 100% within the 10s TTL window, decoupling API throughput from OpenSky's upstream rate limits entirely.

---

## Tech Stack

| Layer | Technology | Why |
|-------|-----------|-----|
| Runtime | Java 17, Spring Boot 3 | LTS, production standard |
| Real-time push | Spring WebSocket (STOMP) | Topic-based subscriptions, per-aircraft channels |
| Event processing | Spring @Scheduled + custom rule engine | Stateless detectors, cooldown via in-memory map |
| Cache L1 | Caffeine | Sub-millisecond in-JVM cache, zero network overhead |
| Cache L2 | Redis (Lettuce) | Distributed live state, survives restarts, shared across instances |
| Rate limiting | Redis INCR + EXPIRE | Atomic counter per IP per minute, no library needed |
| Geospatial | PostgreSQL + PostGIS | GIST-indexed geometry, ST_DWithin for spatial queries |
| Schema management | Flyway | Versioned migrations, same scripts run on every environment |
| Data source | OpenSky Network API | Live ADS-B transponder data, OAuth2 authenticated, US coverage |
| Load testing | K6 | Scriptable scenarios, custom p95/p99 metrics |
| Local infra | Docker Compose | PostGIS + Redis, reproducible in one command |

---

## Key Engineering Decisions

**Two-layer cache with different eviction strategies**
Caffeine (L1) is private to each JVM instance - no network, no serialization, ~2ms. Redis (L2) is shared across all instances - survives restarts, consistent under horizontal scale, ~8ms. Without L1, every cache hit would still pay a network round-trip to Redis. Without L2, a second app instance would have a cold cache and hammer OpenSky. The two layers together mean warm reads are always local and cold reads are shared, which is critical when OpenSky caps authenticated users at 4,000 requests/day.

**Redis as the live state store, not a cache**
The ingestion service writes each aircraft's latest position to a Redis HASH (TTL 5 min) and appends to a position buffer LIST (last 30 positions). This separates live state from query caching - the state store is the source of truth for what's flying right now, while the query cache serves repeated radius searches. This mirrors how production systems like FlightRadar24 maintain aircraft state: a fast read layer that's continuously written to, separate from their serving layer.

**Pattern detection as a stateless rule engine**
Each detector receives the current position and history buffer and returns an `Optional<AlertMessage>` - no shared mutable state, no database reads in the hot path. Cooldowns are tracked in a per-instance `ConcurrentHashMap`. This means detectors can be added, removed, or tuned without touching the ingestion pipeline, and tested in complete isolation.

**Bounding box query into Haversine filter in Java**
OpenSky's API only accepts rectangular bounding boxes. The service computes the smallest enclosing box for a circle query, fetches from OpenSky, then applies the Haversine formula in Java to trim to the exact radius. This avoids needing PostGIS for the serving path (PostGIS is used for the position trail) and means the on-demand endpoint works on any standard Postgres instance.

**Fail-open on infrastructure unavailability**
Both the cache layer and rate limiter treat Redis failures as non-fatal. Cache miss falls through to OpenSky. Rate limiter lets the request through rather than blocking all traffic because Redis is down. This is a deliberate trade-off: a degraded but available service is better than a hard dependency on every infrastructure component being healthy.

**Redis INCR for distributed rate limiting**
`INCR` is atomic in Redis - no locks, no race conditions under concurrent load. The key includes the current minute epoch (`rate:{ip}:{minute}`), so the window resets automatically and expired keys are cleaned up by Redis TTL. No third-party rate-limit library, no distributed coordination overhead.

---

## How It Scales

The app is stateless by design. Adding more instances works because:

- **Live state** lives in Redis, not in-process - every instance reads and writes the same aircraft state
- **Rate limiting** is per-IP across all instances - Redis INCR is atomic regardless of how many app servers increment it
- **Ingestion** is the one stateful concern - in production, you'd run one ingestion leader (via distributed lock or a dedicated worker) and have multiple serving instances read from Redis

To scale the ingestion pipeline itself, the natural next step is replacing the `@Scheduled` poller with a message queue (Kafka or SQS): a fleet of ADS-B receivers publish position updates, workers consume and process them in parallel. The pattern detectors are already structured to work that way - each is a stateless function over a position stream.

---

## API Reference

### 1. Search aircraft by radius

```
GET /api/v1/aircraft?lat={lat}&lng={lng}&radius={km}
```

| Parameter | Type | Constraints |
|-----------|------|-------------|
| `lat` | double | -90 to 90 |
| `lng` | double | -180 to 180 |
| `radius` | double | > 0 (km) |

```json
[
  {
    "icao24": "a1b2c3",
    "callsign": "UAL447",
    "originCountry": "United States",
    "latitude": 40.748,
    "longitude": -74.024,
    "altitudeMeters": 4914.9,
    "velocityMs": 133.93,
    "heading": 163.03,
    "onGround": false,
    "distanceKm": 5.81
  }
]
```

Response headers: `X-Total-Count`, `X-Rate-Limit-Remaining`, `Retry-After` (on 429)

---

### 2. Get current state of one aircraft

Reads directly from the Redis live state store - sub-millisecond response, no database hit.

```
GET /api/v1/aircraft/{icao24}
```

```json
{
  "icao24": "a1b2c3",
  "callsign": "UAL447",
  "latitude": 40.641,
  "longitude": -73.778,
  "altitudeMeters": 3048.0,
  "heading": 142.0,
  "velocityMs": 241.3,
  "verticalRate": -4.2,
  "onGround": false,
  "lastSeenMs": 1710494400000
}
```

Returns `404` if the aircraft has not been seen in the last 5 minutes.

---

### 3. Flight trail (position history)

Returns every recorded position for an aircraft over the last N minutes, queried from PostgreSQL using the PostGIS spatial index. Useful for replaying a flight path.

```
GET /api/v1/aircraft/{icao24}/trail?minutes=30
```

| Parameter | Type | Default | Constraints |
|-----------|------|---------|-------------|
| `minutes` | int | 30 | 1 to 1440 |

```json
[
  { "latitude": 40.64, "longitude": -73.78, "altitudeMeters": 152.0, "heading": 138.0, "velocityMs": 78.2, "capturedAt": "2026-03-15T10:00:00Z" },
  { "latitude": 40.68, "longitude": -73.82, "altitudeMeters": 610.0, "heading": 140.0, "velocityMs": 142.6, "capturedAt": "2026-03-15T10:00:10Z" }
]
```

Response header: `X-Point-Count`

---

### 4. Airspace congestion

Counts distinct aircraft seen within a radius in the last 5 minutes and the last hour. Uses `ST_DWithin` against the PostGIS-indexed `position_history` table.

```
GET /api/v1/aircraft/congestion?lat={lat}&lng={lng}&radius={km}
```

```json
{
  "latitude": 40.6,
  "longitude": -73.8,
  "radiusKm": 200.0,
  "aircraftNow": 312,
  "aircraftLastHour": 487
}
```

---

### 5. Anomaly alerts

```
GET /api/v1/alerts                                          last 100 alerts
GET /api/v1/alerts?type=GO_AROUND                          filter by type
GET /api/v1/alerts?since=2026-03-15T00:00:00Z              filter by time
GET /api/v1/alerts?type=HOLDING_PATTERN&since=2026-03-15T00:00:00Z
GET /api/v1/alerts/{icao24}                                alerts for one aircraft
```

Alert types: `HOLDING_PATTERN`, `GO_AROUND`, `DIVERSION`

```json
{
  "id": 42,
  "icao24": "a1b2c3",
  "callsign": "UAL447",
  "alertType": "GO_AROUND",
  "latitude": 40.641,
  "longitude": -73.778,
  "altitudeMeters": 487.0,
  "description": "Go-around detected: was descending to 312m, now climbing at 4.2 m/s",
  "detectedAt": "2026-03-15T10:30:00Z"
}
```

---

### 6. Live system stats

```
GET /api/v1/stats
```

```json
{
  "trackedAircraftLast5min": 2847,
  "alertsLastHour": 12,
  "totalAlerts": 156
}
```

---

### 7. WebSocket - live alert stream

```
Connect:    ws://localhost:8080/ws  (SockJS + STOMP)
Subscribe:  /topic/alerts           all alerts
            /topic/alerts/{icao24}  alerts for one aircraft
```

```json
{
  "type": "GO_AROUND",
  "icao24": "a1b2c3",
  "callsign": "UAL447",
  "latitude": 40.641,
  "longitude": -73.778,
  "altitudeMeters": 487.0,
  "description": "Go-around detected: was descending to 312m, now climbing at 4.2 m/s",
  "detectedAt": "2026-03-15T10:30:00Z"
}
```

---

## Running Locally

**Prerequisites:** Java 17+, Maven, Docker

```bash
# Start PostGIS and Redis
docker compose up -d

# Run - Flyway migrations run automatically on startup
mvn spring-boot:run
```

```bash
# Search aircraft near JFK Airport (200km radius)
curl "http://localhost:8080/api/v1/aircraft?lat=40.6&lng=-73.8&radius=200"

# Current state of one aircraft (replace with a real ICAO24 from the search above)
curl "http://localhost:8080/api/v1/aircraft/a1b2c3"

# Position trail for the last 30 minutes
curl "http://localhost:8080/api/v1/aircraft/a1b2c3/trail?minutes=30"

# Airspace congestion near JFK
curl "http://localhost:8080/api/v1/aircraft/congestion?lat=40.6&lng=-73.8&radius=200"

# Recent alerts (filter by type or time)
curl "http://localhost:8080/api/v1/alerts"
curl "http://localhost:8080/api/v1/alerts?type=GO_AROUND"

# System stats
curl "http://localhost:8080/api/v1/stats"

# Health check (DB + Redis status)
curl "http://localhost:8080/actuator/health"
```

**Run load test**

```bash
brew install k6
k6 run k6/load-test.js
```

---

## Project Structure

```
src/main/java/com/aircraftapi/
+-- AircraftApiApplication.java
+-- client/
|   +-- OpenSkyClient.java              WebClient + OAuth2, fetches ADS-B positions
|   +-- OpenSkyResponse.java            Raw OpenSky API response model
+-- config/
|   +-- CacheConfig.java                Caffeine (L1) + RedisTemplate (L2) beans
|   +-- WebSocketConfig.java            STOMP endpoint, /topic broker
+-- controller/
|   +-- AircraftController.java         GET /api/v1/aircraft, /{icao24}, /{icao24}/trail, /congestion
|   +-- AlertController.java            GET /api/v1/alerts, /stats
+-- detector/
|   +-- PatternDetector.java            Interface: detect(current, history)
|   +-- HoldingPatternDetector.java     Circular flight path detection
|   +-- GoAroundDetector.java           Aborted landing detection
|   +-- DiversionDetector.java          Sustained heading change detection
+-- domain/
|   +-- FlightAlert.java                JPA entity, flight_alerts table
+-- dto/
|   +-- AircraftResponse.java           On-demand radius search response
|   +-- AircraftStateResponse.java      Single aircraft live state (from Redis)
|   +-- AlertMessage.java               WebSocket push payload
|   +-- CongestionResponse.java         Airspace congestion counts
|   +-- PositionUpdate.java             Internal position event (includes verticalRate)
|   +-- StatsResponse.java              System-wide stats
|   +-- TrailPointResponse.java         One point in a flight trail
+-- exception/
|   +-- GlobalExceptionHandler.java     @RestControllerAdvice, consistent error JSON
+-- filter/
|   +-- RateLimitFilter.java            Redis INCR rate limiting, 20 req/min per IP
+-- repository/
|   +-- FlightAlertRepository.java      Spring Data JPA
+-- service/
    +-- AircraftService.java            On-demand: Caffeine -> Redis -> OpenSky
    +-- AlertBroadcaster.java           STOMP push to /topic/alerts
    +-- IngestionService.java           @Scheduled poller, pattern detection pipeline
    +-- LiveStateStore.java             Redis HASH (state) + LIST (history) per aircraft
    +-- StatsService.java               Trail, stats, and congestion queries (PostGIS)

src/main/resources/
+-- application.yml
+-- db/migration/
    +-- V1__create_aircraft_snapshots.sql
    +-- V2__add_position_history_and_alerts.sql
    +-- V3__drop_aircraft_snapshots.sql

k6/
+-- load-test.js                        Smoke + ramp-up scenarios, custom metrics
```

