/*
 * Search and data-presence filter for the voided-submission page.
 *
 * Client-side deliberately: the page already holds one sheet's whole payload, which is bounded,
 * so a request per keystroke would add latency and buy nothing. No dependency — this runs on
 * plant networks with no route to a CDN.
 */
(function () {
    'use strict';

    function init() {
        var bar = document.getElementById('voidFilterBar');
        if (!bar) return;

        var search = document.getElementById('voidSearch');
        var dataFilter = document.getElementById('voidDataFilter');
        var count = document.getElementById('voidFilterCount');
        var empty = document.getElementById('voidNoMatches');
        var cards = Array.prototype.slice.call(document.querySelectorAll('.void-asset-card'));

        // The searchable text of each card is computed once, not per keystroke: it includes the
        // whole parameter table, so re-reading textContent on every input would be the one thing
        // that could make a large sheet feel slow.
        var haystacks = cards.map(function (card) {
            var attrs = card.getAttribute('data-search') || '';
            return (attrs + ' ' + card.textContent).toLowerCase();
        });

        function apply() {
            var term = (search.value || '').trim().toLowerCase();
            var mode = dataFilter.value;
            var shown = 0;

            cards.forEach(function (card, i) {
                var hasData = card.getAttribute('data-has-data') === 'true';
                var passesData =
                    mode === 'all' ||
                    (mode === 'with' && hasData) ||
                    (mode === 'without' && !hasData);
                var passesTerm = term === '' || haystacks[i].indexOf(term) !== -1;
                var visible = passesData && passesTerm;

                card.classList.toggle('d-none', !visible);
                if (visible) shown++;
            });

            count.textContent = shown === cards.length
                ? cards.length + ' دارایی'
                : shown + ' از ' + cards.length + ' دارایی';
            empty.classList.toggle('d-none', shown !== 0);
        }

        search.addEventListener('input', apply);
        dataFilter.addEventListener('change', apply);
        apply();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
