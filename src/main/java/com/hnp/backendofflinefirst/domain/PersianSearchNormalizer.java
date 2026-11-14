package com.hnp.backendofflinefirst.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * «Does this match» for Persian text — the server's copy of the one normaliser the panel
 * ({@code static/js/choice-search.js}) and the PWA ({@code src/utils/persianSearch.ts}) already
 * share (roadmap §31).
 *
 * <p><b>For matching only, never for what is stored.</b> A label keeps the characters it was typed
 * with; only {@code lookup_items.search_text} — a derived column nobody reads as text — holds the
 * normalised form.
 *
 * <p>Three implementations of one rule drift the first time any of them is fixed, so all three run
 * the same table of cases: {@code src/test/js/persian-search-cases.json}, read here by
 * {@code PersianSearchNormalizerTest}. Change the cases and all three implementations together.
 *
 * <h2>Where Java and JavaScript differ, and how this copy closes the gap</h2>
 * <ul>
 *   <li>JavaScript's {@code \s} includes the no-break space, U+FEFF and the Unicode space
 *       separators; Java's {@code \s} is ASCII unless told otherwise. {@link #WHITESPACE} spells
 *       the JavaScript set out.</li>
 *   <li>JavaScript's {@code trim()} removes that same set; Java's {@code trim()} removes only
 *       {@code <= U+0020}. Runs are collapsed to one ASCII space <i>before</i> trimming, so only
 *       an ASCII space can be left at either end.</li>
 * </ul>
 */
public final class PersianSearchNormalizer {

    /** JavaScript's {@code \s}, character for character. */
    private static final Pattern WHITESPACE = Pattern.compile(
            "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+");

    private PersianSearchNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 'ي' || c == 'ى') {          // Arabic yeh, alef maksura -> Persian yeh
                c = 'ی';
            } else if (c == 'ك') {                     // Arabic kaf -> Persian keheh
                c = 'ک';
            }
            if (c == '‌' || c == '‍' || c == 'ـ') {   // ZWNJ, ZWJ, tatweel
                continue;
            }
            if ((c >= 'ً' && c <= 'ٟ') || c == 'ٰ') { // harakat, superscript alef
                continue;
            }
            if (c >= '۰' && c <= '۹') {           // Persian digits
                c = (char) ('0' + (c - '۰'));
            } else if (c >= '٠' && c <= '٩') {    // Arabic-Indic digits
                c = (char) ('0' + (c - '٠'));
            }
            out.append(c);
        }
        String lower = out.toString().toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(lower).replaceAll(" ").trim();
    }

    /** Whether {@code text} matches {@code query}; an empty query matches everything. */
    public static boolean matches(String text, String query) {
        String q = normalize(query);
        return q.isEmpty() || normalize(text).contains(q);
    }

    /**
     * Persian and Arabic-Indic digits to Latin, and nothing else. Used on a lookup item's
     * <b>code</b>, where it is an identity rule rather than a search rule: «۱۲۳۴» and «1234» typed
     * by two administrators must be one person, not two.
     */
    public static String latinDigits(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '۰' && c <= '۹') {
                c = (char) ('0' + (c - '۰'));
            } else if (c >= '٠' && c <= '٩') {
                c = (char) ('0' + (c - '٠'));
            }
            out.append(c);
        }
        return out.toString();
    }
}
