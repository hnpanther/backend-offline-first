        document.querySelectorAll('.template-form').forEach(function (form) {
            var typeSelect = form.querySelector('.scope-type-select');
            var modeSelect = form.querySelector('.asset-mode-select');
            if (!typeSelect) return;

            var classSelect = form.querySelector('select[name="classId"]');

            function explicitMode() {
                return modeSelect ? modeSelect.value === 'EXPLICIT' : false;
            }

            /** No equipment at all: neither a scope to resolve nor a list to pick. */
            function noAssetMode() {
                return modeSelect ? modeSelect.value === 'NONE' : false;
            }

            /**
             * Scope/class fields are hidden AND disabled in EXPLICIT mode, so the browser
             * neither enforces their `required` nor submits stale values the server would
             * have to ignore. Disabled controls are omitted from the POST entirely.
             */
            function syncScopePickers() {
                var explicit = explicitMode();
                var none = noAssetMode();
                var type = typeSelect.value;

                // Disabled as well as hidden, so the browser neither enforces `required` on a
                // field nobody can see nor submits a stale value the server would ignore.
                typeSelect.disabled = explicit || none;
                typeSelect.required = !explicit && !none;
                if (classSelect) {
                    classSelect.disabled = explicit || none;
                    classSelect.required = !explicit && !none;
                }
                form.querySelectorAll('.mode-scope-field').forEach(function (f) {
                    f.classList.toggle('d-none', explicit || none);
                });
                form.querySelectorAll('.mode-explicit-field').forEach(function (f) {
                    f.classList.toggle('d-none', !explicit);
                });
                // Nothing to scan on a round with no equipment. Hidden rather than merely
                // ignored, because a visible «فقط NFC» beside «بدون دارایی» reads as a setting
                // that will be honoured — and the service overrides it to NONE regardless.
                form.querySelectorAll('.scan-mode-row').forEach(function (f) {
                    f.classList.toggle('d-none', none);
                });

                form.querySelectorAll('.scope-picker').forEach(function (p) { p.classList.add('d-none'); });
                form.querySelectorAll('.scope-id-select').forEach(function (s) {
                    s.disabled = true;
                    s.required = false;
                    s.removeAttribute('name');
                });
                if (explicit || none) return;

                var active = form.querySelector('.scope-picker-' + type);
                if (active) {
                    active.classList.remove('d-none');
                    var sel = active.querySelector('.scope-id-select');
                    if (sel) {
                        sel.disabled = false;
                        sel.setAttribute('name', 'scopeId');
                        sel.required = true;
                    }
                }
            }

            typeSelect.addEventListener('change', syncScopePickers);
            if (modeSelect) modeSelect.addEventListener('change', syncScopePickers);
            syncScopePickers();
        });
    
