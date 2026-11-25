const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('src/main/resources/static/js/form-datetime-bounds.js', 'utf8');

function check(value, limits, now) {
    const handlers = {};
    const feedback = {textContent: ''};
    const field = {dataset: limits, querySelector: selector => selector === '.persian-datetime-value' ? {value} : feedback};
    const document = {addEventListener: (name, callback) => { handlers[name] = callback; }};
    vm.runInNewContext(source, {document, Date: {parse: Date.parse, now: () => now}});
    let blocked = false;
    handlers.submit({target: {querySelectorAll: () => [field]}, preventDefault() {blocked = true;}});
    return {blocked, message: feedback.textContent};
}

test('panel date limits include their boundaries, use Tehran time and reject outside either window', () => {
    const now = Date.parse('2026-11-24T10:00+03:30');
    const limits = {min: String(now - 120000), max: String(now + 120000), before: '1', after: '1'};
    assert.equal(check('2026-11-24T09:59', limits, now).blocked, false);
    assert.equal(check('2026-11-24T10:01', limits, now).blocked, false);
    assert.equal(check('2026-11-24T09:58', limits, now).blocked, true);
    assert.equal(check('2026-11-24T10:03', limits, now).blocked, true);
    assert.equal(check('', limits, now).blocked, false, 'drafts can leave optional dates blank');
});
