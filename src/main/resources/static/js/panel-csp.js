/**
 * Behaviours that used to live in HTML event-handler attributes.
 *
 * script-src 'self' (roadmap §15 stage 3) refuses onclick / onsubmit / onchange.
 * The attributes are now data-*, and this file — served from this origin — binds them.
 * Loaded after Bootstrap so a modal marked data-open-on-load can open.
 */
(function () {
    'use strict';

    function onReady(fn) {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', fn);
        } else {
            fn();
        }
    }

    onReady(function () {
        document.querySelectorAll('[data-open-on-load="true"]').forEach(function (el) {
            if (window.bootstrap && bootstrap.Modal) {
                bootstrap.Modal.getOrCreateInstance(el).show();
            }
        });

        document.querySelectorAll('form[data-confirm]').forEach(function (form) {
            form.addEventListener('submit', function (event) {
                var message = form.getAttribute('data-confirm');
                if (message && !window.confirm(message)) {
                    event.preventDefault();
                }
            });
        });

        document.querySelectorAll('[data-auto-submit]').forEach(function (el) {
            el.addEventListener('change', function () {
                if (el.form) {
                    el.form.submit();
                }
            });
        });

        document.querySelectorAll('[data-copy-from]').forEach(function (button) {
            button.addEventListener('click', function () {
                var field = document.getElementById(button.getAttribute('data-copy-from'));
                if (!field) return;
                var text = field.value != null ? field.value : field.textContent;
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(text);
                } else if (field.select) {
                    field.select();
                    document.execCommand('copy');
                }
                var done = button.getAttribute('data-copy-done');
                if (done) button.textContent = done;
            });
        });

        document.querySelectorAll('form[data-submit-lock]').forEach(function (form) {
            form.addEventListener('submit', function () {
                var button = form.querySelector('button[type="submit"]');
                if (button) {
                    button.disabled = true;
                    button.textContent = form.getAttribute('data-submit-lock');
                }
                var wait = form.querySelector('.lookup-apply-wait');
                if (wait) wait.classList.remove('d-none');
            });
        });

        document.querySelectorAll('form[data-require-units]').forEach(function (form) {
            form.addEventListener('submit', function (event) {
                var picker = form.querySelector('.remote-multi-select[data-required="true"]');
                if (!picker) return;
                if (!picker.querySelector('.remote-multi-hidden input[name="unitIds"]')) {
                    event.preventDefault();
                    window.alert('حداقل یک واحد عملیاتی را انتخاب کنید.');
                }
            });
        });

        document.querySelectorAll('form[data-confirm-prompt]').forEach(function (form) {
            form.addEventListener('submit', function (event) {
                var reason = window.prompt(form.getAttribute('data-confirm-prompt'));
                if (reason === null) {
                    event.preventDefault();
                    return;
                }
                var input = form.querySelector('[name="reason"]');
                if (input) input.value = reason;
            });
        });
    });
})();
