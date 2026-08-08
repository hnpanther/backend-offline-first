/*
 * Event-kind filter for the asset history timeline.
 *
 * Client-side deliberately: the page already holds the whole timeline, which is bounded by the
 * controller's status cap, so a round trip per click would add latency and buy nothing. No
 * dependency — this runs on plant networks with no route to a CDN.
 */
(function () {
    'use strict';

    function init() {
        var group = document.getElementById('assetHistoryFilter');
        var timeline = document.getElementById('assetHistoryTimeline');
        if (!group || !timeline) return;

        var buttons = Array.prototype.slice.call(group.querySelectorAll('button[data-kind]'));
        var items = Array.prototype.slice.call(timeline.querySelectorAll('.asset-history-item'));
        var empty = document.getElementById('assetHistoryNoMatch');

        function apply(kind) {
            var shown = 0;
            items.forEach(function (item) {
                var visible = kind === 'all' || item.getAttribute('data-kind') === kind;
                item.classList.toggle('d-none', !visible);
                if (visible) shown++;
            });
            // The list itself is hidden when empty so its connecting line does not hang in
            // mid-air above the "nothing here" message.
            timeline.classList.toggle('d-none', shown === 0);
            if (empty) empty.classList.toggle('d-none', shown !== 0);

            buttons.forEach(function (b) {
                b.classList.toggle('active', b.getAttribute('data-kind') === kind);
            });
        }

        buttons.forEach(function (b) {
            b.addEventListener('click', function () {
                apply(b.getAttribute('data-kind'));
            });
        });

        apply('all');
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
