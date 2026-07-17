(function () {
    'use strict';

    var initialized = new WeakSet();

    function pad2(value) {
        return String(value).padStart(2, '0');
    }

    function formatLocalDateTime(date) {
        return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate()) +
            'T' + pad2(date.getHours()) + ':' + pad2(date.getMinutes());
    }

    function parseLocalDateTime(value) {
        var match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value);
        if (!match) {
            return null;
        }
        return new Date(
            parseInt(match[1], 10),
            parseInt(match[2], 10) - 1,
            parseInt(match[3], 10),
            parseInt(match[4], 10),
            parseInt(match[5], 10),
            0,
            0
        );
    }

    function tehranWallDateFromEpoch(epochMs) {
        var parts = new Intl.DateTimeFormat('en-US', {
            timeZone: 'Asia/Tehran',
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            hourCycle: 'h23'
        }).formatToParts(new Date(epochMs)).reduce(function (acc, part) {
            acc[part.type] = part.value;
            return acc;
        }, {});
        return new Date(
            parseInt(parts.year, 10),
            parseInt(parts.month, 10) - 1,
            parseInt(parts.day, 10),
            parseInt(parts.hour, 10),
            parseInt(parts.minute, 10),
            0,
            0
        );
    }

    /** Parses hidden field value: yyyy-MM-ddTHH:mm or legacy epoch millis. */
    function parseInitialValue(raw) {
        if (!raw || !String(raw).trim()) {
            return null;
        }
        raw = String(raw).trim();
        if (/^\d+$/.test(raw)) {
            var ms = parseInt(raw, 10);
            return isNaN(ms) ? null : tehranWallDateFromEpoch(ms);
        }
        return parseLocalDateTime(raw);
    }

    function syncHidden(picker, hidden) {
        var selected = picker.getSelectedDate();
        hidden.value = selected ? formatLocalDateTime(selected) : '';
    }

    function initContainer(container) {
        if (!container || initialized.has(container)) {
            return;
        }
        if (typeof mds === 'undefined' || !mds.MdsPersianDateTimePicker) {
            return;
        }

        var textInput = container.querySelector('.persian-datetime-text');
        var hidden = container.querySelector('.persian-datetime-value');
        if (!textInput || !hidden) {
            return;
        }

        var uid = 'pdtp-' + Math.random().toString(36).slice(2, 11);
        textInput.id = uid;

        var initial = parseInitialValue(hidden.value);
        var options = {
            targetTextSelector: '#' + uid,
            enableTimePicker: true,
            textFormat: 'yyyy/MM/dd HH:mm',
            persianNumber: true,
            placement: 'bottom'
        };
        if (initial) {
            options.selectedDate = initial;
            options.selectedDateToShow = initial;
        }

        var picker = new mds.MdsPersianDateTimePicker(textInput, options);
        if (initial) {
            syncHidden(picker, hidden);
        }

        function onPickerClose() {
            syncHidden(picker, hidden);
        }

        textInput.addEventListener('hidden.bs.popover', onPickerClose);

        var form = container.closest('form');
        if (form) {
            form.addEventListener('submit', function (evt) {
                syncHidden(picker, hidden);
                if (container.getAttribute('data-required') === 'true' && !hidden.value.trim()) {
                    evt.preventDefault();
                    textInput.classList.add('is-invalid');
                    textInput.focus();
                } else {
                    textInput.classList.remove('is-invalid');
                }
            });
        }

        initialized.add(container);
        container.setAttribute('data-pdtp-init', '1');
    }

    function initAll(root) {
        var scope = root || document;
        scope.querySelectorAll('.persian-datetime').forEach(function (el) {
            initContainer(el);
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        initAll(document);
        document.querySelectorAll('.modal').forEach(function (modal) {
            modal.addEventListener('shown.bs.modal', function () {
                initAll(modal);
            });
        });
    });

    window.initPersianDateTimePickers = initAll;
})();
