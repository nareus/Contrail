package com.aircraftapi.dto;

public record StatsResponse(
        long trackedAircraftLast5min,
        long alertsLastHour,
        long totalAlerts
) {}
