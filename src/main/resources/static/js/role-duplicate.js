        // Retargets the duplicate form to whichever row was clicked. The form's th:action is a
        // placeholder (/roles/0/duplicate) because Thymeleaf renders one shared modal, not one
        // per row — rewriting the id here keeps a single modal instead of N in the DOM.
        document.addEventListener('DOMContentLoaded', () => {
            const form = document.getElementById('duplicateForm');
            if (!form) return;
            document.querySelectorAll('.js-duplicate-role').forEach(btn => {
                btn.addEventListener('click', () => {
                    const id = btn.dataset.roleId;
                    const code = btn.dataset.roleCode || '';
                    const name = btn.dataset.roleName || '';
                    form.setAttribute('action', '/roles/' + id + '/duplicate');
                    document.getElementById('duplicateSourceName').textContent = name;
                    document.getElementById('duplicateSourceCode').textContent = code;
                    // Pre-fill with an obvious, editable starting point rather than blanks.
                    document.getElementById('duplicateCode').value = code + '_COPY';
                    document.getElementById('duplicateName').value = name + ' (کپی)';
                });
            });
        });
    
