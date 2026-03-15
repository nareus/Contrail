package com.aircraftapi.dto;

public record CongestionResponse(
        double latitude,
        double longitude,
        double radiusKm,
        long aircraftNow,
        long aircraftLastHour
) {}
