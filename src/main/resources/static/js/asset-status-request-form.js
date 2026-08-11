/*
 * Manual asset status change request: choose the asset first, then the statuses its class
 * actually allows.
 *
 * The order matters. A status only means something in the vocabulary of the asset's class, so
 * the second field cannot be filled in until the first is answered — and a class with no status
 * field has no request to make at all, which is refused here and again server-side.
 *
 * No dependency: this runs on plant networks with no route to a CDN.
 */
(function () {
    'use strict';

    function init() {
        var assetSelect = document.getElementById('requestAssetSelect');
        if (!assetSelect) return;

        var wrap = document.getElementById('requestStatusWrap');
        var select = document.getElementById('requestStatusSelect');
        var text = document.getElementById('requestStatusText');
        var hint = document.getElementById('requestStatusHint');
        var noField = document.getElementById('requestNoStatusField');
        var submit = document.getElementById('requestSubmitBtn');

        /**
         * Only one of the two status inputs may carry the `requestedStatus` name at a time —
         * a disabled control is not submitted, so this is what stops the browser sending the
         * empty one alongside the real answer.
         */
        function useControl(active) {
            select.hidden = active !== 'select';
            select.disabled = active !== 'select';
            text.hidden = active !== 'text';
            text.disabled = active !== 'text';
        }

        function reset() {
            wrap.hidden = true;
            noField.hidden = true;
            hint.textContent = '';
            select.innerHTML = '<option value="">انتخاب وضعیت</option>';
            text.value = '';
            useControl('none');
            submit.disabled = true;
        }

        function apply(data) {
            if (!data || !data.supported) {
                // No status field on the class: say so plainly and keep submission disabled.
                wrap.hidden = true;
                useControl('none');
                noField.hidden = false;
                submit.disabled = true;
                return;
            }

            noField.hidden = true;
            wrap.hidden = false;
            submit.disabled = false;

            var current = data.currentStatus || '';
            hint.textContent = current
                ? 'وضعیت فعلی: ' + current + ' — فیلد کلاس: ' + data.fieldKey
                : 'وضعیت فعلی ثبت نشده است — فیلد کلاس: ' + data.fieldKey;

            if (data.options && data.options.length) {
                select.innerHTML = '<option value="">انتخاب وضعیت</option>';
                data.options.forEach(function (opt) {
                    var o = document.createElement('option');
                    o.value = opt;
                    o.textContent = opt;
                    // The value it already holds cannot be "changed" to itself; the server
                    // refuses it, so do not offer it.
                    if (opt === current) o.disabled = true;
                    select.appendChild(o);
                });
                useControl('select');
            } else {
                // A free-text status field: no vocabulary to choose from.
                useControl('text');
            }
        }

        function load(assetId) {
            reset();
            if (!assetId) return;
            fetch('/asset-status-requests/options/statuses?assetId=' + encodeURIComponent(assetId), {
                headers: { 'Accept': 'application/json' }
            })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(apply)
                .catch(function () {
                    // Network trouble must not leave a form that looks ready but is not.
                    reset();
                    hint.textContent = 'خطا در دریافت گزینه‌های وضعیت.';
                });
        }

        assetSelect.addEventListener('change', function () { load(assetSelect.value); });
        reset();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
