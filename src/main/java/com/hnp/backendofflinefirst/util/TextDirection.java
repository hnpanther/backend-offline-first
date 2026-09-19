package com.hnp.backendofflinefirst.util;

/**
 * The {@code dir} attribute a piece of operator-written text should be rendered with.
 *
 * <h2>Why the browser's own answer is not enough</h2>
 *
 * <p>Readings are shown inside {@code <bdi>}, which the log-sheet page needs: a unit («B»,
 * «°C») or a negative number sitting in an RTL paragraph gets its sign or its parentheses
 * mirrored unless it is isolated. But {@code <bdi>} defaults to {@code dir="auto"}, and
 * {@code auto} means <b>the first strong character decides</b>. A note such as
 * «Tgdd پمپ ۱۲ خرابه» starts with a Latin word, so the browser laid the whole line out as an
 * LTR paragraph: the Persian sentence came out reversed and the English word landed at the
 * far end instead of where the operator wrote it. Reported live on log sheet #72.
 *
 * <p>The rule here is what a Persian reader expects: <b>if the text contains any right-to-left
 * letter it is a Persian sentence</b>, whatever token it happens to open with — a tag code, a
 * manufacturer's name, an English word — and it is laid out right-to-left. Only a value with no
 * RTL letter at all («OK», «P-0101A», «17») is left to the browser's default, which renders it
 * left-to-right as it should be.
 *
 * <p>Not a majority count. «پمپ P-0101A OK» has more Latin letters than Persian ones and is
 * still a Persian sentence; counting would flip it to LTR and put its first word last.
 */
public final class TextDirection {

    private TextDirection() {
    }

    /**
     * {@code "rtl"} when {@code text} contains any right-to-left letter, otherwise {@code null}
     * so the caller renders no {@code dir} and the element keeps its default ({@code auto} on a
     * {@code <bdi>}).
     */
    public static String forValue(String text) {
        if (text == null || text.isEmpty()) return null;
        return text.codePoints().anyMatch(TextDirection::isRightToLeft) ? "rtl" : null;
    }

    private static boolean isRightToLeft(int codePoint) {
        byte d = Character.getDirectionality(codePoint);
        // R covers Hebrew and the like; AL is Arabic script, which is what Persian is written in.
        // The embedding/override/isolate controls are deliberately not counted: they are
        // formatting characters, not letters, and never appear in text an operator typed.
        return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
    }
}
