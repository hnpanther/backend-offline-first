        (function () {
            const form = document.getElementById('customLogSheetForm');
            if (!form) return;
            const unitSelect = document.getElementById('customUnitSelect');
            const search = document.getElementById('customAssetSearch');
            const results = document.getElementById('customAssetResults');
            const selectedBox = document.getElementById('customAssetSelected');
            const submitBtn = document.getElementById('customSubmitBtn');
            if (!unitSelect || !search || !results || !selectedBox || !submitBtn) return;
            const selected = new Map(); // assetId -> label

            function debounce(fn, ms) {
                let t;
                return function (...a) { clearTimeout(t); t = setTimeout(() => fn.apply(this, a), ms); };
            }

            var withoutAssets = document.getElementById('customWithoutAssets');
            var assetBlock = document.getElementById('customAssetBlock');
            var scanModeBlock = document.getElementById('customScanModeBlock');

            function syncState() {
                var none = withoutAssets ? withoutAssets.checked : false;
                // A questions-only round submits with nothing selected; an ordinary one still
                // needs at least one asset. The server refuses both-at-once either way.
                submitBtn.disabled = !none && selected.size === 0;
                if (assetBlock) assetBlock.classList.toggle('d-none', none);
                // Nothing to scan on a questions-only round. The server stores NONE for it
                // regardless; hiding the control keeps the form from promising otherwise.
                if (scanModeBlock) scanModeBlock.classList.toggle('d-none', none);
            }

            if (withoutAssets) {
                withoutAssets.addEventListener('change', function () {
                    if (withoutAssets.checked) {
                        // Clearing rather than hiding: a hidden chip still posts its assetIds,
                        // and the server refuses a request that says both.
                        selected.clear();
                        renderSelected();
                    }
                    syncState();
                });
            }

            function renderSelected() {
                selectedBox.innerHTML = '';
                selected.forEach((label, id) => {
                    const chip = document.createElement('span');
                    chip.className = 'badge bg-primary d-inline-flex align-items-center gap-1 p-2';
                    chip.textContent = label;
                    const hidden = document.createElement('input');
                    hidden.type = 'hidden';
                    hidden.name = 'assetIds';
                    hidden.value = id;
                    chip.appendChild(hidden);
                    const x = document.createElement('button');
                    x.type = 'button';
                    x.className = 'btn-close btn-close-white btn-sm';
                    x.setAttribute('aria-label', 'حذف');
                    x.addEventListener('click', () => { selected.delete(id); renderSelected(); syncState(); });
                    chip.appendChild(x);
                    selectedBox.appendChild(chip);
                });
                syncState();
            }

            const loadResults = debounce(async (q) => {
                const unitId = unitSelect.value;
                if (!unitId) { results.classList.add('d-none'); return; }
                try {
                    const url = '/log-sheets/options/assets?unitId=' + encodeURIComponent(unitId)
                        + '&q=' + encodeURIComponent(q || '') + '&limit=30';
                    const res = await fetch(url, { headers: { 'Accept': 'application/json' } });
                    if (!res.ok) { results.classList.add('d-none'); return; }
                    const items = await res.json();
                    results.innerHTML = '';
                    if (items.length === 0) {
                        const empty = document.createElement('div');
                        empty.className = 'list-group-item text-muted small';
                        empty.textContent = 'داراییی یافت نشد';
                        results.appendChild(empty);
                    }
                    items.forEach(item => {
                        const btn = document.createElement('button');
                        btn.type = 'button';
                        btn.className = 'list-group-item list-group-item-action';
                        btn.textContent = item.label;
                        if (selected.has(item.value)) { btn.classList.add('active'); btn.disabled = true; }
                        btn.addEventListener('click', () => {
                            selected.set(item.value, item.label);
                            renderSelected();
                            btn.classList.add('active');
                            btn.disabled = true;
                        });
                        results.appendChild(btn);
                    });
                    results.classList.remove('d-none');
                } catch (e) { /* ignore */ }
            }, 250);

            unitSelect.addEventListener('change', () => {
                selected.clear();
                renderSelected();
                search.value = '';
                results.classList.add('d-none');
                search.disabled = !unitSelect.value;
                search.placeholder = unitSelect.value ? 'جستجوی دارایی…' : 'ابتدا واحد را انتخاب کنید…';
            });

            search.addEventListener('input', () => loadResults(search.value));
            search.addEventListener('focus', () => { if (unitSelect.value) loadResults(search.value); });

            // An empty selection is a REFUSAL for an ordinary round and the INTENT for a
            // questions-only one. Without the checkbox in this condition the «بدون دارایی» form
            // was silently cancelled here: the button enabled itself, the click did nothing, and
            // nothing on the page said why.
            form.addEventListener('submit', (ev) => {
                var none = withoutAssets ? withoutAssets.checked : false;
                if (!none && selected.size === 0) { ev.preventDefault(); }
            });

            // Once at load, so a checkbox the browser restored on a back-navigation decides the
            // button rather than leaving it disabled with the asset picker hidden.
            syncState();
        })();
    
