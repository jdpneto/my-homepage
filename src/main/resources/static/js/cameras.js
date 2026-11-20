/*
 * /admin/cameras: infinite scroll, full-screen player, Keep/Delete/Download, alerts switch.
 *
 * Markup contract: templates/admin/cameras.html. Rows are li.clip with data-id, data-start
 * (ISO with the clip's own offset), data-camera, data-kept ("true"/"false"), data-triggers
 * (comma list); each row's content sits in a button.clip-open, which is what takes focus.
 * #clips carries data-next (cursor, absent when there is no further page) and data-query
 * (current filter as a query string, absent when there is no filter).
 *
 * API used (all under /api/cameras/): clips (paged list), clips/{id} (+ /video, /thumb,
 * /download, /keep), auth/revoke-all, alerts/test, alerts/mute, status (signed-in probe).
 *
 * Data from the server only ever reaches the DOM through textContent / attributes.
 *
 * Busy buttons get aria-disabled="true" instead of the `disabled` property: disabling the
 * focused button would blur it, dropping keyboard and screen-reader focus to <body>.
 */
(function () {
    'use strict';

    const API = '/api/cameras';
    const clips = document.getElementById('clips');
    if (!clips) return;

    const sentinel = document.getElementById('clips-sentinel');
    const rowTemplate = document.getElementById('clip-row');
    const banner = document.getElementById('banner');
    const toastEl = document.getElementById('toast');

    const player = document.getElementById('player');
    const playerTitle = player.querySelector('.player-title');
    const video = player.querySelector('.player-video');
    const playerMessage = player.querySelector('.player-message');
    // Polite live region inside the dialog. While the player is open (aria-modal) screen
    // readers ignore everything outside it, #toast and #banner included.
    const playerLive = player.querySelector('.player-live');
    const btnClose = player.querySelector('.player-close');
    const btnPrev = player.querySelector('.player-prev');
    const btnNext = player.querySelector('.player-next');
    const btnKeep = player.querySelector('.player-keep');
    const btnDelete = player.querySelector('.player-delete');
    const linkDownload = player.querySelector('.player-download');

    const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const KEPT_MESSAGE = 'This clip is kept — unkeep it to delete.';
    const SIGNED_OUT = 'Signed out — reload to sign in';
    const SWIPE_MIN_PX = 60;
    const CONFIRM_MS = 4000;       // the armed "Yes, delete" falls back to "Delete" after this
    const CONFIRM_MIN_MS = 500;    // ...and ignores clicks sooner than this (double-click/tap)
    const RETRY_MIN_MS = 5000;     // infinite scroll: wait after a page that added nothing
    const RETRY_MAX_MS = 60000;
    const CLIP_ID = /^\d+$/;

    // ---------------------------------------------------------------- time

    function pad2(n) {
        return String(n).padStart(2, '0');
    }

    /**
     * Splits "2026-11-18T21:49:41+01:00" into its wall-clock fields and offset, without going
     * through the browser's time zone: times are shown as the camera recorded them.
     * Seconds and fractions are optional (Java omits ":00" seconds in OffsetDateTime.toString()).
     */
    function parseStart(iso) {
        const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$/
            .exec(iso || '');
        if (!m) return null;
        let offsetMin = 0;
        if (m[7] && m[7] !== 'Z') {
            const sign = m[7][0] === '-' ? -1 : 1;
            const digits = m[7].slice(1).replace(':', '');
            offsetMin = sign * (Number(digits.slice(0, 2)) * 60 + Number(digits.slice(2, 4)));
        }
        return {
            y: Number(m[1]), mo: Number(m[2]), d: Number(m[3]),
            h: Number(m[4]), mi: Number(m[5]), s: Number(m[6] || 0),
            offsetMin: offsetMin
        };
    }

    function wallTime(p) {
        return pad2(p.h) + ':' + pad2(p.mi) + ':' + pad2(p.s);
    }

    function dayKey(p) {
        return p.y + '-' + pad2(p.mo) + '-' + pad2(p.d);
    }

    /** "Wed 18 Nov" for a calendar date (weekday computed in UTC so no zone can shift it). */
    function dateLabel(y, mo, d) {
        const weekday = new Date(Date.UTC(y, mo - 1, d)).getUTCDay();
        return DAYS[weekday] + ' ' + d + ' ' + MONTHS[mo - 1];
    }

    /** Same rule as the server: "Today"/"Yesterday" judged in the clip's own offset. */
    function dayLabel(p) {
        // "now" moved into the clip's offset, then read with UTC getters = wall clock there.
        const now = new Date(Date.now() + p.offsetMin * 60000);
        const today = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
        const day = Date.UTC(p.y, p.mo - 1, p.d);
        if (day === today) return 'Today';
        if (day === today - 86400000) return 'Yesterday';
        return dateLabel(p.y, p.mo, p.d);
    }

    // ---------------------------------------------------------------- feedback

    const pendingSpeech = new Map(); // live region -> requestAnimationFrame id

    /**
     * Writes `text` into a live region so it is announced, even when it repeats the last
     * message: clear first, then set the text on the next frame.
     */
    function speak(region, text) {
        silence(region);
        pendingSpeech.set(region, requestAnimationFrame(() => {
            pendingSpeech.delete(region);
            region.textContent = text;
        }));
    }

    /** Empties a live region and drops any text still waiting to be written into it. */
    function silence(region) {
        cancelAnimationFrame(pendingSpeech.get(region));
        pendingSpeech.delete(region);
        region.textContent = '';
    }

    /** Also says `text` inside the player while it is open (see playerLive). */
    function announce(text) {
        if (playerLive && !player.hidden && text) speak(playerLive, text);
    }

    function showBanner(text) {
        banner.textContent = text;
        banner.setAttribute('role', 'alert');
        banner.hidden = false;
        syncBannerSpace();
        announce(text);
    }

    /**
     * While the player is open the banner floats above it (see cameras.css); the player
     * gets top padding of the banner's height so the banner never covers the clip title.
     */
    function syncBannerSpace() {
        const h = !banner.hidden && !player.hidden ? Math.ceil(banner.getBoundingClientRect().height) : 0;
        player.style.setProperty('--banner-space', h ? (h + 8) + 'px' : '0px');
    }

    window.addEventListener('resize', syncBannerSpace);

    // #toast stays rendered (and so in the accessibility tree) the whole time; CSS hides it
    // while it is empty. A live region that appears together with its text is often not read.
    toastEl.hidden = false;
    toastEl.textContent = '';
    let toastTimer = 0;

    function toast(text, isError) {
        clearTimeout(toastTimer);
        toastEl.classList.toggle('error', !!isError);
        speak(toastEl, text);
        // Errors stay longer: they may need reading twice.
        toastTimer = setTimeout(() => { toastEl.textContent = ''; }, isError ? 8000 : 4000);
        announce(text);
    }

    /**
     * Runs `task` with `el` marked busy (aria-disabled, see the header); clicks on a busy
     * button are ignored.
     */
    async function whileBusy(el, task) {
        if (isBusy(el)) return;
        el.setAttribute('aria-disabled', 'true');
        try {
            await task();
        } finally {
            el.removeAttribute('aria-disabled');
        }
    }

    function isBusy(el) {
        return el.getAttribute('aria-disabled') === 'true';
    }

    // ---------------------------------------------------------------- API

    class ApiError extends Error {
        constructor(status, handled) {
            super('HTTP ' + status);
            this.status = status;
            this.handled = handled; // true when the user has already been told (401 banner, network)
        }
    }

    /** Value of the XSRF-TOKEN cookie, read fresh each time (it can rotate after login). */
    function csrfToken() {
        const cookie = document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='));
        if (cookie) return decodeURIComponent(cookie.slice('XSRF-TOKEN='.length));
        const meta = document.querySelector('meta[name="csrf"]');
        return meta ? meta.content : '';
    }

    /**
     * fetch() wrapper: same-origin with the session cookie, CSRF header on writes. Resolves with
     * the parsed JSON (null for 204); rejects with ApiError. 401 shows the signed-out banner.
     */
    async function api(path, method, json) {
        method = method || 'GET';
        const headers = { 'Accept': 'application/json' };
        const init = { method: method, headers: headers, credentials: 'same-origin', cache: 'no-store' };
        if (method !== 'GET') headers['X-XSRF-TOKEN'] = csrfToken();
        if (json !== undefined) {
            headers['Content-Type'] = 'application/json';
            init.body = JSON.stringify(json);
        }
        let res;
        try {
            res = await fetch(API + path, init);
        } catch (e) {
            toast('Network error — check the connection', true);
            throw new ApiError(0, true);
        }
        if (res.status === 401) {
            showBanner(SIGNED_OUT);
            throw new ApiError(401, true);
        }
        // A write refused with 403 usually means the session (and its CSRF cookie) ended,
        // e.g. a logout in another tab. Ask a read-only endpoint: 401 there = signed out.
        // The write is not retried.
        if (res.status === 403 && method !== 'GET' && await signedOut()) {
            showBanner(SIGNED_OUT);
            throw new ApiError(401, true);
        }
        if (!res.ok) throw new ApiError(res.status, false);
        if (res.status === 204) return null;
        try {
            return await res.json();
        } catch (e) {
            toast('Unexpected response from the server', true);
            throw new ApiError(res.status, true);
        }
    }

    /** True when GET /status answers 401 (the session is gone). */
    async function signedOut() {
        try {
            const res = await fetch(API + '/status', {
                headers: { 'Accept': 'application/json' }, credentials: 'same-origin', cache: 'no-store'
            });
            return res.status === 401;
        } catch (e) {
            return false;
        }
    }

    /** Generic failure: toast with the status unless the user has already been told. */
    function failed(what, err) {
        if (err instanceof ApiError) {
            if (!err.handled) toast(what + ' failed (' + err.status + ')', true);
        } else {
            toast(what + ' failed', true);
            console.error(err);
        }
    }

    /**
     * API path for a clip. Ids are numeric (Java longs); anything else is refused, so an id
     * such as ".." can never become a dot-segment that sends a write to another endpoint.
     */
    function clipPath(id, suffix) {
        const s = String(id);
        if (!CLIP_ID.test(s)) throw new Error('Bad clip id: ' + s);
        return '/clips/' + s + (suffix || '');
    }

    // ---------------------------------------------------------------- rows

    function allRows() {
        return Array.from(clips.querySelectorAll('li.clip'));
    }

    function rowById(id) {
        return allRows().find(r => r.dataset.id === String(id)) || null;
    }

    function setRowKept(row, kept) {
        row.dataset.kept = kept ? 'true' : 'false';
        const star = row.querySelector('.clip-kept');
        if (star) {
            star.textContent = kept ? '★' : '☆';
            star.setAttribute('role', 'img'); // a plain span cannot carry a name
            star.setAttribute('aria-label', kept ? 'kept' : 'not kept');
        }
    }

    /** Focuses the row's button (rows themselves are not focusable). */
    function focusRow(row) {
        (row.querySelector('.clip-open') || row).focus({ preventScroll: true });
    }

    function buildRow(c) {
        const row = rowTemplate.content.firstElementChild.cloneNode(true);
        const triggers = Array.isArray(c.triggers) ? c.triggers : [];
        const p = parseStart(c.start);
        row.dataset.id = String(c.id);
        row.dataset.start = c.start;
        row.dataset.camera = c.camera;
        row.dataset.triggers = triggers.join(',');
        row.querySelector('.thumb').src = API + clipPath(c.id, '/thumb');
        row.querySelector('.clip-time').textContent = p ? wallTime(p) : String(c.start);
        row.querySelector('.clip-camera').textContent = c.camera;
        row.querySelector('.clip-triggers').textContent = triggers.join(', ');
        row.querySelector('.clip-duration').textContent = c.durationSeconds + 's';
        setRowKept(row, c.kept === true);
        return row;
    }

    /**
     * Appends a row under the right day heading: same day as the current last row → that
     * row's list; otherwise a new h3.day + ul.clip-list (same structure as the server's).
     */
    function appendRow(row) {
        const rows = allRows();
        const last = rows[rows.length - 1];
        const p = parseStart(row.dataset.start);
        const lastP = last ? parseStart(last.dataset.start) : null;
        if (last && p && lastP && dayKey(p) === dayKey(lastP)) {
            last.parentElement.appendChild(row);
            return;
        }
        const heading = document.createElement('h3');
        heading.className = 'day';
        heading.textContent = p ? dayLabel(p) : '';
        const list = document.createElement('ul');
        list.className = 'clip-list';
        list.appendChild(row);
        clips.appendChild(heading);
        clips.appendChild(list);
    }

    /** Removes a row, and its day heading + list when that day has no rows left. */
    function removeRow(row) {
        const list = row.parentElement;
        row.remove();
        if (list && !list.querySelector('li.clip')) {
            const heading = list.previousElementSibling;
            if (heading && heading.matches('h3.day')) heading.remove();
            list.remove();
        }
        showEmptyIfNone();
    }

    /**
     * The server's empty state when the list has become empty for good: "No clips match this
     * filter." + "Show all clips" when a filter is on (#clips[data-query]), else "No clips here yet."
     */
    function showEmptyIfNone() {
        if (clips.querySelector('li.clip') || clips.dataset.next || document.querySelector('.cam-empty')) return;
        const empty = document.createElement('p');
        empty.className = 'cam-empty';
        if (clips.dataset.query) {
            empty.appendChild(document.createTextNode('No clips match this filter. '));
            const reset = document.createElement('a');
            reset.className = 'cam-empty-reset';
            reset.href = '/admin/cameras';
            reset.textContent = 'Show all clips';
            empty.appendChild(reset);
        } else {
            empty.textContent = 'No clips here yet.';
        }
        clips.parentElement.insertBefore(empty, clips);
    }

    // ---------------------------------------------------------------- infinite scroll

    let loading = null; // the in-flight page load, shared by the observer and the player

    /** Loads the next page (if any). Resolves to the number of rows added. */
    function loadMore() {
        if (loading) return loading;
        const cursor = clips.dataset.next;
        if (!cursor) return Promise.resolve(0);
        const query = clips.dataset.query || '';
        const path = '/clips?' + (query ? query + '&' : '') + 'before=' + encodeURIComponent(cursor) + '&limit=50';
        clips.setAttribute('aria-busy', 'true');
        loading = api(path)
            .then(body => {
                let added = 0;
                for (const c of body.clips || []) {
                    if (!c || !CLIP_ID.test(String(c.id))) continue; // malformed id: never in the DOM
                    if (rowById(c.id)) continue; // never show a clip twice
                    appendRow(buildRow(c));
                    added++;
                }
                if (body.nextBefore) {
                    clips.dataset.next = body.nextBefore;
                } else {
                    delete clips.dataset.next;
                    stopScrolling();
                    showEmptyIfNone();
                }
                return added;
            })
            .catch(err => {
                if (err instanceof ApiError && err.status === 401) stopScrolling();
                failed('Loading more clips', err);
                return 0;
            })
            .finally(() => {
                loading = null;
                clips.removeAttribute('aria-busy');
            });
        return loading;
    }

    let observer = null;
    let retryMs = RETRY_MIN_MS;

    /** No more pages, or signed out: stop for good. */
    function stopScrolling() {
        if (observer) observer.disconnect();
        observer = null;
    }

    /**
     * An observer only fires on changes. If the sentinel is still within reach after a page
     * (short page, tall screen, or a failed load), re-observing reports it again at once.
     */
    function rearm() {
        if (!observer || !clips.dataset.next) return;
        observer.unobserve(sentinel);
        observer.observe(sentinel);
    }

    if (sentinel && 'IntersectionObserver' in window && clips.dataset.next) {
        observer = new IntersectionObserver(entries => {
            if (!entries.some(e => e.isIntersecting)) return;
            loadMore().then(added => {
                if (!observer || !clips.dataset.next) return;
                if (added > 0) {
                    retryMs = RETRY_MIN_MS;
                    rearm();
                } else {
                    // Failed (or all duplicates): try again later, backing off up to a minute.
                    setTimeout(rearm, retryMs);
                    retryMs = Math.min(retryMs * 2, RETRY_MAX_MS);
                }
            });
        }, { rootMargin: '600px 0px' });
        observer.observe(sentinel);
    }

    // ---------------------------------------------------------------- player

    let current = null;      // the li.clip shown in the player
    let returnFocus = null;  // element focused before the player opened
    let detachedPrevious = null; // row before `current` while `current` is deleted and the next page loads
    let deleteArmed = false;
    let deleteArmedAt = 0;
    let deleteTimer = 0;

    function isOpen() {
        return !player.hidden;
    }

    function disarmDelete() {
        deleteArmed = false;
        clearTimeout(deleteTimer);
        btnDelete.textContent = 'Delete';
        btnDelete.classList.remove('confirm');
    }

    function showMessage(text) {
        playerMessage.textContent = text;
        playerMessage.hidden = !text;
        announce(text);
    }

    /** Visible text carries the star; the accessible name is the action alone. */
    function renderKeep(kept) {
        btnKeep.textContent = kept ? '★ Unkeep' : '☆ Keep';
        btnKeep.setAttribute('aria-label', kept ? 'Unkeep' : 'Keep');
        btnKeep.classList.toggle('on', kept);
    }

    /** Stops the video and drops its source so the browser stops downloading the clip. */
    function stopVideo() {
        video.pause();
        video.removeAttribute('src');
        video.load();
    }

    function playerTitleFor(row) {
        const p = parseStart(row.dataset.start);
        const when = p ? dateLabel(p.y, p.mo, p.d) + ' ' + wallTime(p) : row.dataset.start;
        const triggers = (row.dataset.triggers || '').split(',').filter(Boolean).join(', ');
        return [row.dataset.camera, when, triggers].filter(Boolean).join(' · ');
    }

    function open(row) {
        if (!row || !row.isConnected || !CLIP_ID.test(row.dataset.id || '')) return;
        const navigating = isOpen();
        if (!navigating) {
            // Opened with nothing focused (e.g. a scripted click): remember the row's button
            // instead of <body>, so close() lands on the row or, if it was deleted, the fallback.
            const active = document.activeElement;
            returnFocus = active && active !== document.body && active !== document.documentElement
                ? active : row.querySelector('.clip-open');
            if (playerLive) silence(playerLive); // never read out a previous clip
            player.hidden = false;
            syncBannerSpace();
        }
        current = row;
        detachedPrevious = null;
        disarmDelete();
        showMessage('');
        const title = playerTitleFor(row);
        playerTitle.textContent = title;
        renderKeep(row.dataset.kept === 'true');
        linkDownload.href = API + clipPath(row.dataset.id, '/download');
        video.src = API + clipPath(row.dataset.id, '/video');
        const playing = video.play();
        if (playing) playing.catch(() => { /* autoplay refused: the controls are there */ });
        if (!player.contains(document.activeElement)) btnClose.focus();
        // On first open the title is read as the dialog's description (aria-describedby);
        // when moving between clips focus stays put, so say which clip is now shown.
        if (navigating) announce('Showing ' + title);
    }

    function close() {
        if (!isOpen()) return;
        stopVideo();
        disarmDelete();
        showMessage('');
        if (playerLive) silence(playerLive);
        player.hidden = true;
        syncBannerSpace();
        const row = current;
        current = null;
        detachedPrevious = null;
        if (row && row.isConnected) {
            row.scrollIntoView({ block: 'nearest' });
            focusRow(row);
        } else if (returnFocus && returnFocus.isConnected && returnFocus !== document.body
                && !player.contains(returnFocus)) {
            returnFocus.focus({ preventScroll: true });
        } else {
            // The row we came from was deleted: land on the list (or the filters) rather than <body>.
            const first = allRows()[0];
            const target = first ? first.querySelector('.clip-open') : document.querySelector('.cam-filters a');
            if (target) target.focus({ preventScroll: true });
        }
        returnFocus = null;
    }

    /** Opens the row after `row`, loading the next page when `row` is the last one loaded. */
    async function openAfter(row) {
        // Deleted, and dropAndAdvance is already waiting for the next page to move on.
        if (!row.isConnected) return;
        let rows = allRows();
        const i = rows.indexOf(row);
        if (i >= 0 && i + 1 < rows.length) return open(rows[i + 1]);
        if (!clips.dataset.next) return toast('No more clips');
        await loadMore();
        if (!isOpen() || current !== row) return; // the user moved on while it loaded
        rows = allRows();
        const j = rows.indexOf(row);
        if (j >= 0 && j + 1 < rows.length) open(rows[j + 1]);
        // A failed load keeps data-next and has shown its own error: do not claim the end.
        else if (!clips.dataset.next) toast('No more clips');
    }

    function openBefore(row) {
        if (!row.isConnected) {
            // Deleted while the next page loads: the row before it is still the previous clip.
            if (detachedPrevious && detachedPrevious.isConnected) open(detachedPrevious);
            else toast('This is the newest clip');
            return;
        }
        const rows = allRows();
        const i = rows.indexOf(row);
        if (i > 0) open(rows[i - 1]);
        else toast('This is the newest clip');
    }

    /**
     * `row` is gone (deleted here, or already gone on the server): remove it and show the clip
     * that followed it, or close the player when there is none.
     */
    async function dropAndAdvance(row) {
        const wasCurrent = isOpen() && current === row;
        const rows = allRows();
        const i = rows.indexOf(row);
        const following = i >= 0 ? rows[i + 1] : null;
        const previous = i > 0 ? rows[i - 1] : null;
        removeRow(row);
        if (!wasCurrent) return;
        if (following) return open(following);
        if (clips.dataset.next) {
            // `current` stays the removed row until the page arrives. Stop its video first:
            // further range requests would 404 and the error handler would report the clip
            // as gone after a delete that worked. (No src = the error handler ignores it.)
            stopVideo();
            linkDownload.removeAttribute('href');
            disarmDelete();
            showMessage('Loading…');
            detachedPrevious = previous;
            const before = allRows().length;
            await loadMore();
            const after = allRows();
            if (isOpen() && current === row && after.length > before) return open(after[before]);
        }
        if (current === row) close();
    }

    /**
     * `row` turned out to be gone on the server (opening it, or Keep, got 404): say so,
     * remove the row and close the player. Unlike a Delete, nothing was asked for, so the
     * player does not jump to some other clip.
     */
    function clipGone(row) {
        toast('Clip no longer exists', true);
        const wasCurrent = isOpen() && current === row;
        removeRow(row);
        if (wasCurrent) close();
    }

    // A row's button (click, Enter or Space) opens the player.
    clips.addEventListener('click', e => {
        const row = e.target.closest('li.clip');
        if (row && clips.contains(row)) open(row);
    });

    btnClose.addEventListener('click', close);
    btnPrev.addEventListener('click', () => { if (current) openBefore(current); });
    btnNext.addEventListener('click', () => { if (current) openAfter(current); });

    // The <video> element hides the HTTP status. When it fails, ask the JSON API why:
    // 404 = deleted elsewhere, 401 = session expired (banner via api()).
    video.addEventListener('error', () => {
        const row = current;
        if (!row || !isOpen() || !video.getAttribute('src')) return;
        api(clipPath(row.dataset.id))
            .then(() => { if (current === row) toast('This video could not be played'); })
            .catch(err => {
                if (err instanceof ApiError && err.status === 404) clipGone(row);
                else failed('Opening the clip', err);
            });
    });

    // Keep / Unkeep
    btnKeep.addEventListener('click', () => {
        const row = current;
        if (!row || !row.isConnected) return; // detached = deleted, the next page is loading
        whileBusy(btnKeep, async () => {
            const keep = row.dataset.kept !== 'true';
            try {
                const view = await api(clipPath(row.dataset.id, '/keep'), keep ? 'PUT' : 'DELETE');
                const kept = view && typeof view.kept === 'boolean' ? view.kept : keep;
                setRowKept(row, kept);
                if (current === row) {
                    renderKeep(kept);
                    if (!kept && playerMessage.textContent === KEPT_MESSAGE) showMessage('');
                }
            } catch (err) {
                if (err instanceof ApiError && err.status === 404) clipGone(row);
                else failed(keep ? 'Keep' : 'Unkeep', err);
            }
        });
    });

    // Delete: kept clips get the message (no API call); others need a second, separate click
    // within 4 s. A click sooner than CONFIRM_MIN_MS after arming is the other half of a
    // double-click or double-tap and is ignored (the button stays armed).
    btnDelete.addEventListener('click', () => {
        const row = current;
        if (!row || !row.isConnected || isBusy(btnDelete)) return;
        if (row.dataset.kept === 'true') {
            disarmDelete();
            showMessage(KEPT_MESSAGE);
            return;
        }
        if (!deleteArmed) {
            deleteArmed = true;
            deleteArmedAt = performance.now();
            btnDelete.textContent = 'Yes, delete';
            btnDelete.classList.add('confirm');
            deleteTimer = setTimeout(disarmDelete, CONFIRM_MS);
            return;
        }
        if (performance.now() - deleteArmedAt < CONFIRM_MIN_MS) return;
        disarmDelete();
        whileBusy(btnDelete, async () => {
            try {
                await api(clipPath(row.dataset.id), 'DELETE');
                await dropAndAdvance(row);
            } catch (err) {
                if (err instanceof ApiError && err.status === 409) {
                    // Kept on another device since this page loaded.
                    setRowKept(row, true);
                    if (current === row) {
                        renderKeep(true);
                        showMessage(KEPT_MESSAGE);
                    }
                } else if (err instanceof ApiError && err.status === 404) {
                    // Already deleted elsewhere: what was asked for, so move on as after a delete.
                    toast('Clip no longer exists', true);
                    await dropAndAdvance(row);
                } else {
                    failed('Delete', err);
                }
            }
        });
    });
    // A held Enter repeats clicks: never let the repeat arm and then confirm the delete.
    btnDelete.addEventListener('keydown', e => {
        if (e.repeat && (e.key === 'Enter' || e.key === ' ')) e.preventDefault();
    });

    // Keys while the player is open. Capture phase, so ←/→ change clip instead of the
    // video's own seek shortcut when the video has focus.
    document.addEventListener('keydown', e => {
        if (!isOpen() || e.altKey || e.ctrlKey || e.metaKey) return;
        if (e.key === 'Escape') {
            e.preventDefault();
            close();
        } else if (e.key === 'ArrowLeft' && current) {
            e.preventDefault();
            openBefore(current);
        } else if (e.key === 'ArrowRight' && current) {
            e.preventDefault();
            openAfter(current);
        } else if (e.key === 'Tab') {
            trapFocus(e);
        }
    }, true);

    /** Keeps Tab inside the dialog (aria-modal). */
    function trapFocus(e) {
        const focusables = Array.from(player.querySelectorAll('button, a[href], video'))
            .filter(el => !el.disabled && !el.hidden && el.offsetParent !== null);
        if (!focusables.length) return;
        const first = focusables[0];
        const last = focusables[focusables.length - 1];
        if (!player.contains(document.activeElement)) {
            e.preventDefault();
            first.focus();
        } else if (e.shiftKey && document.activeElement === first) {
            e.preventDefault();
            last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first.focus();
        }
    }

    // Horizontal swipe ≥ 60 px: left = next clip, right = previous clip.
    let touchStart = null;
    player.addEventListener('touchstart', e => {
        if (e.touches.length !== 1) { touchStart = null; return; }
        const t = e.touches[0];
        // Drags on the video's own control strip (seek bar) are not swipes.
        const box = video.getBoundingClientRect();
        const onControls = t.clientY > box.bottom - 56 && t.clientY <= box.bottom
            && t.clientX >= box.left && t.clientX <= box.right;
        touchStart = onControls ? null : { x: t.clientX, y: t.clientY };
    }, { passive: true });
    player.addEventListener('touchend', e => {
        if (!touchStart || !current) return;
        const t = e.changedTouches[0];
        const dx = t.clientX - touchStart.x;
        const dy = t.clientY - touchStart.y;
        touchStart = null;
        if (Math.abs(dx) < SWIPE_MIN_PX || Math.abs(dx) <= Math.abs(dy)) return;
        if (dx < 0) openAfter(current);
        else openBefore(current);
    }, { passive: true });
    player.addEventListener('touchcancel', () => { touchStart = null; }, { passive: true });

    // ---------------------------------------------------------------- storage panel buttons

    function onClick(id, handler) {
        const el = document.getElementById(id);
        if (!el) return;
        el.addEventListener('click', () => whileBusy(el, handler));
    }

    // "Sign out all app devices" asks first, like Delete: the first click arms "Yes, sign out all"
    // for 4 s; a second click (not the other half of a double-click) does it.
    const revokeBtn = document.getElementById('revoke-all');
    if (revokeBtn) {
        const revokeLabel = revokeBtn.textContent;
        let revokeArmedAt = 0;
        let revokeTimer = null;
        const disarmRevoke = () => {
            clearTimeout(revokeTimer);
            revokeArmedAt = 0;
            revokeBtn.textContent = revokeLabel;
            revokeBtn.classList.remove('confirm');
        };
        revokeBtn.addEventListener('click', () => {
            if (isBusy(revokeBtn)) return;
            if (!revokeArmedAt) {
                revokeArmedAt = performance.now();
                revokeBtn.textContent = 'Yes, sign out all';
                revokeBtn.classList.add('confirm');
                revokeTimer = setTimeout(disarmRevoke, CONFIRM_MS);
                return;
            }
            if (performance.now() - revokeArmedAt < CONFIRM_MIN_MS) return;
            disarmRevoke();
            whileBusy(revokeBtn, async () => {
                try {
                    const body = await api('/auth/revoke-all', 'POST');
                    const n = Number(body && body.revoked) || 0;
                    toast('Signed out ' + n + (n === 1 ? ' app device' : ' app devices'));
                } catch (err) {
                    failed('Signing out app devices', err);
                }
            });
        });
        revokeBtn.addEventListener('keydown', e => {
            if (e.repeat && (e.key === 'Enter' || e.key === ' ')) e.preventDefault();
        });
    }

    onClick('test-alert', async () => {
        try {
            const body = await api('/alerts/test', 'POST');
            toast(body && body.sent ? 'Test alert sent' : 'Not sent — check the ntfy topic', !(body && body.sent));
        } catch (err) {
            failed('Test alert', err);
        }
    });

    // ---------------------------------------------------------------- alerts switch

    const alertsState = document.getElementById('alerts-state');
    const alertsToggle = document.getElementById('alerts-toggle');
    // Confirms "Turn alerts off" / "Mute 8 h" to screen readers.
    if (alertsState) alertsState.setAttribute('aria-live', 'polite');

    /** Accepts the API's Instant (ISO string; epoch seconds if Jackson ever writes numbers). */
    function toDate(until) {
        if (until == null) return null;
        const d = typeof until === 'number' ? new Date(until < 1e12 ? until * 1000 : until) : new Date(until);
        return isNaN(d.getTime()) ? null : d;
    }

    function renderAlerts(state) {
        const muted = !!(state && state.muted);
        const until = muted ? toDate(state.until) : null;
        if (alertsState) {
            alertsState.dataset.muted = muted ? 'true' : 'false';
            // Browser local time here; the server renders it in the cameras' offset on reload.
            alertsState.textContent = !muted ? 'Alerts on'
                : until ? 'Alerts muted until ' + pad2(until.getHours()) + ':' + pad2(until.getMinutes())
                    : 'Alerts off';
        }
        if (alertsToggle) alertsToggle.textContent = muted ? 'Turn alerts on' : 'Turn alerts off';
    }

    async function setMute(muted, until) {
        try {
            renderAlerts(await api('/alerts/mute', 'PUT', { muted: muted, until: until }));
        } catch (err) {
            failed('Changing alerts', err);
        }
    }

    onClick('alerts-toggle', () => {
        const muted = alertsState && alertsState.dataset.muted === 'true';
        return setMute(!muted, null);
    });

    onClick('alerts-mute-8h', () => setMute(true, new Date(Date.now() + 8 * 3600 * 1000).toISOString()));
})();
