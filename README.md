# SkyWatch — Real-Time Flight Tracking Engine

A backend system inspired by FlightRadar24, built to explore what it takes to track hundreds of aircraft simultaneously in real time. Continuously ingests live ADS-B transponder data, maintains a live state store per aircraft, detects anomalous flight patterns, and pushes alerts to subscribers over WebSocket — all while serving low-latency on-demand radius searches.

```
GET  /api/v1/aircraft?lat=1.35&lng=103.82&radius=150  →  94 live aircraft, p95: 7ms
WS   /topic/alerts                                     →  HOLDING_PATTERN detected: SIA221 over Changi
GET  /api/v1/alerts                                    →  last 100 anomaly events
```

---

## The Problem

FlightRadar24 tracks 180,000+ flights per day across a global network of ADS-B receivers. The core engineering challenge isn't fetching aircraft positions — it's doing something useful with a continuous stream of position updates at scale:

- Maintaining live state for every tracked aircraft without hitting the data source on every read
- Detecting patterns (holding, go-around, diversion) in real time as positions arrive
- Pushing alerts to thousands of concurrent subscribers with sub-second latency
- Surviving upstream API failures and Redis outages without cascading to users

This project tackles each of those problems with production-grade patterns on a single-node setup designed to scale horizontally.

---

## Architecture

```
OpenSky Network API  (ADS-B transponder feed, polled every 10s)
         │
         ▼
  IngestionService   (@Scheduled background worker)
         │
         ├── Redis HASH   aircraft:state:{icao24}     live position per aircraft (TTL 5 min)
         ├── Redis LIST   aircraft:history:{icao24}   last 30 positions per aircraft (TTL 10 min)
         ├── PostgreSQL   position_history             full trail with PostGIS geometry index
         │
         └── Pattern Detection Engine
                  ├── HoldingPatternDetector    heading rotation >= 330° in tight radius
                  ├── GoAroundDetector          descent below 1000m → sudden climb > 2 m/s
                  └── DiversionDetector         consistent heading → sustained change > 45°
                           │
                           ▼
                   AlertBroadcaster  →  WebSocket /topic/alerts   (live push)
                           │
                           └── PostgreSQL flight_alerts           (persisted history)

On-Demand REST (unchanged, runs in parallel):
Client → RateLimitFilter → AircraftController → AircraftService
                                                      ├── L1: Caffeine   ~2ms
                                                      ├── L2: Redis      ~8ms
                                                      └── OpenSky API   ~400ms
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
| Data source | OpenSky Network API | Free live ADS-B transponder data, global coverage |
| Load testing | K6 | Scriptable scenarios, custom p95/p99 metrics |
| Local infra | Docker Compose | PostGIS + Redis, reproducible in one command |

---

## Key Engineering Decisions

**Two-layer cache with different eviction strategies**
Caffeine (L1) is private to each JVM instance — no network, no serialization, ~2ms. Redis (L2) is shared across all instances — survives restarts, consistent under horizontal scale, ~8ms. Without L1, every cache hit would still pay a network round-trip to Redis. Without L2, a second app instance would have a cold cache and hammer OpenSky. The two layers together mean warm reads are always local and cold reads are shared — critical when OpenSky's free tier caps at 400 requests/day.

**Redis as the live state store, not a cache**
The ingestion service writes each aircraft's latest position to a Redis HASH (TTL 5 min) and appends to a position buffer LIST (last 30 positions). This separates live state from query caching — the state store is the source of truth for what's flying right now, while the query cache serves repeated radius searches. This mirrors how production systems like FlightRadar24 maintain aircraft state: a fast read layer that's continuously written to, separate from their serving layer.

**Pattern detection as a stateless rule engine**
Each detector receives the current position and history buffer and returns an `Optional<AlertMessage>` — no shared mutable state, no database reads in the hot path. Cooldowns are tracked in a per-instance `ConcurrentHashMap`. This means detectors can be added, removed, or tuned without touching the ingestion pipeline, and tested in complete isolation.

**Bounding box query → Haversine filter in Java**
OpenSky's API only accepts rectangular bounding boxes. The service computes the smallest enclosing box for a circle query, fetches from OpenSky, then applies the Haversine formula in Java to trim to the exact radius. This avoids needing PostGIS for the serving path (PostGIS is used for the position trail) and means the on-demand endpoint works on any standard Postgres instance.

**Fail-open on infrastructure unavailability**
Both the cache layer and rate limiter treat Redis failures as non-fatal. Cache miss falls through to OpenSky. Rate limiter lets the request through rather than blocking all traffic because Redis is down. This is a deliberate trade-off: a degraded but available service is better than a hard dependency on every infrastructure component being healthy.

**Redis INCR for distributed rate limiting**
`INCR` is atomic in Redis — no locks, no race conditions under concurrent load. The key includes the current minute epoch (`rate:{ip}:{minute}`), so the window resets automatically and expired keys are cleaned up by Redis TTL. No third-party rate-limit library, no distributed coordination overhead.

---

## How It Scales

The app is stateless by design. Adding more instances works because:

- **Live state** lives in Redis, not in-process — every instance reads and writes the same aircraft state
- **Rate limiting** is per-IP across all instances — Redis INCR is atomic regardless of how many app servers increment it
- **Ingestion** is the one stateful concern — in production, you'd run one ingestion leader (via distributed lock or a dedicated worker) and have multiple serving instances read from Redis

To scale the ingestion pipeline itself, the natural next step is replacing the `@Scheduled` poller with a message queue (Kafka or SQS): a fleet of ADS-B receivers publish position updates, workers consume and process them in parallel. The pattern detectors are already structured to work that way — each is a stateless function over a position stream.

---

## API Reference

### Search aircraft by radius

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
    "icao24": "71be27",
    "callsign": "KAL081",
    "originCountry": "Republic of Korea",
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

### Recent anomaly alerts

```
GET /api/v1/alerts           last 100 alerts across all aircraft
GET /api/v1/alerts/{icao24}  alerts for a specific aircraft
```

### WebSocket — live alert stream

```
Connect:    ws://localhost:8080/ws  (SockJS + STOMP)
Subscribe:  /topic/alerts           all alerts
            /topic/alerts/{icao24}  alerts for one aircraft
