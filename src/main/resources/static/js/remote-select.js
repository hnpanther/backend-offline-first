/**
 * Searchable remote <select> for large master-data tables.
 * Markup: <select class="remote-select" data-remote-url="..." data-placeholder="...">
 * Optional preselected: <option value="id" selected>label</option>
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

        const load = debounce(async (q) => {
            try {
                const sep = url.includes('?') ? '&' : '?';
                const res = await fetch(url + sep + 'q=' + encodeURIComponent(q || '') + '&limit=30', {
                    headers: { 'Accept': 'application/json' }
                });
                if (!res.ok) return;
                const items = await res.json();
                fillOptions(items, true);
            } catch (e) {
                /* ignore network errors in UI */
            }
        }, 250);

        search.addEventListener('input', () => load(search.value));
        load('');
    }

    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('select.remote-select').forEach(initRemoteSelect);
    });

    document.addEventListener('shown.bs.modal', (ev) => {
        ev.target.querySelectorAll('select.remote-select').forEach(initRemoteSelect);
    });
})();
