package com.aircraftapi.controller;

import com.aircraftapi.domain.FlightAlert;
import com.aircraftapi.dto.StatsResponse;
import com.aircraftapi.repository.FlightAlertRepository;
import com.aircraftapi.service.StatsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class AlertController {

    private final FlightAlertRepository alertRepository;
    private final StatsService          statsService;

    public AlertController(FlightAlertRepository alertRepository, StatsService statsService) {
        this.alertRepository = alertRepository;
        this.statsService    = statsService;
    }

    @GetMapping("/alerts")
    public ResponseEntity<List<FlightAlert>> getAlerts(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String since) {

        OffsetDateTime sinceTime = since != null ? OffsetDateTime.parse(since) : null;

        List<FlightAlert> alerts = (type != null || sinceTime != null)
                ? alertRepository.findFiltered(type, sinceTime)
                : alertRepository.findTop100ByOrderByDetectedAtDesc();

        return ResponseEntity.ok(alerts);
    }

    @GetMapping("/alerts/{icao24}")
    public ResponseEntity<List<FlightAlert>> getAlertsForAircraft(@PathVariable String icao24) {
        return ResponseEntity.ok(alertRepository.findByIcao24OrderByDetectedAtDesc(icao24));
    }

    @GetMapping("/stats")
    public ResponseEntity<StatsResponse> getStats() {
        return ResponseEntity.ok(statsService.getStats());
    }
}
