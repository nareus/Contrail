package com.aircraftapi.controller;

import com.aircraftapi.dto.AircraftResponse;
import com.aircraftapi.service.AircraftService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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

    public AircraftController(AircraftService aircraftService) {
        this.aircraftService = aircraftService;
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
}
