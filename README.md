# Aircraft Tracker API

A high-throughput geospatial search service that returns live aircraft positions within a given radius. Built to demonstrate production-grade backend engineering patterns: multi-layer caching, distributed rate limiting, and load-tested performance under concurrent traffic.

```
GET /api/v1/aircraft?lat=1.35&lng=103.82&radius=150
  94 live aircraft over Singapore, sorted by distance, p95 latency: 7ms
```

---

## Architecture

```
Client Request
      │
      ▼
 Rate Limiter          Redis INCR: 100 req/min per IP, atomic + distributed
      │
      ▼
 Controller            Validates params, returns ResponseEntity with headers
      │
      ▼
 Service Layer
      │
      ├── L1: Caffeine  In-JVM cache, ~2ms, private per instance
      ├── L2: Redis     Distributed cache, ~8ms, shared across instances
      └── OpenSky API   External HTTP call, ~300-500ms, only on cache miss
                        ↓
                   Haversine filter → sort by distance → return DTO
```

**Why two cache layers?**
Caffeine eliminates network overhead for repeat queries on the same instance (~2ms vs ~8ms). Redis shares warm cache state across all instances and survives restarts — critical when OpenSky's free tier caps at 400 requests/day.

---

## Performance

Load tested with K6 (50 concurrent users, 2-minute ramp):

| Metric | Result | Target |
|--------|--------|--------|
| p95 latency | **7ms** | < 2000ms |
| p99 latency | **10ms** | |
| Throughput | **70 req/s** | |
| Error rate | **0.00%** | < 10% |
| Cache hit latency | **2-8ms** | |
| OpenSky miss latency | **~500ms** | |

Cache hit rate under sustained load approaches 100% within the 10s TTL window, effectively decoupling API throughput from OpenSky's rate limits.

---

## Tech Stack

| Layer | Technology | Why |
|-------|-----------|-----|
| Runtime | Java 17, Spring Boot 3 | LTS, production standard |
| Web | Spring MVC + WebClient | Sync REST layer, async HTTP client for OpenSky |
| Cache L1 | Caffeine | Sub-millisecond in-JVM cache, zero network overhead |
| Cache L2 | Redis (Lettuce) | Distributed, survives restarts, shared across instances |
| Rate limiting | Redis INCR + EXPIRE | Atomic counter per IP per minute, no library needed |
| Database | PostgreSQL + Flyway | Versioned schema migrations, historical snapshots |
| Data source | OpenSky Network API | Free live aircraft transponder data (ADS-B) |
| Load testing | K6 | Scriptable scenarios, p95/p99 metrics |
| Local infra | Docker Compose | Reproducible dev environment |

---

## Key Engineering Decisions

**Bounding box → radius in Java, not SQL**
OpenSky only accepts rectangular queries. We compute the smallest bounding box that contains the search circle, fetch from OpenSky, then apply the Haversine formula in Java to trim to the exact radius. Avoids PostGIS dependency — works on any standard Postgres instance (including Supabase free tier).

**Redis INCR for rate limiting**
Redis `INCR` is atomic, with no race conditions under concurrent load. Storing `rate:{ip}:{minute}` as a plain integer with a 60s TTL means zero cleanup overhead and natural window resets. No third-party rate-limit library needed.

**Cache key rounding**
Cache keys round lat/lng to 2 decimal places (~1.1km grid). `lat=1.3521` and `lat=1.3529` resolve to the same key, preventing cache misses from floating-point variance while keeping granularity meaningful for aircraft search.

**Fail-open on Redis unavailability**
Both the cache layer and rate limiter catch Redis exceptions and continue rather than returning 500. Cache miss falls through to OpenSky. When Redis is down, the rate limiter skips rather than blocking all traffic.

**No Lombok**
Lombok's annotation processor is incompatible with Java 25 (accesses restricted internal compiler APIs). DTOs use Java Records (introduced in Java 16): immutable, concise, and idiomatic. Entities use explicit constructors and getters, making the JPA contract explicit.

---

## API Reference

### Search Aircraft

```
GET /api/v1/aircraft
```

**Query Parameters**

| Parameter | Type | Required | Constraints | Description |
|-----------|------|----------|-------------|-------------|
| `lat` | double | Yes | -90 to 90 | Center latitude |
| `lng` | double | Yes | -180 to 180 | Center longitude |
| `radius` | double | Yes | > 0 | Search radius in kilometres |

**Response**

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

**Response Headers**

| Header | Description |
|--------|-------------|
| `X-Total-Count` | Number of aircraft in response |
| `X-Rate-Limit-Remaining` | Requests remaining in current 60s window |
| `Retry-After` | Seconds until limit resets (only on 429) |

**Status Codes**

| Code | Reason |
|------|--------|
| 200 | Success |
| 400 | Missing or invalid parameter |
| 429 | Rate limit exceeded (100 req/min per IP) |

**Error Response**

```json
{
  "timestamp": "2026-03-14T10:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "lat must be <= 90",
  "path": "/api/v1/aircraft"
}
```

---

## Running Locally

**Prerequisites:** Java 17+, Maven, Docker

```bash
# Start PostgreSQL and Redis
docker compose up -d

# Run the app (Flyway runs migrations automatically on startup)
mvn spring-boot:run
```

```bash
# Search aircraft over New York
curl "http://localhost:8080/api/v1/aircraft?lat=40.7&lng=-74.0&radius=100"

# Health check (shows DB + Redis status)
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
├── AircraftApiApplication.java     Entry point, enables scheduling
├── client/
│   ├── OpenSkyClient.java          WebClient HTTP caller, bbox fetch
│   └── OpenSkyResponse.java        Raw API response model
├── config/
│   └── CacheConfig.java            Caffeine bean + RedisTemplate bean
├── controller/
│   └── AircraftController.java     GET /api/v1/aircraft, param validation
├── domain/
│   └── AircraftSnapshot.java       JPA entity → aircraft_snapshots table
├── dto/
│   └── AircraftResponse.java       Java record, API response shape
├── exception/
│   └── GlobalExceptionHandler.java @RestControllerAdvice, consistent errors
├── filter/
│   └── RateLimitFilter.java        Servlet filter, Redis INCR rate limiting
└── service/
    └── AircraftService.java        L1→L2→OpenSky, Haversine, cache writes

src/main/resources/
├── application.yml
└── db/migration/
    └── V1__create_aircraft_snapshots.sql

k6/
└── load-test.js                    Smoke + ramp-up scenarios
```

---

## What's Next

- [ ] `GET /api/v1/congestion?region=JFK` — detect airspace congestion by grid cell density
- [ ] `@Scheduled` snapshot job — persist aircraft positions to PostgreSQL every 60s
- [ ] Flight path anomaly detection — compare actual vs expected heading over time
- [ ] Deploy to Railway.app with Supabase (Postgres) + Upstash (Redis)
