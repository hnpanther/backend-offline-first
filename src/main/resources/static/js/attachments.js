/*
 * Attachment gallery + upload control for the admin panel.
 *
 * No external dependency: everything here is plain DOM, and the lightbox is a few lines of CSS
 * rather than a library. The whole product has to run on an air-gapped plant network, so a CDN
 * script tag would be a latent outage, not a convenience.
 *
 * Two independent pieces live in this file because they share the thumbnail markup:
 *   1. initGalleries()  — read-only viewing (log sheet detail, voided submission)
 *   2. initUploaders()  — the fill page's capture/upload control
 */
(function () {
    'use strict';

    // -----------------------------------------------------------------------
    // Lightbox
    // -----------------------------------------------------------------------

    var overlay = null;

    function ensureOverlay() {
        if (overlay) return overlay;
        overlay = document.createElement('div');
        overlay.className = 'att-lightbox';
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true');
        overlay.innerHTML =
            '<button type="button" class="att-lightbox-close" aria-label="بستن">&times;</button>' +
            '<div class="att-lightbox-body"></div>' +
            '<div class="att-lightbox-caption"></div>';
        overlay.addEventListener('click', function (e) {
            // Click anywhere outside the media itself closes — the usual expectation, and it
            // matters here because the panel is often driven one-handed on a tablet.
            if (e.target === overlay || e.target.classList.contains('att-lightbox-close')) {
                closeLightbox();
            }
        });
        document.body.appendChild(overlay);
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') closeLightbox();
        });
        return overlay;
    }

    function closeLightbox() {
        if (!overlay) return;
        // Emptying the body stops any playing audio/video. Leaving it would keep a voice note
        // talking behind a closed overlay.
        overlay.querySelector('.att-lightbox-body').innerHTML = '';
        overlay.classList.remove('is-open');
        document.body.classList.remove('att-lightbox-open');
    }

    function openLightbox(kind, url, caption) {
        var box = ensureOverlay();
        var body = box.querySelector('.att-lightbox-body');
        body.innerHTML = '';

        var el;
        if (kind === 'VIDEO') {
            el = document.createElement('video');
            el.controls = true;
            el.autoplay = true;
            el.playsInline = true;
        } else if (kind === 'AUDIO') {
            el = document.createElement('audio');
            el.controls = true;
            el.autoplay = true;
        } else {
            el = document.createElement('img');
            el.alt = caption || 'پیوست';
        }
        el.src = url;
        body.appendChild(el);
        box.querySelector('.att-lightbox-caption').textContent = caption || '';
        box.classList.add('is-open');
        document.body.classList.add('att-lightbox-open');
    }

    function initGalleries(root) {
        (root || document).querySelectorAll('.att-thumb[data-att-url]').forEach(function (thumb) {
            if (thumb.dataset.attBound === '1') return;
            thumb.dataset.attBound = '1';
            thumb.addEventListener('click', function (e) {
                e.preventDefault();
                openLightbox(thumb.dataset.attKind, thumb.dataset.attUrl, thumb.dataset.attCaption);
            });
        });
    }

    // -----------------------------------------------------------------------
    // Upload control (fill page)
    // -----------------------------------------------------------------------

    /** Long edge cap and quality, mirroring the mobile app so both produce similar files. */
    var MAX_IMAGE_DIMENSION = 1600;
    var IMAGE_QUALITY = 0.8;

    /** Delegates to the shared helper in csrf.js (loaded ahead of this file by the layout). */
    function csrf() {
        if (window.AppCsrf) return window.AppCsrf.token();
        var token = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        if (!token || !header) return null;
        return { header: header.getAttribute('content'), token: token.getAttribute('content') };
    }

    function formatBytes(bytes) {
        if (bytes == null) return '';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    }

    /**
     * Re-encodes an image down to the shared cap before upload.
     *
     * A supervisor attaching a photo straight off a phone would otherwise push 8–12 MP through
     * the panel, blow the server's per-kind ceiling, and store something nobody needs at that
     * size. Failing to encode returns the original rather than blocking the upload — the server
     * cap is the real guard, this is the optimisation.
     */
    function compressImage(file) {
        return new Promise(function (resolve) {
            if (!file.type || file.type.indexOf('image/') !== 0 || typeof createImageBitmap !== 'function') {
                resolve(file);
                return;
            }
            createImageBitmap(file).then(function (bitmap) {
                var longest = Math.max(bitmap.width, bitmap.height);
                var scale = longest > MAX_IMAGE_DIMENSION ? MAX_IMAGE_DIMENSION / longest : 1;
                var w = Math.max(1, Math.round(bitmap.width * scale));
                var h = Math.max(1, Math.round(bitmap.height * scale));
                var canvas = document.createElement('canvas');
                canvas.width = w;
                canvas.height = h;
                var ctx = canvas.getContext('2d');
                if (!ctx) { bitmap.close && bitmap.close(); resolve(file); return; }
                ctx.drawImage(bitmap, 0, 0, w, h);
                bitmap.close && bitmap.close();
                canvas.toBlob(function (blob) {
                    resolve(blob ? { blob: blob, width: w, height: h } : file);
                }, 'image/webp', IMAGE_QUALITY);
            }).catch(function () { resolve(file); });
        });
    }

    /**
     * Reads a media file's duration so an over-length clip is refused before it is uploaded.
     * Resolves null when the browser cannot determine it — the server then falls back to its
     * byte ceiling rather than rejecting evidence over missing metadata.
     */
    function readDuration(file, kind) {
        return new Promise(function (resolve) {
            if (kind !== 'AUDIO' && kind !== 'VIDEO') { resolve(null); return; }
            var el = document.createElement(kind === 'VIDEO' ? 'video' : 'audio');
            var url = URL.createObjectURL(file);
            var done = function (value) {
                URL.revokeObjectURL(url);
                resolve(value);
            };
            el.preload = 'metadata';
            el.onloadedmetadata = function () {
                done(isFinite(el.duration) ? Math.round(el.duration * 1000) : null);
            };
            el.onerror = function () { done(null); };
            setTimeout(function () { done(null); }, 5000);
            el.src = url;
        });
    }

    function initUploaders(root) {
        (root || document).querySelectorAll('.att-uploader').forEach(function (box) {
            if (box.dataset.attBound === '1') return;
            box.dataset.attBound = '1';

            var input = box.querySelector('.att-file-input');
            var button = box.querySelector('.att-add-btn');
            var list = box.querySelector('.att-items');
            var status = box.querySelector('.att-status');
            var kind = box.dataset.kind;
            var maxCount = parseInt(box.dataset.maxCount, 10) || 1;
            var maxSeconds = parseInt(box.dataset.maxSeconds, 10) || 0;

            function count() {
                return list.querySelectorAll('.att-item').length;
            }

            function refreshButton() {
                var full = count() >= maxCount;
                button.disabled = full;
                button.classList.toggle('disabled', full);
                box.querySelector('.att-count').textContent = count() + ' / ' + maxCount;
                if (full) {
                    setStatus('به حداکثر تعداد مجاز رسیده‌اید.', 'text-muted');
                }
            }

            function setStatus(text, cls) {
                status.textContent = text || '';
                status.className = 'att-status small ' + (cls || 'text-muted');
            }

            button.addEventListener('click', function () { input.click(); });

            input.addEventListener('change', function () {
                var file = input.files && input.files[0];
                input.value = '';
                if (!file) return;
                if (count() >= maxCount) {
                    setStatus('به حداکثر تعداد مجاز رسیده‌اید.', 'text-danger');
                    return;
                }
                upload(file);
            });

            function upload(file) {
                setStatus('در حال بررسی فایل…', 'text-muted');
                button.disabled = true;

                readDuration(file, kind).then(function (durationMs) {
                    if (maxSeconds > 0 && durationMs != null && durationMs > maxSeconds * 1000 + 1000) {
                        setStatus('مدت این فایل بیش از حد مجاز است (حداکثر ' + maxSeconds + ' ثانیه).', 'text-danger');
                        refreshButton();
                        return null;
                    }
                    return compressImage(file).then(function (result) {
                        var blob = result && result.blob ? result.blob : file;
                        var form = new FormData();
                        form.append('assetId', box.dataset.assetId);
                        form.append('fieldKey', box.dataset.fieldKey);
                        form.append('file', blob, file.name || 'capture');
                        if (durationMs != null) form.append('durationMs', String(durationMs));
                        if (result && result.width) {
                            form.append('width', String(result.width));
                            form.append('height', String(result.height));
                        }

                        var headers = {};
                        var c = csrf();
                        if (c) headers[c.header] = c.token;

                        setStatus('در حال بارگذاری…', 'text-muted');
                        return fetch(box.dataset.uploadUrl, {
                            method: 'POST',
                            body: form,
                            headers: headers,
                            credentials: 'same-origin'
                        }).then(function (res) {
                            return res.json().then(function (body) {
                                if (!res.ok) throw new Error(body && body.message ? body.message : 'بارگذاری ناموفق بود.');
                                return body;
                            }).catch(function (err) {
                                if (!res.ok) throw new Error('بارگذاری ناموفق بود.');
                                throw err;
                            });
                        });
                    });
                }).then(function (dto) {
                    if (!dto) return;
                    addItem(dto);
                    setStatus('افزوده شد.', 'text-success');
                }).catch(function (err) {
                    setStatus(err && err.message ? err.message : 'بارگذاری ناموفق بود.', 'text-danger');
                }).finally(function () {
                    refreshButton();
                });
            }

            function addItem(dto) {
                var url = box.dataset.viewUrlPrefix + dto.id;
                var item = document.createElement('div');
                item.className = 'att-item';

                var thumb = document.createElement('button');
                thumb.type = 'button';
                thumb.className = 'att-thumb';
                thumb.dataset.attKind = dto.kind;
                thumb.dataset.attUrl = url;
                thumb.dataset.attCaption = formatBytes(dto.sizeBytes);
                if (dto.kind === 'IMAGE') {
                    var img = document.createElement('img');
                    img.src = url;
                    img.alt = 'پیوست';
                    thumb.appendChild(img);
                } else {
                    var icon = document.createElement('i');
                    icon.className = dto.kind === 'AUDIO' ? 'bi bi-mic-fill' : 'bi bi-camera-video-fill';
                    thumb.appendChild(icon);
                }
                item.appendChild(thumb);

                var meta = document.createElement('span');
                meta.className = 'att-item-meta';
                meta.textContent = formatBytes(dto.sizeBytes);
                item.appendChild(meta);

                var hidden = document.createElement('input');
                hidden.type = 'hidden';
                hidden.name = box.dataset.fieldName;
                hidden.value = dto.id;
                item.appendChild(hidden);

                var del = document.createElement('button');
                del.type = 'button';
                del.className = 'att-remove btn btn-sm btn-link text-danger p-0';
                del.innerHTML = '<i class="bi bi-trash"></i>';
                del.addEventListener('click', function () { remove(item, dto.id); });
                item.appendChild(del);

                list.appendChild(item);
                initGalleries(item);
            }

            function remove(item, id) {
                var headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
                var c = csrf();
                if (c) headers[c.header] = c.token;
                fetch(box.dataset.deleteUrlPrefix + id + '/delete', {
                    method: 'POST',
                    headers: headers,
                    credentials: 'same-origin'
                }).then(function (res) {
                    if (!res.ok) throw new Error();
                    item.remove();
                    setStatus('حذف شد.', 'text-muted');
                    refreshButton();
                }).catch(function () {
                    setStatus('حذف ناموفق بود.', 'text-danger');
                });
            }

            list.querySelectorAll('.att-remove[data-att-id]').forEach(function (btn) {
                btn.addEventListener('click', function () {
                    remove(btn.closest('.att-item'), btn.dataset.attId);
                });
            });

            refreshButton();
        });
    }

    function init() {
        initGalleries(document);
        initUploaders(document);
    }

    // Exposed so a page that replaces markup after load can rebind the parts that live in it.
    // The fill page re-fetches one asset's read-only summary after each save, and the attachment
    // tiles inside it arrive unbound — clicking one would do nothing without this. Both functions
    // are already idempotent (they mark what they have bound), so calling them on a subtree that
    // is partly bound is safe.
    window.AppAttachments = { initGalleries: initGalleries, initUploaders: initUploaders };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
