package com.aircraftapi.service;

import com.aircraftapi.client.OpenSkyClient;
import com.aircraftapi.dto.AircraftResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Service
public class AircraftService {

    private static final Logger log = LoggerFactory.getLogger(AircraftService.class);
    private static final double EARTH_RADIUS_KM = 6371.0;

    private final OpenSkyClient openSkyClient;
    private final Cache<String, Object> caffeineCache;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${opensky.cache-ttl-seconds}")
    private int cacheTtlSeconds;

    public AircraftService(OpenSkyClient openSkyClient,
                           Cache<String, Object> caffeineCache,
                           RedisTemplate<String, Object> redisTemplate) {
        this.openSkyClient = openSkyClient;
        this.caffeineCache = caffeineCache;
        this.redisTemplate = redisTemplate;
    }

    public List<AircraftResponse> findAircraftWithinRadius(double lat, double lng, double radius) {
        String cacheKey = buildCacheKey(lat, lng, radius);

        List<AircraftResponse> cached = getFromCaffeine(cacheKey);
        if (cached != null) {
            log.info("L1 cache HIT for key={}", cacheKey);
            return cached;
        }

        cached = getFromRedis(cacheKey);
        if (cached != null) {
            log.info("L2 cache HIT for key={}", cacheKey);
            caffeineCache.put(cacheKey, cached);
            return cached;
        }

        log.info("Cache MISS — calling OpenSky for key={}", cacheKey);
        List<AircraftResponse> results = fetchFromOpenSky(lat, lng, radius);

        if (!results.isEmpty()) {
            caffeineCache.put(cacheKey, results);
            redisTemplate.opsForValue().set(cacheKey, results, Duration.ofSeconds(cacheTtlSeconds));
        }

        return results;
    }

    private String buildCacheKey(double lat, double lng, double radius) {
        return String.format("aircraft:%.2f:%.2f:%.1f", lat, lng, radius);
    }

    @SuppressWarnings("unchecked")
    private List<AircraftResponse> getFromCaffeine(String key) {
        Object value = caffeineCache.getIfPresent(key);
        return value != null ? (List<AircraftResponse>) value : null;
    }

    @SuppressWarnings("unchecked")
    private List<AircraftResponse> getFromRedis(String key) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            return value != null ? (List<AircraftResponse>) value : null;
        } catch (Exception e) {
            log.warn("Redis read failed, falling through to OpenSky: {}", e.getMessage());
            return null;
        }
    }

    private List<AircraftResponse> fetchFromOpenSky(double lat, double lng, double radius) {
        double[] bbox = computeBoundingBox(lat, lng, radius);
        List<List<Object>> states = openSkyClient.fetchAircraftInBoundingBox(bbox[0], bbox[1], bbox[2], bbox[3]);

        if (states.isEmpty()) return Collections.emptyList();

        return states.stream()
                .filter(state -> hasValidPosition(state))
                .map(state -> {
                    double aircraftLat = ((Number) state.get(6)).doubleValue();
                    double aircraftLon = ((Number) state.get(5)).doubleValue();
                    double distance = haversine(lat, lng, aircraftLat, aircraftLon);
                    return new Object[]{ state, distance };
                })
                .filter(pair -> (double) pair[1] <= radius)
                .map(pair -> {
                    @SuppressWarnings("unchecked")
                    List<Object> state = (List<Object>) pair[0];
                    return AircraftResponse.fromOpenSkyState(state, (double) pair[1]);
                })
                .sorted((a, b) -> Double.compare(a.distanceKm(), b.distanceKm()))
                .toList();
    }

    private double[] computeBoundingBox(double lat, double lng, double radiusKm) {
        double latDelta = radiusKm / EARTH_RADIUS_KM * (180.0 / Math.PI);
        double lngDelta = radiusKm / EARTH_RADIUS_KM * (180.0 / Math.PI) / Math.cos(Math.toRadians(lat));
        return new double[]{ lat - latDelta, lat + latDelta, lng - lngDelta, lng + lngDelta };
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private boolean hasValidPosition(List<Object> state) {
        return state.size() > 6 && state.get(6) != null && state.get(5) != null;
    }
}
