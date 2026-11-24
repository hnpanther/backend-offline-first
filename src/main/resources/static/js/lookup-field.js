/*
 * The fill page's reference-list picker (roadmap §31 Phase 2) — `fragments/fill-field-input`'s
 * `.lookup-field`.
 *
 * What posts is the hidden input inside each chip: one per chosen item, named like every other
 * answer, holding the item's CODE. Nothing this script creates has a `name` except those inputs —
 * the search box must never become an answer. The server stamps the label and the verdict, and
 * refuses nothing about a code it does not know (it is flagged instead), so this script only
 * offers what the server's search returned.
 *
 * The search is the SERVER's: `data-lookup-url?q=` answers with at most twenty ACTIVE items of a
 * list this round draws from, display columns only (LogSheetWebController.searchLookup). A list
 * of four thousand people is never sent to the browser.
 *
 * Bound by event delegation on the document, so a dialog or a round-level section re-rendered by
 * the server after a save works without re-binding — the summary swap replaces markup, and a
 * listener on the old nodes would silently stop working.
 */
(function () {
    'use strict';

    var DEBOUNCE_MS = 250;
    /** Mirrors LookupValues.MAX_CHOICES; the server refuses more. */
    var MAX_CHOICES = 50;

    /**
     * The code a «use this code» row would record for what was typed, or null when no row is
     * offered (roadmap §31, «A code the device copy does not have yet»).
     *
     * Offered only when the field allows it (`data-accept-code`), the search found NOTHING, and
     * the term could be a code at all — the same rule as the server's `LookupValues.normaliseCode`
     * and the tablet's `normalizeLookupCode`: Persian/Arabic digits made Latin, trimmed, no space,
     * no control or format character, at most 100. Never offered beside results: a typed name that
     * matched somebody is a choice to make from the list, not a code to invent.
     */
    function unlistedCodeFor(term, itemCount, accept) {
        if (!accept || itemCount > 0) return null;
        var code = String(term == null ? '' : term).trim()
            .replace(/[\u06F0-\u06F9]/g, function (d) { return String(d.charCodeAt(0) - 0x06F0); })
            .replace(/[\u0660-\u0669]/g, function (d) { return String(d.charCodeAt(0) - 0x0660); });
        if (!code || code.length > 100 || /[\s\u0000-\u001F\u007F-\u009F\u00AD\u200B-\u200F\u2028-\u202E\u2060-\u2064\uFEFF]/.test(code)) {
            return null;
        }
        return code;
    }

    // Exposed for src/test/js/lookup-field.test.cjs; nothing on the page reads it.
    window.LookupField = { unlistedCodeFor: unlistedCodeFor };

    function fieldOf(node) {
        return node && node.closest ? node.closest('.lookup-field') : null;
    }

    function chipsOf(field) {
        return Array.from(field.querySelectorAll('.lookup-chip'));
    }

    function feedback(field, message) {
        var slot = field.querySelector('.lookup-feedback');
        if (!slot) return;
        slot.textContent = message || '';
        slot.className = 'validation-feedback small mt-1 lookup-feedback' + (message ? ' text-danger' : ' d-none');
    }

    /**
     * Title, then the code in its own left-to-right run: «واحد ۱۲ (۴۵)».
     * One string would mirror the parentheses and sit a numeric code against a number
     * already in the title (AGENTS.md 9c-4).
     */
    function fillChoiceText(el, item) {
        var hasLabel = item.label && item.label !== item.code;
        el.appendChild(document.createTextNode(hasLabel ? item.label : (item.code || '')));
        if (hasLabel) {
            el.appendChild(document.createTextNode(' '));
            var id = document.createElement('bdi');
            id.dir = 'ltr';
            id.className = 'text-muted';
            id.textContent = '(' + item.code + ')';
            el.appendChild(id);
        }
        if (item.note) {
            el.appendChild(document.createTextNode(item.note));
        }
    }

    function hideResults(field) {
        var results = field.querySelector('.lookup-results');
        if (!results) return;
        results.classList.add('d-none');
        results.textContent = '';
    }

    function addChoice(field, item) {
        var multi = field.dataset.multi === 'true';
        var chips = field.querySelector('.lookup-chips');
        if (!chips) return;
        var existing = chipsOf(field).some(function (chip) {
            return String(chip.dataset.code).toLowerCase() === String(item.code).toLowerCase();
        });
        if (existing) return;
        if (!multi) {
            chips.textContent = '';
        } else if (chipsOf(field).length >= MAX_CHOICES) {
            feedback(field, 'بیش از ' + MAX_CHOICES + ' مورد قابل انتخاب نیست.');
            return;
        }
        var chip = document.createElement('span');
        chip.className = 'badge rounded-pill text-bg-light border lookup-chip';
        chip.dataset.code = item.code;
        var text = document.createElement('span');
        fillChoiceText(text, item);
        var hidden = document.createElement('input');
        hidden.type = 'hidden';
        hidden.name = field.dataset.fname;
        hidden.value = item.code;
        var remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'btn-close btn-close-sm ms-1 lookup-chip-remove';
        remove.setAttribute('aria-label', 'حذف این انتخاب');
        chip.appendChild(text);
        chip.appendChild(hidden);
        chip.appendChild(remove);
        chips.appendChild(chip);
        feedback(field, '');
    }

    function renderResults(field, items, term) {
        var results = field.querySelector('.lookup-results');
        if (!results) return;
        results.textContent = '';
        var unlisted = unlistedCodeFor(term, items.length, field.dataset.acceptCode === 'true');
        if (unlisted) {
            // An explicit row, never Enter on a half-typed search: recording a code nobody has
            // checked is a decision. The chip says so, and the server resolves it on save.
            var use = document.createElement('button');
            use.type = 'button';
            use.className = 'list-group-item list-group-item-action lookup-result lookup-unlisted';
            use.textContent = 'استفاده از کد «' + unlisted + '» — هنگام ذخیره بررسی می‌شود';
            use.addEventListener('click', function () {
                addChoice(field, {code: unlisted, note: ' · بررسی هنگام ذخیره'});
                hideResults(field);
                var box = field.querySelector('.lookup-search');
                if (box) box.value = '';
            });
            results.appendChild(use);
            results.classList.remove('d-none');
            return;
        }
        if (!items.length) {
            var none = document.createElement('div');
            none.className = 'list-group-item small text-muted';
            none.textContent = 'موردی پیدا نشد.';
            results.appendChild(none);
        }
        items.forEach(function (item) {
            var button = document.createElement('button');
            button.type = 'button';
            button.className = 'list-group-item list-group-item-action lookup-result';
            button.setAttribute('role', 'option');
            var title = document.createElement('div');
            fillChoiceText(title, item);
            button.appendChild(title);
            if (item.detail) {
                var detail = document.createElement('div');
                detail.className = 'small text-muted';
                detail.textContent = item.detail;
                button.appendChild(detail);
            }
            button.addEventListener('click', function () {
                addChoice(field, item);
                hideResults(field);
                var box = field.querySelector('.lookup-search');
                if (box) box.value = '';
            });
            results.appendChild(button);
        });
        results.classList.remove('d-none');
    }

    var timers = new WeakMap();

    async function search(field, term) {
        var url = field.dataset.lookupUrl;
        if (!url) return;
        try {
            var response = await fetch(url + '?q=' + encodeURIComponent(term), {
                headers: {'Accept': 'application/json'},
                credentials: 'same-origin',
                redirect: 'manual'
            });
            if (response.type === 'opaqueredirect' || response.status === 0 || !response.ok) {
                feedback(field, 'جستجو در فهرست ممکن نشد. صفحه را تازه‌سازی کنید.');
                return;
            }
            var items = await response.json();
            // Only the newest answer counts: a slow reply to «ع» must not overwrite «علی».
            var box = field.querySelector('.lookup-search');
            if (box && box.value.trim() !== term) return;
            feedback(field, '');
            renderResults(field, Array.isArray(items) ? items : [], term);
        } catch (ignored) {
            feedback(field, 'جستجو در فهرست ممکن نشد.');
        }
    }

    document.addEventListener('input', function (event) {
        var box = event.target;
        if (!box.classList || !box.classList.contains('lookup-search')) return;
        var field = fieldOf(box);
        if (!field) return;
        clearTimeout(timers.get(field));
        var term = box.value.trim();
        timers.set(field, setTimeout(function () { search(field, term); }, DEBOUNCE_MS));
    });

    document.addEventListener('focusin', function (event) {
        var box = event.target;
        if (!box.classList || !box.classList.contains('lookup-search')) return;
        var field = fieldOf(box);
        // An empty box still offers the first items: a short list is faster read than searched.
        if (field && box.value.trim() === '') search(field, '');
    });

    document.addEventListener('click', function (event) {
        var remove = event.target.closest ? event.target.closest('.lookup-chip-remove') : null;
        if (remove) {
            var chip = remove.closest('.lookup-chip');
            if (chip) chip.remove();
            return;
        }
        // A click anywhere else closes an open result list.
        document.querySelectorAll('.lookup-field').forEach(function (field) {
            if (!field.contains(event.target)) hideResults(field);
        });
    });

    document.addEventListener('keydown', function (event) {
        if (event.key !== 'Escape') return;
        var field = fieldOf(event.target);
        if (field) hideResults(field);
    });
})();
