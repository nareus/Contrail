package com.aircraftapi.service;

import com.aircraftapi.dto.PositionUpdate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class LiveStateStore {

    private static final Logger log = LoggerFactory.getLogger(LiveStateStore.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${ingestion.position-buffer-size:30}")
    private int bufferSize;

    public LiveStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void updateState(PositionUpdate update) {
        String key = "aircraft:state:" + update.icao24();
        Map<String, String> fields = Map.of(
                "lat",          String.valueOf(update.latitude()),
                "lon",          String.valueOf(update.longitude()),
                "alt",          update.altitudeMeters() != null ? String.valueOf(update.altitudeMeters()) : "",
                "heading",      update.heading() != null ? String.valueOf(update.heading()) : "",
                "velocity",     update.velocityMs() != null ? String.valueOf(update.velocityMs()) : "",
                "verticalRate", update.verticalRate() != null ? String.valueOf(update.verticalRate()) : "",
                "onGround",     String.valueOf(update.onGround()),
                "callsign",     update.callsign() != null ? update.callsign() : "",
                "lastSeen",     String.valueOf(System.currentTimeMillis())
        );
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, Duration.ofMinutes(5));
    }

    public void appendToHistory(PositionUpdate update) {
        String key = "aircraft:history:" + update.icao24();
        try {
            String json = objectMapper.writeValueAsString(update);
            redis.opsForList().leftPush(key, json);
            redis.opsForList().trim(key, 0, bufferSize - 1);
            redis.expire(key, Duration.ofMinutes(10));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize position update for {}: {}", update.icao24(), e.getMessage());
        }
    }

    public List<PositionUpdate> getHistory(String icao24) {
        String key = "aircraft:history:" + icao24;
        List<String> raw = redis.opsForList().range(key, 0, -1);
        if (raw == null) return Collections.emptyList();
        return raw.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, PositionUpdate.class);
                    } catch (JsonProcessingException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
