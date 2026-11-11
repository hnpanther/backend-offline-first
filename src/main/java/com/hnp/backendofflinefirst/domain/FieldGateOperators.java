package com.hnp.backendofflinefirst.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The operators a <strong>state gate</strong> may compare with — one list, used everywhere one
 * is needed.
 *
 * <h2>What a state gate is</h2>
 *
 * <p>A parameter's thresholds are not always relevant. «The current must be above the no-load
 * threshold» is true of a running pump and meaningless for a stopped one, so judging it
 * unconditionally produces a danger on every standby machine — the nuisance alarm ISA-18.2 /
 * IEC 62682 exist to prevent. The standard answer is <em>suppressed by design</em>: the
 * condition is not evaluated in a state where it does not apply. A gate says which state that
 * is, by reading <strong>another answer on the same entry</strong>.
 *
 * <p>See {@code FieldValidationSupport#KEY_CONDITIONS} for the stored shape and the evaluation
 * order, and {@code docs/alarms.md} for the behaviour.
 *
 * <h2>A closed list, deliberately — not an expression language</h2>
 *
 * <p>The obvious design is a little expression typed into a box. It is the wrong one here: an
 * expression needs a parser and an evaluator, both reachable from an administrator's form, and
 * this project already carries the rule that user input never reaches SpEL
 * ({@code AGENTS.md}). A fixed set of operators validated on write is a <em>configuration
 * value</em>; a parsed string is a scripting engine in a jsonb column.
 *
 * <h2>Not a Java enum, for {@link FieldDataTypes}'s reason</h2>
 *
 * <p>The operator travels as a string inside {@code field_definitions.validation} and on to the
 * PWA, which has its own union type. An enum would turn an operator written by a newer build,
 * or by hand, into a deserialisation failure on read rather than a gate that simply does not
 * match. What is actually needed is a <em>whitelist for writes</em>, which {@link #isValid}
 * provides, and tolerance on reads, which {@link #isValid} also gives the evaluator for free.
 */
public final class FieldGateOperators {

    /** The sibling's answer is one of the listed options. */
    public static final String IN = "in";
    /** The sibling's answer is none of the listed options. */
    public static final String NOT_IN = "notIn";
    public static final String GT = "gt";
    public static final String GTE = "gte";
    public static final String LT = "lt";
    public static final String LTE = "lte";
    /** The sibling was answered at all, whatever the answer. */
    public static final String ANSWERED = "answered";
    /** The sibling was not answered. */
    public static final String NOT_ANSWERED = "notAnswered";

    /** Machine value to Persian label, in the order the dropdown shows them. */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    private static final Map<String, String> LABELS_VIEW = Collections.unmodifiableMap(LABELS);

    static {
        LABELS.put(IN, "یکی از");
        LABELS.put(NOT_IN, "هیچ‌کدام از");
        LABELS.put(GT, "بیشتر از");
        LABELS.put(GTE, "بیشتر یا مساوی");
        LABELS.put(LT, "کمتر از");
        LABELS.put(LTE, "کمتر یا مساوی");
        LABELS.put(ANSWERED, "پاسخ داده شده باشد");
        LABELS.put(NOT_ANSWERED, "پاسخ داده نشده باشد");
    }

    private FieldGateOperators() {
    }

    public static Map<String, String> labels() {
        return LABELS_VIEW;
    }

    public static List<String> values() {
        return List.copyOf(LABELS.keySet());
    }

    public static String label(String op) {
        return LABELS.getOrDefault(op, op);
    }

    /** Whether this operator may be <em>written</em>. Reads stay permissive — see the class comment. */
    public static boolean isValid(String op) {
        return op != null && LABELS.containsKey(op);
    }

    /** Compares against a list of option strings, carried in {@code values}. */
    public static boolean takesOptions(String op) {
        return IN.equals(op) || NOT_IN.equals(op);
    }

    /** Compares against a single number, carried in {@code value}. */
    public static boolean takesNumber(String op) {
        return GT.equals(op) || GTE.equals(op) || LT.equals(op) || LTE.equals(op);
    }

    /** Asks only whether the sibling was answered, so it carries no operand at all. */
    public static boolean takesNoOperand(String op) {
        return ANSWERED.equals(op) || NOT_ANSWERED.equals(op);
    }
}