```

```json
{
  "type": "GO_AROUND",
  "icao24": "7c6b2b",
  "callsign": "QFA431",
  "latitude": 1.359,
  "longitude": 103.989,
  "altitudeMeters": 487.0,
  "description": "Go-around detected: was descending to 312m, now climbing at 4.2 m/s",
  "detectedAt": "2026-03-15T10:30:00Z"
}
```

Alert types: `HOLDING_PATTERN`, `GO_AROUND`, `DIVERSION`

---

## Running Locally

**Prerequisites:** Java 17+, Maven, Docker

```bash
# Start PostGIS and Redis
docker compose up -d

# Run — Flyway migrations run automatically on startup
mvn spring-boot:run
```

```bash
# Search aircraft over Singapore
curl "http://localhost:8080/api/v1/aircraft?lat=1.35&lng=103.82&radius=150"

# View detected anomalies
curl "http://localhost:8080/api/v1/alerts"

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
├── AircraftApiApplication.java
├── client/
│   ├── OpenSkyClient.java              WebClient, fetches ADS-B positions
│   └── OpenSkyResponse.java            Raw OpenSky API response model
├── config/
│   ├── CacheConfig.java                Caffeine (L1) + RedisTemplate (L2) beans
│   └── WebSocketConfig.java            STOMP endpoint, /topic broker
├── controller/
│   ├── AircraftController.java         GET /api/v1/aircraft
│   └── AlertController.java            GET /api/v1/alerts
├── detector/
│   ├── PatternDetector.java            Interface: detect(current, history)
│   ├── HoldingPatternDetector.java     Circular flight path detection
│   ├── GoAroundDetector.java           Aborted landing detection
│   └── DiversionDetector.java          Sustained heading change detection
├── domain/
│   └── FlightAlert.java                JPA entity → flight_alerts table
├── dto/
│   ├── AircraftResponse.java           On-demand search response
│   ├── AlertMessage.java               WebSocket push payload
│   └── PositionUpdate.java             Internal position event (includes verticalRate)
├── exception/
│   └── GlobalExceptionHandler.java     @RestControllerAdvice, consistent error JSON
├── filter/
│   └── RateLimitFilter.java            Redis INCR rate limiting, 100 req/min per IP
├── repository/
│   └── FlightAlertRepository.java      Spring Data JPA
└── service/
    ├── AircraftService.java            On-demand: Caffeine → Redis → OpenSky
    ├── AlertBroadcaster.java           STOMP push to /topic/alerts
    ├── IngestionService.java           @Scheduled poller, pattern detection pipeline
    └── LiveStateStore.java             Redis HASH (state) + LIST (history) per aircraft

src/main/resources/
├── application.yml
└── db/migration/
    ├── V1__create_aircraft_snapshots.sql
    ├── V2__add_position_history_and_alerts.sql
    └── V3__drop_aircraft_snapshots.sql

k6/
└── load-test.js                        Smoke + ramp-up scenarios, custom metrics
```

---

## What's Next

- [ ] Flight trail replay: `GET /api/v1/aircraft/{icao24}/trail?minutes=30` — query PostGIS position history
- [ ] Live stats: `GET /api/v1/stats` — tracked aircraft count, alerts per hour, regions monitored
- [ ] Replace `@Scheduled` poller with Kafka consumer — enables multi-receiver ingestion at scale
- [ ] Airspace congestion: `GET /api/v1/congestion` — grid cell density from position history
- [ ] Deploy to Railway with Supabase (PostGIS) + Upstash (Redis)
