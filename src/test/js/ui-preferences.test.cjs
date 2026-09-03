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
