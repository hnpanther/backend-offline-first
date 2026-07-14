(() => {
    function initBulkDelete(root) {
        const form = root.querySelector('form.bulk-delete-form');
        if (!form) return;

        const selectAll = form.querySelector('.bulk-select-all');
        const rowBoxes = () => Array.from(form.querySelectorAll('.bulk-select-row'));
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
