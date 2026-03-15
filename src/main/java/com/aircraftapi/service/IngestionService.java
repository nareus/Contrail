package com.aircraftapi.service;

import com.aircraftapi.client.OpenSkyClient;
import com.aircraftapi.detector.PatternDetector;
import com.aircraftapi.domain.FlightAlert;
import com.aircraftapi.dto.AlertMessage;
import com.aircraftapi.dto.PositionUpdate;
import com.aircraftapi.repository.FlightAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final OpenSkyClient         openSkyClient;
    private final LiveStateStore        liveStateStore;
    private final List<PatternDetector> detectors;
    private final AlertBroadcaster      alertBroadcaster;
    private final FlightAlertRepository alertRepository;
    private final JdbcTemplate          jdbc;

    @Value("${ingestion.region.min-lat}") private double minLat;
    @Value("${ingestion.region.max-lat}") private double maxLat;
    @Value("${ingestion.region.min-lon}") private double minLon;
    @Value("${ingestion.region.max-lon}") private double maxLon;

    public IngestionService(OpenSkyClient openSkyClient,
                            LiveStateStore liveStateStore,
                            List<PatternDetector> detectors,
                            AlertBroadcaster alertBroadcaster,
                            FlightAlertRepository alertRepository,
                            JdbcTemplate jdbc) {
        this.openSkyClient    = openSkyClient;
        this.liveStateStore   = liveStateStore;
        this.detectors        = detectors;
        this.alertBroadcaster = alertBroadcaster;
        this.alertRepository  = alertRepository;
        this.jdbc             = jdbc;
    }

    @Scheduled(fixedDelayString = "${ingestion.poll-interval-ms:10000}")
    public void ingest() {
        List<List<Object>> states = openSkyClient.fetchAircraftInBoundingBox(minLat, maxLat, minLon, maxLon);

        int processed = 0;
        for (List<Object> state : states) {
            if (!hasValidPosition(state)) continue;

            PositionUpdate update = PositionUpdate.fromOpenSkyState(state);

            liveStateStore.updateState(update);
            liveStateStore.appendToHistory(update);
            savePositionHistory(update);

            List<PositionUpdate> history = liveStateStore.getHistory(update.icao24());
            for (PatternDetector detector : detectors) {
                Optional<AlertMessage> alert = detector.detect(update, history);
                alert.ifPresent(a -> {
                    alertBroadcaster.broadcast(a);
                    alertRepository.save(FlightAlert.from(a));
                });
            }
            processed++;
        }

        log.info("Ingestion complete: {} aircraft processed", processed);
    }

    private void savePositionHistory(PositionUpdate u) {
        try {
            jdbc.update(
                    "INSERT INTO position_history " +
                    "(icao24, callsign, latitude, longitude, altitude_meters, velocity_ms, heading, vertical_rate, on_ground) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    u.icao24(), u.callsign(), u.latitude(), u.longitude(),
                    u.altitudeMeters(), u.velocityMs(), u.heading(), u.verticalRate(), u.onGround()
            );
        } catch (Exception e) {
            log.warn("Failed to save position history for {}: {}", u.icao24(), e.getMessage());
        }
    }

    private boolean hasValidPosition(List<Object> state) {
        return state.size() > 6 && state.get(6) != null && state.get(5) != null;
    }
}
