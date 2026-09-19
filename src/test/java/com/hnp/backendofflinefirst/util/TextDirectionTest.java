package com.hnp.backendofflinefirst.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextDirectionTest {

    /** The reported case: a Persian note that happens to open with an English word. */
    @Test
    void aPersianSentenceOpeningWithALatinWordIsStillRightToLeft() {
        assertThat(TextDirection.forValue("Tgdd پمپ ۱۲ خرابه")).isEqualTo("rtl");
    }

    /** A tag code first is the everyday form of the same thing. */
    @Test
    void aPersianSentenceOpeningWithATagCodeIsRightToLeft() {
        assertThat(TextDirection.forValue("P-0101A نشتی دارد")).isEqualTo("rtl");
    }

    @Test
    void aPersianSentenceIsRightToLeft() {
        assertThat(TextDirection.forValue("پمپ ۱۲ خرابه")).isEqualTo("rtl");
    }

    /**
     * More Latin letters than Persian ones, and still a Persian sentence: this is the case a
     * majority count gets wrong, which is why the rule is «any RTL letter», not «most».
     */
    @Test
    void aPersianSentenceWithMoreLatinLettersThanPersianIsStillRightToLeft() {
        assertThat(TextDirection.forValue("پمپ P-0101A OK")).isEqualTo("rtl");
    }

    /** No Persian at all: the browser's default (left-to-right for Latin) is the right answer. */
    @Test
    void latinOnlyTextIsLeftToTheBrowser() {
        assertThat(TextDirection.forValue("OK")).isNull();
        assertThat(TextDirection.forValue("Pump checked.")).isNull();
        assertThat(TextDirection.forValue("P-0101A")).isNull();
    }

    /** Numbers and Persian digits are weak, not strong, and must not force a direction. */
    @Test
    void digitsAloneForceNothing() {
        assertThat(TextDirection.forValue("17")).isNull();
        assertThat(TextDirection.forValue("-5.25")).isNull();
        assertThat(TextDirection.forValue("۱۲")).isNull();
    }

    @Test
    void nullAndEmptyForceNothing() {
        assertThat(TextDirection.forValue(null)).isNull();
        assertThat(TextDirection.forValue("")).isNull();
        assertThat(TextDirection.forValue("—")).isNull();
    }

    /** A multiselect renders as values joined by «، »; one Persian option is enough. */
    @Test
    void aJoinedListWithOnePersianOptionIsRightToLeft() {
        assertThat(TextDirection.forValue("IDLE، روشن")).isEqualTo("rtl");
        assertThat(TextDirection.forValue("IDLE، ON")).isNull();
    }
}
