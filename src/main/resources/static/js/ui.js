/*
 * Tiny DOM helpers shared by the reviewer and admin pages. Everything that comes from the server is put on the page
 * with textContent (never innerHTML), so invoice text from the LLM can never run as HTML.
 */
(function () {
    'use strict';

    var STATUS_LABELS = {
        QUEUED: 'Queued', PROCESSING: 'Extracting', NEEDS_REVIEW: 'Needs review', PENDING_APPROVAL: 'Waiting for approval',
        APPROVED: 'Approved', REJECTED: 'Rejected', FAILED: 'Failed', EXTRACTED: 'Extracted (legacy)', CREATED: 'Created'
    };

    /** h('td', {className: 'num'}, 'text' | node | [children]) */
    function h(tag, attrs, children) {
        var node = document.createElement(tag);
        Object.keys(attrs || {}).forEach(function (key) {
            if (key === 'className') node.className = attrs[key];
            else if (key === 'onClick') node.addEventListener('click', attrs[key]);
            else if (key === 'dataset') Object.keys(attrs[key]).forEach(function (d) { node.dataset[d] = attrs[key][d]; });
            else if (attrs[key] !== undefined && attrs[key] !== null) node.setAttribute(key, attrs[key]);
        });
        append(node, children);
        return node;
    }

    function append(node, children) {
        if (children === undefined || children === null) return;
        if (Array.isArray(children)) {
            children.forEach(function (c) { append(node, c); });
        } else if (typeof children === 'string' || typeof children === 'number' || typeof children === 'boolean') {
            node.appendChild(document.createTextNode(String(children)));
        } else {
            node.appendChild(children);
        }
    }

    function clear(node) {
        while (node.firstChild) node.removeChild(node.firstChild);
    }

    function badge(status) {
        return h('span', { className: 'badge badge--' + status }, STATUS_LABELS[status] || status);
    }

    function readJson(response) {
        return response.text().then(function (text) {
            var data = null;
            try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
            return { status: response.status, ok: response.ok, data: data };
        });
    }

    /** GET/POST/PUT JSON through Task8Session (cookies + CSRF + 401 redirect); resolves to {status, ok, data}. */
    function api(method, url, body) {
        var options = { method: method };
        if (body !== undefined) {
            options.headers = { 'Content-Type': 'application/json' };
            options.body = JSON.stringify(body);
        }
        return Task8Session.apiFetch(url, options).then(readJson)
            .catch(function () { return { status: 0, ok: false, data: null }; });
    }

    function errorText(result) {
        if (result.status === 0) return 'The server could not be reached.';
        return result.data && result.data.message ? result.data.message : 'Request failed (HTTP ' + result.status + ').';
    }

    function dateTime(iso) {
        var m = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/.exec(iso || '');
        return m ? m[1] + ' ' + m[2] : (iso || '—');
    }

    function percent(ratio) {
        return ratio === null || ratio === undefined ? '—' : (ratio * 100).toFixed(1) + ' %';
    }

    function stat(label, value) {
        return h('div', { className: 'stat' }, [h('div', { className: 'stat__label' }, label),
            h('div', { className: 'stat__value' }, value === null || value === undefined ? '—' : String(value))]);
    }

    function table(headers, rows) {
        return h('div', { className: 'table-wrap' }, h('table', { className: 'items' }, [
            h('thead', {}, h('tr', {}, headers.map(function (t) { return h('th', { scope: 'col' }, t); }))),
            h('tbody', {}, rows)]));
    }

    function message(node, text, isError) {
        node.textContent = text || '';
        node.className = text ? (isError ? 'notice notice--bad' : 'notice') : '';
        node.hidden = !text;
    }

    window.Task8Ui = {
        h: h, clear: clear, badge: badge, api: api, errorText: errorText, dateTime: dateTime, percent: percent,
        stat: stat, table: table, message: message, statusLabel: function (s) { return STATUS_LABELS[s] || s; }
    };
})();
