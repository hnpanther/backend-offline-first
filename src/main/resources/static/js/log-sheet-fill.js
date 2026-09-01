(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        var form = document.getElementById('fillForm');
        if (!form) return;

        var cards = Array.from(form.querySelectorAll('.log-sheet-entry-card'));
        var searchInput = document.getElementById('entrySearch');
        var clearSearchButton = document.getElementById('clearEntrySearch');
        var noResults = document.getElementById('entryNoResults');
        var progressBar = document.getElementById('fillProgressBar');
        var progressRoot = progressBar ? progressBar.closest('[role="progressbar"]') : null;
        var activeFilter = 'all';
        var dirty = false;
        var submitting = false;

        function normalizeSearch(value) {
            return String(value || '')
                .toLocaleLowerCase('fa-IR')
                .replace(/ي/g, 'ی')
                .replace(/ك/g, 'ک')
                .replace(/[\u064B-\u065F\u0670]/g, '')
                .replace(/[\u200c\u200d]/g, ' ')
                .replace(/\s+/g, ' ')
                .trim();
        }

        function formatFaNumber(value) {
            try {
                return Number(value).toLocaleString('fa-IR');
            } catch (ignored) {
                return String(value);
            }
        }

        function parseBound(raw) {
            if (raw === undefined || raw === null || raw === '') return null;
            var number = parseFloat(raw);
            return Number.isFinite(number) ? number : null;
        }

        function evaluateNumber(input) {
            var value = parseFloat(input.value);
            if (input.value === '' || !Number.isFinite(value)) return null;
            var dangerMin = parseBound(input.dataset.dangerMin);
            var dangerMax = parseBound(input.dataset.dangerMax);
            var warningMin = parseBound(input.dataset.warningMin);
            var warningMax = parseBound(input.dataset.warningMax);
            if (dangerMin !== null && value < dangerMin) return 'danger';
            if (dangerMax !== null && value > dangerMax) return 'danger';
            if (warningMin !== null && value < warningMin) return 'warning';
            if (warningMax !== null && value > warningMax) return 'warning';
            return 'ok';
        }

        function updateNumberFeedback(input) {
            var wrap = input.closest('.fill-field-wrap');
            var feedback = wrap ? wrap.querySelector('.validation-feedback') : null;
            if (!feedback) return;

            var severity = evaluateNumber(input);
            input.classList.toggle('border-danger', severity === 'danger');
            input.classList.toggle('border-warning', severity === 'warning');

            if (severity === 'danger') {
                feedback.textContent = 'مقدار خارج از بازه خطر است.';
                feedback.className = 'validation-feedback small mt-1 fw-semibold text-danger';
            } else if (severity === 'warning') {
                feedback.textContent = 'مقدار خارج از بازه هشدار است.';
                feedback.className = 'validation-feedback small mt-1 fw-semibold text-warning';
            } else {
                feedback.textContent = '';
                feedback.className = 'validation-feedback small mt-1 d-none';
            }
        }

        /**
         * Whether this asset holds a reading.
         *
         * Read off the summary the server rendered, not off any input. The card has no inputs any
         * more, and asking the dialog instead would answer a different question — "has somebody
         * typed something" rather than "is something stored" — which would light the card up for
         * values that were never saved. `form-data-display` stamps every row with
         * `data-param-state`, so this is the server's own verdict.
         */
        function entryHasMeaningfulData(card) {
            return !!card.querySelector('[data-param-state="filled"]');
        }

        function updateCardState(card) {
            var filled = entryHasMeaningfulData(card);
            card.dataset.entryState = filled ? 'filled' : 'empty';
            card.classList.toggle('log-sheet-entry-filled', filled);

            var header = card.querySelector('.fill-entry-header');
            if (header) header.classList.toggle('log-sheet-entry-filled-header', filled);

            var badge = card.querySelector('[data-entry-state]');
            if (badge) {
                badge.classList.toggle('is-filled', filled);
                var icon = badge.querySelector('i');
                var text = badge.querySelector('span');
                if (icon) icon.className = filled ? 'bi bi-check-circle-fill' : 'bi bi-circle';
                if (text) text.textContent = filled ? 'دارای داده' : 'بدون داده';
            }

            // The button is rendered «تکمیل» or «ویرایش» by the server on load, and the first save
            // of an asset changes which of the two is true. Left alone it kept inviting the
            // operator to "complete" a row they had just completed.
            var editLabel = card.querySelector('.fill-entry-edit [data-edit-label]');
            if (editLabel) editLabel.textContent = filled ? 'ویرایش' : 'تکمیل';

            return filled;
        }

        function updateSummary() {
            var filledCount = cards.filter(function (card) { return card.dataset.entryState === 'filled'; }).length;
            var emptyCount = cards.length - filledCount;
            var percent = cards.length ? Math.round((filledCount * 100) / cards.length) : 0;

            document.getElementById('filledEntryCount').textContent = formatFaNumber(filledCount);
            document.getElementById('filledFilterCount').textContent = formatFaNumber(filledCount);
            document.getElementById('emptyFilterCount').textContent = formatFaNumber(emptyCount);
            document.getElementById('progressPercent').textContent = formatFaNumber(percent) + '٪';
            if (progressBar) progressBar.style.width = percent + '%';
            if (progressRoot) progressRoot.setAttribute('aria-valuenow', String(percent));

            var jumpButton = document.getElementById('jumpToFirstEmpty');
            if (jumpButton) jumpButton.disabled = emptyCount === 0;
        }

        function refreshAllStates() {
            cards.forEach(updateCardState);
            updateSummary();
        }

        function applyFilters() {
            var query = normalizeSearch(searchInput ? searchInput.value : '');
            var visibleCount = 0;

            cards.forEach(function (card) {
                var identity = card.querySelector('.fill-entry-identity');
                var haystack = normalizeSearch(identity ? identity.textContent : card.textContent);
                var matchesSearch = !query || haystack.indexOf(query) !== -1;
                var matchesState = activeFilter === 'all' || card.dataset.entryState === activeFilter;
                var visible = matchesSearch && matchesState;
                card.classList.toggle('d-none', !visible);
                if (visible) visibleCount += 1;
            });

            document.getElementById('visibleEntryCount').textContent = formatFaNumber(visibleCount);
            if (noResults) noResults.classList.toggle('d-none', visibleCount > 0 || cards.length === 0);
            if (clearSearchButton) clearSearchButton.classList.toggle('d-none', !query);
        }

        function setActiveFilter(filter) {
            activeFilter = filter;
            document.querySelectorAll('[data-entry-filter]').forEach(function (button) {
                var active = button.dataset.entryFilter === filter;
                button.classList.toggle('active', active);
                button.setAttribute('aria-pressed', String(active));
            });
            applyFilters();
        }

        /**
         * Unsaved state, which since each asset saves itself means the sheet's notes field only.
         * Readings can no longer be "unsaved": they are either stored or were never confirmed.
         */
        function setDirtyState(value) {
            dirty = value;
            document.querySelectorAll('.fill-save-state').forEach(function (state) {
                state.classList.toggle('is-dirty', dirty);
                state.classList.toggle('is-saved', !dirty);
                state.innerHTML = dirty
                    ? '<i class="bi bi-exclamation-circle-fill" aria-hidden="true"></i><span>توضیحات ذخیره‌نشده دارید</span>'
                    : '<i class="bi bi-check-circle-fill" aria-hidden="true"></i><span>مقادیر هر دارایی هنگام ثبت ذخیره می‌شود</span>';
            });
        }

        function setEntryExpanded(card, expanded) {
            var collapseElement = card.querySelector('.fill-entry-collapse');
            if (!collapseElement) return;
            if (window.bootstrap && window.bootstrap.Collapse) {
                window.bootstrap.Collapse.getOrCreateInstance(collapseElement, {toggle: false})[expanded ? 'show' : 'hide']();
            } else {
                collapseElement.classList.toggle('show', expanded);
            }
            updateEntryToggle(card, expanded);
        }

        function updateEntryToggle(card, expanded) {
            var toggle = card.querySelector('.fill-entry-toggle');
            if (toggle) {
                toggle.setAttribute('aria-expanded', String(expanded));
                var icon = toggle.querySelector('i');
                if (icon) icon.className = expanded ? 'bi bi-chevron-up' : 'bi bi-chevron-down';
            }
        }

        cards.forEach(function (card) {
            var identity = card.querySelector('.fill-entry-identity');
            card.dataset.entrySearch = normalizeSearch(identity ? identity.textContent : '');

            var collapseElement = card.querySelector('.fill-entry-collapse');
            if (collapseElement) {
                collapseElement.addEventListener('shown.bs.collapse', function () { updateEntryToggle(card, true); });
                collapseElement.addEventListener('hidden.bs.collapse', function () { updateEntryToggle(card, false); });
            }
        });

        // -- the per-asset dialogs ----------------------------------------------------------

        function cardFor(entryId) {
            return cards.find(function (card) { return card.dataset.entryId === String(entryId); });
        }

        /**
         * One asset's fields, encoded exactly as the full-page form used to encode them.
         *
         * The shapes the server's `parseFieldValue` reads are not incidental and have to be
         * reproduced here rather than approximated:
         *   - a checkbox is a hidden `false` followed by `true` when ticked, and the pair
         *     ["false","true"] is what the server reads as true; skipping the unticked box
         *     leaves ["false"], which is how it reads false;
         *   - a multiselect contributes one value per selected option;
         *   - an attachment field contributes one hidden input per file already uploaded;
         *   - an empty text input contributes an empty string, NOT nothing. That empty string is
         *     what clears a value; omitting the key would leave the old one standing instead.
         * Iterating every named element and skipping only unchecked boxes produces all four.
         */
        function collectEntryFields(dialog) {
            var params = new URLSearchParams();
            dialog.querySelectorAll('input[name], select[name], textarea[name]').forEach(function (el) {
                if (el.disabled || el.name.indexOf('fd_') !== 0) return;
                if (el.type === 'checkbox' || el.type === 'radio') {
                    if (el.checked) params.append(el.name, el.value);
                    return;
                }
                if (el.tagName === 'SELECT' && el.multiple) {
                    Array.from(el.selectedOptions).forEach(function (option) {
                        params.append(el.name, option.value);
                    });
                    return;
                }
                params.append(el.name, el.value);
            });
            return params;
        }

        function setDialogState(dialog, kind, message) {
            var slot = dialog.querySelector('[data-dialog-state]');
            if (!slot) return;
            slot.className = 'fill-modal-state' + (kind ? ' is-' + kind : '');
            slot.textContent = message || '';
        }

        /**
         * POSTs the fields and resolves with the re-rendered summary markup.
         *
         * Not `AppCsrf.postJson`: this endpoint answers with an HTML fragment and that helper
         * parses JSON. The guards it documents are reproduced instead, because each one is a way
         * this call fails silently otherwise - above all `redirect: 'manual'`, since an expired
         * session answers a POST with a redirect that fetch would follow into a 200 carrying a
         * login page.
         */
        async function postFields(url, params) {
            var contentType = {'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'};
            var response = await fetch(url, {
                method: 'POST',
                headers: window.AppCsrf ? window.AppCsrf.headers(contentType) : contentType,
                body: params.toString(),
                credentials: 'same-origin',
                redirect: 'manual'
            });
            if (response.type === 'opaqueredirect' || response.status === 0
                || (response.status >= 300 && response.status < 400)) {
                // The web chain answers BOTH an expired session and a refused request with a redirect,
                // so this message has to cover both rather than name one of them.
                throw new Error('درخواست پذیرفته نشد. ممکن است نشست شما منقضی شده باشد — صفحه را تازه‌سازی کنید.');
            }
            var text = await response.text();
            if (!response.ok) {
                throw new Error('ذخیره نشد (خطای ' + response.status + ').');
            }
            return text;
        }

        async function saveDialog(button) {
            var dialog = button.closest('.fill-entry-modal');
            var card = cardFor(button.dataset.saveEntry);
            if (!dialog || !card) return;

            button.disabled = true;
            setDialogState(dialog, 'busy', 'در حال ذخیره…');
            try {
                var html = await postFields(button.dataset.saveUrl, collectEntryFields(dialog));
                var target = card.querySelector('[data-entry-summary]');
                if (target) {
                    target.outerHTML = html;
                    // The tiles in the replacement arrived unbound; without this the lightbox
                    // stops opening for exactly the asset that was just edited.
                    if (window.AppAttachments) {
                        window.AppAttachments.initGalleries(card);
                    }
                }
                updateCardState(card);
                updateSummary();
                applyFilters();
                setDialogState(dialog, '', '');
                if (window.bootstrap && window.bootstrap.Modal) {
                    window.bootstrap.Modal.getOrCreateInstance(dialog).hide();
                }
            } catch (error) {
                // Left open on purpose: closing would discard what the operator typed, and the
                // reason it failed is usually something they can act on.
                setDialogState(dialog, 'error', error.message || 'ذخیره نشد.');
            } finally {
                button.disabled = false;
            }
        }

        document.querySelectorAll('.fill-modal-save').forEach(function (button) {
            button.addEventListener('click', function () { void saveDialog(button); });
        });

        document.querySelectorAll('.fill-entry-modal').forEach(function (dialog) {
            dialog.querySelectorAll('.fill-field-number').forEach(updateNumberFeedback);
            dialog.addEventListener('input', function (event) {
                if (event.target.classList.contains('fill-field-number')) updateNumberFeedback(event.target);
            });
            dialog.addEventListener('change', function (event) {
                if (event.target.classList.contains('fill-field-number')) updateNumberFeedback(event.target);
            });
            dialog.addEventListener('show.bs.modal', function () { setDialogState(dialog, '', ''); });
            dialog.addEventListener('shown.bs.modal', function () {
                var first = dialog.querySelector('.fill-field:not([type="hidden"])');
                if (first) first.focus({preventScroll: true});
            });
        });

        document.querySelectorAll('[data-entry-filter]').forEach(function (button) {
            button.addEventListener('click', function () { setActiveFilter(button.dataset.entryFilter); });
        });

        if (searchInput) searchInput.addEventListener('input', applyFilters);
        if (clearSearchButton) clearSearchButton.addEventListener('click', function () {
            searchInput.value = '';
            searchInput.focus();
            applyFilters();
        });

        var resetButton = document.getElementById('resetEntryFilters');
        if (resetButton) resetButton.addEventListener('click', function () {
            if (searchInput) searchInput.value = '';
            setActiveFilter('all');
        });

        var jumpButton = document.getElementById('jumpToFirstEmpty');
        if (jumpButton) jumpButton.addEventListener('click', function () {
            if (searchInput) searchInput.value = '';
            setActiveFilter('all');
            var target = cards.find(function (card) { return card.dataset.entryState === 'empty'; });
            if (!target) return;
            setEntryExpanded(target, true);
            target.scrollIntoView({behavior: 'smooth', block: 'center'});
            window.setTimeout(function () {
                var openButton = target.querySelector('.fill-entry-edit');
                if (openButton) openButton.click();
            }, 350);
        });

        document.getElementById('collapseAllEntries').addEventListener('click', function () {
            cards.filter(function (card) { return !card.classList.contains('d-none'); })
                .forEach(function (card) { setEntryExpanded(card, false); });
        });
        document.getElementById('expandAllEntries').addEventListener('click', function () {
            cards.filter(function (card) { return !card.classList.contains('d-none'); })
                .forEach(function (card) { setEntryExpanded(card, true); });
        });

        form.addEventListener('submit', function (event) {
            if (submitting) {
                event.preventDefault();
                return;
            }
            var submitter = event.submitter;
            if (submitter && submitter.id === 'finalSubmitBtn') {
                var confirmed = window.confirm('با تأیید نهایی، لاگ شیت ثبت قطعی می‌شود. آیا از ادامه مطمئن هستید؟');
                if (!confirmed) {
                    event.preventDefault();
                    return;
                }
            }
            submitting = true;
            dirty = false;
            form.classList.add('is-submitting');
        });

        window.addEventListener('beforeunload', function (event) {
            if (!dirty || submitting) return;
            event.preventDefault();
            event.returnValue = '';
        });

        var notesField = document.getElementById('logSheetNotes');
        if (notesField) {
            var savedNotes = notesField.value;
            notesField.addEventListener('input', function () {
                setDirtyState(notesField.value !== savedNotes);
            });
        }

        refreshAllStates();
        applyFilters();
        setDirtyState(false);
    });
})();
