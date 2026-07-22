(function () {
    'use strict';

    var EMPTY_PATTERN = /(?:هیچ|موجود نیست|ثبت نشده|یافت نشد|داده‌ای موجود نیست|هنوز .* ثبت نشده)/;
    var dirtyForms = new Set();

    function safeRead(key, fallback) {
        try {
            var value = localStorage.getItem(key);
            return value === null ? fallback : JSON.parse(value);
        } catch (ignored) {
            return fallback;
        }
    }

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
        return 'enterpriseTable:' + window.location.pathname + ':' + (table.id || index);
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

    function markOperationColumn(table, headers) {
        headers.forEach(function (header, index) {
            var label = normalizedText(header);
            if (!/(عملیات|جزئیات|اقدامات)/.test(label)) return;

            header.classList.add('enterprise-operation-column');
            Array.prototype.forEach.call(table.tBodies, function (body) {
                Array.prototype.forEach.call(body.rows, function (row) {
                    if (row.cells.length > index && !row.cells[0].hasAttribute('colspan')) {
                        row.cells[index].classList.add('enterprise-operation-column');
                    }
                });
            });
        });
    }

    function markTechnicalColumns(table, headers) {
        headers.forEach(function (header, index) {
            if (!/(کد|NFC|شناسه|نام کاربری|مسیر|Endpoint)/i.test(normalizedText(header))) return;
            Array.prototype.forEach.call(table.tBodies, function (body) {
                Array.prototype.forEach.call(body.rows, function (row) {
                    if (row.cells.length > index && !row.cells[0].hasAttribute('colspan')) {
                        row.cells[index].setAttribute('dir', 'auto');
                        row.cells[index].classList.add('enterprise-technical-cell');
                    }
                });
            });
        });
    }

    function decorateEmptyState(table) {
        if (table.dataset.enterpriseLive === 'true') return;
        Array.prototype.forEach.call(table.tBodies, function (body) {
            Array.prototype.forEach.call(body.rows, function (row) {
                if (row.classList.contains('enterprise-empty-row')) return;
                if (row.cells.length !== 1 || !row.cells[0].hasAttribute('colspan')) return;
                var cell = row.cells[0];
                var message = normalizedText(cell);
                if (!EMPTY_PATTERN.test(message)) return;

                row.classList.add('enterprise-empty-row');
                cell.classList.add('enterprise-empty-cell');
                cell.textContent = '';

                var state = createElement('div', 'enterprise-empty-state');
                state.appendChild(createElement('span', 'enterprise-empty-icon', '<i class="bi bi-inbox"></i>'));
                var copy = createElement('div', 'enterprise-empty-copy');
                copy.appendChild(createElement('strong', '', message));
                copy.appendChild(createElement('small', '', 'با تغییر فیلترها یا ثبت اطلاعات جدید، نتایج در این بخش نمایش داده می‌شوند.'));
                state.appendChild(copy);

                if (window.location.search) {
                    var reset = createElement('a', 'btn btn-sm btn-outline-secondary', '<i class="bi bi-arrow-counterclockwise me-1"></i>پاک‌کردن فیلترها');
                    reset.href = window.location.pathname;
                    state.appendChild(reset);
                }
                cell.appendChild(state);
            });
        });
    }

    function markTruncatableCells(table) {
        Array.prototype.forEach.call(table.tBodies, function (body) {
            Array.prototype.forEach.call(body.rows, function (row) {
                Array.prototype.forEach.call(row.cells, function (cell) {
                    if (cell.hasAttribute('colspan') || cell.querySelector('button, a, form, input, select')) return;
                    if (normalizedText(cell).length > 44) cell.classList.add('enterprise-truncatable');
                });
            });
        });
    }

    function createTableTools(table, headers, index, viewport) {
        if (headers.length < 5) return;

        var key = tableStorageKey(table, index);
        var preferences = safeRead(key, {density: 'normal', hidden: []});
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
        tools.appendChild(summary);
        tools.appendChild(actions);
        viewport.parentNode.insertBefore(tools, viewport);
    }

    function enhanceTables() {
        var tables = document.querySelectorAll('#pageContent table.table');
        Array.prototype.forEach.call(tables, function (table, index) {
            if (table.dataset.enterpriseEnhanced === 'true') return;
            table.dataset.enterpriseEnhanced = 'true';
            if (table.dataset.enterpriseLive === 'true') return;
            table.classList.add('enterprise-data-table');

            var viewport = table.closest('.table-responsive, .table-responsive-modern, .enterprise-table-viewport');
            if (!viewport) {
                viewport = createElement('div', 'enterprise-table-viewport');
                table.parentNode.insertBefore(viewport, table);
                viewport.appendChild(table);
            }
            viewport.classList.add('enterprise-table-viewport');

            var headers = tableHeaders(table);
            markOperationColumn(table, headers);
            markTechnicalColumns(table, headers);
            decorateEmptyState(table);
            markTruncatableCells(table);

            var bodyRows = table.tBodies.length ? table.tBodies[0].rows.length : 0;
            if (bodyRows > 12) viewport.classList.add('enterprise-table-viewport--limited');
            if (table.scrollWidth > viewport.clientWidth) viewport.classList.add('is-scrollable');

            createTableTools(table, headers, index, viewport);

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
            var link = createElement('a', '', '<i class="bi bi-house-door"></i><span>سامانه</span>');
            link.href = '/';
            root.appendChild(link);
        } else {
            root.textContent = 'سامانه';
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

    function enhanceAlerts() {
        document.querySelectorAll('.app-main > .alert-success').forEach(function (alert) {
            alert.classList.add('enterprise-toast');
            alert.setAttribute('role', 'status');
            alert.setAttribute('aria-live', 'polite');
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

    function badgeState(text) {
        if (/غیرفعال/.test(text)) return 'neutral';
        if (/(فعال|تأیید|تکمیل|ارسال‌شده|تمام شد|ذخیره شده|موفق)/.test(text)) return 'success';
        if (/(پیش‌نویس|در انتظار|در حال|آماده|زمان‌بندی)/.test(text)) return 'warning';
        if (/(خطا|ناموفق|منقضی|لغو|متوقف)/.test(text)) return 'danger';
        if (/(انتساب|دستی|سفارشی)/.test(text)) return 'info';
        return null;
    }

    function decorateBadge(badge) {
        var state = badgeState(normalizedText(badge));
        if (!state) {
            delete badge.dataset.enterpriseBadge;
            delete badge.dataset.state;
            return;
        }
        badge.dataset.enterpriseBadge = 'true';
        badge.dataset.state = state;
    }

    function decorateBadges(root) {
        if (!root) return;
        if (root.matches && root.matches('.badge')) decorateBadge(root);
        var badges = root.querySelectorAll ? root.querySelectorAll('.badge') : [];
        Array.prototype.forEach.call(badges, decorateBadge);
    }

    function observeDynamicBadges() {
        if (!window.MutationObserver) return;
        var page = document.getElementById('pageContent');
        if (!page) return;
        var pending = false;
        var observer = new MutationObserver(function (mutations) {
            var nodes = [];
            mutations.forEach(function (mutation) {
                Array.prototype.forEach.call(mutation.addedNodes, function (node) {
                    if (node.nodeType === 1) nodes.push(node);
                });
            });
            if (!nodes.length || pending) return;
            pending = true;
            window.requestAnimationFrame(function () {
                pending = false;
                nodes.forEach(function (node) {
                    decorateBadges(node);
                    if (node.matches && node.matches('table.enterprise-data-table:not([data-enterprise-live="true"])')) {
                        decorateEmptyState(node);
                    }
                    if (node.querySelectorAll) {
                        node.querySelectorAll('table.enterprise-data-table:not([data-enterprise-live="true"])').forEach(decorateEmptyState);
                    }
                });
            });
        });
        observer.observe(page, {childList: true, subtree: true});
        window.addEventListener('pagehide', function (event) {
            if (event.persisted) return;
            observer.disconnect();
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
        decorateBadges(document);
        observeDynamicBadges();
    });
})();
