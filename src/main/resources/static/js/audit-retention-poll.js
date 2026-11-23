        (function () {
            const badge = document.getElementById('auditRetentionBadge');
            const deleted = document.getElementById('auditDeletedCount');
            const statusLabels = {
                IDLE: 'آماده', RUNNING: 'در حال اجرا', COMPLETED: 'تمام شد',
                CANCELLED: 'متوقف شد', FAILED: 'خطا'
            };
            let pollTimer = null;
            let stopped = false;

            function clearPollTimer() {
                if (pollTimer) {
                    clearTimeout(pollTimer);
                    pollTimer = null;
                }
            }

            function schedulePoll(delayMs) {
                clearPollTimer();
                if (!stopped) {
                    pollTimer = setTimeout(poll, delayMs);
                }
            }

            function poll() {
                if (stopped) return;
                fetch('/settings/audit-retention/status', {headers: {'Accept': 'application/json'}})
                    .then(r => r.json())
                    .then(p => {
                        if (stopped) return;
                        badge.textContent = statusLabels[p.status] || p.status;
                        deleted.textContent = p.deletedCount;
                        if (p.status !== 'RUNNING') {
                            location.reload();
                            return;
                        }
                        schedulePoll(3000);
                    })
                    .catch(() => schedulePoll(5000));
            }

            schedulePoll(3000);
            window.addEventListener('pagehide', () => {
                stopped = true;
                clearPollTimer();
            });
            window.addEventListener('pageshow', (event) => {
                if (!event.persisted || !badge) return;
                stopped = false;
                poll();
            });
        })();
    
