/*
 * The panel's signature field (roadmap §32): a pad, not a camera.
 *
 * The drawing is a PNG uploaded to the fill page's ordinary attachment endpoint. The upload does
 * NOT make it the field's answer — the server never adopts a signature into a reading on upload —
 * it only returns an id, which goes into the field's hidden input. The answer changes when the
 * dialog (or the round-level form) is SAVED, because only a save path can stamp who signed, when,
 * and the hash of the answers the signature covers. Nothing here computes a hash: two
 * canonicalisations would drift, and every signature would then read as stale (SignatureValues).
 *
 * «حذف امضا» empties the hidden input rather than deleting the file: saving then clears the field,
 * and the previous signature stays in the history like any replaced answer. An empty value is
 * what clears; an absent key would leave the old answer standing.
 *
 * Pointer events, so a mouse, a pen and a finger all draw. The canvas is filled white first, so
 * the stored image reads the same on any background a later viewer puts it on.
 */
(function (global) {
    'use strict';

    function csrfHeaders() {
        var headers = {};
        if (global.AppCsrf && global.AppCsrf.token) {
            var c = global.AppCsrf.token();
            if (c) headers[c.header] = c.token;
            return headers;
        }
        var token = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        if (token && header) headers[header.getAttribute('content')] = token.getAttribute('content');
        return headers;
    }

    function show(el, on) { if (el) el.classList.toggle('d-none', !on); }

    function initPad(box) {
        if (!box || box.dataset.sigReady === '1') return;
        box.dataset.sigReady = '1';

        var value = box.querySelector('.sig-value');
        var preview = box.querySelector('.sig-preview');
        var board = box.querySelector('.sig-board');
        var canvas = box.querySelector('.sig-canvas');
        var start = box.querySelector('.sig-start');
        var accept = box.querySelector('.sig-accept');
        var clear = box.querySelector('.sig-clear');
        var cancel = box.querySelector('.sig-cancel');
        var remove = box.querySelector('.sig-remove');
        var status = box.querySelector('.sig-status');
        var ctx = canvas.getContext('2d');
        var drawing = false;
        var hasInk = false;
        var last = null;

        function setStatus(text, cls) {
            status.textContent = text || '';
            status.className = 'sig-status small mt-1 ' + (cls || 'text-muted');
        }

        function blank() {
            ctx.fillStyle = '#ffffff';
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            ctx.strokeStyle = '#111827';
            ctx.lineWidth = 2.6;
            ctx.lineCap = 'round';
            ctx.lineJoin = 'round';
            hasInk = false;
            accept.disabled = true;
        }

        function point(event) {
            var rect = canvas.getBoundingClientRect();
            return {
                x: (event.clientX - rect.left) * (canvas.width / rect.width),
                y: (event.clientY - rect.top) * (canvas.height / rect.height)
            };
        }

        canvas.addEventListener('pointerdown', function (event) {
            event.preventDefault();
            drawing = true;
            last = point(event);
            if (canvas.setPointerCapture) canvas.setPointerCapture(event.pointerId);
            // A dot, so a tap leaves a mark the way a pen would.
            ctx.beginPath();
            ctx.arc(last.x, last.y, ctx.lineWidth / 2, 0, Math.PI * 2);
            ctx.fillStyle = ctx.strokeStyle;
            ctx.fill();
            hasInk = true;
            accept.disabled = false;
        });
        canvas.addEventListener('pointermove', function (event) {
            if (!drawing) return;
            event.preventDefault();
            var p = point(event);
            ctx.beginPath();
            ctx.moveTo(last.x, last.y);
            ctx.lineTo(p.x, p.y);
            ctx.stroke();
            last = p;
        });
        function stop() { drawing = false; last = null; }
        canvas.addEventListener('pointerup', stop);
        canvas.addEventListener('pointercancel', stop);
        canvas.addEventListener('pointerleave', stop);

        function mode(drawingMode) {
            show(board, drawingMode);
            show(accept, drawingMode);
            show(clear, drawingMode);
            show(cancel, drawingMode);
            show(start, !drawingMode);
            show(remove, !drawingMode && !!value.value);
        }

        function setPreview(id) {
            var img = preview.querySelector('img');
            if (!id) {
                if (img) img.remove();
                show(preview, false);
                return;
            }
            if (!img) {
                img = document.createElement('img');
                img.className = 'sig-image';
                img.alt = 'امضای ثبت‌شده';
                preview.appendChild(img);
            }
            img.src = box.dataset.viewUrlPrefix + encodeURIComponent(id);
            show(preview, true);
        }

        function announce() {
            // The fill page tracks unsaved edits through events; a programmatic value change fires
            // none, so say so — otherwise leaving the page would not warn about a fresh signature.
            value.dispatchEvent(new Event('change', {bubbles: true}));
            value.dispatchEvent(new Event('input', {bubbles: true}));
        }

        start.addEventListener('click', function () {
            blank();
            mode(true);
            setStatus('در کادر امضا کنید.', 'text-muted');
        });
        clear.addEventListener('click', function () { blank(); });
        cancel.addEventListener('click', function () { mode(false); setStatus(''); });

        remove.addEventListener('click', function () {
            value.value = '';
            setPreview(null);
            mode(false);
            setStatus('امضا با ذخیره حذف می‌شود. امضای قبلی در تاریخچه می‌ماند.', 'text-warning');
            announce();
        });

        accept.addEventListener('click', function () {
            if (!hasInk) return;
            accept.disabled = true;
            setStatus('در حال بارگذاری امضا…', 'text-muted');
            canvas.toBlob(function (blob) {
                if (!blob) {
                    setStatus('تصویر امضا ساخته نشد.', 'text-danger');
                    accept.disabled = false;
                    return;
                }
                var form = new FormData();
                // Omitted for a round-level question, which belongs to no asset: an empty string
                // would reach the server as a blank Long and fail to bind.
                if (box.dataset.assetId) form.append('assetId', box.dataset.assetId);
                form.append('fieldKey', box.dataset.fieldKey);
                form.append('file', blob, 'signature.png');
                form.append('width', String(canvas.width));
                form.append('height', String(canvas.height));
                fetch(box.dataset.uploadUrl, {
                    method: 'POST',
                    body: form,
                    headers: csrfHeaders(),
                    credentials: 'same-origin',
                    // An expired session answers a POST with a redirect to the login page; fetch
                    // would follow it into a 200 carrying HTML. Manual turns that into a failure.
                    redirect: 'manual'
                }).then(function (res) {
                    if (res.type === 'opaqueredirect' || res.status === 0) {
                        throw new Error('نشست شما به پایان رسیده است؛ دوباره وارد شوید.');
                    }
                    return res.json().catch(function () { return null; }).then(function (body) {
                        if (!res.ok || !body || !body.id) {
                            throw new Error(body && body.message ? body.message : 'بارگذاری امضا ناموفق بود.');
                        }
                        return body;
                    });
                }).then(function (dto) {
                    value.value = dto.id;
                    setPreview(dto.id);
                    mode(false);
                    setStatus('امضا آماده است — با «ثبت مقادیر» ذخیره می‌شود.', 'text-success');
                    announce();
                }).catch(function (err) {
                    setStatus(err && err.message ? err.message : 'بارگذاری امضا ناموفق بود.', 'text-danger');
                    accept.disabled = false;
                });
            }, 'image/png');
        });
    }

    function init(root) {
        (root || document).querySelectorAll('.sig-pad').forEach(initPad);
    }

    global.SignaturePad = {init: init};
    document.addEventListener('DOMContentLoaded', function () { init(document); });
})(window);
