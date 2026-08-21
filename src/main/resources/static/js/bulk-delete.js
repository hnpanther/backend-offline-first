(() => {
    /*
     * The checkboxes are NOT inside the form.
     *
     * They join it through `form="bulkDeleteForm"`, because the bulk form used to wrap the table
     * and that nested each row's own delete form inside it — which the HTML parser resolves by
     * ignoring the inner <form>, leaving the first row's delete button bound to the bulk form.
     *
     * So every lookup below is scoped to the [data-bulk-delete] container. Scoping to the form's
     * subtree, as this did, now finds nothing: the controls are associated with the form without
     * being descendants of it.
     */
    function initBulkDelete(root) {
        const form = root.querySelector('form.bulk-delete-form');
        if (!form) return;

        const selectAll = root.querySelector('.bulk-select-all');
        const rowBoxes = () => Array.from(root.querySelectorAll('.bulk-select-row'));
        const deleteBtn = document.querySelector('.bulk-delete-submit');

        function refreshDeleteButton() {
            if (!deleteBtn) return;
            const any = rowBoxes().some(cb => cb.checked);
            deleteBtn.disabled = !any;
        }

        if (selectAll) {
            selectAll.addEventListener('change', () => {
                rowBoxes().forEach(cb => { cb.checked = selectAll.checked; });
                refreshDeleteButton();
            });
        }

        rowBoxes().forEach(cb => cb.addEventListener('change', () => {
            if (selectAll) {
                selectAll.checked = rowBoxes().length > 0 && rowBoxes().every(x => x.checked);
            }
            refreshDeleteButton();
        }));

        form.addEventListener('submit', e => {
            if (!rowBoxes().some(cb => cb.checked)) {
                e.preventDefault();
                return;
            }
            if (!confirm('آیا از حذف موارد انتخاب‌شده مطمئن هستید؟')) {
                e.preventDefault();
            }
        });

        refreshDeleteButton();
    }

    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('[data-bulk-delete]').forEach(initBulkDelete);
    });
})();
