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

    function setupDesktopSidebar() {
        var body = document.body;
        var toggle = document.getElementById('desktopSidebarToggle');
        var storageKey = 'desktopSidebarCollapsed';

        if (!toggle) return;

        function applyState(collapsed) {
            body.classList.toggle('sidebar-collapsed', collapsed);
            toggle.setAttribute('aria-expanded', String(!collapsed));
            toggle.setAttribute('title', collapsed ? 'باز کردن منوی کناری' : 'جمع کردن منوی کناری');
        }

        try {
            applyState(localStorage.getItem(storageKey) === 'true');
        } catch (ignored) {
            applyState(false);
        }

        toggle.addEventListener('click', function () {
            var collapsed = !body.classList.contains('sidebar-collapsed');
            applyState(collapsed);
            try {
                localStorage.setItem(storageKey, String(collapsed));
            } catch (ignored) {
                // The sidebar still works when browser storage is unavailable.
            }
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
        setupDesktopSidebar();
    });
})();
