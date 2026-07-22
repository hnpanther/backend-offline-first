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

        function entryHasMeaningfulData(card) {
            var fields = card.querySelectorAll('input, select, textarea');
            for (var i = 0; i < fields.length; i += 1) {
                var field = fields[i];
                if (field.disabled || field.type === 'hidden') continue;
                if (field.type === 'checkbox' || field.type === 'radio') {
                    if (field.checked) return true;
                    continue;
                }
                if (field.tagName === 'SELECT' && field.multiple) {
                    if (Array.from(field.selectedOptions).some(function (option) { return option.value !== ''; })) return true;
                    continue;
                }
                if (String(field.value || '').trim() !== '') return true;
            }
            return false;
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

        function setDirtyState(value) {
            dirty = value;
            document.querySelectorAll('.fill-save-state').forEach(function (state) {
                state.classList.toggle('is-dirty', dirty);
                state.classList.toggle('is-saved', !dirty);
                state.innerHTML = dirty
                    ? '<i class="bi bi-exclamation-circle-fill" aria-hidden="true"></i><span>تغییرات ذخیره‌نشده دارید</span>'
                    : '<i class="bi bi-check-circle-fill" aria-hidden="true"></i><span>تغییری برای ذخیره وجود ندارد</span>';
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

            card.querySelectorAll('.fill-field-number').forEach(function (input) {
                updateNumberFeedback(input);
            });

            card.addEventListener('input', function (event) {
                if (event.target.classList.contains('fill-field-number')) updateNumberFeedback(event.target);
                updateCardState(card);
                updateSummary();
                setDirtyState(true);
            });
            card.addEventListener('change', function (event) {
                if (event.target.classList.contains('fill-field-number')) updateNumberFeedback(event.target);
                updateCardState(card);
                updateSummary();
                setDirtyState(true);
            });

            var collapseElement = card.querySelector('.fill-entry-collapse');
            if (collapseElement) {
                collapseElement.addEventListener('shown.bs.collapse', function () { updateEntryToggle(card, true); });
                collapseElement.addEventListener('hidden.bs.collapse', function () { updateEntryToggle(card, false); });
            }
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
                var firstField = target.querySelector('.fill-field:not([type="hidden"])');
                if (firstField) firstField.focus({preventScroll: true});
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

        refreshAllStates();
        applyFilters();
        setDirtyState(false);
    });
})();
