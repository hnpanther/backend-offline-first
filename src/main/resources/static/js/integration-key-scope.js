// The scope dialog is one form for every key: the row's button carries the key's action
// URL and its current scope, and the boxes are ticked from those before it opens. The
// form is rendered with th:action so Thymeleaf adds its CSRF field; only the action
// changes here.
document.querySelectorAll('.js-edit-scope').forEach(function (button) {
    button.addEventListener('click', function () {
        var form = document.getElementById('scopeForm');
        var modalEl = document.getElementById('scopeModal');
        if (!form || !modalEl) return;
        form.setAttribute('action', button.dataset.action);
        document.getElementById('scopeModalClient').textContent = button.dataset.client || '';
        var categories = (button.dataset.categories || '').split(' ').filter(Boolean);
        var statuses = (button.dataset.statuses || '').split(' ').filter(Boolean);
        form.querySelectorAll('input.scope-category').forEach(function (box) {
            box.checked = categories.indexOf(box.value) !== -1;
        });
        form.querySelectorAll('input.scope-status').forEach(function (box) {
            box.checked = statuses.indexOf(box.value) !== -1;
        });
        bootstrap.Modal.getOrCreateInstance(modalEl).show();
    });
});
