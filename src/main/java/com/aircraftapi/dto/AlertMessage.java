package com.aircraftapi.dto;

public record AlertMessage(
        String type,
        String icao24,
        String callsign,
        Double latitude,
        Double longitude,
        Double altitudeMeters,
        String description,
        String detectedAt
) {}
