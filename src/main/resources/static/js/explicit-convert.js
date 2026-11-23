        (function () {
            var form = document.getElementById('convertForm');
            if (!form) return;
            var checkAll = document.getElementById('checkAll');
            // The checkboxes are associated with the form by their `form` attribute, not by
            // being inside it, so they have to be found on the document.
            var picks = function () { return Array.prototype.slice.call(document.querySelectorAll('.asset-pick')); };

            function refresh() {
                var chosen = picks().filter(function (c) { return c.checked; }).length;
                document.getElementById('pickedCount').textContent = chosen;
                var confirmBtn = document.getElementById('confirmConvert');
                confirmBtn.disabled = chosen === 0;
                confirmBtn.title = chosen === 0
                    ? 'دست‌کم یک دارایی لازم است، وگرنه این قالب راندهای خالی می‌سازد.' : '';
                if (checkAll) {
                    checkAll.checked = chosen === picks().length && chosen > 0;
                    checkAll.indeterminate = chosen > 0 && chosen < picks().length;
                }
            }

            if (checkAll) {
                checkAll.addEventListener('change', function () {
                    picks().forEach(function (c) { c.checked = checkAll.checked; });
                    refresh();
                });
            }
            picks().forEach(function (c) { c.addEventListener('change', refresh); });
            refresh();
        })();
    
