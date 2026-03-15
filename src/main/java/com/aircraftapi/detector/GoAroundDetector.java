package com.aircraftapi.detector;

import com.aircraftapi.dto.AlertMessage;
import com.aircraftapi.dto.PositionUpdate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects go-arounds: an aircraft on final approach that aborts the landing
 * and climbs away instead.
 *
 * Detection criteria:
 *   - At least 6 position samples
 *   - Aircraft was descending in the first half of the buffer
 *   - Aircraft is now climbing in the second half (vertical rate > 2 m/s)
 *   - Minimum altitude during descent was below 1000 m (confirms it was on approach)
 */
@Component
public class GoAroundDetector implements PatternDetector {

    private static final int    MIN_SAMPLES       = 6;
    private static final double MAX_APPROACH_ALT  = 1000.0;
    private static final double MIN_CLIMB_RATE    = 2.0;
    private static final long   COOLDOWN_MS       = 5 * 60 * 1000L;

    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();

    @Override
    public Optional<AlertMessage> detect(PositionUpdate current, List<PositionUpdate> history) {
        if (history.size() < MIN_SAMPLES) return Optional.empty();
        if (isOnCooldown(current.icao24())) return Optional.empty();

        int mid = history.size() / 2;
        List<PositionUpdate> older = history.subList(mid, history.size());
        List<PositionUpdate> newer = history.subList(0, mid);

        double minAlt = older.stream()
                .filter(p -> p.altitudeMeters() != null)
                .mapToDouble(PositionUpdate::altitudeMeters)
                .min().orElse(Double.MAX_VALUE);

        if (minAlt > MAX_APPROACH_ALT) return Optional.empty();

        boolean wasDescending = isDescending(older);
        boolean nowClimbing   = isClimbing(newer);

        if (!wasDescending || !nowClimbing) return Optional.empty();

        setCooldown(current.icao24());
        return Optional.of(new AlertMessage(
                "GO_AROUND",
                current.icao24(),
                current.callsign(),
                current.latitude(),
                current.longitude(),
                current.altitudeMeters(),
                String.format("Go-around detected: was descending to %.0f m, now climbing at %.1f m/s",
                        minAlt, current.verticalRate() != null ? current.verticalRate() : 0.0),
                Instant.now().toString()
        ));
    }

    private boolean isDescending(List<PositionUpdate> positions) {
        long descending = positions.stream()
                .filter(p -> p.verticalRate() != null && p.verticalRate() < -0.5)
                .count();
        return descending >= positions.size() / 2;
    }

    private boolean isClimbing(List<PositionUpdate> positions) {
        long climbing = positions.stream()
                .filter(p -> p.verticalRate() != null && p.verticalRate() > MIN_CLIMB_RATE)
                .count();
        return climbing >= positions.size() / 2;
    }

    private boolean isOnCooldown(String icao24) {
        Long last = cooldowns.get(icao24);
        return last != null && System.currentTimeMillis() - last < COOLDOWN_MS;
    }

    private void setCooldown(String icao24) {
        cooldowns.put(icao24, System.currentTimeMillis());
    }
}
