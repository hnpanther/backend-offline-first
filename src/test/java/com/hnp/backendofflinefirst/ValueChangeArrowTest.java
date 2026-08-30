package com.hnp.backendofflinefirst;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A before/after pair must be laid out by flexbox, never by inline text flow.
 *
 * <h2>The trap</h2>
 *
 * <p>Markup like {@code <span>old</span> <i class="bi-arrow-left"></i> <span>new</span>} looks
 * obviously correct on an RTL page: reading right to left gives old, then new, and the arrow
 * points left, which is forward. It is correct — but only for some values.
 *
 * <p>The Unicode bidi algorithm reorders runs inside a paragraph. A run of Latin letters or
 * digits sitting in an RTL paragraph is laid out <b>left to right</b>, so the same markup puts
 * {@code old} on the LEFT when the values are Latin and on the RIGHT when they are Persian. The
 * arrow glyph cannot mirror itself, so for half the rows it pointed from the new value back to
 * the old one. Measured in a browser, on one table where rows disagreed with each other:
 * {@code ثبت نشده → OF} rendered correctly while {@code Idle → ON}, three rows below it, was
 * reversed.
 *
 * <h2>Why flex fixes it</h2>
 *
 * <p>Flex items are ordered by the container's {@code direction}, not by bidi. Under the page's
 * {@code rtl} the first child lands on the right whatever script it contains, so the arrow's
 * meaning stops depending on the data. That is what {@code .value-change} does, and it is why
 * {@code .log-detail-assignment-change} — flex since it was written — never had the bug.
 *
 * <h2>What this checks</h2>
 *
 * <p>Every arrow that sits between two values must be inside a container this stylesheet lays
 * out with flex. A new one written as plain inline markup fails here rather than in the field.
 */
class ValueChangeArrowTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    /**
     * Both stylesheets, because the panel's layout lives in both.
     *
     * <p>This read {@code app.css} alone to begin with, and that was a blind spot rather than a
     * shortcut: {@code enterprise.css} carries roughly as many flex rules, so an arrow correctly
     * wrapped in a container styled <em>there</em> was reported as an offender. The dashboard
     * shortcut cards are exactly that case — {@code .dashboard-shortcut} is {@code display: flex},
     * declared in the file this test could not see.
     */
    private static final List<Path> STYLESHEETS = List.of(
            Path.of("src/main/resources/static/css/app.css"),
            Path.of("src/main/resources/static/css/enterprise.css"));

    /**
     * Classes this stylesheet gives {@code display: flex} (or {@code inline-flex}).
     *
     * <p>Read out of the CSS rather than hardcoded, so deleting the rule fails the test instead
     * of leaving it passing against a class that no longer lays anything out.
     */
    private static List<String> flexClasses() throws IOException {
        List<String> classes = new ArrayList<>();
        for (Path stylesheet : STYLESHEETS) {
            String css = Files.readString(stylesheet, StandardCharsets.UTF_8);
            Matcher rule = Pattern.compile("([^{}]+)\\{([^}]*)}").matcher(css);
            while (rule.find()) {
                if (!Pattern.compile("display\\s*:\\s*(inline-)?flex").matcher(rule.group(2)).find()) {
                    continue;
                }
                Matcher name = Pattern.compile("\\.([A-Za-z0-9_-]+)").matcher(rule.group(1));
                while (name.find()) {
                    classes.add(name.group(1));
                }
            }
            assertThat(classes)
                    .as("no flex rules found after reading %s — has it moved?", stylesheet)
                    .isNotEmpty();
        }
        // Bootstrap's flex utilities count too. Its stylesheet is a webjar rather than a file in
        // this repo (`/webjars/bootstrap/5.3.3/css/bootstrap.rtl.min.css`), so the two it defines
        // are named here; an arrow inside `d-flex` is ordered by `direction` exactly like one
        // inside `.value-change`, and the dashboard's shortcut arrows rely on that.
        classes.add("d-flex");
        classes.add("d-inline-flex");
        return classes;
    }

    @Test
    void everyBeforeAfterArrowSitsInAFlexContainer() throws IOException {
        List<String> flex = flexClasses();
        assertThat(flex)
                .as(".value-change is the class the fix introduced; it must still be flex")
                .contains("value-change");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".html")).toList()) {
                offenders.addAll(inlineArrowsIn(file, flex));
            }
        }

        assertThat(offenders)
                .as("an arrow between two values, laid out by inline text flow, points the wrong "
                        + "way whenever the values are Latin — wrap it in a flex container")
                .isEmpty();
    }

    /**
     * Arrows in this file whose nearest enclosing element is not laid out with flex.
     *
     * <p>Only {@code bi-arrow-left} / {@code bi-arrow-right} count. The directionless ones
     * ({@code bi-arrow-repeat}, {@code bi-arrow-counterclockwise}, {@code bi-arrow-left-right})
     * carry no before/after meaning, and a lone arrow used as a "go here" affordance on a link or
     * a button has no second value to point at.
     */
    private static List<String> inlineArrowsIn(Path file, List<String> flexClasses)
            throws IOException {
        String html = Files.readString(file, StandardCharsets.UTF_8);
        List<String> offenders = new ArrayList<>();

        Matcher arrow = Pattern.compile("<i[^>]*\\bclass=\"([^\"]*\\bbi-arrow-(?:left|right)\\b[^\"]*)\"[^>]*>")
                .matcher(html);
        while (arrow.find()) {
            if (!separatesTwoValues(html, arrow.start(), arrow.end())) {
                continue;
            }
            if (enclosedByFlex(html, arrow.start(), flexClasses)) {
                continue;
            }
            offenders.add(file.getFileName() + " @" + arrow.start() + " — " + arrow.group());
        }
        return offenders;
    }

    /**
     * A value on each side is what makes an arrow a before/after marker rather than décor.
     *
     * <p>"On each side" means <em>immediately</em>: the arrow must close one span and open the
     * next, with nothing but whitespace and its own {@code </i>} in between. A window that merely
     * contained a {@code </span>} somewhere in the preceding few hundred characters was too loose
     * — it matched the dashboard shortcut cards, whose chevron is a "go here" affordance with one
     * value and no pair, and whose {@code <span>} on the far side belongs to the next card
     * entirely.
     *
     * <p>The distinction is visible in the markup and holds across every arrow in this project:
     * a real pair is preceded by a <b>closing</b> {@code </span>}, décor by an opening
     * {@code <span …>}, {@code <a …>} or heading.
     */
    private static boolean separatesTwoValues(String html, int start, int end) {
        String before = html.substring(0, start).stripTrailing();
        if (!before.endsWith("</span>")) {
            return false;
        }
        String after = html.substring(end).stripLeading();
        if (after.startsWith("</i>")) {
            after = after.substring("</i>".length()).stripLeading();
        }
        return after.startsWith("<span");
    }

    private static final Set<String> VOID_ELEMENTS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr");

    /**
     * Whether the arrow's <b>immediate parent</b> is laid out with flex.
     *
     * <p>The immediate parent is the whole question, and an earlier version of this asked a looser
     * one: it scanned the last six tags before the arrow and accepted any that carried a flex
     * class. That passes on markup the bidi bug still affects, because a flex grandparent orders
     * <em>its</em> children — the row — while the two values and the arrow are ordered by whatever
     * lays out the span directly around them. Measured: renaming {@code .value-change} off the
     * wrapper in {@code audit-logs.html} left the old check green, because the enclosing
     * {@code li.value-change-row} is flex as well.
     *
     * <p>So this keeps a real tag stack. It is still not an HTML parser — attribute values holding
     * angle brackets would confuse it — but Thymeleaf templates in this project do not contain any,
     * and the mutation above now fails as it should.
     */
    private static boolean enclosedByFlex(String html, int at, List<String> flexClasses) {
        Deque<String> open = new ArrayDeque<>();
        Matcher tag = Pattern.compile("</?(\\w+)([^>]*?)(/?)>").matcher(html.substring(0, at));
        while (tag.find()) {
            String name = tag.group(1).toLowerCase();
            if (tag.group().startsWith("</")) {
                open.pollLast();
                continue;
            }
            if (VOID_ELEMENTS.contains(name) || !tag.group(3).isEmpty()) {
                continue;
            }
            Matcher cls = Pattern.compile("class=\"([^\"]*)\"").matcher(tag.group(2));
            open.addLast(cls.find() ? cls.group(1) : "");
        }
        String parent = open.peekLast();
        if (parent == null) {
            return false;
        }
        for (String candidate : parent.split("\\s+")) {
            if (!candidate.isBlank() && flexClasses.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
