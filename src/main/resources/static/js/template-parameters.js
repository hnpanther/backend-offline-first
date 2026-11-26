(() => {
    const panel = document.querySelector('#parameters');
    if (!panel) return;
    const {plan, rules, names} = JSON.parse(document.querySelector('#parameterData').textContent);
    const editor = document.querySelector('#parameterEditor');
    const search = document.querySelector('#parameterAssetSearch');
    const picker = document.querySelector('#parameterAsset');
    const fields = document.querySelector('#parameterFields');
    const message = document.querySelector('#parameterMessage');
    const modeLabels = {ALL_EXCEPT: 'همه به‌جز انتخاب‌شده‌ها', ONLY: 'فقط انتخاب‌شده‌ها'};
    const selectedByAsset = new Map(plan.assets.map(asset => [asset.id, asset.selected]));
    const classes = new Map();
    const assets = new Map(plan.assets.map(asset => [String(asset.id), asset]));
    // Keep exceptions for other equipment while switching between search results.
    const exceptions = Object.fromEntries(Object.entries(rules.assets).filter(([id]) => assets.has(id)));

    function element(tag, text) {
        const node = document.createElement(tag);
        if (text !== undefined) node.textContent = text;
        return node;
    }
    function select(labels, value) {
        const node = element('select');
        node.className = 'form-select form-select-sm';
        for (const [key, label] of Object.entries(labels)) node.add(new Option(label, key));
        node.value = value;
        return node;
    }
    function describe(field) {
        return (field.label || field.key) + ' (' + field.key + ')' + (field.unit ? ' · ' + field.unit : '')
            + (field.required ? ' · الزامی' : '') + (field.sourceClassId ? ' · ارثی' : '');
    }
    function checkbox(parent, text, checked) {
        const label = element('label');
        label.className = 'd-block';
        const input = element('input');
        input.type = 'checkbox';
        input.checked = checked;
        label.append(input, ' ' + text);
        parent.append(label);
        return input;
    }
    function fillPicker() {
        const term = search.value.trim().toLocaleLowerCase();
        picker.replaceChildren();
        for (const asset of plan.assets) {
            const label = [asset.code, asset.name].filter(Boolean).join(' · ');
            if (label.toLocaleLowerCase().includes(term)) picker.add(new Option(label, String(asset.id)));
        }
        showAsset();
    }
    function showAsset() {
        fields.replaceChildren();
        const asset = assets.get(picker.value);
        if (!asset) return;
        const exception = exceptions[asset.id] ?? {include: [], exclude: [], allowEmpty: false};
        const selected = new Set((selectedByAsset.get(asset.id) || []).map(f => f.bindingId));
        const classBindings = new Set((plan.classes[asset.classId] || []).map(f => f.bindingId));
        fields.append(element('h6', 'پیش‌نمایش تجهیز · ' + (names[asset.classId] || asset.classId)));
        for (const field of asset.effective) {
            const row = element('div');
            row.className = 'd-flex gap-2 align-items-center border-bottom py-1';
            const label = element('label', describe(field) + (classBindings.has(field.bindingId) ? '' : ' · اختصاصی تجهیز')
                + (selected.has(field.bindingId) ? ' · در فرم' : ' · حذف‌شده'));
            if (editor) {
                const value = exception.include.includes(field.bindingId) ? 'include'
                    : exception.exclude.includes(field.bindingId) ? 'exclude' : '';
                const choice = select({'': 'پیروی از کلاس', include: 'افزودن به فرم', exclude: 'حذف از فرم'}, value);
                choice.addEventListener('change', () => {
                    exception.include = exception.include.filter(id => id !== field.bindingId);
                    exception.exclude = exception.exclude.filter(id => id !== field.bindingId);
                    if (choice.value) exception[choice.value].push(field.bindingId);
                    exceptions[asset.id] = exception;
                    pending();
                });
                label.append(choice);
            }
            row.append(label);
            fields.append(row);
        }
        if (editor) {
            checkbox(fields, 'بازدید این تجهیز بدون اندازه‌گیری مجاز است', exception.allowEmpty)
                .addEventListener('change', event => {
                    exception.allowEmpty = event.target.checked;
                    exceptions[asset.id] = exception;
                    pending();
                });
        }
    }
    function pending() {
        message.textContent = 'برای دیدن نتیجهٔ تغییرات، پیش‌نمایش را به‌روز کنید.';
    }
    function payload() {
        const classRules = {};
        for (const [id, row] of classes) {
            if (row.mode.value) classRules[id] = {mode: row.mode.value,
                bindings: row.inputs.filter(input => input.checked).map(input => input.value)};
        }
        const assetRules = {};
        for (const [id, rule] of Object.entries(exceptions)) {
            const available = new Set(assets.get(id).effective.map(field => field.bindingId));
            const include = rule.include.filter(key => available.has(key));
            const exclude = rule.exclude.filter(key => available.has(key));
            if (include.length || exclude.length || rule.allowEmpty) assetRules[id] = {include, exclude, allowEmpty: rule.allowEmpty};
        }
        return {revision: Number(panel.dataset.revision), rules: {
            defaultMode: document.querySelector('#defaultParameterMode').value, classes: classRules, assets: assetRules}};
    }
    if (editor) {
        const defaultMode = document.querySelector('#defaultParameterMode');
        defaultMode.value = rules.defaultMode;
        defaultMode.addEventListener('change', pending);
        for (const [id, definitions] of Object.entries(plan.classes)) {
            const rule = rules.classes[id];
            const group = element('details');
            group.className = 'border rounded p-2';
            group.append(element('summary', (names[id] || id) + ' · ' + plan.assets.filter(a => String(a.classId) === id).length + ' تجهیز'));
            const mode = select({'': 'پیروی از پیش‌فرض قالب', ...modeLabels}, rule?.mode || '');
            const checklist = element('div');
            const title = element('strong');
            checklist.append(title);
            group.append(mode, checklist);
            const inputs = definitions.map(field => {
                const input = checkbox(checklist, describe(field), rule?.bindings.includes(field.bindingId));
                input.value = field.bindingId;
                input.addEventListener('change', pending);
                return input;
            });
            function displayMode() {
                checklist.hidden = !mode.value;
                title.textContent = mode.value === 'ALL_EXCEPT' ? 'پارامترهای حذف‌شده' : 'پارامترهای انتخاب‌شده';
            }
            displayMode();
            mode.addEventListener('change', () => { displayMode(); pending(); });
            document.querySelector('#parameterClasses').append(group);
            classes.set(id, {mode, inputs});
        }
        for (const [id, preview] of [['previewParameters', true], ['saveParameters', false]]) {
            const button = document.getElementById(id);
            button.addEventListener('click', async () => {
                button.disabled = true;
                try {
                    const result = await window.AppCsrf.postJson(panel.dataset.url + (preview ? '/preview' : ''), {
                        headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload())
                    });
                    if (!preview) { window.location.reload(); return; }
                    for (const asset of result.assets) selectedByAsset.set(asset.id, asset.selected);
                    showAsset();
                    message.textContent = result.problems.join(' · ') || 'پیش‌نمایش به‌روز شد.';
                } catch (error) { message.textContent = error.message; }
                finally { button.disabled = false; }
            });
        }
    }
    search.addEventListener('input', fillPicker);
    picker.addEventListener('change', showAsset);
    fillPicker();
})();
