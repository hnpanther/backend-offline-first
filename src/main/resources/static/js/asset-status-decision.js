(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        var modalElement = document.getElementById('statusDecisionModal');
        if (!modalElement) return;
        var confirmButton = document.getElementById('statusDecisionConfirm');
        var cancelButton = document.getElementById('statusDecisionCancel');
        var pendingForm = null;
        var pendingButton = null;
        var confirmedForm = null;
        var modal = window.bootstrap ? bootstrap.Modal.getOrCreateInstance(modalElement) : null;

        document.querySelectorAll('form[data-status-decision]').forEach(function (form) {
            form.addEventListener('submit', function (event) {
                if (confirmedForm === form) {
                    confirmedForm = null;
                    return;
                }
                if (event.defaultPrevented) return;
                var row = form.closest('tr');
                var message = form.dataset.confirmMessage;
                if (!modal) {
                    if (!window.confirm(row.dataset.requestAsset + '\n' + message)) event.preventDefault();
                    return;
                }
                event.preventDefault();
                pendingForm = form;
                pendingButton = event.submitter;
                document.getElementById('statusDecisionTitle').textContent = form.dataset.confirmTitle;
                document.getElementById('statusDecisionAsset').textContent = row.dataset.requestAsset;
                document.getElementById('statusDecisionStatus').textContent = row.dataset.requestStatus;
                document.getElementById('statusDecisionMessage').textContent = message;
                confirmButton.textContent = form.dataset.confirmLabel;
                confirmButton.className = 'btn btn-' + form.dataset.confirmTone;
                confirmButton.disabled = false;
                modal.show();
            });
        });

        modalElement.addEventListener('shown.bs.modal', function () { cancelButton.focus(); });
        modalElement.addEventListener('hidden.bs.modal', function () {
            if (pendingButton) pendingButton.focus();
            pendingForm = null;
            pendingButton = null;
        });
        confirmButton.addEventListener('click', function () {
            if (!pendingForm || confirmButton.disabled) return;
            confirmButton.disabled = true;
            confirmedForm = pendingForm;
            // Submit the original form: same endpoint, CSRF token, target and native validation.
            // No fetch, cloned form or alternate decision path.
            if (pendingButton) pendingForm.requestSubmit(pendingButton);
            else pendingForm.requestSubmit();
            confirmedForm = null;
            modal.hide();
        });
    });
})();
