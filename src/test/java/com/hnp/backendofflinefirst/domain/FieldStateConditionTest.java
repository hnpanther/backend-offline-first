package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * State conditions — a threshold that applies only while another answer says so.
 *
 * <p>The behaviour under test is {@code FieldValidationSupport#KEY_CONDITIONS}. Three groups of
 * assertion, and the first one is the point of the whole file:
 *
 * <ol>
 *   <li><b>Nothing that exists today changes.</b> Every field in the plant has no
 *       {@code conditions} key, and the gate must be invisible to all of them.</li>
 *   <li>The gate itself: when it holds, when it does not, and what happens in each case.</li>
 *   <li>What a malformed gate does — because the answer has to be «suppresses», never
 *       «alerts», and never «throws while an operator is saving a round».</li>
 * </ol>
 */
class FieldStateConditionTest {

    // Pump P-101: FLA 29 A, so "not drawing current" is below 15% of it.
    private static final double NO_LOAD = 4.5;

    @Nested
    @DisplayName("a field with no condition behaves exactly as it did before conditions existed")
    class Unchanged {

        @Test
        @DisplayName("the closed interval is pinned: a danger minimum of ZERO can never fire")
        void aDangerMinimumOfZeroNeverFires() {
            Map<String, Object> v = FieldValidationSupport.build(
                    "number", null, null, null, 0.0, null, null, null);

            // This is the mistake the whole feature exists to replace, and it is kept as an
            // assertion rather than a comment: `0 < 0` is false, so a reading of zero is INSIDE
            // the band. Anybody "fixing" the interval to be half-open would silently re-judge
            // every stored reading that sits exactly on a bound, in a plant, retroactively.
            assertThat(FieldValidationSupport.evaluateValue("number", 0, v))
                    .as("zero is inside [0, ∞) — a minimum of zero catches only negative readings")
                    .isEqualTo(FieldValidationSeverity.OK);
            assertThat(FieldValidationSupport.evaluateValue("number", -1, v))
                    .isEqualTo(FieldValidationSeverity.DANGER);
        }

        @Test
        @DisplayName("a real no-load threshold does fire, with or without the siblings")
        void aThresholdAboveZeroFires() {
            Map<String, Object> v = FieldValidationSupport.build(
                    "number", null, null, null, NO_LOAD, null, null, null);

            assertThat(FieldValidationSupport.evaluateValue("number", 0, v))
                    .isEqualTo(FieldValidationSeverity.DANGER);
            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("status", "روشن")))
                    .as("an ungated field ignores the siblings entirely")
                    .isEqualTo(FieldValidationSeverity.DANGER);
            assertThat(FieldValidationSupport.evaluateValue("number", 27.4, v))
                    .isEqualTo(FieldValidationSeverity.OK);
        }

        @Test
        @DisplayName("thresholdsFor returns the very same map, so nothing is copied per reading")
        void noConditionsMeansNoAllocation() {
            Map<String, Object> v = FieldValidationSupport.build(
                    "number", null, 20.0, 80.0, null, null, null, null);

            assertThat(FieldValidationSupport.thresholdsFor(v, Map.of("status", "روشن")))
                    .isSameAs(v);
        }
    }

    @Nested
    @DisplayName("the gate decides whether the thresholds are judged at all")
    class Gated {

        @Test
        @DisplayName("ON and no current is a danger; OFF and no current is not judged")
        void theOperatorsActualRequest() {
            Map<String, Object> v = gatedNoLoadBand();

            assertThat(FieldValidationSupport.evaluateValue(
                    "number", 0, v, Map.of("status", "روشن", "ampere", 0)))
                    .as("running and drawing nothing — the fault the rule exists for")
                    .isEqualTo(FieldValidationSeverity.DANGER);

            assertThat(FieldValidationSupport.evaluateValue(
                    "number", 0, v, Map.of("status", "خاموش", "ampere", 0)))
                    .as("stopped and drawing nothing is normal, and must not alert")
                    .isEqualTo(FieldValidationSeverity.OK);

            assertThat(FieldValidationSupport.evaluateValue(
                    "number", 27.4, v, Map.of("status", "روشن", "ampere", 27.4)))
                    .isEqualTo(FieldValidationSeverity.OK);
        }

        @Test
        @DisplayName("an unanswered gate parameter does not hold — «we do not know» is not «it was running»")
        void anUnansweredGateDoesNotHold() {
            Map<String, Object> v = gatedNoLoadBand();

            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("ampere", 0)))
                    .isEqualTo(FieldValidationSeverity.OK);
            assertThat(FieldValidationSupport.evaluateValue(
                    "number", 0, v, mapOf("status", null, "ampere", 0)))
                    .isEqualTo(FieldValidationSeverity.OK);
            assertThat(FieldValidationSupport.evaluateValue(
                    "number", 0, v, Map.of("status", "   ", "ampere", 0)))
                    .as("a blank string is absent, not an answer of \"\" — AlarmReadingHistory agrees")
                    .isEqualTo(FieldValidationSeverity.OK);
        }

        @Test
        @DisplayName("notIn suppresses on an unanswered sibling too, which reads as the opposite")
        void notInAlsoNeedsAnAnswer() {
            // «judge the current unless the status says off» — the natural way to write it, and
            // the fail-safe one: a state added to the vocabulary later keeps being judged, where
            // `in ["روشن"]` would silently stop judging it.
            Map<String, Object> v = gate("status", FieldGateOperators.NOT_IN, List.of("خاموش"), null,
                    FieldValidationSupport.build("number", null, 20.3, 31.9, NO_LOAD, 34.8, null, null));

            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("status", "روشن")))
                    .isEqualTo(FieldValidationSeverity.DANGER);
            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("status", "راه‌اندازی")))
                    .as("a state nobody listed is still judged — that is what notIn buys")
                    .isEqualTo(FieldValidationSeverity.DANGER);
            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("status", "خاموش")))
                    .isEqualTo(FieldValidationSeverity.OK);
            assertThat(FieldValidationSupport.evaluateValue("number", 40, v, Map.of("status", "خاموش")))
                    .as("ANY value is suppressed in the excluded state, not just the low ones")
                    .isEqualTo(FieldValidationSeverity.OK);

            // The edge that reads backwards: «the status is NOT off» sounds true of a round where
            // nobody answered the status at all. It is not. Every operator but `notAnswered`
            // needs an answer, because «we do not know» is not «it was in this state» — so a
            // round that recorded the current and skipped the status raises nothing. The fix for
            // that gap is `required` on the gate's parameter, not a looser gate.
            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("ampere", 0)))
                    .isEqualTo(FieldValidationSeverity.OK);
            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("status", "  ")))
                    .isEqualTo(FieldValidationSeverity.OK);
        }

        @Test
        @DisplayName("without the siblings a gated field is suppressed, never alerted")
        void theThreeArgumentFormSuppresses() {
            Map<String, Object> v = gatedNoLoadBand();

            // The documented safe direction: a caller that cannot supply the entry's answers
            // under-reports rather than reddening every stopped machine in the plant.
            assertThat(FieldValidationSupport.evaluateValue("number", 0, v))
                    .isEqualTo(FieldValidationSeverity.OK);
            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, null))
                    .isEqualTo(FieldValidationSeverity.OK);
        }

        @Test
        @DisplayName("the sibling key is matched case-insensitively, as every other key in this schema is")
        void siblingKeyIsCaseInsensitive() {
            Map<String, Object> v = gatedNoLoadBand();

            // ux_field_definitions_class_key_lower already makes Status and status the same
            // field. A case-sensitive gate would silently never match, which turns a danger band
            // into a parameter nothing judges.
            assertThat(FieldValidationSupport.evaluateValue(
                    "number", 0, v, Map.of("STATUS", "روشن")))
                    .isEqualTo(FieldValidationSeverity.DANGER);
        }

        @Test
        @DisplayName("the compared VALUES are case-sensitive, as the submit-time allow-list is")
        void gateValuesAreCaseSensitive() {
            Map<String, Object> v = gate("state", FieldGateOperators.IN, List.of("ON"), null,
                    FieldValidationSupport.build("number", null, null, null, NO_LOAD, null, null, null));

            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("state", "ON")))
                    .isEqualTo(FieldValidationSeverity.DANGER);
            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("state", "on")))
                    .as("a looser rule here would gate on a spelling the server refuses to store")
                    .isEqualTo(FieldValidationSeverity.OK);
            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("state", "  ON  ")))
                    .as("trimmed, though — exactly like evaluateOptionValue")
                    .isEqualTo(FieldValidationSeverity.DANGER);
        }

        @Test
        @DisplayName("notIn is the gate for «stopped but drawing current», the mirror fault")
        void theMirrorFault() {
            // On the STATUS field, not on the current: two faults, two alarms, two responses.
            Map<String, Object> v = new LinkedHashMap<>();
            v.put(FieldValidationSupport.KEY_OPTIONS, List.of("روشن", "خاموش"));
            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put(FieldValidationSupport.KEY_WHEN,
                    whenNumber("ampere", FieldGateOperators.GTE, NO_LOAD));
            condition.put(FieldValidationSupport.KEY_DANGER_OPTIONS, List.of("خاموش"));
            v.put(FieldValidationSupport.KEY_CONDITIONS, List.of(condition));

            assertThat(FieldValidationSupport.evaluateValue(
                    "select", "خاموش", v, Map.of("status", "خاموش", "ampere", 18)))
                    .as("declared stopped while drawing 18 A — a stuck contactor")
                    .isEqualTo(FieldValidationSeverity.DANGER);
            assertThat(FieldValidationSupport.evaluateValue(
                    "select", "خاموش", v, Map.of("status", "خاموش", "ampere", 0)))
                    .isEqualTo(FieldValidationSeverity.OK);
            assertThat(FieldValidationSupport.evaluateValue(
                    "select", "روشن", v, Map.of("status", "روشن", "ampere", 18)))
                    .isEqualTo(FieldValidationSeverity.OK);
        }

        @Test
        @DisplayName("a multiselect gate holds when ANY chosen option matches")
        void multiselectGate() {
            Map<String, Object> v = gate("modes", FieldGateOperators.IN, List.of("RUN"), null,
                    FieldValidationSupport.build("number", null, null, null, NO_LOAD, null, null, null));

            assertThat(FieldValidationSupport.evaluateValue(
                    "number", 0, v, Map.of("modes", List.of("LOCAL", "RUN"))))
                    .isEqualTo(FieldValidationSeverity.DANGER);
            assertThat(FieldValidationSupport.evaluateValue(
                    "number", 0, v, Map.of("modes", List.of("LOCAL", "STOP"))))
                    .isEqualTo(FieldValidationSeverity.OK);
            assertThat(FieldValidationSupport.evaluateValue(
                    "number", 0, v, Map.of("modes", List.of())))
                    .as("an empty multiselect is unanswered")
                    .isEqualTo(FieldValidationSeverity.OK);
        }

        @Test
        @DisplayName("answered / notAnswered ask only whether the sibling was filled in")
        void presenceOperators() {
            Map<String, Object> base =
                    FieldValidationSupport.build("number", null, null, null, NO_LOAD, null, null, null);
            Map<String, Object> onlyIfRead =
                    gate("status", FieldGateOperators.ANSWERED, null, null, base);
            Map<String, Object> onlyIfUnread =
                    gate("status", FieldGateOperators.NOT_ANSWERED, null, null, base);

            assertThat(FieldValidationSupport.evaluateValue("number", 0, onlyIfRead, Map.of("status", "خاموش")))
                    .isEqualTo(FieldValidationSeverity.DANGER);
            assertThat(FieldValidationSupport.evaluateValue("number", 0, onlyIfRead, Map.of("other", 1)))
                    .isEqualTo(FieldValidationSeverity.OK);

            assertThat(FieldValidationSupport.evaluateValue("number", 0, onlyIfUnread, Map.of("other", 1)))
                    .isEqualTo(FieldValidationSeverity.DANGER);
            assertThat(FieldValidationSupport.evaluateValue("number", 0, onlyIfUnread, Map.of("status", "خاموش")))
                    .isEqualTo(FieldValidationSeverity.OK);
        }

        @Test
        @DisplayName("every numeric comparison, at and around its bound")
        void numericOperators() {
            Map<String, Object> base =
                    FieldValidationSupport.build("number", null, null, null, NO_LOAD, null, null, null);

            assertThat(matches(gate("p", FieldGateOperators.GT, null, 10.0, base), 10)).isFalse();
            assertThat(matches(gate("p", FieldGateOperators.GT, null, 10.0, base), 10.1)).isTrue();
            assertThat(matches(gate("p", FieldGateOperators.GTE, null, 10.0, base), 10)).isTrue();
            assertThat(matches(gate("p", FieldGateOperators.LT, null, 10.0, base), 10)).isFalse();
            assertThat(matches(gate("p", FieldGateOperators.LT, null, 10.0, base), 9.9)).isTrue();
            assertThat(matches(gate("p", FieldGateOperators.LTE, null, 10.0, base), 10)).isTrue();
            assertThat(matches(gate("p", FieldGateOperators.GTE, null, 10.0, base), "12"))
                    .as("a number typed as text parses, exactly as the band evaluation parses it")
                    .isTrue();
            assertThat(matches(gate("p", FieldGateOperators.GTE, null, 10.0, base), "n/a"))
                    .as("an unparseable answer cannot satisfy a numeric comparison")
                    .isFalse();
        }

        @Test
        @DisplayName("a condition that declares its own bands states them completely")
        void ownBandsAreComplete() {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put(FieldValidationSupport.KEY_WARNING, Map.of("min", 20.0, "max", 32.0));
            v.put(FieldValidationSupport.KEY_DANGER, Map.of("min", NO_LOAD, "max", 34.8));
            Map<String, Object> stopped = new LinkedHashMap<>();
            stopped.put(FieldValidationSupport.KEY_WHEN,
                    whenOptions("status", FieldGateOperators.IN, List.of("خاموش")));
            // Only a DANGER maximum in this state. The field's own warning band is deliberately
            // NOT inherited: a state described as "danger only" has no warning band, and
            // inheriting one meant for the running state would alert on readings this state
            // calls normal.
            stopped.put(FieldValidationSupport.KEY_DANGER, Map.of("max", 0.05));
            v.put(FieldValidationSupport.KEY_CONDITIONS, List.of(stopped));

            Map<String, Object> off = Map.of("status", "خاموش");
            assertThat(FieldValidationSupport.evaluateValue("number", 18, v, off))
                    .as("18 A on a stopped pump breaches this state's own danger maximum")
                    .isEqualTo(FieldValidationSeverity.DANGER);
            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, off))
                    .as("and the running state's warning band is not applied here")
                    .isEqualTo(FieldValidationSeverity.OK);
        }

        @Test
        @DisplayName("the vocabulary survives a condition that re-marks its options")
        void optionsSurviveDeclaredThresholds() {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put(FieldValidationSupport.KEY_OPTIONS, List.of("A", "B"));
            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put(FieldValidationSupport.KEY_WHEN,
                    whenOptions("mode", FieldGateOperators.IN, List.of("X")));
            condition.put(FieldValidationSupport.KEY_DANGER_OPTIONS, List.of("B"));
            v.put(FieldValidationSupport.KEY_CONDITIONS, List.of(condition));

            Map<String, Object> thresholds =
                    FieldValidationSupport.thresholdsFor(v, Map.of("mode", "X"));
            assertThat(thresholds).containsEntry(FieldValidationSupport.KEY_OPTIONS, List.of("A", "B"));
            assertThat(thresholds).doesNotContainKey(FieldValidationSupport.KEY_CONDITIONS);
        }

        @Test
        @DisplayName("the first matching condition wins, in stored order")
        void firstMatchWins() {
            Map<String, Object> running = new LinkedHashMap<>();
            running.put(FieldValidationSupport.KEY_WHEN,
                    whenOptions("status", FieldGateOperators.IN, List.of("روشن")));
            running.put(FieldValidationSupport.KEY_DANGER, Map.of("min", NO_LOAD));
            Map<String, Object> anything = new LinkedHashMap<>();
            anything.put(FieldValidationSupport.KEY_WHEN,
                    whenPresence("status", FieldGateOperators.ANSWERED));
            anything.put(FieldValidationSupport.KEY_DANGER, Map.of("min", 1000.0));

            Map<String, Object> v = new LinkedHashMap<>();
            v.put(FieldValidationSupport.KEY_CONDITIONS, List.of(running, anything));

            assertThat(FieldValidationSupport.evaluateValue("number", 100, v, Map.of("status", "روشن")))
                    .as("the running condition matched first, and 100 is inside its band")
                    .isEqualTo(FieldValidationSeverity.OK);
            assertThat(FieldValidationSupport.evaluateValue("number", 100, v, Map.of("status", "خاموش")))
                    .as("the catch-all matched instead, and 100 is below its minimum")
                    .isEqualTo(FieldValidationSeverity.DANGER);
        }
    }

    @Nested
    @DisplayName("anything unreadable suppresses, and nothing throws")
    class Defensive {

        @Test
        @DisplayName("an unknown operator never matches")
        void unknownOperator() {
            Map<String, Object> v = gate("status", "approximately", List.of("روشن"), null,
                    FieldValidationSupport.build("number", null, null, null, NO_LOAD, null, null, null));

            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("status", "روشن")))
                    .isEqualTo(FieldValidationSeverity.OK);
        }

        @Test
        @DisplayName("a conditions key that is not a list reads as «no conditions»")
        void malformedConditionsFallBackToTheBaseBands() {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put(FieldValidationSupport.KEY_DANGER, Map.of("min", NO_LOAD));
            v.put(FieldValidationSupport.KEY_CONDITIONS, "when the pump is on");

            // Restoring the pre-gate behaviour, which is a band that works, beats suppressing a
            // danger band because a hand edit put a string where a list belongs.
            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("status", "روشن")))
                    .isEqualTo(FieldValidationSeverity.DANGER);
            assertThat(FieldValidationSupport.conditions(v)).isEmpty();
        }

        @Test
        @DisplayName("a condition with no readable gate suppresses rather than applying its bands")
        void gatelessCondition() {
            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put(FieldValidationSupport.KEY_DANGER, Map.of("min", NO_LOAD));
            Map<String, Object> v = new LinkedHashMap<>();
            v.put(FieldValidationSupport.KEY_CONDITIONS, List.of(condition));

            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("status", "روشن")))
                    .isEqualTo(FieldValidationSeverity.OK);
            assertThat(FieldValidationSupport.summaryFa(v)).contains("نامعتبر");
        }

        @Test
        @DisplayName("a gate whose operand is missing never matches")
        void missingOperand() {
            Map<String, Object> base =
                    FieldValidationSupport.build("number", null, null, null, NO_LOAD, null, null, null);

            assertThat(FieldValidationSupport.evaluateValue("number", 0,
                    gate("status", FieldGateOperators.IN, List.of(), null, base),
                    Map.of("status", "روشن"))).isEqualTo(FieldValidationSeverity.OK);
            assertThat(FieldValidationSupport.evaluateValue("number", 0,
                    gate("status", FieldGateOperators.GTE, null, null, base),
                    Map.of("status", 5))).isEqualTo(FieldValidationSeverity.OK);
        }

        @Test
        @DisplayName("null validation and an empty sibling map are answers, not exceptions")
        void nullsAreSafe() {
            assertThat(FieldValidationSupport.evaluateValue("number", 0, null, Map.of()))
                    .isEqualTo(FieldValidationSeverity.OK);
            assertThat(FieldValidationSupport.conditions(null)).isEmpty();
            assertThat(FieldValidationSupport.gateOf(null)).isEmpty();
            assertThat(FieldValidationSupport.gateSummaryFa(null)).isNull();
            assertThat(FieldValidationSupport.gateMatches(Map.of(), Map.of())).isFalse();
        }
    }

    @Nested
    @DisplayName("build refuses a gate it cannot store meaningfully")
    class Building {

        @Test
        @DisplayName("the editor's single gate switches the field's OWN bands on and off")
        void buildWritesAGateWithNoBandsOfItsOwn() {
            Map<String, Object> v = FieldValidationSupport.build(
                    "number", null, null, null, NO_LOAD, 34.8, null, null, null, true,
                    new FieldValidationSupport.GateSpec("status", FieldGateOperators.IN, "روشن"));

            assertThat(FieldValidationSupport.conditions(v)).hasSize(1);
            assertThat(FieldValidationSupport.conditions(v).get(0))
                    .as("no thresholds of its own — the field's bands are what it gates")
                    .containsOnlyKeys(FieldValidationSupport.KEY_WHEN);
            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("status", "روشن")))
                    .isEqualTo(FieldValidationSeverity.DANGER);
            assertThat(FieldValidationSupport.evaluateValue("number", 0, v, Map.of("status", "خاموش")))
                    .isEqualTo(FieldValidationSeverity.OK);
        }

        @Test
        @DisplayName("a blank gate leaves the key out entirely, so an ungated field stays ungated")
        void blankGateWritesNothing() {
            Map<String, Object> v = FieldValidationSupport.build(
                    "number", null, null, null, NO_LOAD, null, null, null, null, true,
                    new FieldValidationSupport.GateSpec("", "", ""));

            assertThat(v).doesNotContainKey(FieldValidationSupport.KEY_CONDITIONS);
        }

        @Test
        @DisplayName("the ten-argument overload writes no gate — a sheet-level question has no siblings")
        void theNarrowOverloadWritesNoGate() {
            Map<String, Object> v = FieldValidationSupport.build(
                    "number", null, null, null, NO_LOAD, null, null, null, null, true);

            assertThat(v).doesNotContainKey(FieldValidationSupport.KEY_CONDITIONS);
        }

        @Test
        @DisplayName("refused, not ignored: a silently dropped gate is a false alarm on every stopped machine")
        void refusals() {
            assertThatThrownBy(() -> FieldValidationSupport.build(
                    "text", null, null, null, null, null, null, null, null, true,
                    new FieldValidationSupport.GateSpec("status", FieldGateOperators.IN, "روشن")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("number and select");

            assertThatThrownBy(() -> FieldValidationSupport.build(
                    "number", null, null, null, NO_LOAD, null, null, null, null, true,
                    new FieldValidationSupport.GateSpec("status", "roughly", "روشن")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("operator");

            assertThatThrownBy(() -> FieldValidationSupport.build(
                    "number", null, null, null, NO_LOAD, null, null, null, null, true,
                    new FieldValidationSupport.GateSpec("status", FieldGateOperators.IN, "   ")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one value");

            assertThatThrownBy(() -> FieldValidationSupport.build(
                    "number", null, null, null, NO_LOAD, null, null, null, null, true,
                    new FieldValidationSupport.GateSpec("ampere", FieldGateOperators.GTE, "nope")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("a number");
        }

        @Test
        @DisplayName("a presence operator stores no operand even when one was typed")
        void presenceOperatorStoresNoOperand() {
            Map<String, Object> v = FieldValidationSupport.build(
                    "number", null, null, null, NO_LOAD, null, null, null, null, true,
                    new FieldValidationSupport.GateSpec("status", FieldGateOperators.ANSWERED, "ignored"));

            assertThat(FieldValidationSupport.gateOf(FieldValidationSupport.conditions(v).get(0)))
                    .containsOnlyKeys(FieldValidationSupport.KEY_GATE_FIELD,
                            FieldValidationSupport.KEY_GATE_OP);
        }
    }

    @Nested
    @DisplayName("the summary says WHEN, not only how much")
    class Summary {

        @Test
        @DisplayName("the gate is part of the one line a reader gets")
        void gateIsInTheSummary() {
            String summary = FieldValidationSupport.summaryFa(gatedNoLoadBand());

            // A band printed without its condition IS the rule as far as the reader is
            // concerned, and «خطر: ≥ 4.5» on a standby pump reads as a number to re-tune.
            assertThat(summary)
                    .contains("فقط وقتی")
                    .contains("status")
                    .contains("روشن")
                    .contains("4.5");
        }

        @Test
        @DisplayName("a numeric gate reads as a number, with no trailing .0")
        void numericGateSummary() {
            Map<String, Object> v = gate("ampere", FieldGateOperators.GTE, null, 4.5,
                    FieldValidationSupport.build("select", "روشن,خاموش", null, null, null, null, null, "خاموش"));
            assertThat(FieldValidationSupport.gateSummaryFa(
                    FieldValidationSupport.gateOf(FieldValidationSupport.conditions(v).get(0))))
                    .isEqualTo("ampere " + FieldGateOperators.label(FieldGateOperators.GTE) + " 4.5");

            Map<String, Object> whole = gate("ampere", FieldGateOperators.GTE, null, 10.0, Map.of());
            assertThat(FieldValidationSupport.gateSummaryFa(
                    FieldValidationSupport.gateOf(FieldValidationSupport.conditions(whole).get(0))))
                    .endsWith(" 10");
        }

        @Test
        @DisplayName("an ungated field's summary is byte-for-byte what it always was")
        void ungatedSummaryUnchanged() {
            Map<String, Object> v = FieldValidationSupport.build(
                    "number", null, 20.0, 80.0, 10.0, 90.0, null, null);

            assertThat(FieldValidationSupport.summaryFa(v))
                    .isEqualTo(FieldValidationSupport.thresholdSummaryFa(v))
                    .isEqualTo("هشدار: 20–80 · خطر: 10–90");
        }
    }

    // -------------------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------------------

    /** «The current band applies only while the status says running.» */
    private static Map<String, Object> gatedNoLoadBand() {
        return FieldValidationSupport.build(
                "number", null, null, null, NO_LOAD, 34.8, null, null, null, true,
                new FieldValidationSupport.GateSpec("status", FieldGateOperators.IN, "روشن"));
    }

    private static Map<String, Object> gate(String field, String op, List<String> values,
                                             Double value, Map<String, Object> base) {
        Map<String, Object> when = new LinkedHashMap<>();
        when.put(FieldValidationSupport.KEY_GATE_FIELD, field);
        when.put(FieldValidationSupport.KEY_GATE_OP, op);
        if (values != null) {
            when.put(FieldValidationSupport.KEY_GATE_VALUES, values);
        }
        if (value != null) {
            when.put(FieldValidationSupport.KEY_GATE_VALUE, value);
        }
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put(FieldValidationSupport.KEY_WHEN, when);
        Map<String, Object> out = new LinkedHashMap<>(base == null ? Map.of() : base);
        out.put(FieldValidationSupport.KEY_CONDITIONS, List.of(condition));
        return out;
    }

    private static Map<String, Object> whenOptions(String field, String op, List<String> values) {
        Map<String, Object> when = new LinkedHashMap<>();
        when.put(FieldValidationSupport.KEY_GATE_FIELD, field);
        when.put(FieldValidationSupport.KEY_GATE_OP, op);
        when.put(FieldValidationSupport.KEY_GATE_VALUES, values);
        return when;
    }

    private static Map<String, Object> whenNumber(String field, String op, double value) {
        Map<String, Object> when = new LinkedHashMap<>();
        when.put(FieldValidationSupport.KEY_GATE_FIELD, field);
        when.put(FieldValidationSupport.KEY_GATE_OP, op);
        when.put(FieldValidationSupport.KEY_GATE_VALUE, value);
        return when;
    }

    private static Map<String, Object> whenPresence(String field, String op) {
        Map<String, Object> when = new LinkedHashMap<>();
        when.put(FieldValidationSupport.KEY_GATE_FIELD, field);
        when.put(FieldValidationSupport.KEY_GATE_OP, op);
        return when;
    }

    /** True when the gate of this single-condition validation holds for {@code p = value}. */
    private static boolean matches(Map<String, Object> validation, Object value) {
        return FieldValidationSupport.gateMatches(
                FieldValidationSupport.gateOf(FieldValidationSupport.conditions(validation).get(0)),
                mapOf("p", value, null, null));
    }

    /** A map that tolerates a null value, which {@code Map.of} refuses. */
    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(k1, v1);
        if (k2 != null) {
            out.put(k2, v2);
        }
        return out;
    }
}
