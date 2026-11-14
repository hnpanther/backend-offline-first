/*
 * Search, and free entry, on a choice field of the panel's fill page (roadmap §31, phase 1).
 *
 * A progressive enhancement over a plain <select class="fill-field-choice">: the <select> is
 * still the control that posts, so every rule the fill page's save path already relies on — one
 * value per selected option, nothing for an emptied multiselect, the `fd_present_` marker —
 * holds unchanged. Without this script the page works exactly as it did.
 *
 *   - SEARCH is automatic above THRESHOLD options and absent below it: a three-option list is
 *     faster read than searched, and an administrator should not have to know what a combobox
 *     is. It changes what is SHOWN and nothing the server accepts, so it has no setting.
 *   - FREE ENTRY appears only on a field whose definition says `allowCustom`
 *     (data-allow-custom="true"). The typed answer becomes a selected <option> of its own; the
 *     SERVER decides whether it is acceptable (FormDataValidationSupport.validateSelect) — this
 *     only stops the obvious mistakes before a round trip.
 *
 * The normaliser is used for MATCHING ONLY, never for what is stored: «ي/ی», «ك/ک», ZWNJ,
 * tatweel and diacritics, Persian/Arabic digits, case. The PWA has the same function
 * (src/utils/persianSearch.ts) and both run the same table of cases
 * (src/test/js/persian-search-cases.json here, src/utils/persianSearch.cases.json there) — two
 * implementations of «does this match» drift the first time either is fixed, and the symptom is
 * an option the tablet finds and the panel does not.
 *
 * No `name` on anything this adds: the fill page posts every named control whose name starts
 * with `fd_` / `sfd_`, and a search box must never become an answer.
 */
