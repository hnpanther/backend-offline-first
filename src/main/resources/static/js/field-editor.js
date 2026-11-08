/*
 * The shared behaviour of a field editor: the three blocks that appear only for certain data
 * types, the option-severity table that follows the vocabulary, and the min/max sanity check.
 *
 * Extracted from field-definitions.html when a SECOND editor appeared — the round-level
 * questions on a log-sheet template. Two copies of this would drift the first time either was
 * fixed, which is the failure FieldDataTypes exists to prevent on the server side: that class
 * was written because the create and edit modals each carried their own hardcoded type list,
 * and the edit one silently retyped every image field it touched.
 *
 * Everything here is selector-driven and scoped to a form, so a page using it needs no
 * configuration: include the script, give the type <select> `.data-type-select`, and reuse
 * `fragments/field-validation-blocks`.
 */
document.querySelectorAll('.data-type-select').forEach(sel => {
    const toggle = () => {
        const form = sel.closest('form');
        const showSelect = sel.value === 'select' || sel.value === 'multiselect';
        form?.querySelector('.select-options-block')
            ?.classList.toggle('d-none', !showSelect);
        form?.querySelector('.number-validation-block')
            ?.classList.toggle('d-none', sel.value !== 'number');
        // The severity table belongs to the same two types as the vocabulary itself.
        form?.querySelector('.option-severity-block')
            ?.classList.toggle('d-none', !showSelect);
        // Scan-fill is for the two types a label can resolve to. A select has a vocabulary and
        // a checkbox has two states; neither is something you read off a nameplate.
        const scannable = sel.value === 'text' || sel.value === 'number';
        form?.querySelector('.scan-fill-block')
            ?.classList.toggle('d-none', !scannable);
    };
    sel.addEventListener('change', toggle);
    toggle();
});

/*
 * The scan sources follow their own switch.
 *
 * Unchecking the switch must also CLEAR the sources, not merely hide them: a hidden checked box
 * still posts its value, and the server would store a scan rule the administrator just turned
 * off. Same reason the custom-sheet form clears its asset chips when «بدون دارایی» is ticked.
 */
document.querySelectorAll('.scan-enabled-toggle').forEach(toggleInput => {
    const block = toggleInput.closest('.scan-fill-block');
    const row = block?.querySelector('.scan-sources-row');
    if (!row) return;

    const sync = () => {
        const on = toggleInput.checked;
        row.classList.toggle('d-none', !on);
        if (!on) {
            block.querySelectorAll('.scan-source-check').forEach(c => { c.checked = false; });
        }
    };
    toggleInput.addEventListener('change', sync);
    sync();
});

/*
 * The severity table follows the vocabulary.
 *
 * The SERVER renders it for a field that already has options, with the right radio
 * checked — that is the first paint and it must not be rebuilt on load (AGENTS.md #126).
 * This only reacts to the administrator editing the textarea: rows are rebuilt from the
 * text, a severity is KEPT for an option that is still there (matched by its exact text)
 * and dropped for one that is gone, so a deleted option cannot leave a rule behind.
 *
 * `warningOptions` / `dangerOptions` are what the server reads; the radios are display.
 * They are newline-joined because a comma separates options and so cannot occur inside
 * one. `FieldValidationSupport.build` narrows both lists to the vocabulary again on the
 * server, so a stale value here can never be stored.
 */
document.querySelectorAll('.option-severity-block').forEach(block => {
    const form = block.closest('form');
    const textarea = form?.querySelector('.select-options-block textarea[name="selectOptions"]');
    const body = block.querySelector('.option-severity-rows');
    const empty = block.querySelector('.option-severity-empty');
    const warnInput = block.querySelector('.warning-options-input');
    const dangerInput = block.querySelector('.danger-options-input');
    if (!textarea || !body || !warnInput || !dangerInput) return;

    const parse = text => {
        const seen = [];
        text.split(/[,\n،]/).forEach(part => {
            const t = part.trim();
            if (t && seen.indexOf(t) === -1) seen.push(t);
        });
        return seen;
    };

    const readSeverities = () => {
        const map = {};
        body.querySelectorAll('.opt-sev:checked').forEach(radio => {
            map[radio.getAttribute('data-option')] = radio.value;
        });
        return map;
    };

    const writeHidden = () => {
        const map = readSeverities();
        const warn = [], danger = [];
        Object.keys(map).forEach(opt => {
            if (map[opt] === 'warning') warn.push(opt);
            else if (map[opt] === 'danger') danger.push(opt);
        });
        warnInput.value = warn.join('\n');
        dangerInput.value = danger.join('\n');
    };

    const rebuild = () => {
        const previous = readSeverities();
        const options = parse(textarea.value);
        body.innerHTML = '';
        options.forEach((opt, index) => {
            const severity = previous[opt] || 'ok';
            const tr = document.createElement('tr');
            const name = document.createElement('td');
            const code = document.createElement('code');
            code.textContent = opt;
            name.appendChild(code);
            tr.appendChild(name);
            ['ok', 'warning', 'danger'].forEach(level => {
                const td = document.createElement('td');
                td.className = 'text-center';
                const radio = document.createElement('input');
                radio.type = 'radio';
                radio.className = 'form-check-input opt-sev';
                radio.name = 'optSev_' + index;
                radio.value = level;
                radio.setAttribute('data-option', opt);
                radio.checked = severity === level;
                radio.addEventListener('change', writeHidden);
                td.appendChild(radio);
                tr.appendChild(td);
            });
            body.appendChild(tr);
        });
        if (empty) empty.classList.toggle('d-none', options.length > 0);
        writeHidden();
    };

    // Bind the rows the server already rendered, without rebuilding them.
    body.querySelectorAll('.opt-sev').forEach(r => r.addEventListener('change', writeHidden));
    textarea.addEventListener('input', rebuild);
});

// Catch a backwards min/max range (warning or danger) before submit — the browser's
// native validation tooltip keeps the user right where they are, inside the modal,
// instead of a full-page redirect that closes it. Server-side check in
// FieldValidationSupport.build() stays as the authoritative backstop (e.g. if JS is
// disabled), so nothing here weakens that guarantee.
document.querySelectorAll('.number-validation-block').forEach(block => {
    [['warningMin', 'warningMax'], ['dangerMin', 'dangerMax']].forEach(([minName, maxName]) => {
        const minInput = block.querySelector(`input[name="${minName}"]`);
        const maxInput = block.querySelector(`input[name="${maxName}"]`);
        if (!minInput || !maxInput) return;
        const check = () => {
            const min = minInput.value !== '' ? parseFloat(minInput.value) : null;
            const max = maxInput.value !== '' ? parseFloat(maxInput.value) : null;
            const invalid = min !== null && max !== null && !Number.isNaN(min) && !Number.isNaN(max) && min > max;
            maxInput.setCustomValidity(invalid ? 'حداکثر نمی‌تواند کمتر از حداقل باشد.' : '');
        };
        minInput.addEventListener('input', check);
        maxInput.addEventListener('input', check);
    });
});
