/*
 * /admin/cameras: infinite scroll, full-screen player, Keep/Delete/Download, alerts switch.
 *
 * Markup contract: templates/admin/cameras.html. Rows are li.clip with data-id, data-start
 * (ISO with the clip's own offset), data-camera, data-kept ("true"/"false"), data-triggers
 * (comma list), data-size (bytes); each row's content sits in a button.clip-open, which is
 * what takes focus. #clips carries data-next (cursor, absent when there is no further page)
 * and data-query (current filter as a query string, absent when there is no filter), and
 * gets class "selecting" in select mode. Each day is an h3.day (span.day-label,
 * span.day-meta, button.day-select, button.day-delete) followed by its ul.clip-list.
 * Select mode: button#select-toggle (filter row), div#select-bar with #select-count,
 * #select-delete, #select-cancel.
 * Mode switch: div#mode-switch (data-current, and data-pending-id / data-pending-mode while
 * a request is pending) with button[data-mode="disarm|home|away"][aria-pressed],
 * span.cam-mode-other (a mode with no button, e.g. "off") and span#mode-status (role=status).
 *
 * API used (all under /api/cameras/): clips (paged list), clips/{id} (+ /video, /thumb,
 * /download, /keep), clips/delete (bulk, explicit ids), auth/revoke-all, alerts/test,
 * alerts/mute, mode (switch request + its status), status (signed-in probe, storage line).
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
    const BULK_MAX = 500;          // ids per POST /clips/delete (the server refuses more)
    const ARMED_SLACK_PX = 8;      // an armed day Delete disarms once its heading moves further
    const KEPT_NOT_SELECTABLE = 'Kept clips can\'t be deleted — unkeep first';

    // Select mode: selected ids (string) -> their li.clip.
    let selecting = false;
    const selected = new Map();

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

    // Per-button "ignore clicks until" times. After an action completes (or the player moves
    // to another clip) the second half of a double-click/double-tap must not start a new
    // action: Keep would unkeep, an alerts toggle would flip back, Delete would re-arm.
    const quietUntil = new WeakMap();

    function quietFor(el, ms) {
        quietUntil.set(el, performance.now() + ms);
    }

    function isQuiet(el) {
        return performance.now() < (quietUntil.get(el) || 0);
    }

    /** whileBusy, ignoring clicks while quiet; quiet for CONFIRM_MIN_MS once the task ends. */
    async function guarded(el, task) {
        if (isBusy(el) || isQuiet(el)) return;
        try {
            await whileBusy(el, task);
        } finally {
            quietFor(el, CONFIRM_MIN_MS);
        }
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
        row.dataset.size = String(Math.max(0, Number(c.sizeBytes) || 0));
        setRowKept(row, c.kept === true);
        decorateRow(row);
        return row;
    }

    function rowSize(row) {
        return Number(row.dataset.size) || 0;
    }

    function sumSizes(rows) {
        return rows.reduce((n, r) => n + rowSize(r), 0);
    }

    /** Sizes in headings, the bar and toasts: "3.4 MB" under 10 MB, else "34 MB". */
    function mb(bytes) {
        const v = bytes / 1048576;
        if (v <= 0) return '0 MB';
        if (v < 0.05) return '< 0.1 MB';
        return (v < 9.95 ? v.toFixed(1) : String(Math.round(v))) + ' MB';
    }

    function clipCount(n) {
        return n + (n === 1 ? ' clip' : ' clips');
    }

    /** Same format as the server's storage line (AdminCamerasController.size). */
    function humanSize(bytes) {
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        let v = Math.max(0, Number(bytes) || 0);
        let u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return u === 0 ? v + ' B' : v.toFixed(1) + ' ' + units[u];
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
        const heading = buildHeading(p ? dayLabel(p) : '');
        const list = document.createElement('ul');
        list.className = 'clip-list';
        list.appendChild(row);
        clips.appendChild(heading);
        clips.appendChild(list);
    }

    /** Same structure as the server's day heading (templates/admin/cameras.html). */
    function buildHeading(label) {
        const heading = document.createElement('h3');
        heading.className = 'day';
        const text = document.createElement('span');
        text.className = 'day-label';
        text.textContent = label;
        const meta = document.createElement('span');
        meta.className = 'day-meta';
        const pick = document.createElement('button');
        pick.type = 'button';
        pick.className = 'day-select';
        pick.setAttribute('aria-pressed', 'false');
        pick.textContent = 'Select day';
        const del = document.createElement('button');
        del.type = 'button';
        del.className = 'day-delete';
        del.textContent = 'Delete';
        heading.append(text, meta, pick, del);
        return heading;
    }

    /** Takes a row out of the DOM (and the selection); its day goes too when left empty. */
    function detachRow(row) {
        const list = row.parentElement;
        selected.delete(row.dataset.id);
        row.remove();
        if (list && !list.querySelector('li.clip')) {
            const heading = list.previousElementSibling;
            if (heading && heading.matches('h3.day')) heading.remove();
            list.remove();
        }
    }

    /** Removes a row, and its day heading + list when that day has no rows left. */
    function removeRow(row) {
        detachRow(row);
        showEmptyIfNone();
        refreshListUi();
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
        // Nothing left to select: as the server renders an empty list (no Select chip).
        if (selecting) setSelecting(false);
        const toggle = document.getElementById('select-toggle');
        if (toggle) toggle.hidden = true;
    }

    // ---------------------------------------------------------------- camera cards

    // On a phone the cards are one horizontal strip (cameras.css): bring the selected camera
    // into view. Only the strip's scrollLeft moves; never the page (no scrollIntoView).
    (function revealCurrentCard() {
        const card = document.querySelector('.cam-cards .camera-card[aria-current="true"]');
        const strip = card && card.parentElement;
        if (!strip || strip.scrollWidth <= strip.clientWidth) return;
        const s = strip.getBoundingClientRect();
        const c = card.getBoundingClientRect();
        if (c.left >= s.left && c.right <= s.right) return; // already fully visible
        strip.scrollLeft += c.left - s.left; // card at the start, matching scroll-snap-align: start
    })();

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
                refreshListUi();
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
        // A quick second tap after "Yes, delete" (or after prev/next) lands on this clip's
        // Delete: it must not arm it.
        quietFor(btnDelete, CONFIRM_MIN_MS);
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

    // A row's button (click, Enter or Space) opens the player, or in select mode ticks the
    // row. The day headings' buttons sit in #clips too.
    clips.addEventListener('click', e => {
        const dayDelete = e.target.closest('button.day-delete');
        if (dayDelete) return onDayDelete(dayDelete);
        const daySelect = e.target.closest('button.day-select');
        if (daySelect) return onDaySelect(daySelect);
        const row = e.target.closest('li.clip');
        if (!row || !clips.contains(row)) return;
        if (selecting) toggleRow(row);
        else open(row);
    });
    // A held Enter repeats clicks: no auto-repeat on the heading buttons or rows (a repeat
    // would arm and then confirm a day delete, or flip a tick back and forth).
    clips.addEventListener('keydown', e => {
        if (e.repeat && (e.key === 'Enter' || e.key === ' ')) e.preventDefault();
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
        // guarded: a double-tap must not keep and then at once unkeep.
        guarded(btnKeep, async () => {
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
    // double-click or double-tap and is ignored (the button stays armed). Clicks are also
    // ignored while the delete runs and for CONFIRM_MIN_MS after it (and after any clip
    // opens), so the second tap of a double-tap on "Yes, delete" does not arm the next clip.
    btnDelete.addEventListener('click', () => {
        const row = current;
        if (!row || !row.isConnected || isBusy(btnDelete) || isQuiet(btnDelete)) return;
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
        guarded(btnDelete, async () => {
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

    // ---------------------------------------------------------------- day headings, select mode, bulk delete

    const selectToggle = document.getElementById('select-toggle');
    const selectBar = document.getElementById('select-bar');
    const selectCount = document.getElementById('select-count');
    const selectDelete = document.getElementById('select-delete');
    const selectCancel = document.getElementById('select-cancel');

    function rowsOf(list) {
        return list ? Array.from(list.querySelectorAll('li.clip')) : [];
    }

    function isKept(row) {
        return row.dataset.kept === 'true';
    }

    /** The ul.clip-list under a day heading. */
    function listOf(heading) {
        const list = heading && heading.nextElementSibling;
        return list && list.matches('ul.clip-list') ? list : null;
    }

    /**
     * True when the day may go on past the loaded rows: it is the last day loaded and
     * there is a further page (days are appended in order, so a later list = day ended).
     */
    function dayMayContinue(list) {
        return !list.nextElementSibling && !!clips.dataset.next;
    }

    /**
     * Loads pages until a row of an older day appears under `list`'s day, or the list ends.
     * False when a page failed to load (loadMore has said why) or the day disappeared.
     */
    async function loadRestOfDay(list) {
        while (list.isConnected && dayMayContinue(list)) {
            const cursor = clips.dataset.next;
            await loadMore();
            if (clips.dataset.next === cursor) return false; // failed: no progress
        }
        return list.isConnected;
    }

    let headingSeq = 0;

    /**
     * Accessible names: the heading is "Today · 12 clips · 34 MB" (not its buttons' text),
     * and its buttons name the day ("Delete Today", "Yes, delete 12 clips · 34 MB Today").
     */
    function labelHeading(heading) {
        if (heading.dataset.named) return;
        const label = heading.querySelector('.day-label');
        const meta = heading.querySelector('.day-meta');
        if (!label || !meta) return;
        const n = ++headingSeq;
        label.id = label.id || 'day-label-' + n;
        meta.id = meta.id || 'day-meta-' + n;
        heading.setAttribute('aria-labelledby', label.id + ' ' + meta.id);
        for (const btn of heading.querySelectorAll('.day-select, .day-delete')) {
            btn.id = btn.id || btn.className + '-' + n;
            btn.setAttribute('aria-labelledby', btn.id + ' ' + label.id);
        }
        heading.dataset.named = 'true';
    }

    /** "Today · 12 clips · 34 MB" ("50+ clips" while the day may continue). */
    function updateDayHeadings() {
        for (const heading of clips.querySelectorAll('h3.day')) {
            labelHeading(heading);
            const list = listOf(heading);
            const rows = rowsOf(list);
            const meta = heading.querySelector('.day-meta');
            if (meta) {
                const more = list && dayMayContinue(list);
                const n = rows.length + (more ? '+' : '');
                meta.textContent = ' · ' + n + (rows.length === 1 && !more ? ' clip' : ' clips')
                    + ' · ' + mb(sumSizes(rows));
            }
            const pick = heading.querySelector('.day-select');
            if (pick) {
                const selectable = rows.filter(r => !isKept(r));
                const all = selectable.length > 0 && selectable.every(r => selected.has(r.dataset.id));
                pick.setAttribute('aria-pressed', all ? 'true' : 'false');
            }
        }
    }

    function updateSelectCount() {
        if (!selectCount) return;
        const rows = Array.from(selected.values());
        const text = rows.length + ' selected · ' + mb(sumSizes(rows));
        if (selectCount.textContent !== text) selectCount.textContent = text;
        if (selectBar) selectBar.dataset.count = String(rows.length);
    }

    /** After any change to the rows or the selection. */
    function refreshListUi() {
        updateDayHeadings();
        updateSelectCount();
    }

    /** Select-mode state of one row (ticked: aria-pressed on its button). */
    function decorateRow(row) {
        const btn = row.querySelector('.clip-open');
        if (!selecting) {
            row.classList.remove('selected');
            if (btn) {
                btn.removeAttribute('aria-pressed');
                btn.removeAttribute('aria-disabled');
                btn.setAttribute('aria-haspopup', 'dialog');
            }
            return;
        }
        const kept = isKept(row);
        if (kept) selected.delete(row.dataset.id);
        const on = selected.has(row.dataset.id);
        row.classList.toggle('selected', on);
        if (btn) {
            btn.removeAttribute('aria-haspopup'); // ticks, does not open the player
            btn.setAttribute('aria-pressed', on ? 'true' : 'false');
            if (kept) btn.setAttribute('aria-disabled', 'true');
            else btn.removeAttribute('aria-disabled');
        }
    }

    function setSelecting(on) {
        if (on === selecting) return;
        disarmBulk();
        selecting = on;
        selected.clear();
        clips.classList.toggle('selecting', on);
        if (selectToggle) selectToggle.setAttribute('aria-pressed', on ? 'true' : 'false');
        const hadFocus = selectBar && selectBar.contains(document.activeElement);
        if (selectBar) selectBar.hidden = !on;
        allRows().forEach(decorateRow);
        refreshListUi();
        if (hadFocus && selectToggle) selectToggle.focus({ preventScroll: true });
    }

    function toggleRow(row) {
        if (isKept(row)) {
            toast(KEPT_NOT_SELECTABLE);
            return;
        }
        const id = row.dataset.id;
        if (selected.has(id)) selected.delete(id);
        else selected.set(id, row);
        disarmBulk(); // the armed numbers would be stale
        decorateRow(row);
        refreshListUi();
    }

    // ---- two-tap confirm shared by the day Delete buttons and the bar's Delete

    let armed = null;      // { btn, idle, at, timer, ids, heading, top }
    let bulkBusy = false;  // a day is loading or a delete is running: other bulk taps wait
    let bulkQuietUntil = 0;

    /** Busy, or the second half of a double-tap after a bulk action (the rows have moved). */
    function bulkBlocked() {
        return bulkBusy || performance.now() < bulkQuietUntil;
    }

    function disarmBulk() {
        if (!armed) return;
        clearTimeout(armed.timer);
        armed.btn.textContent = armed.idle;
        armed.btn.classList.remove('confirm');
        armed = null;
    }

    function armBulk(btn, text, ids) {
        disarmBulk();
        const heading = btn.closest('h3.day');
        armed = {
            btn: btn, idle: btn.textContent, at: performance.now(), timer: 0, ids: ids,
            heading: heading, top: heading ? heading.getBoundingClientRect().top : 0,
        };
        btn.textContent = text;
        btn.classList.add('confirm');
        armed.timer = setTimeout(disarmBulk, CONFIRM_MS);
    }

    /**
     * An armed day Delete is only good while its day is the one on screen around it: once the
     * heading has scrolled away, or a later day's sticky heading slides over it (all headings
     * stick at the top of #clips), a tap there could delete a day the user is not looking at.
     */
    function armedDayOutOfPlace() {
        const h = armed && armed.heading;
        if (!h) return false;
        if (!h.isConnected) return true;
        const r = h.getBoundingClientRect();
        if (Math.abs(r.top - armed.top) > ARMED_SLACK_PX) return true;
        const next = nextDayHeading(h);
        return !!next && next.getBoundingClientRect().top < r.bottom - 1;
    }

    function disarmIfOutOfPlace() {
        if (armedDayOutOfPlace()) disarmBulk();
    }

    window.addEventListener('scroll', disarmIfOutOfPlace, { passive: true });
    window.addEventListener('resize', disarmIfOutOfPlace);

    /** Second tap on an armed button: >= CONFIRM_MIN_MS after arming, within CONFIRM_MS. */
    async function confirmBulk() {
        const a = armed;
        if (!a || performance.now() - a.at < CONFIRM_MIN_MS) return; // other half of a double-tap
        if (armedDayOutOfPlace()) { // moved since the label armed: start again
            disarmBulk();
            return;
        }
        disarmBulk();
        // Exactly what the label showed, minus anything that has since gone or been kept.
        const byId = new Map(allRows().map(r => [r.dataset.id, r]));
        const ids = a.ids.filter(id => byId.has(id) && !isKept(byId.get(id)));
        if (!ids.length) return;
        const btn = a.btn;
        const nextHeading = btn.closest('h3.day') && nextDayHeading(btn.closest('h3.day'));
        const hadFocus = document.activeElement === btn;
        bulkBusy = true;
        btn.setAttribute('aria-disabled', 'true');
        try {
            await deleteIds(ids);
        } finally {
            bulkBusy = false;
            bulkQuietUntil = performance.now() + CONFIRM_MIN_MS;
            btn.removeAttribute('aria-disabled');
        }
        if (hadFocus && !btn.isConnected) {
            // The day is gone: land on the next day's Delete, else the list, else the filters.
            const next = nextHeading && nextHeading.isConnected ? nextHeading.querySelector('.day-delete') : null;
            const first = allRows()[0];
            const target = next || (first && first.querySelector('.clip-open')) || selectToggle;
            if (target) target.focus({ preventScroll: true });
        }
    }

    function nextDayHeading(heading) {
        const list = listOf(heading);
        const next = list && list.nextElementSibling;
        return next && next.matches('h3.day') ? next : null;
    }

    async function onDayDelete(btn) {
        if (selecting || bulkBlocked() || isBusy(btn)) return;
        if (armed && armed.btn === btn) return confirmBulk();
        disarmBulk();
        const list = listOf(btn.closest('h3.day'));
        if (!list) return;
        const idle = btn.textContent;
        bulkBusy = true;
        btn.setAttribute('aria-disabled', 'true');
        btn.textContent = 'Loading…';
        let complete = false;
        try {
            complete = await loadRestOfDay(list);
        } finally {
            bulkBusy = false;
            btn.removeAttribute('aria-disabled');
            btn.textContent = idle;
        }
        if (!complete || !btn.isConnected || selecting) return;
        const rows = rowsOf(list);
        const doomed = rows.filter(r => !isKept(r));
        const kept = rows.length - doomed.length;
        if (!doomed.length) {
            toast('Nothing to delete — all kept');
            return;
        }
        const n = doomed.length;
        armBulk(btn, kept
            ? 'Yes, delete ' + n + ' · ' + kept + ' kept stay'
            : 'Yes, delete ' + clipCount(n) + ' · ' + mb(sumSizes(doomed)),
            doomed.map(r => r.dataset.id));
    }

    /** "Select day": loads the rest of the day, then ticks all its non-kept rows (or unticks). */
    async function onDaySelect(btn) {
        if (!selecting || bulkBlocked() || isBusy(btn)) return;
        const list = listOf(btn.closest('h3.day'));
        if (!list) return;
        bulkBusy = true;
        btn.setAttribute('aria-disabled', 'true');
        let complete = false;
        try {
            complete = await loadRestOfDay(list);
        } finally {
            bulkBusy = false;
            btn.removeAttribute('aria-disabled');
        }
        if (!complete || !selecting) return;
        const selectable = rowsOf(list).filter(r => !isKept(r));
        if (!selectable.length) {
            toast(KEPT_NOT_SELECTABLE);
            return;
        }
        const all = selectable.every(r => selected.has(r.dataset.id));
        for (const r of selectable) {
            if (all) selected.delete(r.dataset.id);
            else selected.set(r.dataset.id, r);
            decorateRow(r);
        }
        disarmBulk();
        refreshListUi();
    }

    /**
     * POST /clips/delete in chunks of BULK_MAX, one after the other; results are merged.
     * Stops when signed out (banner) or offline; any other refused chunk stays on screen.
     */
    async function deleteIds(ids) {
        const total = { deleted: [], freedBytes: 0, skippedKept: [], notFound: [], failed: [] };
        let error = null;
        let unsent = 0;
        for (let i = 0; i < ids.length; i += BULK_MAX) {
            const chunk = ids.slice(i, i + BULK_MAX);
            try {
                const body = await api('/clips/delete', 'POST', { ids: chunk.map(Number) }) || {};
                for (const k of ['deleted', 'skippedKept', 'notFound', 'failed']) {
                    if (Array.isArray(body[k])) total[k].push(...body[k].map(String));
                }
                total.freedBytes += Number(body.freedBytes) || 0;
            } catch (err) {
                error = err;
                unsent += chunk.length;
                if (err instanceof ApiError && (err.status === 401 || err.status === 0)) {
                    unsent += Math.max(0, ids.length - i - chunk.length);
                    break;
                }
            }
        }
        applyBulkResult(total, error, unsent);
    }

    function applyBulkResult(r, error, unsent) {
        const byId = new Map(allRows().map(row => [row.dataset.id, row]));
        let closePlayer = false;
        for (const id of r.deleted.concat(r.notFound)) {
            const row = byId.get(id);
            if (!row) continue;
            if (isOpen() && current === row) closePlayer = true;
            detachRow(row);
        }
        for (const id of r.skippedKept) {
            const row = byId.get(id);
            if (!row) continue;
            setRowKept(row, true); // kept on another device since the page loaded
            decorateRow(row);
        }
        disarmBulk();
        showEmptyIfNone();
        refreshListUi();
        rearm(); // the sentinel may have moved into view
        if (closePlayer) close();

        const done = r.deleted.length + r.notFound.length + r.skippedKept.length + r.failed.length;
        if (error && !done) {
            failed('Deleting clips', error);
        } else if (done) {
            let text = 'Deleted ' + clipCount(r.deleted.length) + ' · ' + mb(r.freedBytes) + ' freed';
            if (r.skippedKept.length) text += ' · ' + r.skippedKept.length + ' kept skipped';
            if (r.notFound.length) text += ' · ' + r.notFound.length + ' already gone';
            if (r.failed.length) text += ' · ' + r.failed.length + ' could not be deleted';
            if (unsent) text += ' · ' + unsent + ' not sent' + (error && error.status ? ' (' + error.status + ')' : '');
            toast(text, r.failed.length > 0 || unsent > 0);
        }
        if (r.deleted.length) refreshStorage();
    }

    /** Re-renders the storage line from GET /status, in the server's wording. */
    async function refreshStorage() {
        const line = document.querySelector('#storage .cam-storage-line');
        if (!line) return;
        try {
            const s = await api('/status');
            if (!s) return;
            const quota = Number(s.quotaBytes) || 0;
            const free = quota > 0 ? Math.max(0, quota - (Number(s.quotaUsed) || 0)) : s.volumeFree;
            line.textContent = 'Kept ' + humanSize(s.keptBytes) + ' · Last 30 days '
                + humanSize(s.unkeptBytes) + ' · Free ' + humanSize(free);
        } catch (e) {
            // The line keeps its old numbers; a 401 has shown the banner.
        }
    }

    if (selectToggle) selectToggle.addEventListener('click', () => setSelecting(!selecting));
    if (selectCancel) selectCancel.addEventListener('click', () => setSelecting(false));
    if (selectDelete) {
        selectDelete.addEventListener('click', () => {
            if (!selecting || bulkBlocked() || isBusy(selectDelete)) return;
            if (armed && armed.btn === selectDelete) return confirmBulk();
            const rows = Array.from(selected.values()).filter(r => r.isConnected && !isKept(r));
            if (!rows.length) {
                toast('Tap clips to select them first');
                return;
            }
            armBulk(selectDelete, 'Yes, delete ' + rows.length, rows.map(r => r.dataset.id));
        });
    }
    if (selectBar) {
        selectBar.addEventListener('keydown', e => {
            if (e.repeat && (e.key === 'Enter' || e.key === ' ')) e.preventDefault();
        });
    }
    // Escape leaves select mode (the player, when open, takes Escape first: capture phase).
    document.addEventListener('keydown', e => {
        if (e.key === 'Escape' && selecting && !isOpen() && !e.defaultPrevented) {
            e.preventDefault();
            setSelecting(false);
        }
    });

    refreshListUi();

    // ---------------------------------------------------------------- storage panel buttons

    /** Busy while `handler` runs, then quiet for CONFIRM_MIN_MS (no double-tap repeats). */
    function onClick(id, handler) {
        const el = document.getElementById(id);
        if (!el) return;
        el.addEventListener('click', () => guarded(el, handler));
    }

    // "Sign out all devices" (app tokens and remembered browsers) asks first, like Delete: the first click arms "Yes, sign out all"
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
            // Quiet after it fired: the second half of a double-click must not re-arm it.
            if (isBusy(revokeBtn) || isQuiet(revokeBtn)) return;
            if (!revokeArmedAt) {
                revokeArmedAt = performance.now();
                revokeBtn.textContent = 'Yes, sign out all';
                revokeBtn.classList.add('confirm');
                revokeTimer = setTimeout(disarmRevoke, CONFIRM_MS);
                return;
            }
            if (performance.now() - revokeArmedAt < CONFIRM_MIN_MS) return;
            disarmRevoke();
            guarded(revokeBtn, async () => {
                try {
                    const body = await api('/auth/revoke-all', 'POST');
                    const apps = Number(body && body.revoked) || 0;
                    const devices = Number(body && body.devices) || 0;
                    toast('Signed out ' + apps + (apps === 1 ? ' app' : ' apps') + ' and '
                        + devices + (devices === 1 ? ' remembered browser' : ' remembered browsers'));
                } catch (err) {
                    failed('Signing out all devices', err);
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

    // ---------------------------------------------------------------- mode switch

    // Disarm / Home / Away. A tap writes a request (POST /mode) that the Mac mini applies within
    // a cycle; #mode-status follows it with GET /mode every 3 s while pending (at most 7 min):
    // "Switching to away…" → "Away ✓" / "Not applied: <reason>" / "Expired — …". aria-pressed
    // follows the hub's mode as the heartbeat reports it, never the tap alone. Disarm asks first,
    // like Delete: the first tap arms "Yes, disarm" for 4 s, a separate second tap sends it.
    const modeSwitch = document.getElementById('mode-switch');
    if (modeSwitch) {
        const MODE_POLL_MS = 3000;
        const MODE_POLL_MAX_MS = 7 * 60 * 1000; // past expires_at (5 min) + the server's 1 min grace
        const MODE_LABELS = { disarm: 'Disarm', home: 'Home', away: 'Away' };
        const MODE_EXPIRED = "Expired — the Mac mini didn't pick it up";
        const MODE_UNCHECKED = "Couldn't check the switch — reload to see it";
        const modeButtons = Array.from(modeSwitch.querySelectorAll('button[data-mode]'));
        const modeStatus = document.getElementById('mode-status');
        const modeOther = modeSwitch.querySelector('.cam-mode-other');
        const disarmBtn = modeSwitch.querySelector('button[data-mode="disarm"]');
        const disarmLabel = disarmBtn ? disarmBtn.textContent : '';
        let disarmArmedAt = 0;
        let disarmTimer = 0;
        let pollTimer = 0;
        let pollId = null;   // id of the request being followed
        let pollUntil = 0;

        const isModeName = m => Object.prototype.hasOwnProperty.call(MODE_LABELS, m);

        /** Highlights the hub's mode; an unknown (null) mode leaves what is shown. */
        function renderCurrent(mode) {
            if (typeof mode !== 'string' || !mode.trim()) return;
            const m = mode.trim().toLowerCase();
            modeSwitch.dataset.current = m;
            modeButtons.forEach(b => b.setAttribute('aria-pressed', b.dataset.mode === m ? 'true' : 'false'));
            if (modeOther) {
                modeOther.textContent = isModeName(m) ? '' : '(now ' + m + ')';
                modeOther.hidden = isModeName(m);
            }
        }

        /** Status text, announced only when it changes (polls repeat the same pending text). */
        function setModeStatus(text, state) {
            if (!modeStatus) return;
            modeStatus.dataset.state = state || '';
            if (modeStatus.dataset.text === text) return;
            modeStatus.dataset.text = text;
            if (text) speak(modeStatus, text);
            else silence(modeStatus);
        }

        function renderRequest(r, current) {
            renderCurrent(current || (r && r.status === 'applied' ? r.mode : null));
            if (!r) return;
            const mode = String(r.mode || '');
            if (r.status === 'pending') {
                setModeStatus('Switching to ' + mode + '…', 'pending');
            } else if (r.status === 'applied') {
                setModeStatus((MODE_LABELS[mode] || mode) + ' ✓', 'applied');
            } else if (r.status === 'failed') {
                setModeStatus('Not applied: ' + (r.error ? String(r.error) : 'unknown reason'), 'failed');
            } else if (r.status === 'expired') {
                setModeStatus(MODE_EXPIRED, 'expired');
            }
        }

        function stopPolling() {
            clearTimeout(pollTimer);
            pollTimer = 0;
            pollId = null;
        }

        function follow(id) {
            stopPolling();
            pollId = id;
            pollUntil = performance.now() + MODE_POLL_MAX_MS;
            pollTimer = setTimeout(poll, MODE_POLL_MS);
        }

        async function poll() {
            pollTimer = 0;
            const id = pollId;
            if (!id) return;
            let s = null;
            try {
                s = await api('/mode');
            } catch (err) {
                if (id !== pollId) return;
                // Signed out (banner shown) or offline (toast shown): stop, do not repeat it every 3 s.
                if (!(err instanceof ApiError) || err.handled) {
                    stopPolling();
                    setModeStatus(MODE_UNCHECKED, 'failed');
                    return;
                }
                // Anything else (e.g. a 5xx while the server restarts): try again next time.
            }
            if (id !== pollId) return; // a newer tap took over
            if (s) {
                const r = s.request;
                if (r && r.id !== id) pollId = r.id; // replaced from another device: follow that one
                renderRequest(r, s.current);
                if (!r || r.status !== 'pending') {
                    stopPolling();
                    if (!r) setModeStatus('', '');
                    return;
                }
            }
            if (performance.now() >= pollUntil) {
                stopPolling();
                setModeStatus(MODE_UNCHECKED, 'failed');
                return;
            }
            pollTimer = setTimeout(poll, MODE_POLL_MS);
        }

        async function requestMode(mode) {
            stopPolling();
            try {
                const r = await api('/mode', 'POST', { mode: mode });
                if (!r || !r.id) throw new Error('No request id in the answer');
                renderRequest({ id: r.id, mode: r.mode || mode, status: 'pending' }, null);
                follow(r.id);
            } catch (err) {
                if (err instanceof ApiError && err.status === 503) {
                    setModeStatus('Not sent — the camera volume is missing', 'failed');
                } else if (err instanceof ApiError && err.status === 400) {
                    setModeStatus('Not sent — unknown mode', 'failed');
                } else {
                    setModeStatus('', '');
                    failed('Switching mode', err);
                }
            }
        }

        function disarmDisarm() {
            if (!disarmBtn) return;
            clearTimeout(disarmTimer);
            disarmArmedAt = 0;
            disarmBtn.textContent = disarmLabel;
            disarmBtn.classList.remove('confirm');
        }

        modeSwitch.addEventListener('click', e => {
            const btn = e.target.closest('button[data-mode]');
            if (!btn || !modeSwitch.contains(btn)) return;
            // One switch at a time; quiet after a send (no double-tap repeats).
            if (modeButtons.some(isBusy) || isQuiet(btn)) return;
            const mode = btn.dataset.mode;
            if (!isModeName(mode)) return;
            if (mode === 'disarm') {
                if (!disarmArmedAt) {
                    disarmArmedAt = performance.now();
                    btn.textContent = 'Yes, disarm';
                    btn.classList.add('confirm');
                    disarmTimer = setTimeout(disarmDisarm, CONFIRM_MS);
                    return;
                }
                // The other half of a double-click/double-tap: stays armed.
                if (performance.now() - disarmArmedAt < CONFIRM_MIN_MS) return;
            }
            disarmDisarm();
            guarded(btn, () => requestMode(mode));
        });
        // A held Enter repeats clicks: never let the repeat arm and then confirm Disarm.
        modeSwitch.addEventListener('keydown', e => {
            if (e.repeat && (e.key === 'Enter' || e.key === ' ')) e.preventDefault();
        });

        // A request still pending when the page loaded: keep following it.
        if (modeSwitch.dataset.pendingId) {
            renderRequest({ id: modeSwitch.dataset.pendingId, mode: modeSwitch.dataset.pendingMode, status: 'pending' }, null);
            follow(modeSwitch.dataset.pendingId);
        }
    }

    // ---------------------------------------------------------------- alerts switch

    const alertsState = document.getElementById('alerts-state');
    const alertsToggle = document.getElementById('alerts-toggle');

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
            // Browser local time, on load (below) as after a toggle, so the two always agree.
            alertsState.textContent = !muted ? 'Alerts on'
                : until ? 'Alerts muted until ' + pad2(until.getHours()) + ':' + pad2(until.getMinutes())
                    : 'Alerts off';
        }
        if (alertsToggle) alertsToggle.textContent = muted ? 'Turn alerts on' : 'Turn alerts off';
    }

    // The server writes "muted until" in the cameras' offset; re-render it from data-until
    // (ISO instant) in local time like a toggle does. Before aria-live, so it is not announced.
    if (alertsState && alertsState.dataset.muted === 'true' && toDate(alertsState.dataset.until)) {
        renderAlerts({ muted: true, until: alertsState.dataset.until });
    }
    // Confirms "Turn alerts off" / "Mute 8 h" to screen readers.
    if (alertsState) alertsState.setAttribute('aria-live', 'polite');

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
