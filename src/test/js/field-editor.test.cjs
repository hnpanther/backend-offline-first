const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

/*
 * The field editor's state-condition controls, driven as a browser would drive them.
 *
 * Why these are here and not in `mvn test`: the behaviour is a script, and the two things that
 * can go wrong are invisible to every server-side test. A hidden control still POSTs its value,
 * so a stale gate left behind by a type change is REFUSED by the server with a message about a
 * control the administrator can no longer see; and a gate cleared when it should have been kept
 * would silently un-gate a danger band. Same harness as `ui-preferences.test.cjs` — node:test
 * and node:vm, no package.json, nothing fetched.
 *
 *   node --test src/test/js/field-editor.test.cjs
 */
const source = fs.readFileSync('src/main/resources/static/js/field-editor.js', 'utf8');

/** A DOM node with just enough behaviour for this script: classes, events, and a subtree. */
function el(classes, props = {}) {
    const node = {
        _classes: new Set(classes ? classes.split(' ') : []),
        _handlers: {},
        children: [],
        parent: null,
        ...props
    };
    node.classList = {
        toggle(name, force) {
            if (force === undefined) {
                node._classes.has(name) ? node._classes.delete(name) : node._classes.add(name);
            } else if (force) {
                node._classes.add(name);
            } else {
                node._classes.delete(name);
            }
        },
        add: name => node._classes.add(name),
        remove: name => node._classes.delete(name),
        contains: name => node._classes.has(name)
    };
    node.hidden = () => node._classes.has('d-none');
    node.addEventListener = (type, fn) => { (node._handlers[type] ||= []).push(fn); };
    node.fire = type => (node._handlers[type] || []).forEach(fn => fn());
    node.append = (...kids) => {
        for (const kid of kids) { kid.parent = node; node.children.push(kid); }
        return node;
    };
    node.querySelectorAll = selector => descendants(node).filter(n => matches(n, selector));
    node.querySelector = selector => node.querySelectorAll(selector)[0] ?? null;
    node.closest = selector => {
        let cursor = node;
        while (cursor) {
            if (matches(cursor, selector)) return cursor;
            cursor = cursor.parent;
        }
        return null;
    };
    node.getAttribute = name => (node.attrs || {})[name] ?? null;
    node.setAttribute = (name, value) => { (node.attrs ||= {})[name] = value; };
    return node;
}

function descendants(node) {
    return node.children.flatMap(kid => [kid, ...descendants(kid)]);
}

/** Only what the script actually uses: a single class, or a tag name. */
function matches(node, selector) {
    if (selector.startsWith('.')) return node._classes.has(selector.slice(1));
    return node.tag === selector;
}

/**
 * One editor form: a type <select> and a complete state-condition block.
 *
 * `gate` seeds the saved state — what the SERVER rendered — so a test can assert the round trip
 * as well as the interaction.
 */
function form({dataType = 'number', gate = null} = {}) {
    const typeSelect = el('data-type-select', {tag: 'select', value: dataType});

    const toggleInput = el('gate-enabled-toggle', {tag: 'input', checked: !!gate});
    const fieldSelect = el('gate-field-select', {tag: 'select', value: gate ? gate.field : ''});
    const opSelect = el('gate-op-select', {tag: 'select', value: gate ? gate.op : 'in'});
    const operandInput = el('gate-operand-input', {tag: 'textarea', value: gate ? gate.operand : ''});
    const operandCell = el('gate-operand-cell').append(operandInput);
    const row = el(gate ? 'gate-row' : 'gate-row d-none')
        .append(fieldSelect, opSelect, operandCell);
    const gateBlock = el('state-condition-block d-none').append(toggleInput, row);

    const root = el(null, {tag: 'form'}).append(typeSelect, gateBlock);

    const document = {
        querySelectorAll: selector => root.querySelectorAll(selector),
        createElement: tag => el(null, {tag, textContent: '', value: ''})
    };
    vm.runInNewContext(source, {document, window: {}});

    return {typeSelect, gateBlock, row, toggleInput, fieldSelect, opSelect, operandCell, operandInput};
}

// =============================================================================================

test('a number field offers the condition block at the first paint', () => {
    const f = form({dataType: 'number'});
    assert.equal(f.gateBlock.hidden(), false);
});

