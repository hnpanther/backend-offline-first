        // «همه آلارم‌ها» disables the rule boxes, so the form never posts a list it does not use.
        document.querySelectorAll('.js-rule-scope').forEach(function (radio) {
            radio.addEventListener('change', function () {
                var list = document.getElementById(radio.dataset.prefix + '-rule-list');
                if (!list) return;
                var selected = radio.value === 'SELECTED' && radio.checked;
                if (radio.value === 'ALL' && radio.checked) selected = false;
                list.querySelectorAll('input[name="ruleIds"]').forEach(function (box) {
                    box.disabled = !selected;
                });
            });
        });
    
