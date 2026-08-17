package com.hnp.backendofflinefirst;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both log formats stay wired up in {@code logback-spring.xml}.
 *
 * <p>The README offers `SPRING_PROFILES_ACTIVE=json-logs` as the way to ship logs to
 * Filebeat/Logstash. A shipper pointed at a directory of human-readable text indexes nothing
 * useful, and the application logs perfectly happily either way — so the failure is silent, and
 * the thing worth guarding is that every appender still declares both branches.
 *
 * <p><b>Why this is a structural check rather than a runtime one.</b> Logback's configuration is
 * global to the JVM, and Spring caches test contexts, so whichever profile last initialised
 * logging wins for every test that follows. A pair of runtime tests asserting "json here, text
 * there" passes or fails on execution order alone — tried, and it did exactly that. A flaky test
 * over a shipping feature is worse than none.
 *
 * <p>The runtime behaviour was verified by hand instead, and is worth recording: under
 * `json-logs` the console appender resolved to {@code LogstashEncoder}, and without it to
 * {@code PatternLayoutEncoder}. That is despite the eleven
 * {@code SpringProfileIfNestedWithinSecondPhaseElementSanityChecker} warnings printed at every
 * startup, which object to {@code <springProfile>} being nested inside an {@code <appender>} —
 * the nesting is not documented as supported, and it works. See the header of
 * `logback-spring.xml`.
 */
class LogbackProfileConfigTest {

    private static final String CONFIG = "src/main/resources/logback-spring.xml";

    @Test
    void everyAppenderOffersBothAJsonAndAPlainTextEncoder() throws Exception {
        List<String> missingJson = new ArrayList<>();
        List<String> missingText = new ArrayList<>();
        int appenders = 0;

        for (Element appender : encodingAppenders()) {
            appenders++;
            String name = appender.getAttribute("name");
            List<Element> profiles = childElements(appender, "springProfile");
            boolean json = profiles.stream().anyMatch(p -> "json-logs".equals(p.getAttribute("name")));
            boolean text = profiles.stream().anyMatch(p -> "!json-logs".equals(p.getAttribute("name")));
            if (!json) missingJson.add(name);
            if (!text) missingText.add(name);
        }

        // Cannot pass by finding nothing.
        assertThat(appenders).isGreaterThanOrEqualTo(5);
        assertThat(missingJson)
                .as("appenders with no json-logs encoder — these would stay human-readable under the profile")
                .isEmpty();
        assertThat(missingText)
                .as("appenders with no default encoder — these would go silent without the profile")
                .isEmpty();
    }

    @Test
    void theJsonBranchReallyUsesTheLogstashEncoder() throws Exception {
        // A `<springProfile name="json-logs">` holding an ordinary pattern encoder would satisfy
        // the structure above and still ship text.
        for (Element appender : encodingAppenders()) {
            for (Element profile : childElements(appender, "springProfile")) {
                if (!"json-logs".equals(profile.getAttribute("name"))) continue;
                List<Element> encoders = childElements(profile, "encoder");
                assertThat(encoders)
                        .as("json-logs encoder of appender " + appender.getAttribute("name"))
                        .isNotEmpty();
                assertThat(encoders.get(0).getAttribute("class"))
                        .as("json-logs encoder class of appender " + appender.getAttribute("name"))
                        .contains("logstash");
            }
        }
    }

    /**
     * The appenders that own an encoder.
     *
     * <p>`AsyncAppender` is excluded because it has none: it is a queue in front of another
     * appender and forwards events untouched, so the format is decided by the appender it wraps.
     * Requiring encoders of it would be requiring something that cannot exist.
     */
    private List<Element> encodingAppenders() throws Exception {
        return appenders().stream()
                .filter(a -> !a.getAttribute("class").endsWith("AsyncAppender"))
                .toList();
    }

    private List<Element> appenders() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder().parse(new File(CONFIG));
        NodeList nodes = doc.getElementsByTagName("appender");
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) out.add((Element) nodes.item(i));
        return out;
    }

    private List<Element> childElements(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) out.add((Element) n);
        }
        return out;
    }
}
