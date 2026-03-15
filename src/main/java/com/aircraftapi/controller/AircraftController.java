package com.aircraftapi.controller;

import com.aircraftapi.dto.AircraftResponse;
import com.aircraftapi.dto.AircraftStateResponse;
import com.aircraftapi.dto.CongestionResponse;
import com.aircraftapi.dto.TrailPointResponse;
import com.aircraftapi.service.AircraftService;
import com.aircraftapi.service.LiveStateStore;
import com.aircraftapi.service.StatsService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/aircraft")
public class AircraftController {

    private static final Logger log = LoggerFactory.getLogger(AircraftController.class);

    private final AircraftService aircraftService;
    private final LiveStateStore  liveStateStore;
    private final StatsService    statsService;

    public AircraftController(AircraftService aircraftService,
                              LiveStateStore liveStateStore,
                              StatsService statsService) {
        this.aircraftService = aircraftService;
        this.liveStateStore  = liveStateStore;
        this.statsService    = statsService;
    }

    @GetMapping
    public ResponseEntity<List<AircraftResponse>> searchAircraft(
            @RequestParam @DecimalMin(value = "-90.0",  message = "lat must be >= -90")
                          @DecimalMax(value = "90.0",   message = "lat must be <= 90") double lat,
            @RequestParam @DecimalMin(value = "-180.0", message = "lng must be >= -180")
                          @DecimalMax(value = "180.0",  message = "lng must be <= 180") double lng,
            @RequestParam @Positive(message = "radius must be a positive number") double radius) {

        log.info("GET /api/v1/aircraft lat={} lng={} radius={}", lat, lng, radius);
        List<AircraftResponse> aircraft = aircraftService.findAircraftWithinRadius(lat, lng, radius);

        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(aircraft.size()))
                .body(aircraft);
    }

    @GetMapping("/{icao24}")
    public ResponseEntity<AircraftStateResponse> getAircraftState(@PathVariable String icao24) {
        log.info("GET /api/v1/aircraft/{}", icao24);
        return liveStateStore.getState(icao24)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{icao24}/trail")
    public ResponseEntity<List<TrailPointResponse>> getTrail(
            @PathVariable String icao24,
            @RequestParam(defaultValue = "30") @Min(1) @Max(1440) int minutes) {

        log.info("GET /api/v1/aircraft/{}/trail minutes={}", icao24, minutes);
        List<TrailPointResponse> trail = statsService.getTrail(icao24, minutes);

        return ResponseEntity.ok()
                .header("X-Point-Count", String.valueOf(trail.size()))
                .body(trail);
    }

    @GetMapping("/congestion")
    public ResponseEntity<CongestionResponse> getCongestion(
            @RequestParam @DecimalMin(value = "-90.0",  message = "lat must be >= -90")
                          @DecimalMax(value = "90.0",   message = "lat must be <= 90") double lat,
            @RequestParam @DecimalMin(value = "-180.0", message = "lng must be >= -180")
                          @DecimalMax(value = "180.0",  message = "lng must be <= 180") double lng,
            @RequestParam @Positive(message = "radius must be a positive number") double radius) {

        log.info("GET /api/v1/aircraft/congestion lat={} lng={} radius={}", lat, lng, radius);
        return ResponseEntity.ok(statsService.getCongestion(lat, lng, radius));
    }
}
