package com.aircraftapi.service;

import com.aircraftapi.dto.CongestionResponse;
import com.aircraftapi.dto.StatsResponse;
import com.aircraftapi.dto.TrailPointResponse;
import com.aircraftapi.repository.FlightAlertRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class StatsService {

    private final JdbcTemplate jdbc;
    private final FlightAlertRepository alertRepository;

    public StatsService(JdbcTemplate jdbc, FlightAlertRepository alertRepository) {
        this.jdbc = jdbc;
        this.alertRepository = alertRepository;
    }

    public List<TrailPointResponse> getTrail(String icao24, int minutes) {
        Timestamp since = Timestamp.from(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        return jdbc.query(
                "SELECT latitude, longitude, altitude_meters, heading, velocity_ms, captured_at " +
                "FROM position_history " +
                "WHERE icao24 = ? AND captured_at >= ? " +
                "ORDER BY captured_at ASC",
                (rs, row) -> new TrailPointResponse(
                        rs.getDouble("latitude"),
                        rs.getDouble("longitude"),
                        (Double) rs.getObject("altitude_meters"),
                        (Double) rs.getObject("heading"),
                        (Double) rs.getObject("velocity_ms"),
                        rs.getObject("captured_at", OffsetDateTime.class).toString()
                ),
                icao24, since
        );
    }

    public StatsResponse getStats() {
        Long tracked = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT icao24) FROM position_history " +
                "WHERE captured_at >= NOW() - INTERVAL '5 minutes'",
                Long.class
        );
        long alertsLastHour = alertRepository.countByDetectedAtAfter(
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)
        );
        return new StatsResponse(
                tracked != null ? tracked : 0L,
                alertsLastHour,
                alertRepository.count()
        );
    }

    public CongestionResponse getCongestion(double lat, double lng, double radiusKm) {
        double radiusMeters = radiusKm * 1000;
        Long now = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT icao24) FROM position_history " +
                "WHERE ST_DWithin(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?) " +
                "AND captured_at >= NOW() - INTERVAL '5 minutes'",
                Long.class, lng, lat, radiusMeters
        );
        Long lastHour = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT icao24) FROM position_history " +
                "WHERE ST_DWithin(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?) " +
                "AND captured_at >= NOW() - INTERVAL '1 hour'",
                Long.class, lng, lat, radiusMeters
        );
        return new CongestionResponse(lat, lng, radiusKm,
                now != null ? now : 0L,
                lastHour != null ? lastHour : 0L);
    }
}
