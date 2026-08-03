/**
 * Searchable remote <select> for large master-data tables.
 * Markup: <select class="remote-select" data-remote-url="..." data-placeholder="...">
 * Optional: data-depends-on=".css-selector" data-depends-param="unitId"
 * Optional preselected: <option value="id" selected>label</option>
 * Optional restrict toggle: data-restrict-toggle=".css-selector" data-restrict-param="restrictToUnit"
 *   Points at a checkbox. Its checked state is sent as the named parameter, and while it
 *   is UNchecked the data-depends-on value stops being required (the list is unfiltered).
 */
(function () {
    function debounce(fn, ms) {
        let t;
        return function (...args) {
            clearTimeout(t);
            t = setTimeout(() => fn.apply(this, args), ms);
        };
    }

    function initRemoteSelect(select) {
        if (select.dataset.remoteReady === '1') return;
        select.dataset.remoteReady = '1';

        const url = select.dataset.remoteUrl;
        if (!url) return;

        const wrap = document.createElement('div');
        wrap.className = 'remote-select-wrap position-relative';
        select.parentNode.insertBefore(wrap, select);
        wrap.appendChild(select);

        const search = document.createElement('input');
        search.type = 'search';
        search.className = 'form-control form-control-sm mb-1 remote-select-search';
        search.placeholder = select.dataset.placeholder || 'جستجو…';
        search.autocomplete = 'off';
        wrap.insertBefore(search, select);

        const selectedValue = select.value;
        const selectedLabel = select.options[select.selectedIndex]?.text;
        const dependsSelector = select.dataset.dependsOn;
        const dependsParam = select.dataset.dependsParam || 'unitId';
        const formRoot = select.closest('form') || document;

        const restrictSelector = select.dataset.restrictToggle;
        const restrictParam = select.dataset.restrictParam || 'restrictToUnit';

        function dependencyValue() {
            if (!dependsSelector) return null;
            const el = formRoot.querySelector(dependsSelector);
            return el && el.value ? el.value : null;
        }

        /** No toggle in the markup → behave exactly as before (always restricted). */
        function restrictEnabled() {
            if (!restrictSelector) return true;
            const el = formRoot.querySelector(restrictSelector);
            return el ? !!el.checked : true;
        }

        function fillOptions(items, keepSelected) {
            const current = keepSelected ? select.value : '';
            select.innerHTML = '';
            const empty = document.createElement('option');
            empty.value = '';
            empty.textContent = select.dataset.emptyLabel || 'انتخاب…';
            select.appendChild(empty);

            const groups = new Map();
            items.forEach(item => {
                if (item.group) {
                    if (!groups.has(item.group)) {
                        const og = document.createElement('optgroup');
                        og.label = item.group;
                        groups.set(item.group, og);
                        select.appendChild(og);
                    }
                    const opt = document.createElement('option');
                    opt.value = item.value;
                    opt.textContent = item.label;
                    groups.get(item.group).appendChild(opt);
                } else {
                    const opt = document.createElement('option');
                    opt.value = item.value;
                    opt.textContent = item.label;
                    select.appendChild(opt);
                }
            });

            if (keepSelected && current) {
                let found = false;
                for (const opt of select.options) {
                    if (opt.value === current) {
                        opt.selected = true;
                        found = true;
                        break;
                    }
                }
                if (!found && selectedValue === current && selectedLabel) {
                    const opt = document.createElement('option');
                    opt.value = current;
                    opt.textContent = selectedLabel;
                    opt.selected = true;
                    select.appendChild(opt);
                }
            }
        }

        const load = debounce(async (q, keepSelected) => {
            try {
                const restricted = restrictEnabled();
                const dep = dependencyValue();
                // The dependency only gates the list while the restriction is on; an
                // unrestricted picker lists everything and needs no unit selected yet.
                if (dependsSelector && restricted && !dep) {
                    fillOptions([], false);
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
                const res = await fetch(requestUrl, {
                    headers: { 'Accept': 'application/json' }
                });
                if (!res.ok) return;
                const items = await res.json();
                fillOptions(items, keepSelected !== false);
            } catch (e) {
                /* ignore network errors in UI */
            }
        }, 250);

        search.addEventListener('input', () => load(search.value, true));

        if (dependsSelector) {
            const depEl = formRoot.querySelector(dependsSelector);
            if (depEl) {
                depEl.addEventListener('change', () => {
                    search.value = '';
                    select.value = '';
                    load('', false);
                });
            }
        }

        if (restrictSelector) {
            const toggleEl = formRoot.querySelector(restrictSelector);
            if (toggleEl) {
                // Switching the restriction changes which hierarchy is offered, so the
                // previously picked scope may no longer be valid — clear and reload.
                toggleEl.addEventListener('change', () => {
                    search.value = '';
                    select.value = '';
                    load('', false);
                });
            }
        }

        load('', true);
    }

    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('select.remote-select').forEach(initRemoteSelect);
    });

    document.addEventListener('shown.bs.modal', (ev) => {
        ev.target.querySelectorAll('select.remote-select').forEach(initRemoteSelect);
    });
})();
