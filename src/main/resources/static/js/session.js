/*
 * Login session helpers shared by every page (loaded before the page's own script).
 *
 * The login token lives in the HttpOnly cookie TASK8_AUTH: this script can NOT read it, by design.
 * It only reads XSRF-TOKEN (a different, deliberately readable cookie) and copies it into the X-XSRF-TOKEN
 * header of every POST/PUT/DELETE. The server rejects writes without that header (CSRF protection).
 *
 * All text from the server is written with textContent, never innerHTML.
 */
(function () {
    'use strict';

    var LOGIN_PAGE = 'login.html';

    function readCookie(name) {
        var parts = document.cookie ? document.cookie.split('; ') : [];
        for (var i = 0; i < parts.length; i++) {
            var eq = parts[i].indexOf('=');
            if (parts[i].substring(0, eq) === name) {
                return decodeURIComponent(parts[i].substring(eq + 1));
            }
        }
        return null;
    }

    function goToLogin() {
        window.location.replace(LOGIN_PAGE);
    }

    /**
     * fetch() for our own API: same-origin cookies, JSON Accept header, the CSRF header on unsafe methods,
     * and a redirect to the login page when the session has expired (401).
     * options.allowUnauthorized = true: the caller handles 401 itself (login page, "who am I" check).
     */
    function apiFetch(url, options) {
        options = options || {};
        var method = (options.method || 'GET').toUpperCase();
        var headers = {};
        var given = options.headers || {};
        Object.keys(given).forEach(function (k) { headers[k] = given[k]; });
        if (!headers.Accept) headers.Accept = 'application/json';
        if (method !== 'GET' && method !== 'HEAD') {
            var token = readCookie('XSRF-TOKEN');
            if (token) headers['X-XSRF-TOKEN'] = token;
        }
        var init = {
            method: method,
            headers: headers,
            body: options.body,
            credentials: 'same-origin'
        };
        return fetch(url, init).then(function (response) {
            if (response.status === 401 && !options.allowUnauthorized) {
                goToLogin();
            }
            return response;
        });
    }

    /** Resolves to the logged-in user ({id, email, displayName, role}) or null. */
    function currentUser() {
        return apiFetch('api/auth/me', { allowUnauthorized: true })
            .then(function (response) { return response.ok ? response.json() : null; })
            .catch(function () { return null; });
    }

    /** For pages that need a login: calls onUser(me), or sends the browser to the login page. */
    function requireUser(onUser) {
        currentUser().then(function (me) {
            if (!me) {
                goToLogin();
                return;
            }
            showUserBar(me);
            onUser(me);
        });
    }

    var ROLE_LABELS = { ADMIN: 'Administrator', VENDOR: 'Vendor', REVIEWER: 'Reviewer' };

    // The page each role lands on after login. Only a convenience: the server checks every request anyway.
    var HOME_PAGES = { ADMIN: 'admin.html', VENDOR: 'vendor.html', REVIEWER: 'reviewer.html' };

    function homeFor(role) {
        return HOME_PAGES[role] || 'login.html';
    }

    /** For role-specific pages: anyone else is sent to their own home page. */
    function requireRole(role, onUser) {
        requireUser(function (me) {
            if (me.role !== role) {
                window.location.replace(homeFor(me.role));
                return;
            }
            onUser(me);
        });
    }

    function showUserBar(me) {
        var bar = document.getElementById('user-bar');
        if (!bar) return;
        document.getElementById('user-name').textContent = me.displayName;
        document.getElementById('user-role').textContent = ROLE_LABELS[me.role] || me.role;
        bar.hidden = false;
        var logoutBtn = document.getElementById('logout-btn');
        logoutBtn.addEventListener('click', function () {
            logoutBtn.disabled = true;
            apiFetch('api/auth/logout', { method: 'POST', allowUnauthorized: true })
                .catch(function () { /* the cookie may already be gone; go to the login page anyway */ })
                .then(goToLogin);
        });
    }

    window.Task8Session = {
        apiFetch: apiFetch,
        currentUser: currentUser,
        requireUser: requireUser,
        requireRole: requireRole,
        homeFor: homeFor,
        readCookie: readCookie,
        roleLabel: function (role) { return ROLE_LABELS[role] || role; }
    };
})();
