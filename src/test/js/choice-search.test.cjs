const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

/*
 * The panel's search and free entry on a choice field (roadmap §31), driven as a browser would.
 *
 * Why here and not in `mvn test`: what can go wrong is a script, and every server-side test is
 * blind to it. The two failures that matter are both silent — a search box that carries a
 * `name` would be POSTed as an answer by the fill page (which posts every `fd_*` / `sfd_*`
 * control), and a normaliser that disagrees with the tablet's finds an option on one device and
 * not on the other. Same harness as the other two files: node:test and node:vm, nothing fetched.
 *
 *   node --test src/test/js/choice-search.test.cjs
 */
const source = fs.readFileSync('src/main/resources/static/js/choice-search.js', 'utf8');
const cases = JSON.parse(fs.readFileSync('src/test/js/persian-search-cases.json', 'utf8'));

// ------------------------------------------------------------------ a small DOM

function makeDocument() {
    const doc = {};
    doc.createElement = tag => makeNode(tag.toUpperCase(), doc);
    doc.querySelectorAll = () => [];
    return doc;
}

function makeNode(tagName, doc) {
    const node = {
        tagName, ownerDocument: doc, children: [], parentNode: null, attrs: {}, dataset: {},
        _handlers: {}, textContent: '', hidden: false, disabled: false, value: '', type: '',
        className: ''
    };
    const classes = () => new Set(node.className.split(/\s+/).filter(Boolean));
    node.classList = {
        toggle(name, force) {
            const set = classes();
            const on = force === undefined ? !set.has(name) : !!force;
            on ? set.add(name) : set.delete(name);
            node.className = [...set].join(' ');
        },
        contains: name => classes().has(name)
    };
    node.setAttribute = (k, v) => { node.attrs[k] = String(v); };
    node.getAttribute = k => node.attrs[k] ?? null;
    node.removeAttribute = k => { delete node.attrs[k]; if (k === 'size') node.size = 0; };
    node.appendChild = kid => { kid.parentNode = node; node.children.push(kid); return kid; };
    node.insertBefore = (kid, ref) => {
        kid.parentNode = node;
        const i = ref ? node.children.indexOf(ref) : -1;
        i < 0 ? node.children.push(kid) : node.children.splice(i, 0, kid);
        return kid;
    };
    Object.defineProperty(node, 'nextSibling', {
        get() {
            if (!node.parentNode) return null;
            const siblings = node.parentNode.children;
            return siblings[siblings.indexOf(node) + 1] ?? null;
        }
    });
    node.addEventListener = (type, fn) => { (node._handlers[type] ||= []).push(fn); };
    node.dispatchEvent = event => { (node._handlers[event.type] || []).forEach(fn => fn(event)); return true; };
    return node;
}

/** A <select> with these option values, inside a wrapper, the way the fill fragment renders it. */
function makeSelect(values, {multiple = false, allowCustom = false, withEmpty = false} = {}) {
    const doc = makeDocument();
    const wrap = makeNode('DIV', doc);
    const select = makeNode('SELECT', doc);
    select.multiple = multiple;
    select.className = 'form-select fill-field fill-field-choice';
    select.name = 'fd_1_reason';
    if (allowCustom) select.dataset.allowCustom = 'true';
    const all = (withEmpty ? [''] : []).concat(values);
    for (const v of all) {
        const o = makeNode('OPTION', doc);
        o.value = v;
        o.textContent = v === '' ? '— انتخاب —' : v;
        o.selected = false;
        select.appendChild(o);
    }
    Object.defineProperty(select, 'options', {get: () => select.children});
    Object.defineProperty(select, 'value', {
        get: () => (select.children.find(o => o.selected) || {value: ''}).value,
        set: v => select.children.forEach(o => { o.selected = o.value === v; })
    });
    select.querySelector = sel => sel === 'option[value=""]' ? (select.children.find(o => o.value === '') ?? null) : null;
    wrap.appendChild(select);
    doc.querySelectorAll = sel => sel === 'select.fill-field-choice' ? [select] : [];
    return {doc, wrap, select};
}

function load(doc) {
    const window = {};
    const context = {window, document: doc, Event: class { constructor(type) { this.type = type; } }, Array, String, Math};
    vm.runInNewContext(source, context);
    return window.ChoiceSearch;
}

const searchBox = wrap => wrap.children.find(c => c.className.includes('choice-search')) ?? null;
const inputOf = wrap => searchBox(wrap).children.find(c => c.tagName === 'INPUT');
const addOf = wrap => searchBox(wrap).children.find(c => c.tagName === 'BUTTON');
const type = (input, text) => { input.value = text; input.dispatchEvent({type: 'input'}); };

// ------------------------------------------------------------------ the shared table

test('the normaliser agrees with the shared table of cases', () => {
    const {doc} = makeSelect([]);
    const api = load(doc);
    for (const c of cases.normalize) {
        assert.equal(api.normalize(c.in), c.out, `normalize(${JSON.stringify(c.in)})`);
    }
    for (const c of cases.match) {
        assert.equal(api.matches(c.option, c.query), c.match, `matches(${c.option}, ${c.query})`);
    }
});

