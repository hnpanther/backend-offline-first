        const statusLabels = {
            PENDING: 'در صف',
            RUNNING: 'در حال اجرا',
            COMPLETED: 'پایان یافته',
            FAILED: 'خطا',
            CANCELLED: 'متوقف شده'
        };
        const statusClass = {
            PENDING: 'bg-secondary',
            RUNNING: 'bg-primary',
            COMPLETED: 'bg-success',
            FAILED: 'bg-danger',
            CANCELLED: 'bg-warning text-dark'
        };

        function actionButtons(j) {
            const uuid = escapeHtml(j.jobUuid);
            const parts = [];
            if (j.active) {
                parts.push(`<button type="button" class="btn btn-sm btn-outline-warning btn-cancel" data-job-uuid="${uuid}">توقف</button>`);
            } else {
                parts.push(`<button type="button" class="btn btn-sm btn-outline-danger btn-delete" data-job-uuid="${uuid}">حذف</button>`);
            }
            // Only for a job that has gone quiet. Stop is cooperative and cannot touch a job
            // whose thread is gone; this is the way out that used to require a restart.
            if (j.stalled) {
                parts.push(`<button type="button" class="btn btn-sm btn-danger btn-abandon" data-job-uuid="${uuid}" title="پردازشگر این عملیات پاسخ نمی‌دهد">رها کردن</button>`);
            }
            if (j.status === 'COMPLETED' && j.errorCount > 0) {
                parts.push(`<button type="button" class="btn btn-sm btn-outline-secondary btn-errors" data-job-uuid="${uuid}">خطاها</button>`);
            }
            return parts.join(' ');
        }

        function escapeHtml(value) {
            return String(value ?? '')
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;');
        }

        function renderJobs(jobs) {
            const body = document.getElementById('jobsBody');
            if (!jobs.length) {
                body.innerHTML = '<tr id="emptyJobsRow"><td colspan="6" class="text-center text-muted py-4">هنوز عملیاتی ثبت نشده است.</td></tr>';
                return;
            }
            body.innerHTML = jobs.map(j => {
                const total = j.totalRows > 0 ? j.totalRows : '؟';
                const result = j.status === 'COMPLETED'
                    ? `موفق: ${j.successCount} — خطا: ${j.errorCount}`
                    : ((j.status === 'FAILED' || j.status === 'CANCELLED') ? (j.errorMessage || '') : '');
                return `<tr data-job-uuid="${escapeHtml(j.jobUuid)}">
                    <td>${escapeHtml(j.entityLabel)}</td>
                    <td class="small text-truncate" style="max-width:120px" title="${escapeHtml(j.fileName)}">${escapeHtml(j.fileName)}</td>
                    <td><span class="badge job-status status-badge ${statusClass[j.status] || 'bg-secondary'}">${escapeHtml(j.statusLabel)}</span></td>
                    <td>
                        <div class="progress" style="height:8px"><div class="progress-bar" style="width:${Number(j.progressPercent) || 0}%"></div></div>
                        <span class="small text-muted">${j.processedRows} / ${total}</span>
                    </td>
                    <td class="small ${j.status === 'FAILED' || j.status === 'CANCELLED' ? 'text-danger' : ''}">${escapeHtml(result)}</td>
                    <td class="text-nowrap">${actionButtons(j)}</td>
                </tr>`;
            }).join('');
            bindErrorButtons();
            bindJobActionButtons();
        }

        /**
         * Runs a job action and reports what happened.
         *
         * Every failure here used to be invisible. The old code called
         * `fetch(url, {method:'POST'})` with no CSRF token; the web chain has CSRF enabled,
         * WebAccessDeniedHandler answers with a redirect rather than a 403, fetch followed it
         * and returned the HTML page with status 200, and `res.json()` then threw inside an
         * async function whose rejection nobody handled. Result: the Delete and Stop buttons
         * did nothing at all, silently, for every click. AppCsrf.postJson sends the token,
         * refuses the redirect, and throws a readable Error — so this catch is the whole
         * point, not decoration.
         */
        async function postJobAction(url, confirmMessage) {
            if (!confirm(confirmMessage)) {
                return;
            }
            try {
                const data = await AppCsrf.postJson(url);
                if (!data || !data.success) {
                    alert((data && data.message) || 'عملیات انجام نشد.');
                }
            } catch (e) {
                alert(e.message || 'عملیات انجام نشد.');
            }
            await pollJobs();
        }

        function bindJobActionButtons() {
            document.querySelectorAll('.btn-cancel').forEach(btn => {
                btn.onclick = () => postJobAction(
                    `/batch-import/jobs/${btn.getAttribute('data-job-uuid')}/cancel`,
                    'آیا از توقف این عملیات مطمئن هستید؟'
                );
            });
            document.querySelectorAll('.btn-abandon').forEach(btn => {
                btn.onclick = () => postJobAction(
                    `/batch-import/jobs/${btn.getAttribute('data-job-uuid')}/abandon`,
                    'این عملیات مدتی است پیشرفتی گزارش نکرده است.\n\n'
                    + 'با رها کردن، وضعیت آن «خطا» ثبت می‌شود و امکان شروع ورود جدید فراهم می‌گردد. '
                    + 'ردیف‌هایی که تا این لحظه وارد شده‌اند در دیتابیس باقی می‌مانند.\n\nادامه می‌دهید؟'
                );
            });
            document.querySelectorAll('.btn-delete').forEach(btn => {
                btn.onclick = () => postJobAction(
                    `/batch-import/jobs/${btn.getAttribute('data-job-uuid')}/delete`,
                    'آیا از حذف این عملیات مطمئن هستید؟'
                );
            });
        }

        function bindErrorButtons() {
            document.querySelectorAll('.btn-errors').forEach(btn => {
                btn.onclick = async () => {
                    const uuid = btn.getAttribute('data-job-uuid');
                    const res = await fetch(`/batch-import/jobs/${uuid}/errors`);
                    const errors = await res.json();
                    const list = document.getElementById('errorsList');
                    list.innerHTML = errors.length
                        ? errors.map(e => `<li class="list-group-item small">ردیف ${escapeHtml(e.row)}: ${escapeHtml(e.message)}</li>`).join('')
                        : '<li class="list-group-item text-muted">خطایی ثبت نشده است.</li>';
                    bootstrap.Modal.getOrCreateInstance(document.getElementById('errorsModal')).show();
                };
            });
        }

        let pollTimer = null;
        let pollInFlight = false;
        let pollingActive = false;
        let pollFailures = 0;

        function clearPollTimer() {
            if (pollTimer) {
                clearTimeout(pollTimer);
                pollTimer = null;
            }
        }

        function schedulePoll(delayMs) {
            clearPollTimer();
            pollTimer = setTimeout(pollJobs, delayMs);
        }

        /**
         * Enables or disables the submit form from the live `busy` flag.
         *
         * `busy` is system-wide (any user's import blocks submission), which is why the
         * server sends it explicitly instead of the page inferring it from the job rows —
         * those are only ever the current user's. Without this the form stayed disabled from
         * the render that followed the upload redirect until a full page reload, so a
         * finished import still looked like a running one.
         */
        function applyBusyState(busy) {
            const submitBtn = document.getElementById('importSubmitBtn');
            const fileInput = document.getElementById('importFileInput');
            const alertBox = document.getElementById('importBusyAlert');
            if (!submitBtn) return;
            // Permission, not congestion: a user with no importable entity types must stay
            // disabled no matter what the queue is doing.
            const noAccess = submitBtn.getAttribute('data-no-access') === 'true';
            submitBtn.disabled = noAccess || busy;
            if (fileInput) fileInput.disabled = noAccess || busy;
            if (alertBox) alertBox.classList.toggle('d-none', !busy);
        }

        async function pollJobs() {
            if (pollInFlight) return;
            pollInFlight = true;
            clearPollTimer();
            try {
                const res = await fetch('/batch-import/jobs', { credentials: 'same-origin' });
                if (!res.ok) throw new Error('poll failed: ' + res.status);
                const data = await res.json();
                const jobs = data.jobs || [];
                pollFailures = 0;
                renderJobs(jobs);
                applyBusyState(!!data.busy);
                // Keep polling while ANY import is live, not just one of this user's, so the
                // form un-disables the moment the queue clears rather than at the next reload.
                pollingActive = !!data.busy || jobs.some(j => j.active);
                document.getElementById('pollIndicator').textContent = pollingActive ? 'به‌روزرسانی زنده' : 'آخرین وضعیت';
                document.getElementById('pollIndicator').className = 'badge ' + (pollingActive ? 'bg-primary' : 'bg-secondary');
                if (pollingActive) {
                    schedulePoll(2500);
                }
            } catch (e) {
                document.getElementById('pollIndicator').textContent = 'خطا در به‌روزرسانی';
                // Retry even when idle — a dropped request used to leave the page stuck on
                // the error badge forever — but only a few times, so a page left open against
                // a server that is down does not poll it indefinitely.
                pollFailures++;
                if (pollingActive || pollFailures <= 3) {
                    schedulePoll(10000);
                }
            } finally {
                pollInFlight = false;
            }
        }

        document.addEventListener('DOMContentLoaded', () => {
            bindErrorButtons();
            bindJobActionButtons();
            pollJobs();
        });

        window.addEventListener('pagehide', clearPollTimer);
        window.addEventListener('pageshow', (event) => {
            if (event.persisted) {
                pollJobs();
            }
        });
    
