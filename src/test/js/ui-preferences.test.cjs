const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('src/main/resources/static/js/ui-preferences.js', 'utf8');
function run(entries = {}, blocked = false) {
    const styles = [], classes = {}, window = {location: {pathname: '/my-inbox'}};
    const localStorage = {
        get length() { if (blocked) throw Error('blocked'); return Object.keys(entries).length; },
        key(i) { return Object.keys(entries)[i]; },
        getItem(k) { if (blocked) throw Error('blocked'); return entries[k] ?? null; }
    };
    const document = {
        documentElement: {classList: {toggle: (k, v) => { classes[k] = v; }}},
        createElement: tag => ({tag}), head: {appendChild: style => styles.push(style)}
    };
    vm.runInNewContext(source, {window, localStorage, document});
    return {styles: styles.map(s => s.textContent).join('\n'), classes,
        read: k => JSON.parse(JSON.stringify(window.EnterprisePreferences.read(k)))};
}
test('default and blocked storage stay usable without an API call', () => {
    for (const blocked of [false, true]) {
        const ui = run({}, blocked);
        assert.deepEqual(ui.read('missing'), {density: 'normal', hidden: []});
        assert.equal(ui.styles, '');
    }
});
test('each table keeps its own density and hidden columns before paint', () => {
    const ui = run({
        desktopSidebarCollapsed: 'true',
        'enterpriseTable:/my-inbox:0': JSON.stringify({density: 'compact', hidden: [2]}),
        'enterpriseTable:/my-inbox:1': JSON.stringify({density: 'comfortable', hidden: [4]}),
        'enterpriseTable:/unrelated:0': JSON.stringify({density: 'compact', hidden: [8]})
    });
    assert.equal(ui.classes['sidebar-collapsed'], true);
    assert.match(ui.styles, /key="0".*height:37px/);
    assert.match(ui.styles, /key="1".*height:57px/);
    assert.match(ui.styles, /:nth-child\(3\)/);
    assert.match(ui.styles, /:nth-child\(5\)/);
    assert.doesNotMatch(ui.styles, /:nth-child\(9\)/);
    assert.match(ui.styles, /:not\(\[data-enterprise-enhanced\]\)/);
});
test('bad JSON, null and wrong types cannot abort enhancement', () => {
    const ui = run({bad: '{', nil: 'null', array: '[]',
        invalid: JSON.stringify({density: 'url(https://bad)', hidden: [1, 1, -1, 1.5, '2', 100, null]})});
    for (const key of ['bad', 'nil', 'array']) assert.deepEqual(ui.read(key), {density: 'normal', hidden: []});
    assert.deepEqual(ui.read('invalid'), {density: 'normal', hidden: [1]});
});
test('a corrupt entry does not suppress a later valid preference; keys cannot inject CSS', () => {
    const ui = run({'enterpriseTable:/my-inbox:0': '{',
        'enterpriseTable:/my-inbox:1': '{"density":"comfortable"}',
        'enterpriseTable:/my-inbox:evil"]{}': '{"density":"compact"}'});
    assert.match(ui.styles, /height:57px/);
    assert.doesNotMatch(ui.styles, /evil|height:37px/);
});
test('a folded filter bar is folded before paint, and only for its own page', () => {
    // The harness runs as if the browser were on /my-inbox, so the entry for another path must
    // not reach this page's stylesheet — a bar folded on the log-sheet list has nothing to say
    // about the inbox.
    const ui = run({
        'enterpriseFilters:/my-inbox': 'collapsed',
        'enterpriseFilters:/log-sheets': 'collapsed'
    });
    assert.match(ui.styles, /\[data-filter-advanced\]:not\(\[data-filter-ready\]\)\{display:none\}/);
    // The rule must stand down once the script owns the element. Without this clause it
    // outranks the class the toggle flips, and a folded bar can never be reopened.
    assert.match(ui.styles, /:not\(\[data-filter-ready\]\)/);
    assert.doesNotMatch(ui.styles, /log-sheets/);
    // The guard that makes folding safe: the rule declines to apply to a bar the SERVER marked
    // as carrying an active filter. Without this clause a reader lands on a filtered list with
    // the controls hidden and no way to see why rows are missing.
    assert.match(ui.styles, /:not\(\[data-filters-active\]\)/);
});
test('only the exact value folds the bar, and a crafted key cannot reach the stylesheet', () => {
    for (const value of ['open', 'COLLAPSED', 'true', '', 'collapsed ']) {
        assert.doesNotMatch(run({'enterpriseFilters:/my-inbox': value}).styles,
            /data-filter-advanced/, `"${value}" must not fold the bar`);
    }
    // The key is a fixed string built from `location.pathname`, never interpolated from
    // storage, so there is no crafted key to find — this asserts the absence stays true.
    const ui = run({'enterpriseFilters:/my-inbox"]{}body{display:none}': 'collapsed'});
    assert.doesNotMatch(ui.styles, /body\{display:none\}/);
});