// ------------------------------------------------------------------ when it appears

test('a short list gets no search box — it is faster read than searched', () => {
    const {doc, wrap} = makeSelect(['a', 'b', 'c']);
    load(doc).enhance(doc);
    assert.equal(searchBox(wrap), null);
});

test('a list above the threshold gets one, automatically', () => {
    const values = Array.from({length: 9}, (_, i) => 'گزینه ' + i);
    const {doc, wrap} = makeSelect(values);
    load(doc).enhance(doc);
    assert.ok(searchBox(wrap));
});

test('a short list that allows free entry still gets the box — it is where the answer is typed', () => {
    const {doc, wrap} = makeSelect(['a', 'b'], {allowCustom: true});
    load(doc).enhance(doc);
    assert.ok(searchBox(wrap));
    assert.ok(addOf(wrap), 'an «افزودن» button');
});

test('enhancing twice does not add a second box', () => {
    const values = Array.from({length: 9}, (_, i) => 'v' + i);
    const {doc, wrap} = makeSelect(values);
    const api = load(doc);
    api.enhance(doc);
    api.enhance(doc);
    assert.equal(wrap.children.filter(c => (c.className || '').startsWith('choice-search d-flex')).length, 1);
});

// ------------------------------------------------------------------ what it must never do

test('nothing it adds carries a name — the fill page would post it as an answer', () => {
    const {doc, wrap} = makeSelect(['a'], {allowCustom: true});
    load(doc).enhance(doc);
    for (const c of searchBox(wrap).children) {
        assert.equal(c.name, undefined, `${c.tagName} must not be named`);
    }
});

// ------------------------------------------------------------------ search

test('typing hides what does not match, by the normalised text', () => {
    const values = ['نشتی آب‌بند', 'نشتی روغن', 'قطع برق', 'خرابی مکانیکی', 'v4', 'v5', 'v6', 'v7', 'v8'];
    const {doc, wrap, select} = makeSelect(values);
    load(doc).enhance(doc);
    type(inputOf(wrap), 'نشت');
    const shown = select.options.filter(o => !o.hidden).map(o => o.value);
    assert.deepEqual(shown, ['نشتی آب‌بند', 'نشتی روغن']);
});

test('the current answer is never hidden by a search, so it never looks cleared', () => {
    const values = ['قطع برق', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i'];
    const {doc, wrap, select} = makeSelect(values);
    select.value = 'قطع برق';
    load(doc).enhance(doc);
    type(inputOf(wrap), 'zzz');
    assert.equal(select.options.find(o => o.value === 'قطع برق').hidden, false);
});

// ------------------------------------------------------------------ free entry

test('«افزودن» appends the typed text as a selected option, marked دستی, with the exact value', () => {
    const {doc, wrap, select} = makeSelect(['قطع برق', 'خرابی'], {allowCustom: true});
    load(doc).enhance(doc);
    type(inputOf(wrap), '  گیر کردن شیر برقی  ');
    assert.equal(addOf(wrap).disabled, false);
    addOf(wrap).dispatchEvent({type: 'click'});
    const added = select.options.find(o => o.dataset.custom === 'true');
    assert.ok(added);
    assert.equal(added.value, 'گیر کردن شیر برقی', 'trimmed, and the value is the text itself');
    assert.equal(added.textContent, 'گیر کردن شیر برقی · دستی');
    assert.equal(select.value, 'گیر کردن شیر برقی');
});

test('typing an existing option selects it instead of adding a duplicate', () => {
    const {doc, wrap, select} = makeSelect(['کمپرسور'], {allowCustom: true});
    load(doc).enhance(doc);
    type(inputOf(wrap), 'كمپرسور'); // Arabic kaf: the same option to a reader
    assert.equal(addOf(wrap).disabled, true);
    assert.equal(select.options.filter(o => o.dataset.custom === 'true').length, 0);
});

test('a multiselect keeps its other choices when a typed one is added', () => {
    const {doc, wrap, select} = makeSelect(['a', 'b'], {allowCustom: true, multiple: true});
    select.options[0].selected = true;
    load(doc).enhance(doc);
    type(inputOf(wrap), 'c');
    addOf(wrap).dispatchEvent({type: 'click'});
    assert.deepEqual(select.options.filter(o => o.selected).map(o => o.value), ['a', 'c']);
});

test('an answer longer than the server accepts is refused before the round trip', () => {
    const {doc, wrap, select} = makeSelect(['a'], {allowCustom: true});
    load(doc).enhance(doc);
    type(inputOf(wrap), 'x'.repeat(201));
    addOf(wrap).dispatchEvent({type: 'click'});
    assert.equal(select.options.filter(o => o.dataset.custom === 'true').length, 0);
});

test('a field without free entry offers no «افزودن», however long the list', () => {
    const values = Array.from({length: 12}, (_, i) => 'v' + i);
    const {doc, wrap} = makeSelect(values);
    load(doc).enhance(doc);
    assert.equal(addOf(wrap), undefined);
});
