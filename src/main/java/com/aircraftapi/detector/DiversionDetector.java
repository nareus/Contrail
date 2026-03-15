package com.aircraftapi.detector;

import com.aircraftapi.dto.AlertMessage;
import com.aircraftapi.dto.PositionUpdate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects potential diversions: an aircraft that was flying a consistent heading
 * and has made a significant sustained course change.
 *
 * Detection criteria:
 *   - At least 10 position samples
 *   - First half: consistent heading (standard deviation < 20 degrees)
 *   - Second half: sustained heading change > 45 degrees from the first half mean
 *   - Aircraft must be airborne (not on ground)
 */
@Component
public class DiversionDetector implements PatternDetector {

    private static final int    MIN_SAMPLES          = 10;
    private static final double MAX_FIRST_HALF_STDEV = 20.0;
    private static final double MIN_HEADING_CHANGE   = 45.0;
    private static final long   COOLDOWN_MS          = 10 * 60 * 1000L;

    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();

    @Override
    public Optional<AlertMessage> detect(PositionUpdate current, List<PositionUpdate> history) {
        if (history.size() < MIN_SAMPLES) return Optional.empty();
        if (current.onGround()) return Optional.empty();
        if (isOnCooldown(current.icao24())) return Optional.empty();

        int mid = history.size() / 2;
        List<PositionUpdate> older = history.subList(mid, history.size());
        List<PositionUpdate> newer = history.subList(0, mid);

        double oldMean  = meanHeading(older);
        double oldStdev = headingStdev(older, oldMean);
        double newMean  = meanHeading(newer);

        if (oldStdev > MAX_FIRST_HALF_STDEV) return Optional.empty();

        double change = headingDifference(oldMean, newMean);
        if (change < MIN_HEADING_CHANGE) return Optional.empty();

        setCooldown(current.icao24());
        return Optional.of(new AlertMessage(
                "DIVERSION",
                current.icao24(),
                current.callsign(),
                current.latitude(),
                current.longitude(),
                current.altitudeMeters(),
                String.format("Possible diversion: heading changed %.0f degrees (was %.0f, now %.0f)",
                        change, oldMean, newMean),
                Instant.now().toString()
        ));
    }

    private double meanHeading(List<PositionUpdate> positions) {
        return positions.stream()
                .filter(p -> p.heading() != null)
                .mapToDouble(PositionUpdate::heading)
                .average().orElse(0);
    }

    private double headingStdev(List<PositionUpdate> positions, double mean) {
        double variance = positions.stream()
                .filter(p -> p.heading() != null)
                .mapToDouble(p -> {
                    double diff = headingDifference(p.heading(), mean);
                    return diff * diff;
                })
                .average().orElse(0);
        return Math.sqrt(variance);
    }

    private double headingDifference(double h1, double h2) {
        double diff = Math.abs(h2 - h1) % 360;
        return diff > 180 ? 360 - diff : diff;
    }

    private boolean isOnCooldown(String icao24) {
        Long last = cooldowns.get(icao24);
        return last != null && System.currentTimeMillis() - last < COOLDOWN_MS;
    }

    private void setCooldown(String icao24) {
        cooldowns.put(icao24, System.currentTimeMillis());
    }
}
