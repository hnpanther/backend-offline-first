/*
 * Search and filters for the voided-submission page.
 *
 * Client-side deliberately: the page already holds one sheet's whole payload, which is bounded,
 * so a request per keystroke would add latency and buy nothing. No dependency — this runs on
 * plant networks with no route to a CDN.
 *
 * Two axes, and they compose, exactly as they do on the sheet's own detail page:
 *
 *   assets     همه / دارای داده / بدون داده        which asset cards are listed
 *   parameters همه پارامترها / فقط دارای مقدار     which rows are shown inside each of them
 *
 * They are deliberately the same words, the same markup and the same styling as
 * `log-sheet-detail.js` uses. This is the page where a supervisor compares a refused payload
 * against the sheet it was refused from, and it behaving differently from that sheet is the one
 * thing that makes the comparison harder than it needs to be.
 */
(function () {
    'use strict';

    function init() {
        var bar = document.getElementById('voidFilterBar');
        if (!bar) return;

        var search = document.getElementById('voidSearch');
        var count = document.getElementById('voidFilterCount');
        var empty = document.getElementById('voidNoMatches');
        var list = document.getElementById('voidAssetList');
        var cards = Array.prototype.slice.call(document.querySelectorAll('.void-asset-card'));

        // The searchable text of each card is computed once, not per keystroke: it includes the
        // whole parameter table, so re-reading textContent on every input would be the one thing
        // that could make a large sheet feel slow.
        var haystacks = cards.map(function (card) {
            var attrs = card.getAttribute('data-search') || '';
            return (attrs + ' ' + card.textContent).toLowerCase();
        });

        var withData = cards.filter(function (card) {
            return card.getAttribute('data-has-data') === 'true';
        }).length;

        var assetMode = 'all';

        function apply() {
            var term = (search.value || '').trim().toLowerCase();
            var shown = 0;

            cards.forEach(function (card, i) {
                var hasData = card.getAttribute('data-has-data') === 'true';
                var passesData =
                    assetMode === 'all' ||
                    (assetMode === 'with' && hasData) ||
                    (assetMode === 'without' && !hasData);
                var passesTerm = term === '' || haystacks[i].indexOf(term) !== -1;
                var visible = passesData && passesTerm;

                card.classList.toggle('d-none', !visible);
                if (visible) shown++;
            });

            // Unfiltered, this says the SHAPE of the submission rather than just its size —
            // usually "most of these assets are empty", which is the thing worth knowing before
            // scrolling. The server renders the same sentence so it is not blank until this runs;
            // reporting only the total here would have overwritten that on load.
            count.textContent = shown === cards.length
                ? cards.length + ' دارایی · ' + withData + ' دارای داده'
                : shown + ' از ' + cards.length + ' دارایی';
            empty.classList.toggle('d-none', shown !== 0);
        }

        function press(selector, attribute, value) {
            document.querySelectorAll(selector).forEach(function (button) {
                var active = button.getAttribute(attribute) === value;
                button.classList.toggle('active', active);
                button.setAttribute('aria-pressed', String(active));
            });
        }

        document.querySelectorAll('[data-void-asset-filter]').forEach(function (button) {
            button.addEventListener('click', function () {
                assetMode = button.getAttribute('data-void-asset-filter');
                press('[data-void-asset-filter]', 'data-void-asset-filter', assetMode);
                apply();
            });
        });

        /*
         * The parameters axis is a class on the list and a CSS rule — not a per-row rewrite.
         * Every row is already in the DOM tagged `data-param-state`, and the asset filter and the
         * search box re-run `apply()` constantly over the same elements: anything hiding rows
         * imperatively here would fight them for control of `display`.
         */
        document.querySelectorAll('[data-void-param-filter]').forEach(function (button) {
            button.addEventListener('click', function () {
                var mode = button.getAttribute('data-void-param-filter');
                if (list) list.classList.toggle('hide-empty-params', mode === 'filled');
                press('[data-void-param-filter]', 'data-void-param-filter', mode);
            });
        });

        search.addEventListener('input', apply);
        apply();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
