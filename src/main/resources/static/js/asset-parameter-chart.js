// Axis labels are Latin digits. The Persian face covers no Latin, so the canvas
// must name the face we serve rather than a system font.
const CHART_FONT = 'Vazirmatn Persian, Vazirmatn, sans-serif';
(function () {
    const island = document.getElementById('parameterChartSeries');
    if (!island || typeof Chart === 'undefined') return;
    let series;
    try {
        series = JSON.parse(island.textContent);
    } catch (e) {
        return;
    }
    if (!series || !series.points || !series.points.length) return;

    const labels = series.points.map(p => {
        const d = new Date(p.timestamp);
        return d.toLocaleString('fa-IR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
    });
    const values = series.points.map(p => p.value);

    const datasets = [{
        // A middot, not parentheses: this label is drawn into an RTL canvas where the
        // paired glyphs mirror.
        label: series.fieldLabel + (series.unit ? ' · ' + series.unit : ''),
        data: values,
        borderColor: '#2563eb',
        backgroundColor: 'rgba(37, 99, 235, 0.08)',
        borderWidth: 2.5,
        pointRadius: 4,
        pointHoverRadius: 6,
        pointBackgroundColor: '#2563eb',
        tension: 0.25,
        fill: true
    }];

    function addBand(key, color, label) {
        const min = series[key + 'Min'];
        const max = series[key + 'Max'];
        if (min != null) {
            datasets.push({
                label: label + ' (حداقل)',
                data: labels.map(() => min),
                borderColor: color,
                borderDash: [6, 4],
                borderWidth: 1.5,
                pointRadius: 0,
                fill: false
            });
        }
        if (max != null) {
            datasets.push({
                label: label + ' (حداکثر)',
                data: labels.map(() => max),
                borderColor: color,
                borderDash: [6, 4],
                borderWidth: 1.5,
                pointRadius: 0,
                fill: false
            });
        }
    }
    addBand('warning', '#f59e0b', 'هشدار');
    addBand('danger', '#ef4444', 'خطر');

    new Chart(document.getElementById('parameterChart'), {
        type: 'line',
        data: { labels, datasets },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            plugins: {
                legend: {
                    position: 'bottom',
                    labels: { usePointStyle: true, padding: 16, font: { family: CHART_FONT } }
                },
                tooltip: {
                    rtl: true,
                    titleFont: { family: CHART_FONT },
                    bodyFont: { family: CHART_FONT }
                }
            },
            scales: {
                x: {
                    grid: { display: false },
                    ticks: { font: { family: CHART_FONT, size: 11 }, maxRotation: 45 }
                },
                y: {
                    grid: { color: 'rgba(148, 163, 184, 0.2)' },
                    ticks: { font: { family: CHART_FONT } }
                }
            }
        }
    });
})();
