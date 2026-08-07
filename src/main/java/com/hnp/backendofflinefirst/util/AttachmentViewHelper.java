package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.domain.AttachmentKind;
import com.hnp.backendofflinefirst.domain.LocationValues;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.service.AppSettingsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Small view-layer helper for the attachment controls on the fill page.
 *
 * <p>Exists so the Thymeleaf templates stay free of type juggling. Working out whether a field
 * takes media, which ceiling applies to it, and which of the sheet's attachments belong to one
 * (asset, field) pair is all ordinary Java — expressing it in template expressions would be
 * both unreadable and untestable.
 */
@Component("attachmentView")
public class AttachmentViewHelper {

    /** True when this field stores a GPS coordinate rather than an attachment or a scalar. */
    public boolean isLocationField(String dataType) {
        return LocationValues.isLocationField(dataType);
    }

    /** A stored coordinate as readable text, or empty when the field has not been answered. */
    public String locationDisplay(Object value) {
        LocationValues.Coordinate coordinate = LocationValues.parse(value);
        if (coordinate == null) {
            return "";
        }
        String text = coordinate.display();
        if (coordinate.accuracyMeters() != null) {
            text += " (±" + Math.round(coordinate.accuracyMeters()) + " m)";
        }
        return text;
    }

    /**
     * The value to carry through the form untouched, as {@code lat,lng}.
     *
     * <p>The parser accepts that shape and {@code retainKnownKeys} turns it back into the
     * canonical object on save, so a web save neither loses the coordinate nor lets anyone
     * invent one. Empty when there is nothing stored, so saving does not write a junk value.
     */
    public String locationRaw(Object value) {
        LocationValues.Coordinate coordinate = LocationValues.parse(value);
        return coordinate == null ? "" : coordinate.lat() + "," + coordinate.lng();
    }

    /** The attachment kind name for a field data type, or {@code null} when it takes none. */
    public String kindOf(String dataType) {
        AttachmentKind kind = AttachmentKind.forFieldDataType(dataType);
        return kind == null ? null : kind.name();
    }

    /** Same as {@link #kindOf} but as the enum, for the limits lookup. */
    public AttachmentKind kindEnum(String dataType) {
        return AttachmentKind.forFieldDataType(dataType);
    }

    /**
     * The duration ceiling in whole seconds, or 0 for a kind that has no duration.
     *
     * <p>Zero rather than null so the template can compare numerically without a null guard,
     * and the JavaScript reads it as "no duration limit applies".
     */
    public int maxSecondsFor(AppSettingsService.AttachmentLimits limits, String dataType) {
        if (limits == null) {
            return 0;
        }
        Long ms = limits.maxDurationMsFor(AttachmentKind.forFieldDataType(dataType));
        return ms == null ? 0 : (int) (ms / 1000);
    }

    /**
     * The sheet's attachments that belong to one asset's field, oldest first.
     *
     * <p>Ordered by upload time so the tiles stay in a stable, meaningful sequence across
     * reloads — a grid that reshuffles itself makes "the second photo" meaningless.
     */
    public List<FormDataViewHelper.AttachmentView> forEntryField(
            Map<String, Attachment> attachmentsById, Long assetId, String fieldKey) {
        List<FormDataViewHelper.AttachmentView> out = new ArrayList<>();
        if (attachmentsById == null || attachmentsById.isEmpty() || assetId == null || fieldKey == null) {
            return out;
        }
        attachmentsById.values().stream()
                .filter(a -> Objects.equals(assetId, a.getAssetId()) && fieldKey.equals(a.getFieldKey()))
                .sorted(Comparator.comparing(
                        Attachment::getUploadedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(a -> out.add(new FormDataViewHelper.AttachmentView(
                        a.getId(),
                        a.getKind() != null ? a.getKind().name() : null,
                        a.getMimeType(),
                        a.getSizeBytes(),
                        a.getDurationMs(),
                        false)));
        return out;
    }
}
