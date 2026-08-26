package com.hnp.backendofflinefirst.web.advice;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query string a pagination link has to carry forward.
 *
 * <p>The bug being pinned: {@code fragments/list-toolbar :: pagination} used to enumerate four
 * parameter names, so any page with a filter called anything else showed a filtered first page
 * and an <em>unfiltered</em> second one — silently, with the only symptom being that the rows
 * stopped matching.
 */
class ListFilterAdviceTest {

    private final ListFilterAdvice advice = new ListFilterAdvice();

    @Test
    void nothingToCarryProducesAnEmptyString() {
        // Appended to a link unconditionally, so it must never render the word "null".
        assertThat(advice.filterQuery(new MockHttpServletRequest())).isEmpty();
    }

    /**
     * Every parameter, whatever it is called. This is the whole point: {@code assetId} and
     * {@code fieldKey} are exactly the names the enumerated version did not know about.
     */
    @Test
    void everyFilterIsCarriedRegardlessOfItsName() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("status", "OPEN");
        request.setParameter("assetId", "42");
        request.setParameter("fieldKey", "temp");

        String query = advice.filterQuery(request);

        assertThat(query).contains("&status=OPEN");
        assertThat(query).contains("&assetId=42");
        assertThat(query).contains("&fieldKey=temp");
    }

    /**
     * The pager supplies both itself. A duplicated {@code page} is not merely untidy: Spring binds
     * the first occurrence, so «بعدی» would navigate to the page you are already on.
     */
    @Test
    void thePagersOwnParametersAreDropped() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("page", "3");
        request.setParameter("size", "50");
        request.setParameter("status", "OPEN");

        String query = advice.filterQuery(request);

        assertThat(query).isEqualTo("&status=OPEN");
    }

    /** An empty filter is the same as no filter everywhere in this panel. */
    @Test
    void blankValuesAreNotDraggedAlong() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("status", "");
        request.setParameter("q", "pump");

        assertThat(advice.filterQuery(request)).isEqualTo("&q=pump");
    }

    /**
     * Values are encoded, not trusted. An unescaped {@code &} in a search term would otherwise
     * split into a parameter of its own and change what the next page queries for.
     */
    @Test
    void valuesAreUrlEncoded() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("q", "a&b=c d");

        String query = advice.filterQuery(request);

        assertThat(query).doesNotContain("a&b");
        assertThat(query).startsWith("&q=");
        assertThat(query.split("&", -1)).hasSize(2);
    }

    /** A multi-valued parameter keeps all of its values, not just the first. */
    @Test
    void repeatedParametersAreAllCarried() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("unitId", "1", "2");

        assertThat(advice.filterQuery(request)).isEqualTo("&unitId=1&unitId=2");
    }
}
