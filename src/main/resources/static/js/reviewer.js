/*
 * Reviewer page: the invoices an admin assigned to me. For one invoice: the original PDF, why it needs attention,
 * the live automatic checks, a correction form (saved as a NEW version through PUT api/invoices/{id} with
 * baseVersion - V1 and the PDF never change) and "submit this exact version for approval".
 * The server decides everything (assignment, status, version, checks); this page only shows its answers.
 */
(function () {
    'use strict';

    var U = Task8Ui;
    var h = U.h;

    var HEADER = [
        { name: 'invoiceNumber', label: 'Invoice number' }, { name: 'invoiceDate', label: 'Invoice date (YYYY-MM-DD)' },
        { name: 'sellerName', label: 'Seller' }, { name: 'sellerTaxId', label: 'Seller tax id' },
        { name: 'buyerName', label: 'Buyer' }, { name: 'buyerTaxId', label: 'Buyer tax id' },
        { name: 'currency', label: 'Currency (ISO, e.g. INR)' }];
    var TOTALS = [
        { name: 'subtotal', label: 'Subtotal' }, { name: 'discountAmount', label: 'Discount' },
        { name: 'shippingAmount', label: 'Shipping' }, { name: 'taxAmount', label: 'Tax' }, { name: 'totalAmount', label: 'Total' }];
    var ITEM = ['description', 'quantity', 'unitPrice', 'taxRate', 'lineTotal'];

    var el = {
        page: document.getElementById('reviewer-page'),
        summary: document.getElementById('summary'),
        summaryTz: document.getElementById('summary-tz'),
        queue: document.getElementById('queue'),
        queueEmpty: document.getElementById('queue-empty'),
        refresh: document.getElementById('refresh-btn'),
        detail: document.getElementById('detail'),
        title: document.getElementById('detail-title'),
        status: document.getElementById('detail-status'),
        meta: document.getElementById('detail-meta'),
        message: document.getElementById('detail-message'),
        pdfFrame: document.getElementById('pdf-frame'),
        pdfLink: document.getElementById('pdf-link'),
        reasons: document.getElementById('reasons'),
        checks: document.getElementById('checks'),
        form: document.getElementById('review-form'),
        headerFields: document.getElementById('header-fields'),
        totalFields: document.getElementById('total-fields'),
        itemRows: document.getElementById('item-rows'),
        addLine: document.getElementById('add-line-btn'),
        reason: document.getElementById('change-reason'),
        save: document.getElementById('save-btn'),
        submit: document.getElementById('submit-btn'),
        versions: document.getElementById('versions')
    };

    var current = null;   // the invoice shown (server response)
    var busy = false;

    Task8Session.requireRole('REVIEWER', function () {
        el.page.hidden = false;
        loadSummary();
        loadQueue();
    });

    el.refresh.addEventListener('click', function () { loadSummary(); loadQueue(); });

    function loadSummary() {
        U.api('GET', 'api/dashboard/reviewer?days=1').then(function (r) {
            if (!r.ok) return;
            var c = r.data.assignedStatusCounts || {};
            el.summaryTz.textContent = 'Business day in ' + r.data.timeZone;
            U.clear(el.summary);
            [U.stat('Needs my review', c.NEEDS_REVIEW || 0), U.stat('Waiting for approval', c.PENDING_APPROVAL || 0),
                U.stat('Submitted today', r.data.submittedInPeriod), U.stat('Corrections saved today', r.data.correctionsSavedInPeriod),
                U.stat('Oldest waiting since', U.dateTime(r.data.oldestWaitingSince))]
                .forEach(function (s) { el.summary.appendChild(s); });
        });
    }

    function loadQueue() {
        U.api('GET', 'api/invoices?size=200').then(function (r) {
            if (!r.ok) return;
            U.clear(el.queue);
            el.queueEmpty.hidden = r.data.length > 0;
            if (!r.data.length) return;
            el.queue.appendChild(U.table(['Invoice', 'File', 'Status', 'Category', 'Reasons', 'Updated', ''],
                r.data.map(function (inv) {
                    return h('tr', {}, [h('td', {}, String(inv.id)), h('td', {}, inv.fileName || ''), h('td', {}, U.badge(inv.status)),
                        h('td', {}, inv.categoryCode || '—'), h('td', { className: 'num' }, String(inv.reviewReasonCount)),
                        h('td', {}, U.dateTime(inv.updatedAt)),
                        h('td', {}, h('button', { type: 'button', className: 'btn btn--ghost btn--small open-btn',
                            dataset: { id: inv.id }, onClick: function () { openInvoice(inv.id); } }, 'Open'))]);
                })));
        });
    }

    // ---------- one invoice ----------

    function openInvoice(id) {
        U.message(el.message, '');
        U.api('GET', 'api/invoices/' + id).then(function (r) {
            if (!r.ok) {
                U.message(el.message, U.errorText(r), true);
                return;
            }
            current = r.data;
            render();
            loadReasons(id);
            loadChecks(id);
            loadVersions(id);
        });
    }

    function render() {
        var inv = current;
        el.detail.hidden = false;
        el.title.textContent = 'Invoice ' + inv.id + ' — version ' + inv.currentVersion;
        U.clear(el.status);
        el.status.appendChild(U.badge(inv.status));
        el.meta.textContent = (inv.sourceFileName || '') + ' · category ' + (inv.workflow.categoryCode || '—');
        if (inv.sourceDocument) {
            el.pdfFrame.src = 'api/documents/' + encodeURIComponent(inv.sourceDocument.id);
            el.pdfLink.href = el.pdfFrame.src;
        }
        U.clear(el.headerFields);
        HEADER.forEach(function (f) { el.headerFields.appendChild(field(f, inv[f.name])); });
        U.clear(el.totalFields);
        TOTALS.forEach(function (f) { el.totalFields.appendChild(field(f, inv[f.name])); });
        U.clear(el.itemRows);
        (inv.items || []).forEach(function (item) { el.itemRows.appendChild(itemRow(item)); });
        el.reason.value = '';
        var editable = inv.status === 'NEEDS_REVIEW';
        [el.save, el.submit, el.addLine].forEach(function (b) { b.disabled = !editable || busy; });
        Array.prototype.forEach.call(el.form.querySelectorAll('input'), function (i) { i.disabled = !editable; });
    }

    function field(f, value) {
        var id = 'f-' + f.name;
        return h('div', { className: 'form-field' }, [h('label', { 'for': id }, f.label),
            h('input', { type: 'text', id: id, value: value === null || value === undefined ? '' : String(value),
                dataset: { field: f.name } }),
            h('span', { className: 'field-error', dataset: { errorFor: f.name } }, '')]);
    }

    function itemRow(item) {
        var tr = h('tr', { className: 'item-row' });
        ITEM.forEach(function (name) {
            var v = item[name];
            tr.appendChild(h('td', {}, h('input', { type: 'text', value: v === null || v === undefined ? '' : String(v),
                'aria-label': name, dataset: { itemField: name } })));
        });
        tr.appendChild(h('td', {}, h('button', { type: 'button', className: 'btn btn--ghost btn--small',
            onClick: function () { tr.parentNode.removeChild(tr); } }, 'Remove')));
        return tr;
    }

    el.addLine.addEventListener('click', function () {
        el.itemRows.appendChild(itemRow({}));
    });

    /** The complete invoice as typed: amounts stay TEXT, the server parses and validates them exactly. */
    function formBody() {
        var body = { baseVersion: current.currentVersion, changeReason: el.reason.value || null, items: [] };
        HEADER.concat(TOTALS).forEach(function (f) {
            var v = el.form.querySelector('input[data-field="' + f.name + '"]').value.trim();
            body[f.name] = v === '' ? null : v;
        });
        Array.prototype.forEach.call(el.itemRows.querySelectorAll('tr'), function (tr) {
            var item = {};
            ITEM.forEach(function (name) {
                var v = tr.querySelector('input[data-item-field="' + name + '"]').value.trim();
                item[name] = v === '' ? null : v;
            });
            body.items.push(item);
        });
        return body;
    }

    el.form.addEventListener('submit', function (event) {
        event.preventDefault();
        if (busy) return;
        setBusy(true);
        U.api('PUT', 'api/invoices/' + current.id, formBody()).then(function (r) {
            setBusy(false);
            showFieldErrors(r.data && r.data.fieldErrors);
            if (r.ok) {
                current = r.data.invoice;
                render();
                U.message(el.message, r.data.changed ? 'Saved as version ' + current.currentVersion + '.' : 'Nothing changed.');
                loadChecks(current.id);
                loadVersions(current.id);
                loadQueue();
            } else if (r.status === 409) {
                U.message(el.message, U.errorText(r) + ' Open the invoice again to load the latest version.', true);
            } else {
                U.message(el.message, U.errorText(r), true);
            }
        });
    });

    el.submit.addEventListener('click', function () {
        if (busy) return;
        setBusy(true);
        U.api('POST', 'api/invoices/' + current.id + '/submit', { version: current.currentVersion }).then(function (r) {
            setBusy(false);
            showFieldErrors(r.data && r.data.fieldErrors);
            if (r.ok) {
                current = r.data;
                render();
                U.message(el.message, 'Version ' + current.workflow.submittedVersion + ' was submitted for approval.');
                loadSummary();
                loadQueue();
            } else {
                U.message(el.message, U.errorText(r), true);
            }
        });
    });

    function showFieldErrors(errors) {
        Array.prototype.forEach.call(el.form.querySelectorAll('.field-error'), function (s) { s.textContent = ''; });
        Array.prototype.forEach.call(el.form.querySelectorAll('input[aria-invalid]'), function (i) { i.removeAttribute('aria-invalid'); });
        (errors || []).forEach(function (e) {
            var slot = el.form.querySelector('[data-error-for="' + e.field + '"]');
            var input = el.form.querySelector('input[data-field="' + e.field + '"]');
            if (slot) slot.textContent = e.message;
            if (input) input.setAttribute('aria-invalid', 'true');
        });
    }

    function setBusy(value) {
        busy = value;
        [el.save, el.submit].forEach(function (b) { b.disabled = value || !current || current.status !== 'NEEDS_REVIEW'; });
        el.save.textContent = value ? 'Working…' : 'Save correction';
    }

    function loadReasons(id) {
        U.api('GET', 'api/invoices/' + id + '/review-reasons').then(function (r) {
            U.clear(el.reasons);
            if (!r.ok) return;
            if (!r.data.length) el.reasons.appendChild(h('li', {}, 'No automatic check failed on the extraction.'));
            r.data.forEach(function (x) { el.reasons.appendChild(h('li', {}, x.code + ' — ' + x.message)); });
        });
    }

    function loadChecks(id) {
        U.api('GET', 'api/invoices/' + id + '/checks').then(function (r) {
            U.clear(el.checks);
            if (!r.ok) return;
            if (!r.data.length) el.checks.appendChild(h('li', {}, 'All automatic checks pass on the current version.'));
            r.data.forEach(function (x) { el.checks.appendChild(h('li', {}, x.code + ' — ' + x.message)); });
        });
    }

    function loadVersions(id) {
        U.api('GET', 'api/invoices/' + id + '/versions').then(function (r) {
            U.clear(el.versions);
            if (!r.ok) return;
            r.data.forEach(function (v) {
                el.versions.appendChild(h('li', {}, 'V' + v.versionNumber + ' · ' + v.changeSource + ' · '
                    + (v.changedBy || 'system') + ' · ' + U.dateTime(v.createdAt)
                    + (v.changedFields && v.changedFields.length ? ' · ' + v.changedFields.length + ' change(s)' : '')
                    + (v.changeReason ? ' · "' + v.changeReason + '"' : '')));
            });
        });
    }
})();
