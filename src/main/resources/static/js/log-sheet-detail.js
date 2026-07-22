(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        var searchInput = document.getElementById('detailEntrySearch');
        var clearButton = document.getElementById('detailClearSearch');
        var visibleCount = document.getElementById('detailVisibleEntryCount');
        var noResults = document.getElementById('detailEntryNoResults');
        var rows = Array.from(document.querySelectorAll('.log-detail-entry-row'));
        var activeFilter = 'all';

        if (!searchInput) return;

        function normalize(value) {
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

        rows.forEach(function (row) {
            var sources = row.querySelectorAll('.log-detail-entry-search-source');
            row.dataset.searchText = normalize(Array.from(sources).map(function (cell) {
                return cell.textContent;
            }).join(' '));
        });

        function applyFilters() {
            var query = normalize(searchInput.value);
            var count = 0;

            rows.forEach(function (row) {
                var matchesSearch = !query || row.dataset.searchText.indexOf(query) !== -1;
                var matchesState = activeFilter === 'all' || row.dataset.entryState === activeFilter;
                var show = matchesSearch && matchesState;
                row.classList.toggle('d-none', !show);
                if (show) count += 1;
            });

            visibleCount.textContent = formatFaNumber(count);
            if (noResults) noResults.classList.toggle('d-none', count > 0 || rows.length === 0);
            if (clearButton) clearButton.classList.toggle('d-none', !query);
        }

        function selectFilter(filter) {
            activeFilter = filter;
            document.querySelectorAll('[data-detail-entry-filter]').forEach(function (button) {
                var active = button.dataset.detailEntryFilter === filter;
                button.classList.toggle('active', active);
                button.setAttribute('aria-pressed', String(active));
            });
            applyFilters();
        }

        searchInput.addEventListener('input', applyFilters);

        if (clearButton) clearButton.addEventListener('click', function () {
            searchInput.value = '';
            searchInput.focus();
            applyFilters();
        });

        document.querySelectorAll('[data-detail-entry-filter]').forEach(function (button) {
            button.addEventListener('click', function () {
                selectFilter(button.dataset.detailEntryFilter);
            });
        });

        document.querySelectorAll('.log-detail-section-nav a, .log-detail-footer-actions a[href^="#"]').forEach(function (link) {
            link.addEventListener('click', function (event) {
                var target = document.querySelector(link.getAttribute('href'));
                if (!target) return;
                event.preventDefault();
                target.scrollIntoView({behavior: 'smooth', block: 'start'});
            });
        });

        applyFilters();
    });
})();
