        function syncAddPasswordField() {
            const authType = document.getElementById('addAuthType')?.value;
            const password = document.getElementById('addPassword');
            const requiredMark = document.getElementById('addPasswordRequired');
            if (!password) return;
            const adOnly = authType === 'ACTIVE_DIRECTORY';
            password.required = !adOnly;
            password.disabled = adOnly;
            if (adOnly) password.value = '';
            if (requiredMark) requiredMark.style.display = adOnly ? 'none' : 'inline';
        }
        document.getElementById('addAuthType')?.addEventListener('change', syncAddPasswordField);
        document.addEventListener('DOMContentLoaded', syncAddPasswordField);
    
