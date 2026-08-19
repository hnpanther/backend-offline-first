package com.hnp.backendofflinefirst.dto.integration;

import java.util.List;

/**
 * One complete log sheet: the round, the assets on it, and what was recorded against each.
 *
 * <p>Same rule as {@link IntegrationLogSheetSummary} — everything here was chosen, and nothing
 * arrives by inheriting from an entity.
 *
 * @param summary     the identifying header, byte-for-byte the same shape the list returns, so
 *                    a caller can hold one type for both endpoints
 * @param fields      the parameter schema frozen when the sheet was generated. Published so a
 *                    consumer can label and unit-annotate the values without holding a copy of
 *                    the plant's field catalogue — and so that a definition edited afterwards
 *                    cannot retroactively change what a historic reading appears to mean
 * @param assets      one entry per asset the round covered, in the order the sheet carries them
 * @param cancelledAt when the sheet was cancelled, if it was
 * @param expiredAt   when the sheet expired, if it did
 */
public record IntegrationLogSheetDetail(
        IntegrationLogSheetSummary summary,
        List<Field> fields,
        List<AssetRecord> assets,
        String expiredAt,
        String cancelledAt) {

    /**
     * One parameter definition as it stood when the sheet was raised.
     *
     * @param key       the identifier the values are keyed by
     * @param label     Persian display label
     * @param dataType  number, text, boolean, image, audio, video, …
     * @param unit      unit of measure, when the parameter has one
     * @param required  whether the operator had to answer it
     * @param classId   the asset class this parameter belongs to — a sheet may span several
     * @param order     display order within its class
     */
    public record Field(String key, String label, String dataType, String unit,
                        boolean required, Long classId, Integer order) {}

    /**
     * What was recorded against one asset.
     *
     * @param asset        which asset, identified by plant codes rather than only by row id
     * @param values       one row per parameter, in schema order
     * @param maxSeverity  worst validation severity across this asset's values — OK, WARNING or
     *                     DANGER; null when nothing was evaluated. This is the field an external
     *                     maintenance system will actually act on
     * @param breachedFields keys that breached, most severe first
     * @param filledAt     device time of the latest edit to this asset's values
     * @param filledBy     who last recorded them
     * @param entrySource  how the asset was reached — an NFC scan, or a manual fallback. Worth
     *                     publishing because it is the difference between a reading taken at
     *                     the equipment and one typed from somewhere else
     */
    public record AssetRecord(
            IntegrationReferences.Asset asset,
            List<Value> values,
            String maxSeverity,
            List<String> breachedFields,
            String filledAt,
            IntegrationReferences.Person filledBy,
            String entrySource) {}

    /**
     * One recorded parameter value.
     *
     * <p>{@code value} is the reading as it was stored — a number stays a number, a boolean
     * stays a boolean. It is null for a parameter the operator did not answer and for an
     * attachment-typed parameter, whose content is described by {@link #attachments} instead.
     *
     * @param attachments the photos/voice notes recorded for this parameter. <b>Metadata only —
     *                    never bytes.</b> A round with fifty photos would otherwise turn a
     *                    JSON response into a hundred-megabyte base64 document, and the caller
     *                    asked to know that a photo exists, not to receive it
     */
    public record Value(String key, String label, String unit, String dataType,
                        Object value, List<Attachment> attachments) {}

    /**
     * That an attachment exists, and what it is.
     *
     * <p>No bytes, no URL, and no download endpoint in this phase — by design. Publishing the
     * id makes the file addressable later without changing this shape; publishing a link now
     * would commit to serving plant photographs to an external system, which is a decision
     * nobody has taken.
     *
     * @param id         the attachment's identifier
     * @param kind       IMAGE, AUDIO or VIDEO
     * @param mimeType   verified server-side from the file's magic bytes, never the uploader's claim
     * @param sizeBytes  size on disk
     * @param durationMs length of an audio or video note
     * @param capturedAt when it was uploaded
     */
    public record Attachment(String id, String kind, String mimeType, Long sizeBytes,
                             Integer width, Integer height, Long durationMs, String capturedAt) {}
}
