// Dependency-free event tests: node --test src/test/js/operations-ui.test.cjs
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function element(properties = {}) {
    return Object.assign(new EventTarget(), {
        value: '', checked: false, type: 'text', dataset: {}, disabled: false,
        classList: {add() {}, contains() { return false; }},
        hasAttribute() { return false; }, closest() { return null; },
        querySelector() { return null; }, focus() { this.focused = true; }
    }, properties);
}
function load(file, document, window) {
    vm.runInNewContext(readFileSync(path.join(__dirname, '../../main/resources/static/js', file), 'utf8'),
        {document, window, bootstrap: window.bootstrap, queueMicrotask, console});
    document.dispatchEvent(new Event('DOMContentLoaded'));
}
function dirtyPage() {
    const fields = [element(), element({type: 'checkbox'}), element(), element()];
    const form = element({method: 'post', querySelectorAll() { return fields; }});
    const document = element({
        getElementById() { return null; },
        querySelectorAll(selector) { return selector === '#pageContent form' ? [form] : []; }
    });
    const window = element({location: {pathname: '/integration-keys'}});
    load('enterprise-ui.js', document, window);
    return {fields, form, warns() {
        return !window.dispatchEvent(new Event('beforeunload', {cancelable: true}));
    }};
}

test('opening the key form and date-picker initialisation do not count as edits', () => {
    const page = dirtyPage();
    page.fields[0].dispatchEvent(new Event('change'));
    page.fields[0].dispatchEvent(new Event('input'));
    assert.equal(page.warns(), false);
});
test('synthetic date selection is still tracked, and restoring the value clears the warning', () => {
    const page = dirtyPage();
    page.fields[0].value = '1405/08/27 12:00';
    page.fields[0].dispatchEvent(new Event('change'));
    assert.equal(page.warns(), true);
    page.fields[0].value = '';
    page.fields[0].dispatchEvent(new Event('change'));
    assert.equal(page.warns(), false);
});
test('undoing one edit does not hide another outstanding edit', () => {
    const page = dirtyPage();
    page.fields[0].value = 'maintenance';
    page.fields[0].dispatchEvent(new Event('input'));
    page.fields[1].checked = true;
    page.fields[1].dispatchEvent(new Event('change'));
    page.fields[0].value = '';
    page.fields[0].dispatchEvent(new Event('input'));
    assert.equal(page.warns(), true);
    page.fields[1].checked = false;
    page.fields[1].dispatchEvent(new Event('change'));
    assert.equal(page.warns(), false);
});
test('a prevented save keeps the warning; an actual submit clears it', () => {
    const page = dirtyPage();
    page.fields[0].value = 'changed';
    page.fields[0].dispatchEvent(new Event('input'));
    const blocked = new Event('submit', {cancelable: true});
    blocked.preventDefault();
    page.form.dispatchEvent(blocked);
    assert.equal(page.warns(), true);
    page.form.dispatchEvent(new Event('submit', {cancelable: true}));
    assert.equal(page.warns(), false);
});

function decisionPage(withBootstrap = true) {
    const ids = Object.fromEntries(['statusDecisionModal', 'statusDecisionConfirm', 'statusDecisionCancel',
        'statusDecisionTitle', 'statusDecisionAsset', 'statusDecisionStatus', 'statusDecisionMessage']
        .map(id => [id, element()]));
    const row = {dataset: {requestAsset: '<Pump & 1>', requestStatus: 'روشن'}};
    const button = element();
    let sent = 0, shown = 0, confirmationAnswer = false;
    const form = element({
        dataset: {confirmTitle: 'تأیید درخواست', confirmMessage: 'تأیید شود؟', confirmLabel: 'بله', confirmTone: 'success'},
        closest(selector) { return selector === '[data-request-asset]' ? row : null; },
        requestSubmit(submitter) {
            const event = Object.assign(new Event('submit', {cancelable: true}), {submitter});
            if (this.dispatchEvent(event)) sent++;
        }
    });
    const document = element({getElementById(id) { return ids[id]; }, querySelectorAll() { return [form]; }});
    const modal = {show() { shown++; }, hide() { ids.statusDecisionModal.dispatchEvent(new Event('hidden.bs.modal')); }};
    const window = {confirm() { return confirmationAnswer; }};
    if (withBootstrap) window.bootstrap = {Modal: {getOrCreateInstance() { return modal; }}};
    load('asset-status-decision.js', document, window);
    return {ids, form, button, sent: () => sent, shown: () => shown,
        answer(value) { confirmationAnswer = value; },
        click() { form.requestSubmit(button); }};
}
test('approve/reject first opens confirmation without submitting, and cancellation sends nothing', () => {
    const page = decisionPage();
    page.click();
    assert.equal(page.sent(), 0);
    assert.equal(page.shown(), 1);
    assert.equal(page.ids.statusDecisionAsset.textContent, '<Pump & 1>');
    page.ids.statusDecisionModal.dispatchEvent(new Event('shown.bs.modal'));
    assert.equal(page.ids.statusDecisionCancel.focused, true);
    page.ids.statusDecisionModal.dispatchEvent(new Event('hidden.bs.modal'));
    page.ids.statusDecisionConfirm.dispatchEvent(new Event('click'));
    assert.equal(page.sent(), 0);
    assert.equal(page.button.focused, true);
});
test('confirmation submits the same form exactly once even after a double click', () => {
    const page = decisionPage();
    page.click();
    page.ids.statusDecisionConfirm.dispatchEvent(new Event('click'));
    page.ids.statusDecisionConfirm.dispatchEvent(new Event('click'));
    assert.equal(page.sent(), 1);
    assert.equal(page.shown(), 1);
});
test('pressing Enter to submit also requires confirmation', () => {
    const page = decisionPage();
    page.form.requestSubmit();
    assert.equal(page.sent(), 0);
    page.ids.statusDecisionConfirm.dispatchEvent(new Event('click'));
    assert.equal(page.sent(), 1);
});
test('if Bootstrap is unavailable the native confirmation still guards submission', () => {
    const page = decisionPage(false);
    page.click();
    assert.equal(page.sent(), 0);
    page.answer(true);
    page.click();
    assert.equal(page.sent(), 1);
});
