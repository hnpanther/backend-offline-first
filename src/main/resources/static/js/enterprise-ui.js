(function () {
    'use strict';

    var dirtyForms = new Set();

    function safeWrite(key, value) {
        try {
            localStorage.setItem(key, JSON.stringify(value));
        } catch (ignored) {
            // UI preferences are optional when browser storage is unavailable.
        }
    }

    function normalizedText(element) {
        return (element && element.textContent ? element.textContent : '').replace(/\s+/g, ' ').trim();
    }

    function createElement(tag, className, html) {
        var element = document.createElement(tag);
        if (className) element.className = className;
        if (html !== undefined) element.innerHTML = html;
        return element;
    }

    function tableStorageKey(table, index) {
        return 'enterpriseTable:' + window.location.pathname + ':' + (table.id || table.dataset.enterpriseTableKey || index);
    }

    function tableHeaders(table) {
        if (!table.tHead || !table.tHead.rows.length) return [];
        return Array.prototype.slice.call(table.tHead.rows[table.tHead.rows.length - 1].cells);
    }

    function setColumnVisible(table, columnIndex, visible) {
        Array.prototype.forEach.call(table.rows, function (row) {
            if (row.cells.length > columnIndex && !row.cells[0].hasAttribute('colspan')) {
                row.cells[columnIndex].classList.toggle('enterprise-column-hidden', !visible);
            }
        });
    }

    function createTableTools(table, headers, index, viewport) {
        if (headers.length < 5) return;

        var key = tableStorageKey(table, index);
        var preferences = window.EnterprisePreferences ? window.EnterprisePreferences.read(key) : {density: 'normal', hidden: []};
        var tools = createElement('div', 'enterprise-table-tools');
        var rowCount = 0;
        Array.prototype.forEach.call(table.tBodies, function (body) {
            Array.prototype.forEach.call(body.rows, function (row) {
                if (!row.classList.contains('enterprise-empty-row')) rowCount += 1;
            });
        });

        var summary = createElement('span', 'enterprise-table-summary', '<i class="bi bi-view-list"></i><span>' + rowCount + ' ردیف در این صفحه</span>');
        var actions = createElement('div', 'enterprise-table-actions');

        var densityLabel = createElement('label', 'enterprise-density-control');
        densityLabel.innerHTML = '<i class="bi bi-distribute-vertical"></i><span>تراکم</span>';
        var density = createElement('select', 'form-select form-select-sm');
        density.setAttribute('aria-label', 'تراکم نمایش جدول');
        [['compact', 'فشرده'], ['normal', 'عادی'], ['comfortable', 'راحت']].forEach(function (optionData) {
            var option = document.createElement('option');
            option.value = optionData[0];
            option.textContent = optionData[1];
            density.appendChild(option);
        });
        density.value = preferences.density || 'normal';
        table.dataset.density = density.value;
        density.addEventListener('change', function () {
            table.dataset.density = density.value;
            preferences.density = density.value;
            safeWrite(key, preferences);
        });
        densityLabel.appendChild(density);

        var columns = createElement('details', 'enterprise-column-picker');
        columns.appendChild(createElement('summary', 'btn btn-sm btn-outline-secondary', '<i class="bi bi-layout-three-columns me-1"></i>ستون‌ها'));
        var panel = createElement('div', 'enterprise-column-panel');
        panel.appendChild(createElement('div', 'enterprise-column-title', 'ستون‌های قابل نمایش'));

        headers.forEach(function (header, columnIndex) {
            var title = normalizedText(header);
            if (!title) return;
            var item = createElement('label', 'enterprise-column-option');
            var checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.className = 'form-check-input';
            checkbox.checked = preferences.hidden.indexOf(columnIndex) === -1;
            checkbox.dataset.columnIndex = String(columnIndex);
            item.appendChild(checkbox);
            item.appendChild(document.createTextNode(title));
            panel.appendChild(item);
            setColumnVisible(table, columnIndex, checkbox.checked);

            checkbox.addEventListener('change', function () {
                var checked = panel.querySelectorAll('input:checked');
                if (checked.length === 0) {
                    checkbox.checked = true;
                    return;
                }
                setColumnVisible(table, columnIndex, checkbox.checked);
                preferences.hidden = Array.prototype.map.call(panel.querySelectorAll('input:not(:checked)'), function (input) {
                    return Number(input.dataset.columnIndex);
                });
                safeWrite(key, preferences);
            });
        });
        columns.appendChild(panel);
        columns.addEventListener('toggle', function () {
            var card = columns.closest('.card');
            if (card) card.classList.toggle('enterprise-column-picker-open', columns.open);
        });

        actions.appendChild(densityLabel);
        actions.appendChild(columns);

        // Prefer the bar the template already reserved. Inserting one here happens after the
        // first paint, so it pushes everything below it down by its own height — measured at
        // 51px, which was most of the page's late settle. A reserved slot is already in the
        // layout, so filling it moves nothing.
        //
        // The fallback is not dead code: a slot is only rendered where the column count is
        // knowable from the template, and a table whose columns depend on th:if or
        // sec:authorize could otherwise be left with an empty bar and no toolbar to put in it.
        var slot = viewport.previousElementSibling;
        if (slot && slot.matches('.enterprise-table-tools[data-enterprise-tools-slot]')) {
            slot.appendChild(summary);
            slot.appendChild(actions);
        } else {
            tools.appendChild(summary);
            tools.appendChild(actions);
            viewport.parentNode.insertBefore(tools, viewport);
        }
    }

    function enhanceTables() {
        var tables = document.querySelectorAll('#pageContent table.table');
        var page = document.getElementById('pageContent');
        if (tables.length && page) page.classList.add('enterprise-list-page');
        Array.prototype.forEach.call(tables, function (table, index) {
            if (table.dataset.enterpriseEnhanced === 'true') return;
            if (table.dataset.enterpriseLive === 'true') return;
            table.classList.add('enterprise-data-table');

            var dataCard = table.closest('.card');
            if (dataCard) dataCard.classList.add('enterprise-data-card');

            var viewport = table.closest('.table-responsive, .table-responsive-modern, .enterprise-table-viewport');
            if (!viewport) {
                viewport = createElement('div', 'enterprise-table-viewport');
                table.parentNode.insertBefore(viewport, table);
                viewport.appendChild(table);
            }
            viewport.classList.add('enterprise-table-viewport');

            var headers = tableHeaders(table);

            var bodyRows = table.tBodies.length ? table.tBodies[0].rows.length : 0;
            if (bodyRows > 12) viewport.classList.add('enterprise-table-viewport--limited');
            if (table.scrollWidth > viewport.clientWidth) viewport.classList.add('is-scrollable');

            createTableTools(table, headers, index, viewport);
            table.dataset.enterpriseEnhanced = 'true';

            viewport.addEventListener('mouseover', function (event) {
                var cell = event.target.closest('td, th');
                if (!cell || cell.hasAttribute('title')) return;
                if (cell.scrollWidth > cell.clientWidth) cell.setAttribute('title', normalizedText(cell));
            });
        });
    }

    function isActionContainer(element) {
        if (!element || element.nodeType !== 1) return false;
        if (element.matches('.enterprise-page-actions, .btn, .btn-group, form, nav, p, .text-muted, small')) return false;
        return !!element.querySelector('.btn, button, a.btn, form');
    }

    function createBreadcrumb() {
        var page = document.getElementById('pageContent');
        if (!page || window.location.pathname === '/' || page.querySelector('.breadcrumb, .enterprise-breadcrumb')) return;
        var heading = page.querySelector('h1, h2, h3, h4');
        var title = normalizedText(heading) || document.title;
        if (!title) return;

        var breadcrumb = createElement('nav', 'enterprise-breadcrumb');
        breadcrumb.setAttribute('aria-label', 'مسیر صفحه');
        var list = createElement('ol');
        var root = createElement('li');
        var dashboardLink = document.querySelector('.app-sidebar a[href="/"]');
        if (dashboardLink) {
            var link = createElement('a', '', '<i class="bi bi-house-door"></i><span>خانه</span>');
            link.href = '/';
            root.appendChild(link);
        } else {
            root.textContent = 'خانه';
        }
        var current = createElement('li', 'active');
        current.setAttribute('aria-current', 'page');
        current.textContent = title;
        list.appendChild(root);
        list.appendChild(current);
        breadcrumb.appendChild(list);
        page.insertBefore(breadcrumb, page.firstChild);
    }

    function enhancePageHeader() {
        var page = document.getElementById('pageContent');
        if (!page) return;
        var heading = page.querySelector('h1, h2, h3, h4');
        if (!heading) return;
        heading.classList.add('page-title');
        var container = heading.parentElement;
        if (container && container.parentElement === page && container !== page) {
            container.classList.add('page-header', 'enterprise-page-header');
            Array.prototype.forEach.call(container.children, function (child) {
                if (child !== heading && isActionContainer(child)) {
                    child.classList.add('enterprise-page-actions');
                }
            });
        }
    }

    function enhanceFilterBars() {
        var forms = document.querySelectorAll('#pageContent form[method="get"], #pageContent form:not([method])');
        Array.prototype.forEach.call(forms, function (form) {
            if (!form.querySelector('input, select')) return;
            form.classList.add('enterprise-filter-bar');
            var clearLink = Array.prototype.find.call(form.querySelectorAll('a'), function (link) {
                return /پاک/.test(normalizedText(link));
            });
            if (clearLink && !clearLink.querySelector('i')) {
                clearLink.insertAdjacentHTML('afterbegin', '<i class="bi bi-x-lg me-1"></i>');
            }
        });
    }

    function enhanceIconButtons() {
        var labels = {
            'bi-pencil': 'ویرایش',
            'bi-trash': 'حذف',
            'bi-eye': 'مشاهده جزئیات',
            'bi-graph-up-arrow': 'مشاهده گزارش',
            'bi-download': 'دریافت فایل',
            'bi-x-lg': 'بستن',
            'bi-three-dots': 'گزینه‌های بیشتر'
        };

        document.querySelectorAll('#pageContent .btn').forEach(function (button) {
            if (normalizedText(button) || button.hasAttribute('aria-label')) return;
            var icon = button.querySelector('i[class*="bi-"]');
            if (!icon) return;
            var label = null;
            Object.keys(labels).some(function (iconClass) {
                if (!icon.classList.contains(iconClass)) return false;
                label = labels[iconClass];
                return true;
            });
            if (!label) return;
            if (!button.hasAttribute('title')) button.setAttribute('title', label);
            button.setAttribute('aria-label', label);
        });
    }

    /*
     * Flash messages — behaviour only.
     *
     * The cards are rendered by `fragments/layout.html` in their final form, already inside
     * `.enterprise-toast-stack`. Nothing here creates or moves an element, and that is the
     * point: the previous version built the toast in JS from a plain `.alert` inside <main>,
     * so the browser painted the old inline alert first and the toast replaced it a moment
     * later — the same message shown twice, in two places, in two styles. No script can avoid
     * that, because a script runs after the first paint by definition. The markup had to change.
     *
     * AUTO-DISMISS IS FOR SUCCESS ONLY, marked by `data-toast-autodismiss` on the server.
     * "Saved" confirms something the user just did and already expected; making them click it
     * away is busywork. An error or a warning is news — often the only place a refused save
     * explains itself — and a message that removes itself before it has been read has failed at
     * the one job it had. The import-error list is a report, so it is not marked either.
     */
    var TOAST_LIFE_MS = 4200;

    function dismissToast(toast) {
        if (toast.dataset.leaving === 'true') return;
        toast.dataset.leaving = 'true';
        toast.classList.add('enterprise-toast--leaving');
        // Remove on the animation's own event rather than a matching setTimeout, so the two
        // cannot drift apart — with a fallback, because `animationend` never fires when the
        // animation is disabled by prefers-reduced-motion.
        var done = false;
        var remove = function () {
            if (done) return;
            done = true;
            if (toast.parentNode) toast.parentNode.removeChild(toast);
        };
        toast.addEventListener('animationend', remove, {once: true});
        window.setTimeout(remove, 400);
    }

    function enhanceAlerts() {
        var stack = document.querySelector('.enterprise-toast-stack');
        if (!stack) return;

        Array.prototype.forEach.call(stack.querySelectorAll('.enterprise-toast'), function (toast) {
            var close = toast.querySelector('.btn-close');
            if (close) {
                // Take the click from Bootstrap so the leaving animation runs. Without JS the
                // element keeps `data-bs-dismiss`, so the button still works — just abruptly.
                close.removeAttribute('data-bs-dismiss');
                close.addEventListener('click', function () { dismissToast(toast); });
            }

            if (toast.dataset.toastAutodismiss !== 'true') return;

            toast.style.setProperty('--enterprise-toast-life', TOAST_LIFE_MS + 'ms');
            var deadline = window.setTimeout(function () { dismissToast(toast); }, TOAST_LIFE_MS);
            var pause = function () {
                window.clearTimeout(deadline);
                toast.dataset.paused = 'true';
            };
            // Reading a message should not be a race. Pointer or keyboard focus stops the clock;
            // leaving restarts it with a full life rather than the remainder, because the reason
            // it was paused is that it had not been read yet.
            var resume = function () {
                toast.dataset.paused = 'false';
                deadline = window.setTimeout(function () { dismissToast(toast); }, TOAST_LIFE_MS);
            };
            toast.addEventListener('mouseenter', pause);
            toast.addEventListener('mouseleave', resume);
            toast.addEventListener('focusin', pause);
            toast.addEventListener('focusout', resume);
        });

        document.addEventListener('keydown', function (event) {
            if (event.key !== 'Escape') return;
            var open = stack.querySelectorAll('.enterprise-toast:not([data-leaving="true"])');
            if (open.length) dismissToast(open[open.length - 1]);
        });
    }

    function enhanceForms() {
        var requiredFields = document.querySelectorAll('#pageContent input[required], #pageContent select[required], #pageContent textarea[required]');
        Array.prototype.forEach.call(requiredFields, function (field) {
            var group = field.closest('[class*="col-"], .mb-3, .mb-4, .form-group');
            var label = group ? group.querySelector('.form-label') : null;
            if (label && label.textContent.indexOf('*') === -1 && !label.querySelector('.text-danger')) {
                label.classList.add('enterprise-required-label');
            }
        });

        var forms = document.querySelectorAll('#pageContent form');
        Array.prototype.forEach.call(forms, function (form) {
            if ((form.method || '').toLowerCase() === 'get' || form.closest('.modal') || form.classList.contains('bulk-delete-form')) return;
            var fields = form.querySelectorAll('input:not([type="hidden"]), select, textarea');
            if (fields.length < 4) return;

            form.classList.add('enterprise-long-form');
            Array.prototype.forEach.call(fields, function (field) {
                field.addEventListener('input', function () {
                    dirtyForms.add(form);
                }, {once: true});
                field.addEventListener('change', function () {
                    dirtyForms.add(form);
                }, {once: true});
            });
            form.addEventListener('submit', function () {
                dirtyForms.delete(form);
                form.classList.add('is-submitting');
            });

            var submit = form.querySelector('button[type="submit"], input[type="submit"]');
            if (submit && submit.parentElement && submit.parentElement !== form) {
                submit.parentElement.classList.add('enterprise-form-actions');
            }
        });

        window.addEventListener('beforeunload', function (event) {
            if (!dirtyForms.size) return;
            event.preventDefault();
            event.returnValue = '';
        });
    }

    document.addEventListener('click', function (event) {
        document.querySelectorAll('.enterprise-column-picker[open]').forEach(function (picker) {
            if (!picker.contains(event.target)) picker.removeAttribute('open');
        });
    });

    document.addEventListener('DOMContentLoaded', function () {
        createBreadcrumb();
        enhancePageHeader();
        enhanceFilterBars();
        enhanceTables();
        enhanceForms();
        enhanceIconButtons();
        enhanceAlerts();
    });
})();
