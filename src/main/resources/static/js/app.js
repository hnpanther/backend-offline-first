(function () {
    'use strict';

    function preserveSidebarScroll(sidebar) {
        if (!sidebar) return;
        var key = 'sidebarScrollTop';
        var saved = sessionStorage.getItem(key);
        if (saved) {
            sidebar.scrollTop = parseInt(saved, 10);
        }
        sidebar.querySelectorAll('a.nav-link').forEach(function (link) {
            link.addEventListener('click', function () {
                sessionStorage.setItem(key, sidebar.scrollTop);
            });
        });
    }

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
        preserveSidebarScroll(document.querySelector('.app-sidebar'));
        var offcanvasBody = document.querySelector('#mobileSidebar .offcanvas-body');
        if (offcanvasBody) {
            preserveSidebarScroll(offcanvasBody);
        }
    });
})();
