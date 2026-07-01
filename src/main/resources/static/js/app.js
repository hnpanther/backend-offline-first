(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        var toggle = document.getElementById('sidebarToggle');
        if (toggle) {
            toggle.addEventListener('click', function () {
                var offcanvas = document.getElementById('mobileSidebar');
                if (offcanvas && window.bootstrap) {
                    bootstrap.Offcanvas.getOrCreateInstance(offcanvas).toggle();
                }
            });
        }
    });
})();
