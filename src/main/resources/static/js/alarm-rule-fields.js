/*
 * The alarm-rule parameter picker: filter the checkbox list as you type, and count what is ticked.
 *
 * Progressive enhancement only. Without this file the picker is a plain list of checkboxes
 * grouped by class, and it submits exactly the same thing — so nothing about which parameters a
 * rule covers depends on it. No network, no dependency: the list is already in the page.
 *
 * Persian is normalised for matching ONLY (never for what is submitted): Arabic yeh/kaf to
 * Persian, ZWNJ and tatweel removed, Persian/Arabic digits to Latin, case-folded — so «پمپ»,
 * «پمپ‌ها» and a key typed in Latin all find what they should.
 */
(function () {
    'use strict';

    var PERSIAN_DIGITS = '۰۱۲۳۴۵۶۷۸۹';
    var ARABIC_DIGITS = '٠١٢٣٤٥٦٧٨٩';

    function normalise(text) {
        var out = String(text || '')
            .replace(/[يى]/g, 'ی')
            .replace(/ك/g, 'ک')
            .replace(/[‌‍ـ]/g, '')
            .toLowerCase();
        var result = '';
        for (var i = 0; i < out.length; i++) {
            var ch = out.charAt(i);
            var p = PERSIAN_DIGITS.indexOf(ch);
            var a = ARABIC_DIGITS.indexOf(ch);
            result += p >= 0 ? String(p) : (a >= 0 ? String(a) : ch);
        }
        return result.replace(/\s+/g, ' ').trim();
    }

    function setUp(picker) {
        var search = picker.querySelector('[data-picker-search]');
        var count = picker.querySelector('[data-picker-count]');
        var empty = picker.querySelector('[data-picker-empty]');
        var options = Array.prototype.slice.call(picker.querySelectorAll('[data-picker-option]'));
        var groups = Array.prototype.slice.call(picker.querySelectorAll('[data-picker-group]'));
        var haystacks = options.map(function (option) {
            return normalise(option.getAttribute('data-search'));
        });

        function updateCount() {
            if (!count) {
                return;
            }
            var ticked = picker.querySelectorAll('input[type="checkbox"]:checked').length;
            count.textContent = String(ticked);
        }

        function filter() {
            var terms = normalise(search ? search.value : '').split(' ').filter(Boolean);
            var anyVisible = false;
            options.forEach(function (option, index) {
                var match = terms.every(function (term) {
                    return haystacks[index].indexOf(term) >= 0;
                });
                option.hidden = !match;
                anyVisible = anyVisible || match;
            });
            groups.forEach(function (group) {
                group.hidden = !group.querySelector('[data-picker-option]:not([hidden])');
            });
            if (empty) {
                empty.hidden = anyVisible || options.length === 0;
            }
        }

        if (search) {
            search.addEventListener('input', filter);
            // Enter in the search box must not submit the rule form half-filled.
            search.addEventListener('keydown', function (event) {
                if (event.key === 'Enter') {
                    event.preventDefault();
                }
            });
        }
        picker.addEventListener('change', updateCount);
        updateCount();
    }

    function init() {
        var pickers = document.querySelectorAll('[data-alarm-field-picker]');
        for (var i = 0; i < pickers.length; i++) {
            setUp(pickers[i]);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
