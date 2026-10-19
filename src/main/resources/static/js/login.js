/*
 * Login page. Sends email + password as JSON to POST api/auth/login (with the CSRF header, via session.js).
 * On success the server sets the HttpOnly login cookie; this script never sees the token.
 */
(function () {
    'use strict';

    var form = document.getElementById('login-form');
    var email = document.getElementById('login-email');
    var password = document.getElementById('login-password');
    var button = document.getElementById('login-btn');
    var errorBox = document.getElementById('login-error');

    // Already logged in? Go straight to the workspace.
    Task8Session.currentUser().then(function (me) {
        if (me) window.location.replace(Task8Session.homeFor(me.role));
    });

    function showError(text) {
        errorBox.textContent = text;
        errorBox.hidden = false;
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        errorBox.hidden = true;
        if (!email.value.trim() || !password.value) {
            showError('Enter your email and password.');
            return;
        }
        button.disabled = true;
        button.textContent = 'Signing in…';

        Task8Session.apiFetch('api/auth/login', {
            method: 'POST',
            allowUnauthorized: true,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: email.value.trim(), password: password.value })
        }).then(function (response) {
            if (response.ok) {
                // Each role lands on its own page (the server checks every request anyway).
                return response.json().then(function (me) { window.location.replace(Task8Session.homeFor(me.role)); });
            }
            return response.json().catch(function () { return null; }).then(function (body) {
                if (response.status === 403) {
                    showError('Your session token is missing. Reload the page and try again.');
                } else {
                    showError(body && body.message ? body.message : 'Sign-in failed (HTTP ' + response.status + ').');
                }
                password.value = '';
                password.focus();
            });
        }).catch(function () {
            showError('The server could not be reached. Is the application running?');
        }).then(function () {
            button.disabled = false;
            button.textContent = 'Sign in';
        });
    });

    // Lets tests (and impatient users) know the JSON login handler is attached.
    form.dataset.ready = 'true';
})();
