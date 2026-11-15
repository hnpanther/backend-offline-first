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
        var text = document.createElement('bdi');
        text.textContent = item.label && item.label !== item.code ? item.label + ' (' + item.code + ')' : item.code;
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

    function renderResults(field, items) {
        var results = field.querySelector('.lookup-results');
        if (!results) return;
        results.textContent = '';
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
            title.textContent = item.label;
            var code = document.createElement('small');
            code.className = 'text-muted ms-2';
            code.textContent = item.code;
            title.appendChild(code);
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
            renderResults(field, Array.isArray(items) ? items : []);
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
