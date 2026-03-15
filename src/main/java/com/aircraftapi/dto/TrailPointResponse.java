package com.aircraftapi.dto;

public record TrailPointResponse(
        double latitude,
        double longitude,
        Double altitudeMeters,
        Double heading,
        Double velocityMs,
        String capturedAt
) {}