(function (global) {
    'use strict';

    /** Above this many options a list gets a search box. Same number on the tablet. */
    var THRESHOLD = 8;
    /** Mirrors FieldValidationSupport.MAX_CUSTOM_OPTION_LENGTH; the server enforces it. */
    var MAX_CUSTOM_LENGTH = 200;

    function normalize(value) {
        var s = String(value == null ? '' : value);
        s = s.replace(/[\u064A\u0649]/g, '\u06CC')      // Arabic yeh, alef maksura -> Persian yeh
             .replace(/\u0643/g, '\u06A9')              // Arabic kaf -> Persian keheh
             .replace(/\u200C|\u200D|\u0640/g, '')      // ZWNJ, ZWJ, tatweel
             .replace(/[\u064B-\u065F\u0670]/g, '')      // harakat and superscript alef
             .replace(/[\u06F0-\u06F9]/g, function (d) { return String(d.charCodeAt(0) - 0x06F0); })
             .replace(/[\u0660-\u0669]/g, function (d) { return String(d.charCodeAt(0) - 0x0660); })
             .toLowerCase()
             .replace(/\s+/g, ' ')
             .trim();
        return s;
    }

    /** Whether `option` matches `query`; an empty query matches everything. */
    function matches(option, query) {
        var q = normalize(query);
        return q === '' || normalize(option).indexOf(q) !== -1;
    }

    /** Control characters in a typed answer — the server refuses them, so say so first. */
    function hasControl(text) {
        return /[\u0000-\u001F\u007F-\u009F]/.test(text);
    }

    function choiceOptions(select) {
        return Array.prototype.filter.call(select.options, function (o) { return o.value !== ''; });
    }

    function enhanceSelect(select) {
        if (!select || select.dataset.choiceSearchReady === '1') return;
        var allowCustom = select.dataset.allowCustom === 'true';
        var count = choiceOptions(select).length;
        if (count <= THRESHOLD && !allowCustom) return;
        select.dataset.choiceSearchReady = '1';

        var doc = select.ownerDocument;
        var box = doc.createElement('div');
        box.className = 'choice-search d-flex gap-1 mb-1';

        var input = doc.createElement('input');
        input.type = 'search';
        input.className = 'form-control form-control-sm choice-search-input';
        input.autocomplete = 'off';
        input.placeholder = allowCustom ? 'جستجو یا نوشتن پاسخ…' : 'جستجو در گزینه‌ها…';
        input.setAttribute('aria-label', allowCustom ? 'جستجو یا نوشتن پاسخ' : 'جستجو در گزینه‌ها');
        box.appendChild(input);

        var add = null;
        if (allowCustom) {
            add = doc.createElement('button');
            add.type = 'button';
            add.className = 'btn btn-sm btn-outline-primary text-nowrap choice-search-add';
            add.textContent = 'افزودن';
            add.title = 'این متن را به‌عنوان پاسخ دستی ثبت کن';
            add.disabled = true;
            box.appendChild(add);
        }
        select.parentNode.insertBefore(box, select);

        var note = doc.createElement('div');
        note.className = 'form-text choice-search-note d-none';
        select.parentNode.insertBefore(note, select.nextSibling);

        var single = !select.multiple;

        function exact(text) {
            var n = normalize(text);
            return choiceOptions(select).find(function (o) { return normalize(o.value) === n; }) || null;
        }

        function setNote(text, warn) {
            note.textContent = text || '';
            note.classList.toggle('d-none', !text);
            note.classList.toggle('text-danger', !!warn);
        }

        function refresh() {
            var query = input.value;
            var visible = 0;
            choiceOptions(select).forEach(function (o) {
                // A selected option is never hidden: hiding the current answer would make it look
                // cleared, and on a single <select> the browser would then show another option.
                var show = o.selected || matches(o.textContent, query) || matches(o.value, query);
                o.hidden = !show;
                if (show) visible++;
            });
            // A single <select> is a closed dropdown, where a filtered list is invisible until it
            // is opened. While a query is in force it becomes a short list box instead, so the
            // matches are on screen; cleared, it goes back to being a dropdown.
            if (single) {
                if (query.trim()) {
                    select.size = Math.max(2, Math.min(visible + (select.querySelector('option[value=""]') ? 1 : 0), 8));
                } else {
                    select.removeAttribute('size');
                }
            }
            if (add) {
                var text = query.trim();
                add.disabled = text === '' || exact(text) !== null;
            }
            if (query.trim() && visible === 0) {
                setNote(allowCustom ? 'در فهرست نیست — با «افزودن» به‌عنوان پاسخ دستی ثبت کنید.' : 'گزینه‌ای پیدا نشد.', false);
            } else {
                setNote('', false);
            }
        }

        function choose(option) {
            if (single) {
                select.value = option.value;
            } else {
                option.selected = true;
            }
            select.dispatchEvent(new Event('change', {bubbles: true}));
        }

        function addCustom() {
            var text = input.value.trim();
            if (!text) return;
            var existing = exact(text);
            if (existing) {
                choose(existing);
            } else {
                if (Array.from(text).length > MAX_CUSTOM_LENGTH) {
                    setNote('پاسخ دستی حداکثر ' + MAX_CUSTOM_LENGTH + ' نویسه است.', true);
                    return;
                }
                if (hasControl(text)) {
                    setNote('پاسخ دستی نباید شکست خط یا نویسهٔ کنترلی داشته باشد.', true);
                    return;
                }
                var option = doc.createElement('option');
                option.value = text;
                option.textContent = text + ' · دستی';
                option.dataset.custom = 'true';
                select.appendChild(option);
                choose(option);
            }
            input.value = '';
            refresh();
        }

        input.addEventListener('input', refresh);
        input.addEventListener('keydown', function (event) {
            if (event.key !== 'Enter') return;
            // Never submit the surrounding form from a search box.
            event.preventDefault();
            var visible = choiceOptions(select).filter(function (o) { return !o.hidden && !o.selected; });
            if (allowCustom && input.value.trim() && exact(input.value) === null) {
                addCustom();
            } else if (visible.length === 1) {
                choose(visible[0]);
                input.value = '';
                refresh();
            }
        });
        if (add) add.addEventListener('click', addCustom);
        select.addEventListener('change', function () { if (single && !input.value.trim()) refresh(); });
    }

    /** Enhances every choice field under `root` (a document, a dialog, a replaced fragment). */
    function enhance(root) {
        var scope = root || document;
        if (!scope.querySelectorAll) return;
        scope.querySelectorAll('select.fill-field-choice').forEach(enhanceSelect);
    }

    global.ChoiceSearch = {enhance: enhance, normalize: normalize, matches: matches, THRESHOLD: THRESHOLD};

    if (typeof document !== 'undefined' && document.addEventListener) {
        document.addEventListener('DOMContentLoaded', function () { enhance(document); });
    }
})(typeof window !== 'undefined' ? window : this);