test('a choice field offers it too — an option can be the alert', () => {
    for (const dataType of ['select', 'multiselect']) {
        assert.equal(form({dataType}).gateBlock.hidden(), false, dataType);
    }
});

test('a type with no threshold hides the block AND clears it', () => {
    // The clearing is the point. A hidden <select> still posts, the server refuses a condition
    // on a text field, and the administrator would get an error about a control they can no
    // longer see — on a save whose only change was the field's type.
    const f = form({dataType: 'text', gate: {field: 'status', op: 'in', operand: 'روشن'}});

    assert.equal(f.gateBlock.hidden(), true);
    assert.equal(f.fieldSelect.value, '');
    assert.equal(f.operandInput.value, '');
});

test('retyping a gated number to text clears the gate, and back again leaves it cleared', () => {
    const f = form({dataType: 'number', gate: {field: 'status', op: 'in', operand: 'روشن'}});
    assert.equal(f.fieldSelect.value, 'status');

    f.typeSelect.value = 'text';
    f.typeSelect.fire('change');
    assert.equal(f.fieldSelect.value, '');

    // Deliberately NOT restored: the script has no memory of it, and inventing one would mean a
    // gate reappearing on a field the administrator had retyped twice.
    f.typeSelect.value = 'number';
    f.typeSelect.fire('change');
    assert.equal(f.gateBlock.hidden(), false);
    assert.equal(f.fieldSelect.value, '');
});

test('a saved gate arrives open, filled in, and untouched', () => {
    // The round trip. `build(...)` REBUILDS validation on every save, so a form that opened with
    // these three boxes empty would erase the condition on an unrelated edit.
    const f = form({dataType: 'number', gate: {field: 'status', op: 'in', operand: 'روشن'}});

    assert.equal(f.toggleInput.checked, true);
    assert.equal(f.row.hidden(), false);
    assert.equal(f.fieldSelect.value, 'status');
    assert.equal(f.opSelect.value, 'in');
    assert.equal(f.operandInput.value, 'روشن');
});

test('the switch reveals the row, and switching it off clears the gate', () => {
    const f = form({dataType: 'number'});
    assert.equal(f.row.hidden(), true, 'collapsed until asked for');

    f.toggleInput.checked = true;
    f.toggleInput.fire('change');
    assert.equal(f.row.hidden(), false);

    f.fieldSelect.value = 'status';
    f.operandInput.value = 'روشن';
    f.toggleInput.checked = false;
    f.toggleInput.fire('change');

    assert.equal(f.row.hidden(), true);
    assert.equal(f.fieldSelect.value, '', 'switched off must mean no gate, not a hidden one');
    assert.equal(f.operandInput.value, '');
});

test('the operand box disappears for the two operators that take none', () => {
    const f = form({dataType: 'number', gate: {field: 'status', op: 'in', operand: 'روشن'}});
    assert.equal(f.operandCell.hidden(), false);

    for (const op of ['answered', 'notAnswered']) {
        f.opSelect.value = op;
        f.opSelect.fire('change');
        assert.equal(f.operandCell.hidden(), true, op);
    }

    f.opSelect.value = 'gte';
    f.opSelect.fire('change');
    assert.equal(f.operandCell.hidden(), false, 'a comparison needs its number back');
});

test('a gate saved with a presence operator opens with its operand already hidden', () => {
    const f = form({dataType: 'number', gate: {field: 'status', op: 'notAnswered', operand: ''}});

    assert.equal(f.row.hidden(), false);
    assert.equal(f.operandCell.hidden(), true);
});

test('a page with no condition block at all still runs — the sheet-question editor has none', () => {
    // `log-sheet-questions.html` shares this script and renders no gate: a round-level question
    // belongs to the round, so it has no sibling answers to gate on.
    const typeSelect = el('data-type-select', {tag: 'select', value: 'number'});
    const root = el(null, {tag: 'form'}).append(typeSelect);
    const document = {
        querySelectorAll: selector => root.querySelectorAll(selector),
        createElement: tag => el(null, {tag, textContent: '', value: ''})
    };

    assert.doesNotThrow(() => vm.runInNewContext(source, {document, window: {}}));
});
