(function () {
    'use strict';

    // Runs synchronously in <head>. No body, API requests, observers or business state.
    function read(key) {
        var raw;
        try { raw = JSON.parse(localStorage.getItem(key)); } catch (ignored) { /* Optional storage. */ }
        if (!raw || typeof raw !== 'object') raw = {};
        return {
            density: ['compact', 'normal', 'comfortable'].indexOf(raw.density) >= 0 ? raw.density : 'normal',
            hidden: Array.isArray(raw.hidden) ? raw.hidden.filter(function (value, index, values) {
                return Number.isInteger(value) && value >= 0 && value < 100 && values.indexOf(value) === index;
            }) : []
        };
    }
    window.EnterprisePreferences = {read: read};

    try {
        document.documentElement.classList.toggle('sidebar-collapsed', localStorage.getItem('desktopSidebarCollapsed') === 'true');
        var prefix = 'enterpriseTable:' + window.location.pathname + ':';
        var rules = [];
        for (var i = 0; i < localStorage.length; i++) {
            var key = localStorage.key(i);
            if (!key || key.indexOf(prefix) !== 0) continue;
            var tableKey = key.slice(prefix.length);
            // Template-owned identifiers only; storage must never be able to inject CSS.
            if (!/^[a-zA-Z0-9_-]+$/.test(tableKey)) continue;
            var preference = read(key);
            var table = ':where(#pageContent) table.enterprise-data-table[data-enterprise-table-key="' + tableKey + '"]:not([data-enterprise-enhanced])';
            if (preference.density === 'compact') rules.push(table + ' tbody td{height:37px;padding-top:.3rem;padding-bottom:.3rem;font-size:.78rem}');
            if (preference.density === 'comfortable') rules.push(table + ' tbody td{height:57px;padding-top:.85rem;padding-bottom:.85rem}');
            preference.hidden.forEach(function (column) {
                rules.push(table + ' tr:not(:has(> :first-child[colspan])) > :nth-child(' + (column + 1) + '){display:none!important}');
            });
        }
        if (rules.length) {
            var style = document.createElement('style');
            style.id = 'enterprise-initial-preferences';
            style.textContent = rules.join('\n');
            document.head.appendChild(style);
        }
    } catch (ignored) {
        // Blocked storage still yields a fully styled, usable page at the default density.
    }
})();
