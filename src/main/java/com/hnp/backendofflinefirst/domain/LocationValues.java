package com.hnp.backendofflinefirst.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reading and writing a {@code location} field's value — a GPS coordinate.
 *
 * <p>The canonical stored shape mirrors the attachment reference: a small object with an
 * explicit {@code type}, so a reader can tell at a glance what a value is without consulting
 * the field definition.
 *
 * <pre>{ "pump_position": { "type": "location", "lat": 35.6892, "lng": 51.3890,
 *                           "accuracy": 12.5, "capturedAt": 1786105032313 } }</pre>
 *
 * <p><b>Why an object and not a "35.6892,51.3890" string.</b> A string forces every consumer to
 * re-parse and re-guess: which value came first, what the decimal separator is, whether a third
 * number is altitude or accuracy. It also silently loses precision the moment anyone formats it
 * for display. Numbers in named keys survive Jackson, jsonb, Excel export and any future map
 * rendering without a parsing step.
 *
 * <p>{@code accuracy} (metres) and {@code capturedAt} are optional but carried deliberately: a
 * coordinate with no accuracy figure cannot be judged, and in a plant a phone fix can be tens of
 * metres out — the difference between "at the pump" and "at the next pump".
 *
 * <p><b>Nothing here fetches a map.</b> Rendering is coordinates as text, because the product has
 * to run on a network with no route to the internet; a tile server or a map link would be a
 * dependency that fails exactly where the app is used.
 */
public final class LocationValues {

    public static final String TYPE_KEY = "type";
    public static final String TYPE_VALUE = "location";
    public static final String LAT_KEY = "lat";
    public static final String LNG_KEY = "lng";
    public static final String ACCURACY_KEY = "accuracy";
    public static final String CAPTURED_AT_KEY = "capturedAt";

    /** Valid WGS-84 bounds. Anything outside is data corruption, not a place. */
    public static final double MIN_LAT = -90.0;
    public static final double MAX_LAT = 90.0;
    public static final double MIN_LNG = -180.0;
    public static final double MAX_LNG = 180.0;

    private LocationValues() {}

    /**
     * Canonical stored form of a coordinate, for writing.
     *
     * <p>Both entry paths funnel through this so a value captured by GPS and one typed on the
     * web are indistinguishable once stored — otherwise every reader would need to handle two
     * shapes and would eventually handle one of them wrongly.
     */
    public static Map<String, Object> toStoredValue(Coordinate coordinate) {
        if (coordinate == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(TYPE_KEY, TYPE_VALUE);
        out.put(LAT_KEY, coordinate.lat());
        out.put(LNG_KEY, coordinate.lng());
        if (coordinate.accuracyMeters() != null) {
            out.put(ACCURACY_KEY, coordinate.accuracyMeters());
        }
        if (coordinate.capturedAt() != null) {
            out.put(CAPTURED_AT_KEY, coordinate.capturedAt());
        }
        return out;
    }

    /**
     * The web fill form's submission for a location field: exactly two same-named inputs,
     * latitude then longitude, in document order.
     *
     * <p>Both blank means the field was left unanswered — returns null, which the caller
     * stores as "no value" rather than as a broken coordinate. One blank or an out-of-range
     * number is a typo, and also yields null so validation reports it rather than storing
     * half a position.
     */
    public static Map<String, Object> fromWebPair(Object raw) {
        if (!(raw instanceof java.util.List<?> pair) || pair.size() != 2) {
            return null;
        }
        String lat = pair.get(0) == null ? "" : String.valueOf(pair.get(0)).trim();
        String lng = pair.get(1) == null ? "" : String.valueOf(pair.get(1)).trim();
        if (lat.isEmpty() && lng.isEmpty()) {
            return null;
        }
        Coordinate coordinate = parse(lat + "," + lng);
        return coordinate == null ? null : toStoredValue(coordinate);
    }

    /** One coordinate, already validated. */
    public record Coordinate(double lat, double lng, Double accuracyMeters, Long capturedAt) {

        /**
         * Display form: six decimals, which is about 11 cm — far finer than any phone GPS, and
         * the point past which extra digits are noise pretending to be precision.
         */
        public String display() {
            return String.format(java.util.Locale.ROOT, "%.6f, %.6f", lat, lng);
        }
    }

    /**
     * Parses a stored value into a coordinate.
     *
     * <p>Tolerant on shape for the same reason the attachment parser is: an older or
     * hand-written value may be a bare {@code {lat, lng}} object without the {@code type} marker,
     * or a {@code "lat,lng"} string. Strict on content — a coordinate outside WGS-84 bounds, or
     * one missing either half, is not a place and is rejected rather than stored as a fact.
     *
     * @return the coordinate, or {@code null} when the value holds no usable one
     */
    public static Coordinate parse(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Double lat = toDouble(map.get(LAT_KEY));
            Double lng = toDouble(map.get(LNG_KEY));
            if (lat == null || lng == null || !inRange(lat, lng)) {
                return null;
            }
            return new Coordinate(lat, lng, toDouble(map.get(ACCURACY_KEY)),
                    toLong(map.get(CAPTURED_AT_KEY)));
        }
        if (value instanceof String text) {
            String[] parts = text.split(",");
            if (parts.length != 2) {
                return null;
            }
            Double lat = toDouble(parts[0].trim());
            Double lng = toDouble(parts[1].trim());
            if (lat == null || lng == null || !inRange(lat, lng)) {
                return null;
            }
            return new Coordinate(lat, lng, null, null);
        }
        return null;
    }

    /** True when the value is shaped like a location, even if the coordinate is unusable. */
    public static boolean looksLikeLocationValue(Object value) {
        return value instanceof Map<?, ?> map && TYPE_VALUE.equals(String.valueOf(map.get(TYPE_KEY)));
    }

    /** Canonical form written back, so stored values are consistent regardless of client. */
    public static Map<String, Object> toValue(Coordinate coordinate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(TYPE_KEY, TYPE_VALUE);
        if (coordinate == null) {
            return out;
        }
        out.put(LAT_KEY, coordinate.lat());
        out.put(LNG_KEY, coordinate.lng());
        if (coordinate.accuracyMeters() != null) {
            out.put(ACCURACY_KEY, coordinate.accuracyMeters());
        }
        if (coordinate.capturedAt() != null) {
            out.put(CAPTURED_AT_KEY, coordinate.capturedAt());
        }
        return out;
    }

    /** True when this field's data type stores a coordinate. */
    public static boolean isLocationField(String dataType) {
        return dataType != null && TYPE_VALUE.equals(dataType.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean inRange(double lat, double lng) {
        return lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG;
    }

    private static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try {
            String text = String.valueOf(v).trim();
            return text.isEmpty() ? null : Double.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            String text = String.valueOf(v).trim();
            return text.isEmpty() ? null : Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
