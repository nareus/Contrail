package com.aircraftapi.detector;

import com.aircraftapi.dto.AlertMessage;
import com.aircraftapi.dto.PositionUpdate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects holding patterns: an aircraft circling the same area at constant altitude
 * while waiting for approach clearance.
 *
 * Detection criteria:
 *   - At least 10 position samples in the buffer
 *   - Cumulative heading change >= 330 degrees (nearly a full rotation)
 *   - All positions within 50 km of their centroid (not just turning toward a destination)
 *   - Altitude variance < 500 m (constant-altitude hold)
 */
@Component
public class HoldingPatternDetector implements PatternDetector {

    private static final int    MIN_SAMPLES         = 10;
    private static final double MIN_HEADING_CHANGE  = 330.0;
    private static final double MAX_SPREAD_KM       = 50.0;
    private static final double MAX_ALT_VARIANCE_M  = 500.0;
    private static final long   COOLDOWN_MS         = 5 * 60 * 1000L;
    private static final double EARTH_RADIUS_KM     = 6371.0;

    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();

    @Override
    public Optional<AlertMessage> detect(PositionUpdate current, List<PositionUpdate> history) {
        if (history.size() < MIN_SAMPLES) return Optional.empty();
        if (isOnCooldown(current.icao24())) return Optional.empty();

        double headingChange = cumulativeHeadingChange(history);
        if (headingChange < MIN_HEADING_CHANGE) return Optional.empty();

        double[] centroid = centroid(history);
        double maxDist = history.stream()
                .mapToDouble(p -> haversineKm(p.latitude(), p.longitude(), centroid[0], centroid[1]))
                .max().orElse(0);
        if (maxDist > MAX_SPREAD_KM) return Optional.empty();

        double altVariance = altitudeVariance(history);
        if (altVariance > MAX_ALT_VARIANCE_M) return Optional.empty();

        setCooldown(current.icao24());
        double circles = headingChange / 360.0;
        return Optional.of(new AlertMessage(
                "HOLDING_PATTERN",
                current.icao24(),
                current.callsign(),
                current.latitude(),
                current.longitude(),
                current.altitudeMeters(),
                String.format("%.1f circles detected within %.1f km radius at ~%.0f m altitude",
                        circles, maxDist, altitudeMean(history)),
                Instant.now().toString()
        ));
    }

    private double cumulativeHeadingChange(List<PositionUpdate> positions) {
        double total = 0;
        for (int i = 1; i < positions.size(); i++) {
            Double h1 = positions.get(i - 1).heading();
            Double h2 = positions.get(i).heading();
            if (h1 == null || h2 == null) continue;
            double delta = Math.abs(h2 - h1);
            if (delta > 180) delta = 360 - delta;
            total += delta;
        }
        return total;
    }

    private double[] centroid(List<PositionUpdate> positions) {
        double sumLat = 0, sumLon = 0;
        for (PositionUpdate p : positions) { sumLat += p.latitude(); sumLon += p.longitude(); }
        return new double[]{ sumLat / positions.size(), sumLon / positions.size() };
    }

    private double altitudeVariance(List<PositionUpdate> positions) {
        double mean = altitudeMean(positions);
        return positions.stream()
                .filter(p -> p.altitudeMeters() != null)
                .mapToDouble(p -> Math.abs(p.altitudeMeters() - mean))
                .max().orElse(0);
    }

    private double altitudeMean(List<PositionUpdate> positions) {
        return positions.stream()
                .filter(p -> p.altitudeMeters() != null)
                .mapToDouble(PositionUpdate::altitudeMeters)
                .average().orElse(0);
    }

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private boolean isOnCooldown(String icao24) {
        Long last = cooldowns.get(icao24);
        return last != null && System.currentTimeMillis() - last < COOLDOWN_MS;
    }

    private void setCooldown(String icao24) {
        cooldowns.put(icao24, System.currentTimeMillis());
    }
}
