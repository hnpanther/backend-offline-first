(function () {
    'use strict';

    var initialized = new WeakSet();

    /** Parses hidden field value: epoch millis or legacy yyyy-MM-ddTHH:mm in Asia/Tehran. */
    function parseInitialValue(raw) {
        if (!raw || !String(raw).trim()) {
            return null;
        }
        raw = String(raw).trim();
        if (/^\d+$/.test(raw)) {
            var ms = parseInt(raw, 10);
            return isNaN(ms) ? null : new Date(ms);
        }
        if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(raw)) {
            return new Date(raw + ':00+03:30');
        }
        return null;
    }

    function syncHidden(picker, hidden) {
        var selected = picker.getSelectedDate();
        hidden.value = selected ? String(selected.getTime()) : '';
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
