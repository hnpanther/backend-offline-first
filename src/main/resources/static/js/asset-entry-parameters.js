        (function () {
            var form = document.getElementById('parametersForm');
            if (!form) return;

            function apply() {
                var selected = form.querySelector('input[name="fieldSource"]:checked');
                var mode = selected ? selected.value : 'INHERIT';
                var isFull = mode === 'FULL';
                var isInherit = mode === 'INHERIT';

                // One class on the form drives both groups, so the first paint (stamped by
                // Thymeleaf) and every later click go through exactly the same CSS rule.
                form.classList.toggle('js-mode-full', isFull);
                form.classList.toggle('js-mode-limited', !isFull);
                document.getElementById('inheritWarning').style.display = isInherit ? '' : 'none';

                // Under INHERIT the rows are shown for reference but cannot be edited: what is
                // stored is nothing at all, so an editable field here would be a lie.
                document.querySelectorAll('#rowsBody .band-input').forEach(function (el) {
                    el.disabled = isInherit;
                });

                // A row the admin added belongs to FULL. Leaving it submitted under RANGES_ONLY
                // would post a key the class does not have, which the server drops in silence —
                // the admin would see their new parameter simply not appear. Disabling the inputs
                // keeps them out of the POST while leaving the row visible, so switching the mode
                // back brings the typed values with it.
                document.querySelectorAll('#rowsBody .added-row').forEach(function (row) {
                    row.style.display = isFull ? '' : 'none';
                    row.querySelectorAll('input').forEach(function (el) { el.disabled = !isFull; });
                });
            }

            form.querySelectorAll('input[name="fieldSource"]').forEach(function (el) {
                el.addEventListener('change', apply);
            });

            // --- adding a parameter this asset alone has ---------------------------------------
            //
            // Indices only have to be unique and contiguous enough for Spring's indexed binding;
            // they carry no meaning. Counting from the rows already rendered avoids colliding with
            // them, and a removed row simply leaves a gap, which Spring tolerates because each
            // input names its own index.
            var body = document.getElementById('rowsBody');
            var tpl = document.getElementById('newRowTemplate');
            var addButton = document.getElementById('addRowButton');
            var nextIndex = body ? body.querySelectorAll(':scope > .parameter-row').length : 0;
            var search = document.getElementById('parameterSearch');
            var searchResult = document.getElementById('parameterSearchResult');

            function normaliseSearch(value) {
                return value.toLowerCase().replace(/ي/g, 'ی').replace(/ك/g, 'ک').trim();
            }

            // Visibility only: filtered rows keep their inputs and indexed names in the POST.
            function filterRows() {
                var query = normaliseSearch(search ? search.value : '');
                var rows = body.querySelectorAll(':scope > .parameter-row, :scope > .added-row');
                var visible = 0;
                rows.forEach(function (row) {
                    var text = row.getAttribute('data-search') || '';
                    row.querySelectorAll('input[name$=".label"], input[name$=".fieldKey"], input[name$=".unit"]').forEach(function (input) {
                        text += ' ' + input.value;
                    });
                    // New unsaved rows stay visible so required controls remain reachable.
                    row.hidden = !row.classList.contains('added-row') && normaliseSearch(text).indexOf(query) < 0;
                    if (!row.hidden) visible++;
                });
                document.getElementById('parameterNoMatch').hidden = !query || visible > 0;
                if (searchResult) searchResult.textContent = query ? visible + ' از ' + rows.length + ' پارامتر' : '';
            }
            if (search) {
                search.addEventListener('input', filterRows);
                search.addEventListener('keydown', function (event) {
                    if (event.key === 'Enter') event.preventDefault();
                    if (event.key === 'Escape') { search.value = ''; filterRows(); }
                });
            }
            form.addEventListener('invalid', function () {
                if (search) { search.value = ''; filterRows(); }
            }, true);

            function addRow() {
                if (!tpl || !body) return;
                var row = tpl.content.firstElementChild.cloneNode(true);
                var index = nextIndex++;
                row.querySelectorAll('[data-field]').forEach(function (input) {
                    input.name = 'rows[' + index + '].' + input.getAttribute('data-field');
                    input.removeAttribute('data-field');
                });
                // `custom` is what tells the server this row is the asset's own definition rather
                // than a re-banded one of the class's; without it the label and unit are ignored.
                var flag = document.createElement('input');
                flag.type = 'hidden';
                flag.name = 'rows[' + index + '].custom';
                flag.value = 'true';
                row.firstElementChild.appendChild(flag);

                row.querySelector('.remove-added').addEventListener('click', function () {
                    row.remove();
                    var empty = body.querySelector('.parameter-empty-row');
                    if (empty) empty.hidden = !!body.querySelector('.added-row');
                    filterRows();
                });
                bindRowType(row);
                body.appendChild(row);
                var empty = body.querySelector('.parameter-empty-row');
                if (empty) empty.hidden = true;
                if (search) search.value = '';
                filterRows();
                var key = row.querySelector('input[name$=".fieldKey"]');
                if (key) key.focus();
            }

            /**
             * What an added row offers depends on the type it declares.
             *
             * The band inputs are DISABLED rather than hidden for a non-numeric type, because a
             * disabled input is not submitted at all — so the server never has to decide what a
             * warning range on a select was supposed to mean. It refuses that combination, and
             * this keeps an administrator from reaching the refusal by accident.
             */
            function bindRowType(row) {
                var select = row.querySelector('.row-data-type');
                if (!select) return;
                var optionsBlock = row.querySelector('.row-options-block');
                var lookupBlock = row.querySelector('.row-lookup-block');
                var bands = row.querySelectorAll('.band-input');

                function sync() {
                    var type = select.value;
                    var isSelect = type === 'select' || type === 'multiselect';
                    var isLookup = type === 'lookup' || type === 'lookupmulti';
                    var isNumber = type === 'number';
                    if (optionsBlock) optionsBlock.classList.toggle('d-none', !isSelect);
                    if (lookupBlock) {
                        lookupBlock.classList.toggle('d-none', !isLookup);
                        // A hidden select still POSTs; clear a stale list when the type is not a
                        // lookup so the server never has to refuse a leftover code on a text field.
                        if (!isLookup) {
                            var list = lookupBlock.querySelector('[name$=".lookupList"], [data-field="lookupList"]');
                            if (list) list.value = '';
                            var accepts = lookupBlock.querySelector('[name$=".lookupAcceptsUnlistedCode"], [data-field="lookupAcceptsUnlistedCode"]');
                            if (accepts) accepts.checked = false;
                        }
                    }
                    bands.forEach(function (input) {
                        input.disabled = !isNumber;
                        if (!isNumber) input.value = '';
                        input.closest('td').classList.toggle('text-muted', !isNumber);
                    });
                }

                select.addEventListener('change', sync);
                sync();
            }

            if (addButton) addButton.addEventListener('click', addRow);

            // --- option severities, per row ----------------------------------------------------
            //
            // The table arrives server-rendered with the right radio checked; the panel is only
            // COLLAPSED, not unbuilt, so nothing here reconstructs it (AGENTS.md #126). The two
            // hidden inputs are what the server reads, and they are rewritten whenever a radio
            // changes — newline-joined, because a comma separates options and so cannot occur
            // inside one.
            document.querySelectorAll('.option-severity-toggle').forEach(function (button) {
                var index = button.getAttribute('data-row');
                var panel = document.querySelector('[data-row-panel="' + index + '"]');
                if (!panel) return;
                button.addEventListener('click', function () {
                    panel.classList.toggle('d-none');
                    button.setAttribute('aria-expanded', String(!panel.classList.contains('d-none')));
                });
            });

            function syncOptionSeverities(index) {
                var warn = [], danger = [];
                document.querySelectorAll('.asset-opt-sev[data-row="' + index + '"]:checked')
                    .forEach(function (radio) {
                        var option = radio.getAttribute('data-option');
                        if (radio.value === 'warning') warn.push(option);
                        else if (radio.value === 'danger') danger.push(option);
                    });
                var w = document.querySelector('.asset-warning-options[data-row="' + index + '"]');
                var d = document.querySelector('.asset-danger-options[data-row="' + index + '"]');
                if (w) w.value = warn.join('\n');
                if (d) d.value = danger.join('\n');
            }

            document.querySelectorAll('.asset-opt-sev').forEach(function (radio) {
                radio.addEventListener('change', function () {
                    syncOptionSeverities(radio.getAttribute('data-row'));
                });
            });

            /*
             * The per-asset state gate: reveal the block, and hide the operand box for the two
             * operators that have none.
             *
             * Reveal only. The server already rendered the gate with its parameter and operator
             * selected and the block open when there is one, so this adds no state and the first
             * paint is already correct (AGENTS.md #126).
             *
             * It also does NOT clear the inputs when collapsed, and that is the opposite of the
             * class editor's rule on purpose: here the collapsed state is "not expanded", not
             * "no gate", and an inherited gate sits in those boxes waiting to be posted back.
             * Clearing it would strip the class's condition off every row an administrator did
             * not happen to open.
             */
            document.querySelectorAll('.asset-gate-cell').forEach(function (cell) {
                var toggle = cell.querySelector('.asset-gate-toggle');
                var body = cell.querySelector('.asset-gate-body');
                var op = cell.querySelector('.asset-gate-op');
                var operandCell = cell.querySelector('.asset-gate-operand-cell');
                if (!toggle || !body) return;

                function syncOperand() {
                    if (!op || !operandCell) return;
                    var none = op.value === 'answered' || op.value === 'notAnswered';
                    operandCell.classList.toggle('d-none', none);
                }

                toggle.addEventListener('click', function () {
                    var open = body.classList.toggle('d-none');
                    toggle.setAttribute('aria-expanded', open ? 'false' : 'true');
                });
                if (op) op.addEventListener('change', syncOperand);
                syncOperand();
            });

            apply();
        })();
    
