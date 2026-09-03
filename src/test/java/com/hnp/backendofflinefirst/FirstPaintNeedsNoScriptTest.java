package com.hnp.backendofflinefirst;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A list page must be the right size before any script runs.
 *
 * <h2>The trap</h2>
 *
 * <p>{@code enterprise-ui.js} used to install the whole list-page design at
 * {@code DOMContentLoaded}: it added {@code enterprise-list-page} to {@code #pageContent},
 * {@code enterprise-data-table} to every {@code table.table}, and moved each table into a
 * {@code .enterprise-table-viewport} it created on the spot.
 *
 * <p>Stylesheets block the first paint. Scripts do not. So the browser painted a page styled by
 * plain Bootstrap — 14px cells, no {@code min-width: max-content}, so every long Persian value
 * wrapped onto a second line — and then, whenever the last of nine script files had finished
 * downloading, the classes landed and the page re-laid itself out under the reader. Measured on
 * a 20-row log-sheet list at 1180px: cell font 14px → 12.32px, row height 64px → 50px, page
 * height 1378px → 679px. Half the page's height, arriving about a second late.
 *
 * <p>{@code layout.html} already carries this lesson for flash messages: <em>a script cannot fix
 * that — anything it does happens after the first paint by definition.</em> The same answer
 * applies here, so the server now emits the classes and the script only finds them already set.
 *
 * <h2>What this checks</h2>
 *
 * <p>For every page template holding a data table, the three class decisions that determine box
 * geometry are present in the markup. Cosmetic per-cell classes the script still adds
 * ({@code enterprise-primary-cell}, {@code enterprise-technical-cell}, {@code enterprise-badge-cell},
 * {@code enterprise-truncatable}) are deliberately <b>not</b> checked: they change colour and
 * weight, not size, so their late arrival is invisible.
 *
 * <p>Adding a new list page without these classes reintroduces the flash on that page only,
 * which is exactly the kind of regression nobody notices in review — hence a test rather than a
 * note in a document.
 */
class FirstPaintNeedsNoScriptTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** The tag that opens the page's content root, with whatever attributes it carries. */
    private static final Pattern PAGE_CONTENT = Pattern.compile("<div id=\"pageContent\"[^>]*>");

    /** A Bootstrap data table. The class attribute always leads with {@code table} in this codebase. */
    private static final Pattern DATA_TABLE = Pattern.compile("<table class=\"table[^\"]*\"[^>]*>");

    /** The three wrappers {@code enhanceTables} accepts instead of creating its own. */
    private static final Pattern VIEWPORT_DIV = Pattern.compile(
            "<div\\b[^>]*class=\"[^\"]*(?:table-responsive|enterprise-table-viewport)[^\"]*\"[^>]*>");

    @Test
    void listPagesCarryTheirEnterpriseClassesInTheMarkup() throws IOException {
        List<String> problems = new ArrayList<>();

        for (Path file : pageTemplatesWithTables()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String name = TEMPLATES.relativize(file).toString().replace('\\', '/');

            Matcher pageContent = PAGE_CONTENT.matcher(source);
            if (pageContent.find() && !pageContent.group().contains("enterprise-list-page")) {
                problems.add(name + " → #pageContent is missing enterprise-list-page");
            }

            Matcher table = DATA_TABLE.matcher(source);
            while (table.find()) {
                // A live table is refreshed by its own script and enhanceTables skips it, so the
                // server must skip it too — matching behaviour is the point, not decorating more.
                if (table.group().contains("data-enterprise-live")) {
                    continue;
                }
                if (!table.group().contains("enterprise-data-table")) {
                    problems.add(name + " → a table is missing enterprise-data-table");
                }
                if (!hasScrollViewport(source, table.start())) {
                    problems.add(name + " → a table is not inside an enterprise-table-viewport");
                }
            }
        }

        assertThat(problems)
                .as("These pages would paint at Bootstrap's size and resize once enterprise-ui.js ran")
                .isEmpty();
    }

    /**
     * Whether the table opening at {@code tableStart} is wrapped by a scroll viewport.
     *
     * <p>"Wrapped" means the candidate {@code <div>} is still open where the table begins, which
     * is what {@code table.closest(...)} answers at runtime. Counting div tags between the two is
     * enough here because none of these templates nests a table inside another one.
     */
    private static boolean hasScrollViewport(String source, int tableStart) {
        Matcher wrapper = VIEWPORT_DIV.matcher(source.substring(0, tableStart));
        while (wrapper.find()) {
            String between = source.substring(wrapper.end(), tableStart);
            boolean stillOpen = count(between, "<div") == count(between, "</div>");
            if (stillOpen && wrapper.group().contains("enterprise-table-viewport")) {
                return true;
            }
        }
        return false;
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            total++;
        }
        return total;
    }

    /**
     * Page templates that render a data table. Fragments are excluded on purpose: several are
     * rendered into a dialog after load, where {@code enhanceTables} never reaches them, so
     * giving them these classes would change how they look rather than when.
     */
    private static List<Path> pageTemplatesWithTables() throws IOException {
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".html"))
                    .filter(path -> !path.toString().replace('\\', '/').contains("/fragments/"))
                    .filter(FirstPaintNeedsNoScriptTest::containsDataTable)
                    .sorted()
                    .toList();
        }
    }

    private static boolean containsDataTable(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains("<table class=\"table");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}
