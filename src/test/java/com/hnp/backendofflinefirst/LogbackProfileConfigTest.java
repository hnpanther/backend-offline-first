package com.hnp.backendofflinefirst;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both log formats stay wired up in {@code logback-spring.xml}, and the two profile branches
 * stay in step.
 *
 * <h2>What is being guarded</h2>
 *
 * <p>The README offers {@code SPRING_PROFILES_ACTIVE=json-logs} as the way to ship logs to
 * Filebeat/Logstash. Every failure mode here is <b>silent</b>: an appender that loses its JSON
 * encoder still logs perfectly happily, in text, into a pipeline that indexes none of it; an
 * appender missing from one branch simply writes nothing under that profile. Nobody finds out
 * until they need the logs.
 *
 * <h2>Why the file declares its five appenders twice, and why that is safe</h2>
 *
 * <p>{@code <springProfile>} used to be nested <em>inside</em> each appender to pick its encoder.
 * That printed eleven {@code SpringProfileIfNestedWithinSecondPhaseElementSanityChecker} warnings
 * at every startup, and — worse than the noise — it worked by accident, on a construction Spring
 * Boot explicitly rejects. Depending on unsupported behaviour to guard a silent failure is the
 * wrong way round, so the profile now wraps the appenders from outside, which is the supported
 * form. The price is one duplicated block.
 *
 * <p>The objection to that duplication was always drift: a rotation policy or a size cap that
 * differs between the branches loses production logs in whichever format nobody is reading.
 * {@link #theTwoBranchesAgreeOnEverythingButTheEncoder()} removes the objection by comparing the
 * branches element by element, so a divergence fails the build instead of shipping.
 *
 * <h2>Why this is a structural check and not a runtime one</h2>
 *
 * <p>Logback's configuration is global to the JVM and Spring caches test contexts, so whichever
 * profile last initialised logging wins for every test that follows. A pair of runtime tests
 * asserting "json here, text there" passes or fails on execution order alone — that was tried,
 * and it did exactly that. A flaky test over a shipping feature is worse than none.
 *
 * <p>(An earlier version of the header in {@code logback-spring.xml} credited two classes,
 * {@code JsonLogProfileIntegrationTest} and {@code PlainTextLogProfileIntegrationTest}, with
 * asserting the runtime resolution. Neither has ever existed in this repository. This class is
 * the whole of the automated coverage; the runtime behaviour is confirmed by hand at the boot
 * described in the README's startup-warnings table.)
 */
class LogbackProfileConfigTest {

    private static final String CONFIG = "src/main/resources/logback-spring.xml";

    private static final String JSON_PROFILE = "json-logs";
    private static final String TEXT_PROFILE = "!json-logs";

    /** The appenders that own an encoder, and therefore have to exist in both branches. */
    private static final List<String> ENCODING_APPENDERS =
            List.of("CONSOLE", "FILE", "BUSINESS_FILE", "AUDIT_FILE", "ERROR_FILE");

    // ─────────────────────────────────────────────────────────────────────────
    // Both branches exist, and hold the same appenders
    // ─────────────────────────────────────────────────────────────────────────

    /** Losing a branch is how every appender silently ends up in the wrong format. */
    @Test
    void bothProfileBranchesArePresentAtTheTopLevel() throws Exception {
        assertThat(branchNames())
                .as("the two top-level <springProfile> branches of logback-spring.xml")
                .containsExactlyInAnyOrder(JSON_PROFILE, TEXT_PROFILE);
    }

    /**
     * Every encoder-owning appender is declared in both branches.
     *
     * <p>An appender present in only one is not a formatting problem but a missing file: the
     * loggers below reference it by name, and under the other profile there is nothing to
     * resolve.
     */
    @Test
    void everyAppenderIsDeclaredInBothBranches() throws Exception {
        assertThat(appenderNamesIn(JSON_PROFILE))
                .as("appenders under " + JSON_PROFILE)
                .containsExactlyInAnyOrderElementsOf(ENCODING_APPENDERS);
        assertThat(appenderNamesIn(TEXT_PROFILE))
                .as("appenders under " + TEXT_PROFILE)
                .containsExactlyInAnyOrderElementsOf(ENCODING_APPENDERS);
    }

    /**
     * Every {@code appender-ref} outside the branches names an appender both branches declare.
     *
     * <p>The AsyncAppenders, the loggers and {@code <root>} sit outside the profile blocks and
     * resolve against whichever branch was kept. A reference that only one branch satisfies is a
     * stream that goes silent under the other profile — which logback reports, at startup, as a
     * line nobody reads.
     */
    @Test
    void everyAppenderRefResolvesUnderEitherProfile() throws Exception {
        List<String> declaredInBoth = appenderNamesIn(JSON_PROFILE).stream()
                .filter(appenderNamesIn(TEXT_PROFILE)::contains)
                .toList();

        List<String> unresolvable = new ArrayList<>();
        for (Element ref : elementsByTag("appender-ref")) {
            String name = ref.getAttribute("ref");
            boolean satisfied = declaredInBoth.contains(name) || asyncAppenderNames().contains(name);
            if (!satisfied) {
                unresolvable.add(name);
            }
        }

        assertThat(unresolvable)
                .as("appender-refs naming an appender that is missing under one of the profiles")
                .isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The branches agree — this is what makes the duplication safe
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The two branches are identical in everything except their encoders.
     *
     * <p>This is the test that pays for the duplicated block. File paths, rolling policies,
     * histories, size caps and the ERROR threshold filter must read the same under both formats;
     * a value that drifts loses production logs in whichever format nobody is watching, and does
     * so quietly. Comparing the branches structurally catches that at build time.
     *
     * <p>The {@code <encoder>} subtree is the one deliberate difference, so it is removed from
     * both sides before comparing — its content is asserted separately below.
     */
    @Test
    void theTwoBranchesAgreeOnEverythingButTheEncoder() throws Exception {
        Map<String, String> json = skeletonsIn(JSON_PROFILE);
        Map<String, String> text = skeletonsIn(TEXT_PROFILE);

        assertThat(json.keySet()).isEqualTo(text.keySet());
        for (String name : json.keySet()) {
            assertThat(text.get(name))
                    .as("""
                        appender %s differs between the json-logs and default branches in \
                        something other than its encoder. Everything but the encoder must match: \
                        a rotation policy, path or size cap that drifts between the two formats \
                        loses logs in whichever one is not being read. Values belong in the \
                        properties at the top of logback-spring.xml, referenced by both branches.\
                        """.formatted(name))
                    .isEqualTo(json.get(name));
        }
    }

    /**
     * Nothing rotational is written as a literal inside a branch.
     *
     * <p>The agreement test above catches a value that has already drifted. This one removes the
     * opportunity: if archive patterns, histories and caps are only ever {@code ${…}} references
     * to the single definition at the top of the file, the branches cannot disagree about them in
     * the first place.
     */
    @Test
    void rotationSettingsAreReferencesRatherThanLiterals() throws Exception {
        List<String> literals = new ArrayList<>();
        for (String profile : List.of(JSON_PROFILE, TEXT_PROFILE)) {
            for (Element appender : appendersIn(profile)) {
                for (Element policy : childElements(appender, "rollingPolicy")) {
                    for (Element setting : childElements(policy)) {
                        String value = setting.getTextContent().trim();
                        if (!value.startsWith("${") || !value.endsWith("}")) {
                            literals.add("%s/%s/%s = %s".formatted(
                                    profile, appender.getAttribute("name"),
                                    setting.getNodeName(), value));
                        }
                    }
                }
            }
        }

        assertThat(literals)
                .as("rolling-policy settings hard-coded inside a profile branch — these can drift "
                    + "between the two formats; define them once as properties instead")
                .isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The encoders themselves
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The JSON branch really uses the Logstash encoder.
     *
     * <p>A branch named {@code json-logs} holding an ordinary pattern encoder satisfies every
     * structural check above and still ships text to Elasticsearch.
     */
    @Test
    void theJsonBranchUsesTheLogstashEncoderEverywhere() throws Exception {
        for (Element appender : appendersIn(JSON_PROFILE)) {
            List<Element> encoders = childElements(appender, "encoder");
            assertThat(encoders)
                    .as("encoder of " + appender.getAttribute("name") + " under json-logs")
                    .hasSize(1);
            assertThat(encoders.getFirst().getAttribute("class"))
                    .as("encoder class of " + appender.getAttribute("name") + " under json-logs")
                    .contains("logstash");
        }
    }

    /** And the default branch really uses a pattern, in UTF-8 — Persian text is in these files. */
    @Test
    void theDefaultBranchUsesAPatternEncoderInUtf8() throws Exception {
        for (Element appender : appendersIn(TEXT_PROFILE)) {
            String name = appender.getAttribute("name");
            List<Element> encoders = childElements(appender, "encoder");
            assertThat(encoders).as("encoder of " + name + " by default").hasSize(1);

            Element encoder = encoders.getFirst();
            assertThat(encoder.getAttribute("class"))
                    .as("the default encoder of " + name + " must be logback's own")
                    .doesNotContain("logstash");
            assertThat(text(encoder, "pattern"))
                    .as("pattern of " + name)
                    .startsWith("${").endsWith("}");
            assertThat(text(encoder, "charset"))
                    .as("charset of " + name + " — these files carry Persian message text")
                    .isEqualTo("UTF-8");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The warnings must not come back
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * No {@code <springProfile>} is nested inside an appender, logger or root.
     *
     * <p>That nesting is what produced the eleven startup warnings, and it is an easy thing to
     * reintroduce while adding a sixth appender — it reads as the natural place to put it. Spring
     * Boot rejects the construction; that it currently still works is not something to build on.
     */
    @Test
    void noProfileIsNestedInsideAnAppenderOrLogger() throws Exception {
        List<String> nested = new ArrayList<>();
        for (String tag : List.of("appender", "logger", "root")) {
            for (Element element : elementsByTag(tag)) {
                if (!childElements(element, "springProfile").isEmpty()) {
                    nested.add(tag + " " + element.getAttribute("name"));
                }
            }
        }

        assertThat(nested)
                .as("<springProfile> nested inside a second-phase element — Spring Boot rejects "
                    + "this with SpringProfileIfNestedWithinSecondPhaseElementSanityChecker at "
                    + "every startup. Wrap the whole appender from the top level instead.")
                .isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reading the file
    // ─────────────────────────────────────────────────────────────────────────

    private Document document() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new File(CONFIG));
    }

    private List<Element> elementsByTag(String tag) throws Exception {
        NodeList nodes = document().getElementsByTagName(tag);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    /** The top-level profile branches, keyed by their {@code name} attribute. */
    private List<String> branchNames() throws Exception {
        return topLevelProfiles().stream().map(e -> e.getAttribute("name")).toList();
    }

    private List<Element> topLevelProfiles() throws Exception {
        return childElements(document().getDocumentElement(), "springProfile");
    }

    private List<Element> appendersIn(String profile) throws Exception {
        for (Element branch : topLevelProfiles()) {
            if (profile.equals(branch.getAttribute("name"))) {
                return childElements(branch, "appender");
            }
        }
        return List.of();
    }

    private List<String> appenderNamesIn(String profile) throws Exception {
        return appendersIn(profile).stream().map(a -> a.getAttribute("name")).toList();
    }

    /** Appenders declared outside the branches — the async wrappers. */
    private List<String> asyncAppenderNames() throws Exception {
        return childElements(document().getDocumentElement(), "appender").stream()
                .map(a -> a.getAttribute("name"))
                .toList();
    }

    /**
     * Each appender of a branch rendered as canonical text with its {@code <encoder>} removed.
     *
     * <p>Comments and whitespace are dropped so that formatting cannot make two identical
     * configurations compare unequal; attributes are sorted so that attribute order cannot
     * either. What remains is the configuration itself.
     */
    private Map<String, String> skeletonsIn(String profile) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (Element appender : appendersIn(profile)) {
            out.put(appender.getAttribute("name"), canonical(appender));
        }
        return out;
    }

    private String canonical(Element element) {
        StringBuilder sb = new StringBuilder();
        canonicalise(element, sb);
        return sb.toString();
    }

    private void canonicalise(Element element, StringBuilder sb) {
        if ("encoder".equals(element.getNodeName())) {
            return;   // the one deliberate difference between the branches
        }
        sb.append('<').append(element.getNodeName());

        Map<String, String> attributes = new TreeMap<>();
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node attribute = element.getAttributes().item(i);
            attributes.put(attribute.getNodeName(), attribute.getNodeValue());
        }
        attributes.forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(v).append('"'));
        sb.append('>');

        List<Element> children = childElements(element);
        if (children.isEmpty()) {
            sb.append(element.getTextContent().trim());
        } else {
            children.forEach(child -> canonicalise(child, sb));
        }
        sb.append("</").append(element.getNodeName()).append('>');
    }

    private String text(Element parent, String tag) {
        List<Element> found = childElements(parent, tag);
        return found.isEmpty() ? null : found.getFirst().getTextContent().trim();
    }

    private List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private List<Element> childElements(Element parent, String tag) {
        return childElements(parent).stream().filter(e -> tag.equals(e.getNodeName())).toList();
    }
}
