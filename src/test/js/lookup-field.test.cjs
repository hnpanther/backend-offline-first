const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

/*
 * The panel picker's «use this code» decision (roadmap §31, «A code the device copy does not have
 * yet») — when the row is offered, and which code it would record.
 *
 * The rule mirrors the server's `LookupValues.normaliseCode` and the PWA's `normalizeLookupCode`:
 * a row that offered a code the server then refuses as malformed would turn a deliberate choice
 * into a refused save.
 *
 *   node --test src/test/js/lookup-field.test.cjs
 */
const source = fs.readFileSync('src/main/resources/static/js/lookup-field.js', 'utf8');
const window = {};
vm.runInNewContext(source, {window, document: {addEventListener() {}}});
const {unlistedCodeFor} = window.LookupField;

test('offered only when the field allows it and the search found nothing', () => {
    assert.equal(unlistedCodeFor('1077', 0, true), '1077');
    assert.equal(unlistedCodeFor('1077', 0, false), null, 'off by default');
    assert.equal(unlistedCodeFor('1077', 2, true), null, 'never beside results');
});

test('the code is normalised the way the server keys it', () => {
    assert.equal(unlistedCodeFor('  ۱۰۷۷ ', 0, true), '1077');
    assert.equal(unlistedCodeFor('١٢', 0, true), '12');
    assert.equal(unlistedCodeFor('A-12', 0, true), 'A-12');
});

test('nothing that could never be a code is offered', () => {
    assert.equal(unlistedCodeFor('', 0, true), null);
    assert.equal(unlistedCodeFor('   ', 0, true), null);
    assert.equal(unlistedCodeFor('two words', 0, true), null);
    assert.equal(unlistedCodeFor('x'.repeat(101), 0, true), null);
    assert.equal(unlistedCodeFor('a‌b', 0, true), null, 'a format character');
    assert.equal(unlistedCodeFor('a\tb', 0, true), null);
});
