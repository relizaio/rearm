/*
 * Reliza login theme (TPF-95): auto-continue the base theme's "Page has expired" page
 * once per login attempt and browser tab. Loaded on every login page through the
 * theme.properties `scripts=` line; it acts only when the continue link rendered by
 * login-page-expired.ftl is present. Without JavaScript, or with storage blocked, the
 * page stays as rendered. The target is always the href the page itself rendered, on
 * this origin, under /realms/<realm>/login-actions/. Storage holds only short hashes.
 */
(function () {
  'use strict';

  var STORAGE_KEY = 'rearm.kc.autoContinueExpired';
  var MAX_REMEMBERED = 8;       // attempt ids remembered per tab
  var BURST_LIMIT = 3;          // loop breaker: at most this many automatic continues ...
  var BURST_WINDOW_MS = 60000;  // ... per tab within this window

  // The continue link: by id first; otherwise the anchor inside #instruction1 whose href
  // is under /login-actions/ and is not the restart link (/login-actions/restart). The
  // continue href carries execution, client_id, tab_id and client_data (no session_code);
  // the restart link is the only other /login-actions/ anchor on the page.
  function findContinueLink() {
    var byId = document.getElementById('loginContinueLink');
    if (byId && byId.getAttribute('href')) {
      return byId;
    }
    var box = document.getElementById('instruction1');
    if (!box) {
      return null;
    }
    var anchors = box.getElementsByTagName('a');
    for (var i = 0; i < anchors.length; i++) {
      var href = anchors[i].getAttribute('href') || '';
      if (href.indexOf('/login-actions/') !== -1 && href.indexOf('/login-actions/restart') === -1) {
        return anchors[i];
      }
    }
    return null;
  }

  // Resolve the href and accept it only on this origin under /realms/<realm>/login-actions/.
  function loginActionTarget(href) {
    var url;
    try {
      url = new URL(href, window.location.href);
    } catch (e) {
      return null;
    }
    if (url.origin !== window.location.origin) {
      return null;
    }
    if (!/\/realms\/[^\/]+\/login-actions\//.test(url.pathname)) {
      return null;
    }
    return url;
  }

  // djb2 over a string, 8 hex chars: an identity for the storage entries, not a secret.
  function hash(s) {
    var h = 5381;
    for (var i = 0; i < s.length; i++) {
      h = ((h << 5) + h + s.charCodeAt(i)) | 0;
    }
    return (h >>> 0).toString(16);
  }

  // The attempt identity: realm + tab_id (the authentication session of this tab, stable
  // for the whole attempt and different for every new attempt). Without a tab_id in the
  // href (getLastExecutionUrl always adds one, so this is only a safety fallback), the
  // whole href is hashed; that href is the stable last-step URL (execution, client_id,
  // tab_id, client_data, no session_code), so at worst the page is continued once per
  // flow step rather than once per attempt. Only the hash is stored.
  function attemptId(url) {
    var realm = (url.pathname.match(/\/realms\/([^\/]+)\//) || [])[1] || '';
    var tab = url.searchParams.get('tab_id');
    return hash(tab ? realm + '/tab/' + tab : url.href);
  }

  function readEntries() {
    try {
      var raw = window.sessionStorage.getItem(STORAGE_KEY);
      var parsed = raw ? JSON.parse(raw) : [];
      return Array.isArray(parsed) ? parsed : [];
    } catch (e) {
      return null; // storage blocked or unreadable: the caller does nothing
    }
  }

  function writeEntries(entries) {
    try {
      window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
      return true;
    } catch (e) {
      return false;
    }
  }

  function run() {
    var link = findContinueLink();
    if (!link) {
      return; // not the expired page
    }
    var target = loginActionTarget(link.getAttribute('href'));
    if (!target) {
      return;
    }
    var entries = readEntries();
    if (entries === null) {
      return; // cannot guarantee "once": leave the page to the user
    }
    var id = attemptId(target);
    var now = Date.now();
    var recent = 0;
    for (var i = 0; i < entries.length; i++) {
      if (entries[i] && entries[i].id === id) {
        return; // already continued once for this attempt in this tab
      }
      if (entries[i] && now - entries[i].t < BURST_WINDOW_MS) {
        recent++;
      }
    }
    if (recent >= BURST_LIMIT) {
      return; // something is looping through new attempts: stop helping
    }
    entries.push({ id: id, t: now });
    while (entries.length > MAX_REMEMBERED) {
      entries.shift();
    }
    if (!writeEntries(entries)) {
      return; // guard not persisted: do not navigate
    }
    window.location.replace(target.href);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', run);
  } else {
    run();
  }
})();
