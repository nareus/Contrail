package com.aircraftapi.dto;

public record AircraftStateResponse(
        String icao24,
        String callsign,
        Double latitude,
        Double longitude,
        Double altitudeMeters,
        Double heading,
        Double velocityMs,
        Double verticalRate,
        boolean onGround,
        long lastSeenMs
) {}
