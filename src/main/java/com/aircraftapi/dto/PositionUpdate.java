package com.aircraftapi.dto;

import java.util.List;

public record PositionUpdate(
        String icao24,
        String callsign,
        double latitude,
        double longitude,
        Double altitudeMeters,
        Double velocityMs,
        Double heading,
        Double verticalRate,
        boolean onGround,
        long timestampMs
) {
    public static PositionUpdate fromOpenSkyState(List<Object> state) {
        return new PositionUpdate(
                extractString(state, 0),
                trimCallsign(extractString(state, 1)),
                ((Number) state.get(6)).doubleValue(),
                ((Number) state.get(5)).doubleValue(),
                extractDouble(state, 7),
                extractDouble(state, 9),
                extractDouble(state, 10),
                extractDouble(state, 11),
                extractBoolean(state, 8),
                System.currentTimeMillis()
        );
    }

    private static String extractString(List<Object> state, int i) {
        if (i >= state.size() || state.get(i) == null) return null;
        return state.get(i).toString();
    }

    private static Double extractDouble(List<Object> state, int i) {
        if (i >= state.size() || state.get(i) == null) return null;
        return ((Number) state.get(i)).doubleValue();
    }

    private static boolean extractBoolean(List<Object> state, int i) {
        if (i >= state.size() || state.get(i) == null) return false;
        return (Boolean) state.get(i);
    }

    private static String trimCallsign(String cs) {
        return cs == null ? null : cs.trim();
    }
}
