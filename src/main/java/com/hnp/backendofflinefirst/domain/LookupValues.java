package com.hnp.backendofflinefirst.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The answer of a {@code lookup} / {@code lookupmulti} field — a choice from a reference list
 * (roadmap §31, Phase 2; the lists are V22's {@code lookup_lists}).
 *
 * <h2>The stored shape</h2>
 *
 * <pre>{@code
 * lookup:      {"code": "1234", "label": "علی رضایی", "state": "ACTIVE"}
 * lookupmulti: [{"code": "1234", "label": "علی رضایی", "state": "ACTIVE"}, …]
 * }</pre>
 *
 * <p><b>The code is the identity; the label is what it read when the answer was given.</b>
 * Reports group by code. The label is kept beside it so that a record stays readable when its
 * item is renamed, deactivated, anonymised — or when the list is not on the device that shows it.
 * That one fact is what makes every deletion rule of the device lease safe (roadmap §31, «How the
 * lists reach the tablet»): nothing that removes a list can make a recorded answer unreadable.
 *
 * <h2>{@code state} is the server's verdict at entry, and it is never refused</h2>
 *
 * <ul>
 *   <li>{@link State#ACTIVE} — the code named an active item.</li>
 *   <li>{@link State#INACTIVE} — it named an item that had been deactivated. Accepted: a tablet
 *       that filled a round offline last night, before the item was retired this morning, did its
 *       work correctly, and an operator cannot repair a refused offline submission (decided
 *       2026-11-14).</li>
 *   <li>{@link State#UNKNOWN} — no item has that code: typed in code-only mode, or the list
 *       reached the device after the server lost the item. Accepted and flagged for the same
 *       reason; the label is whatever the client sent, and is shown as unverified.</li>
 * </ul>
 *
 * <p>Stamped by {@code LookupAnswerService} on every write path, beside the signature stamp. A
 * client's {@code state} is never trusted, and a client's label is kept only for an unknown code:
 * a known code takes the list's label, so a label cannot be forged for a real person. An answer
 * whose code is unchanged keeps the stamp it had, byte for byte — re-saving a form writes no
 * revision, and «inactive at entry» stays what it was at entry.
 *
 * <p>Never a severity: a lookup field has no band and no alerting option
 * ({@code FieldValidationSupport.evaluateValue} answers {@code OK} for both types).
 */
public final class LookupValues {

    public static final String TYPE_SINGLE = "lookup";
    public static final String TYPE_MULTI = "lookupmulti";

    public static final String KEY_CODE = "code";
    public static final String KEY_LABEL = "label";
    public static final String KEY_STATE = "state";

    /** Same ceiling as {@code lookup_items.code}. */
    public static final int MAX_CODE = 100;
    /** Same ceiling as {@code lookup_items.label}. */
    public static final int MAX_LABEL = 255;
    /** A multi-choice answer names people or things, not a list's worth of them. */
    public static final int MAX_CHOICES = 50;

    public enum State {
        ACTIVE, INACTIVE, UNKNOWN;

        public static State fromStored(Object raw) {
            if (raw == null) {
                return null;
            }
            try {
                return State.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** One choice as a client sent it. {@code label} may be null; {@code code} never is. */
    public record Choice(String code, String label) {}

    private LookupValues() {}

    public static boolean isLookupField(String dataType) {
        return TYPE_SINGLE.equals(dataType) || TYPE_MULTI.equals(dataType);
    }

    public static boolean isMulti(String dataType) {
        return TYPE_MULTI.equals(dataType);
    }

    /**
     * The choices a client sent, or {@code null} when the value cannot be read as choices at all.
     *
     * <p>Accepted: a bare code (a string or a number — a personnel code typed on a numeric keypad
     * arrives as one), an object with a {@code code} and optionally a {@code label}, or a list of
     * either. An empty list, a blank string, or an object with no code is <em>no answer</em> and
     * yields an empty list. Duplicate codes collapse — case-insensitively, as the list's own key
     * does — keeping the first.
     *
     * <p>The code is normalised exactly as {@code LookupListService.normaliseItemCode} normalises
     * an item's (trimmed, Persian/Arabic digits made Latin) so «۱۲۳۴» typed on a Persian keypad
     * finds item 1234. A code that could never be an item's — over {@link #MAX_CODE}, or with a
     * space or a control character — makes the whole value unreadable rather than unknown: it is a
     * malformed request, not a person nobody has heard of.
     */
    public static List<Choice> parseIncoming(Object raw) {
        if (raw == null) {
            return List.of();
        }
        Collection<?> items = raw instanceof Collection<?> many ? many : List.of(raw);
        List<Choice> out = new ArrayList<>(items.size());
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : items) {
            Choice choice = parseOne(item);
            if (choice == NOT_A_CHOICE) {
                return null;
            }
            if (choice == null) {
                continue;
            }
            if (seen.add(choice.code().toLowerCase(Locale.ROOT))) {
                out.add(choice);
            }
        }
        return out;
    }

    private static final Choice NOT_A_CHOICE = new Choice("", null);

    private static Choice parseOne(Object item) {
        if (item == null) {
            return null;
        }
        Object rawCode;
        Object rawLabel = null;
        if (item instanceof Map<?, ?> map) {
            rawCode = map.get(KEY_CODE);
            rawLabel = map.get(KEY_LABEL);
            if (rawCode == null || String.valueOf(rawCode).isBlank()) {
                // {"label": "…"} with no code is a free-text answer, which a lookup field does not
                // take in this release; {} is simply nothing.
                return rawLabel == null || String.valueOf(rawLabel).isBlank() ? null : NOT_A_CHOICE;
            }
        } else if (item instanceof CharSequence || item instanceof Number) {
            rawCode = item;
        } else {
            return NOT_A_CHOICE;
        }
        if (rawCode instanceof Number number) {
            rawCode = plainNumber(number);
        }
        if (!(rawCode instanceof CharSequence)) {
            return NOT_A_CHOICE;
        }
        String code = normaliseCode(rawCode.toString());
        if (code == null) {
            return rawCode.toString().isBlank() ? null : NOT_A_CHOICE;
        }
        String label = null;
        if (rawLabel != null) {
            if (!(rawLabel instanceof CharSequence)) {
                return NOT_A_CHOICE;
            }
            label = rawLabel.toString().strip();
            if (label.isEmpty()) {
                label = null;
            } else if (label.codePointCount(0, label.length()) > MAX_LABEL
                    || label.codePoints().anyMatch(Character::isISOControl)) {
                return NOT_A_CHOICE;
            }
        }
        return new Choice(code, label);
    }

    /**
     * A code as the list keys it, or null when it cannot be one. Mirrors
     * {@code LookupListService.normaliseItemCode} without throwing: Latin digits, stripped, at most
     * {@link #MAX_CODE}, no whitespace, control or format character.
     */
    public static String normaliseCode(String raw) {
        if (raw == null) {
            return null;
        }
        String code = PersianSearchNormalizer.latinDigits(raw.strip());
        if (code.isEmpty() || code.length() > MAX_CODE) {
            return null;
        }
        for (int i = 0; i < code.length(); ) {
            int cp = code.codePointAt(i);
            int type = Character.getType(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)
                    || type == Character.CONTROL || type == Character.FORMAT) {
                return null;
            }
            i += Character.charCount(cp);
        }
        return code;
    }

    private static String plainNumber(Number number) {
        double d = number.doubleValue();
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /** The stored elements of an answer as written — a map for one, a list of maps for many. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> storedChoices(Object stored) {
        if (stored == null) {
            return List.of();
        }
        Collection<?> items = stored instanceof Collection<?> many ? many : List.of(stored);
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof Map<?, ?> map && map.get(KEY_CODE) != null) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    /** One stored element. */
    public static Map<String, Object> element(String code, String label, State state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(KEY_CODE, code);
        out.put(KEY_LABEL, label == null || label.isBlank() ? code : label);
        out.put(KEY_STATE, state.name());
        return out;
    }

    /** The stored answer from its elements: the element itself for one, the list for many. */
    public static Object toValue(List<Map<String, Object>> elements, boolean multi) {
        if (elements.isEmpty()) {
            return null;
        }
        return multi ? List.copyOf(elements) : elements.get(0);
    }

    /** The codes an answer names, for a guard that must know whether anything still names one. */
    public static List<String> codesOf(Object stored) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> element : storedChoices(stored)) {
            out.add(String.valueOf(element.get(KEY_CODE)));
        }
        return out;
    }

    /**
     * «علی رضایی · 1234» — one element as a person reads it, with what the verdict means beside it
     * when it is not the ordinary case. Shared by the detail page, the fill summary and the export.
     * A middle dot, not parentheses: in a single RTL string a parenthesised code mirrors and, when
     * the title itself contains a number, the two numbers read as one. The picker draws the same
     * code in parentheses inside its own left-to-right run.
     */
    public static String displayOf(Map<String, Object> element) {
        Object code = element.get(KEY_CODE);
        Object label = element.get(KEY_LABEL);
        String text = label == null || String.valueOf(label).isBlank() || String.valueOf(label).equals(String.valueOf(code))
                ? String.valueOf(code)
                : label + " · " + code;
        State state = State.fromStored(element.get(KEY_STATE));
        if (state == State.INACTIVE) {
            return text + " · غیرفعال";
        }
        if (state == State.UNKNOWN) {
            return text + " · خارج از فهرست";
        }
        return text;
    }

    /** Every element of an answer as a person reads it, joined, or null for no answer. */
    public static String displayOfValue(Object stored) {
        List<Map<String, Object>> elements = storedChoices(stored);
        if (elements.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>(elements.size());
        for (Map<String, Object> element : elements) {
            parts.add(displayOf(element));
        }
        return String.join("، ", parts);
    }
}
