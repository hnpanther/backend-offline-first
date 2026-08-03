/**
 * Searchable remote MULTI-picker for large master-data tables.
 *
 * Unlike remote-select.js (one value, a real <select>), this renders a search box, a
 * result list and a chip list, and submits the chosen ids as repeated hidden inputs so
 * the server binds them to a List<Long> request parameter.
 *
 * Markup:
 *   <div class="remote-multi-select"
 *        data-remote-url="/log-sheet-templates/options/assets"
 *        data-field-name="assetIds"
 *        data-depends-on=".template-unit-select" data-depends-param="unitId"
 *        data-restrict-toggle=".scope-restrict-toggle" data-restrict-param="restrictToUnit"
 *        data-placeholder="جستجو…">
 *     <span class="remote-multi-preselected" data-value="12" data-label="…"></span>
 *   </div>
 *
 * The chip list is the single source of truth: hidden inputs are rebuilt from it on every
 * change, so a chip removed in the UI is genuinely absent from the POST.
 *
 * No external dependency — plain DOM APIs only.
 */
(function () {
    function debounce(fn, ms) {
        let t;
        return function (...args) {
            clearTimeout(t);
            t = setTimeout(() => fn.apply(this, args), ms);
        };
    }

    function initMulti(root) {
        if (root.dataset.multiReady === '1') return;
        root.dataset.multiReady = '1';

        const url = root.dataset.remoteUrl;
        const fieldName = root.dataset.fieldName;
        if (!url || !fieldName) return;

        const dependsSelector = root.dataset.dependsOn;
        const dependsParam = root.dataset.dependsParam || 'unitId';
        const restrictSelector = root.dataset.restrictToggle;
        const restrictParam = root.dataset.restrictParam || 'restrictToUnit';
        const formRoot = root.closest('form') || document;

        // Preselected values are declared as markup so the server stays the source of truth.
        const selected = new Map();
        root.querySelectorAll('.remote-multi-preselected').forEach(function (el) {
            if (el.dataset.value) selected.set(String(el.dataset.value), el.dataset.label || el.dataset.value);
            el.remove();
        });

        const search = document.createElement('input');
        search.type = 'search';
        search.className = 'form-control form-control-sm remote-multi-search';
        search.placeholder = root.dataset.placeholder || 'جستجو…';
        search.autocomplete = 'off';
        root.appendChild(search);

        const results = document.createElement('div');
        results.className = 'list-group list-group-flush remote-multi-results border rounded mt-1';
        results.style.maxHeight = '11rem';
        results.style.overflowY = 'auto';
        root.appendChild(results);

        const chips = document.createElement('div');
        chips.className = 'd-flex flex-wrap gap-1 mt-2 remote-multi-chips';
        root.appendChild(chips);

        const counter = document.createElement('div');
        counter.className = 'form-text remote-multi-counter';
        root.appendChild(counter);

        const hidden = document.createElement('div');
        hidden.className = 'remote-multi-hidden d-none';
        root.appendChild(hidden);

        function dependencyValue() {
            if (!dependsSelector) return null;
            const el = formRoot.querySelector(dependsSelector);
            return el && el.value ? el.value : null;
        }

        function restrictEnabled() {
            if (!restrictSelector) return true;
            const el = formRoot.querySelector(restrictSelector);
            return el ? !!el.checked : true;
        }

        function renderSelected() {
            chips.innerHTML = '';
            hidden.innerHTML = '';
            selected.forEach(function (label, value) {
                const chip = document.createElement('span');
                chip.className = 'badge bg-secondary-subtle text-body border d-inline-flex align-items-center gap-1';

                const text = document.createElement('span');
                text.textContent = label;
                chip.appendChild(text);

                const remove = document.createElement('button');
                remove.type = 'button';
                remove.className = 'btn-close btn-close-sm';
                remove.setAttribute('aria-label', 'حذف ' + label);
                remove.addEventListener('click', function () {
                    selected.delete(value);
                    renderSelected();
                    markResults();
                });
                chip.appendChild(remove);
                chips.appendChild(chip);

                const input = document.createElement('input');
                input.type = 'hidden';
                input.name = fieldName;
                input.value = value;
                hidden.appendChild(input);
            });
            counter.textContent = selected.size === 0
                ? 'هیچ دارایی‌ای انتخاب نشده است.'
                : selected.size + ' دارایی انتخاب شده است.';
            root.dispatchEvent(new CustomEvent('remote-multi:change', { bubbles: true }));
        }

        function markResults() {
            results.querySelectorAll('[data-option-value]').forEach(function (btn) {
                const chosen = selected.has(btn.dataset.optionValue);
                btn.classList.toggle('active', chosen);
                btn.disabled = chosen;
            });
        }

        function renderResults(items) {
            results.innerHTML = '';
            if (!items.length) {
                const empty = document.createElement('div');
                empty.className = 'list-group-item text-muted small';
                empty.textContent = 'موردی یافت نشد.';
                results.appendChild(empty);
                return;
            }
            items.forEach(function (item) {
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.className = 'list-group-item list-group-item-action py-1 small';
                btn.dataset.optionValue = String(item.value);
                btn.textContent = item.label;
                btn.addEventListener('click', function () {
                    selected.set(String(item.value), item.label);
                    renderSelected();
                    markResults();
                });
                results.appendChild(btn);
            });
            markResults();
        }

        const load = debounce(async function (q) {
            try {
                const restricted = restrictEnabled();
                const dep = dependencyValue();
                // While the restriction is on, the unit gates the list — same rule as remote-select.js.
                if (dependsSelector && restricted && !dep) {
                    renderResults([]);
                    return;
                }
                const sep = url.includes('?') ? '&' : '?';
                let requestUrl = url + sep + 'q=' + encodeURIComponent(q || '') + '&limit=30';
                if (dep) {
                    requestUrl += '&' + encodeURIComponent(dependsParam) + '=' + encodeURIComponent(dep);
                }
                if (restrictSelector) {
                    requestUrl += '&' + encodeURIComponent(restrictParam) + '=' + (restricted ? 'true' : 'false');
                }
                const res = await fetch(requestUrl, { headers: { 'Accept': 'application/json' } });
                if (!res.ok) return;
                renderResults(await res.json());
            } catch (e) {
                /* ignore network errors in UI */
            }
        }, 250);

        search.addEventListener('input', function () { load(search.value); });

        // Changing the unit or the restriction changes which assets are offered, but the
        // already-picked list is deliberately KEPT: an EXPLICIT template is a frozen set the
        // author curated, and silently dropping entries on an unrelated field change would
        // lose their work. The server re-validates every id against the author's authority.
        [dependsSelector, restrictSelector].forEach(function (sel) {
            if (!sel) return;
            const el = formRoot.querySelector(sel);
            if (el) {
                el.addEventListener('change', function () {
                    search.value = '';
                    load('');
                });
            }
        });

        renderSelected();
        load('');
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('.remote-multi-select').forEach(initMulti);
    });

    document.addEventListener('shown.bs.modal', function (ev) {
        ev.target.querySelectorAll('.remote-multi-select').forEach(initMulti);
    });
})();
