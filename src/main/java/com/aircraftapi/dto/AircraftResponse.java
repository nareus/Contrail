package com.aircraftapi.dto;

import java.util.List;

public record AircraftResponse(
        String icao24,
        String callsign,
        String originCountry,
        Double latitude,
        Double longitude,
        Double altitudeMeters,
        Double velocityMs,
        Double heading,
        boolean onGround,
        double distanceKm
) {

    public static AircraftResponse fromOpenSkyState(List<Object> state, double distanceKm) {
        return new AircraftResponse(
                extractString(state, 0),
                trimCallsign(extractString(state, 1)),
                extractString(state, 2),
                extractDouble(state, 6),
                extractDouble(state, 5),
                extractDouble(state, 7),
                extractDouble(state, 9),
                extractDouble(state, 10),
                extractBoolean(state, 8),
                distanceKm
        );
    }

    private static String extractString(List<Object> state, int index) {
        if (index >= state.size() || state.get(index) == null) return null;
        return state.get(index).toString();
    }

    private static Double extractDouble(List<Object> state, int index) {
        if (index >= state.size() || state.get(index) == null) return null;
        return ((Number) state.get(index)).doubleValue();
    }

    private static boolean extractBoolean(List<Object> state, int index) {
        if (index >= state.size() || state.get(index) == null) return false;
        return (Boolean) state.get(index);
    }

    private static String trimCallsign(String callsign) {
        return callsign == null ? null : callsign.trim();
    }
}
