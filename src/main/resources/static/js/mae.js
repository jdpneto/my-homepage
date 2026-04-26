(function () {
    'use strict';

    const dropzone = document.getElementById('dropzone');
    if (dropzone) initUploader();

    function initUploader() {
        const fileInput = document.getElementById('file-input');
        const list = document.getElementById('upload-list');
        const nameInput = document.getElementById('uploader-name');
        const captionInput = document.getElementById('batch-caption');
        const csrfToken = document.getElementById('csrf-token').value;
        const csrfName = document.getElementById('csrf-name').value;

        const cookieName = 'mae_contributor';
        const cookieMatch = document.cookie.split('; ').find(r => r.startsWith(cookieName + '='));
        if (cookieMatch) nameInput.value = decodeURIComponent(cookieMatch.split('=')[1]);
        nameInput.addEventListener('change', () => {
            const v = encodeURIComponent(nameInput.value || '');
            document.cookie = cookieName + '=' + v + '; max-age=' + (60 * 60 * 24 * 365) + '; path=/mae; secure; samesite=lax';
        });

        dropzone.addEventListener('click', () => fileInput.click());
        ['dragenter', 'dragover'].forEach(ev =>
            dropzone.addEventListener(ev, e => { e.preventDefault(); dropzone.classList.add('drag'); }));
        ['dragleave', 'drop'].forEach(ev =>
            dropzone.addEventListener(ev, e => { e.preventDefault(); dropzone.classList.remove('drag'); }));
        dropzone.addEventListener('drop', e => uploadAll(e.dataTransfer.files));
        fileInput.addEventListener('change', () => uploadAll(fileInput.files));

        let inflight = 0;
        const queue = [];
        const MAX = 3;

        function uploadAll(files) {
            for (const f of files) queue.push(f);
            pump();
        }

        function pump() {
            while (inflight < MAX && queue.length > 0) {
                inflight++;
                uploadOne(queue.shift()).finally(() => { inflight--; pump(); });
            }
        }

        function buildRow(filename) {
            const row = document.createElement('div');
            row.className = 'upload-row';
            const nameSpan = document.createElement('span');
            nameSpan.textContent = filename;
            const progress = document.createElement('progress');
            progress.max = 100; progress.value = 0;
            const status = document.createElement('span');
            status.className = 'status';
            row.appendChild(nameSpan);
            row.appendChild(progress);
            row.appendChild(status);
            return { row: row, progress: progress, status: status };
        }

        function setStatusLink(statusEl, href, text) {
            statusEl.textContent = '';
            const a = document.createElement('a');
            a.setAttribute('href', href);
            a.textContent = text;
            statusEl.appendChild(a);
        }

        function uploadOne(file) {
            const built = buildRow(file.name);
            list.appendChild(built.row);

            return new Promise(resolve => {
                const fd = new FormData();
                fd.append('file', file);
                if (nameInput.value) fd.append('uploaderName', nameInput.value);
                if (captionInput.value) fd.append('caption', captionInput.value);
                fd.append(csrfName, csrfToken);

                const xhr = new XMLHttpRequest();
                xhr.open('POST', '/mae/api/items');
                xhr.upload.onprogress = e => { if (e.lengthComputable) built.progress.value = (e.loaded / e.total) * 100; };
                xhr.onload = () => {
                    if (xhr.status >= 200 && xhr.status < 300) {
                        let arr = [];
                        try { arr = JSON.parse(xhr.responseText); } catch (e) {}
                        const r = arr[0] || {};
                        if (r.error) {
                            built.status.textContent = '✗ ' + String(r.error);
                        } else if (r.deduped) {
                            setStatusLink(built.status, '/mae/item/' + Number(r.id), 'already in gallery');
                        } else {
                            setStatusLink(built.status, '/mae/item/' + Number(r.id), '✓ added');
                        }
                    } else {
                        built.status.textContent = '✗ ' + xhr.status;
                    }
                    resolve();
                };
                xhr.onerror = () => { built.status.textContent = '✗ network error'; resolve(); };
                xhr.send(fd);
            });
        }
    }

    const lightbox = document.querySelector('.lightbox[data-item-id]');
    if (lightbox) initLightbox();

    function initLightbox() {
        const id = lightbox.getAttribute('data-item-id');
        const display = lightbox.querySelector('.caption-display');
        const edit = lightbox.querySelector('.caption-edit');
        const editBtn = lightbox.querySelector('.btn-edit-caption');
        const saveBtn = lightbox.querySelector('.btn-save-caption');
        const cancelBtn = lightbox.querySelector('.btn-cancel-caption');
        const deleteBtn = lightbox.querySelector('.btn-delete');
        const csrfToken = getCookie('XSRF-TOKEN');

        editBtn.addEventListener('click', () => {
            display.style.display = 'none';
            edit.style.display = 'block';
            editBtn.style.display = 'none';
            saveBtn.style.display = 'inline-block';
            cancelBtn.style.display = 'inline-block';
            edit.focus();
        });
        cancelBtn.addEventListener('click', () => resetEdit());
        saveBtn.addEventListener('click', () => {
            fetch('/mae/api/items/' + Number(id), {
                method: 'PATCH',
                headers: {'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken},
                body: JSON.stringify({caption: edit.value})
            }).then(r => r.json()).then(j => {
                display.textContent = j.caption || 'No caption yet — click edit to add one.';
                resetEdit();
            }).catch(() => alert('Save failed'));
        });

        function resetEdit() {
            display.style.display = 'block';
            edit.style.display = 'none';
            editBtn.style.display = 'inline-block';
            saveBtn.style.display = 'none';
            cancelBtn.style.display = 'none';
        }

        if (deleteBtn) deleteBtn.addEventListener('click', () => {
            if (!confirm('Delete this item permanently?')) return;
            fetch('/mae/api/items/' + Number(id), {
                method: 'DELETE',
                headers: {'X-XSRF-TOKEN': csrfToken}
            }).then(r => {
                if (r.status === 204) window.location.href = '/mae';
                else alert('Delete failed: ' + r.status);
            });
        });

        document.addEventListener('keydown', e => {
            const prev = lightbox.querySelector('.nav-prev');
            const next = lightbox.querySelector('.nav-next');
            if (e.key === 'ArrowLeft' && prev) window.location.href = prev.getAttribute('href');
            if (e.key === 'ArrowRight' && next) window.location.href = next.getAttribute('href');
            if (e.key === 'Escape') window.history.back();
        });
    }

    function getCookie(name) {
        const m = document.cookie.match('(^|;)\\s*' + name + '\\s*=\\s*([^;]+)');
        return m ? decodeURIComponent(m[2]) : '';
    }
})();
