package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two ways a coordinate reaches the database, and the one shape it must end up in.
 *
 * <p>The mobile app captures it from the device and sends the canonical object; the web fill
 * form has no device position to read, so a supervisor types two numbers and the server pairs
 * them. Both must store the same thing — otherwise every reader (display, Excel export, a
 * future map) has to cope with two shapes and will eventually cope with one of them wrongly.
 */
class LocationValuesTest {

    @Test
    void pairsTheWebFormsTwoInputsIntoTheStoredObject() {
        Map<String, Object> stored = LocationValues.fromWebPair(List.of("35.6892", "51.3890"));

        assertThat(stored).containsEntry("type", "location")
                .containsEntry("lat", 35.6892)
                .containsEntry("lng", 51.389);
        // Nothing invented: a typed coordinate has no accuracy figure and no capture instant.
        assertThat(stored).doesNotContainKeys("accuracy", "capturedAt");
    }

    @Test
    void bothInputsBlankMeansTheFieldWasLeftUnanswered() {
        // Not an error — this is simply a field nobody filled in, and it must not be stored as
        // a broken coordinate.
        assertThat(LocationValues.fromWebPair(List.of("", ""))).isNull();
        assertThat(LocationValues.fromWebPair(List.of("  ", "  "))).isNull();
    }

    @Test
    void halfAPositionIsRefusedRatherThanStored() {
        // A latitude with no longitude is a typo mid-edit. Storing it would look like data.
        assertThat(LocationValues.fromWebPair(List.of("35.6892", ""))).isNull();
        assertThat(LocationValues.fromWebPair(List.of("", "51.3890"))).isNull();
    }

    @Test
    void outOfRangeCoordinatesAreRefused() {
        // Outside WGS-84 bounds is corruption, not a place.
        assertThat(LocationValues.fromWebPair(List.of("91", "0"))).isNull();
        assertThat(LocationValues.fromWebPair(List.of("0", "181"))).isNull();
        assertThat(LocationValues.fromWebPair(List.of("abc", "1"))).isNull();
    }

    @Test
    void anythingOtherThanExactlyTwoValuesIsRefused() {
        // The web form submits exactly two same-named inputs. One or three means the markup
        // changed underneath this and the pairing assumption no longer holds.
        assertThat(LocationValues.fromWebPair(List.of("35.6892"))).isNull();
        assertThat(LocationValues.fromWebPair(List.of("1", "2", "3"))).isNull();
        assertThat(LocationValues.fromWebPair("35.6892,51.3890")).isNull();
        assertThat(LocationValues.fromWebPair(null)).isNull();
    }

    @Test
    void theMobileObjectAndTheWebPairRoundTripToTheSameCoordinate() {
        Map<String, Object> fromWeb = LocationValues.fromWebPair(List.of("35.6892", "51.3890"));
        Map<String, Object> fromDevice = LocationValues.toStoredValue(
                new LocationValues.Coordinate(35.6892, 51.3890, 12.4, 1786105032313L));

        LocationValues.Coordinate web = LocationValues.parse(fromWeb);
        LocationValues.Coordinate device = LocationValues.parse(fromDevice);

        assertThat(web).isNotNull();
        assertThat(device).isNotNull();
        assertThat(web.lat()).isEqualTo(device.lat());
        assertThat(web.lng()).isEqualTo(device.lng());
        // The device reading keeps what only it can know.
        assertThat(device.accuracyMeters()).isEqualTo(12.4);
        assertThat(device.capturedAt()).isEqualTo(1786105032313L);
        assertThat(web.accuracyMeters()).isNull();
    }

    @Test
    void aCapturedCoordinateKeepsItsAccuracyAndInstantWhenStored() {
        Map<String, Object> stored = LocationValues.toStoredValue(
                new LocationValues.Coordinate(1.0, 2.0, 8.5, 99L));

        // Accuracy is what makes a plant fix judgeable — tens of metres is the difference
        // between "at the pump" and "at the next pump".
        assertThat(stored).containsEntry("accuracy", 8.5).containsEntry("capturedAt", 99L);
    }
}
