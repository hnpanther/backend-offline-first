/* Local panel feedback; the service independently enforces the same inclusive limits. */
(function () {
    'use strict';

    function limit(field, key) {
        var raw = field.dataset[key];
        return raw == null || raw === '' ? null : Number(raw);
    }

    function validate(field, now) {
        var input = field.querySelector('.persian-datetime-value');
        var message = '';
        if (input && input.value) {
            // The shared picker posts Tehran wall time, independent of the browser timezone.
            var epoch = Date.parse(input.value + '+03:30');
            var min = limit(field, 'min');
            var max = limit(field, 'max');
            var before = limit(field, 'before');
            var after = limit(field, 'after');
            if (!Number.isFinite(epoch)) message = 'تاریخ و ساعت معتبر وارد کنید.';
            else if ((min != null && epoch < min) || (max != null && epoch > max) ||
                (before != null && epoch < now - before * 60000) ||
                (after != null && epoch > now + after * 60000)) {
                message = 'تاریخ و ساعت باید داخل بازهٔ تعیین‌شده باشد.';
            }
        }
        field.querySelector('.datetime-bounds-feedback').textContent = message;
        return !message;
    }

    document.addEventListener('change', function (event) {
        var field = event.target.closest('.datetime-bounds');
        if (field) validate(field, Date.now());
    });
    document.addEventListener('submit', function (event) {
        var now = Date.now();
        var valid = true;
        event.target.querySelectorAll('.datetime-bounds').forEach(function (field) {
            if (!validate(field, now)) valid = false;
        });
        if (!valid) event.preventDefault();
    });
})();
