package com.aircraftapi.controller;

import com.aircraftapi.domain.FlightAlert;
import com.aircraftapi.repository.FlightAlertRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final FlightAlertRepository alertRepository;

    public AlertController(FlightAlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @GetMapping
    public ResponseEntity<List<FlightAlert>> getRecentAlerts() {
        return ResponseEntity.ok(alertRepository.findTop100ByOrderByDetectedAtDesc());
    }

    @GetMapping("/{icao24}")
    public ResponseEntity<List<FlightAlert>> getAlertsForAircraft(@PathVariable String icao24) {
        return ResponseEntity.ok(alertRepository.findByIcao24OrderByDetectedAtDesc(icao24));
    }
}
