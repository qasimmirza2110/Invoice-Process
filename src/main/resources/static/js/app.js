/*
 * Invoice Extraction Workspace.
 * Lets the user pick up to three local PDFs, previews the active one, and sends them ONE AT A TIME
 * to the existing single-file POST /api/invoices/extract endpoint (one request per file, never in
 * parallel, never retried). Every file keeps its own result. All values are written with
 * textContent, never innerHTML, because invoice text comes from the LLM and is untrusted.
 *
 * After extraction the user can correct the extracted DATA (never the PDF). "Save correction" sends
 * the complete invoice with the version it was based on (PUT /api/invoices/{id}); the server stores
 * it as a new version and keeps every earlier one (GET /api/invoices/{id}/versions). Amounts are kept
 * and sent as text, so the browser never does floating-point money maths; the server validates.
 */
(function () {
    'use strict';

    var EXTRACT_URL = 'api/invoices/extract'; // relative: same origin as the page
    var MAX_BYTES = 10 * 1024 * 1024;         // matches the backend's 10 MB limit
    var MAX_FILES = 3;                        // batch size limit for now

    var STATE_LABELS = { pending: 'Pending', processing: 'Processing', extracted: 'Extracted', failed: 'Failed' };

    var el = {
        input: document.getElementById('file-input'),
        label: document.getElementById('file-label'),
        info: document.getElementById('file-info'),
        summary: document.getElementById('file-summary'),
        fileError: document.getElementById('file-error'),
        fileList: document.getElementById('file-list'),
        processBtn: document.getElementById('process-btn'),
        resetBtn: document.getElementById('reset-btn'),
        previewEmpty: document.getElementById('preview-empty'),
        previewFrame: document.getElementById('preview-frame'),
        previewFallback: document.getElementById('preview-fallback'),
        previewLink: document.getElementById('preview-link'),
        resultHint: document.getElementById('result-hint'),
        batchSummary: document.getElementById('batch-summary'),
        elapsed: document.getElementById('elapsed'),
        errorBox: document.querySelector('[data-state="error"]'),
        errorTitle: document.getElementById('error-title'),
        errorMessage: document.getElementById('error-message'),
        errorNote: document.getElementById('error-note'),
        statusBadge: document.getElementById('status-badge'),
        invoiceMeta: document.getElementById('invoice-meta'),
        failureReason: document.getElementById('failure-reason'),
        invoiceFields: document.getElementById('invoice-fields'),
        itemsBody: document.getElementById('items-body'),
        invoiceTotals: document.getElementById('invoice-totals'),
        usageFields: document.getElementById('usage-fields'),
        versionBar: document.getElementById('version-bar'),
        versionLabel: document.getElementById('version-label'),
        versionMeta: document.getElementById('version-meta'),
        sourceDoc: document.getElementById('source-doc'),
        sourceDocHash: document.getElementById('source-doc-hash'),
        sourceDocLink: document.getElementById('source-doc-link'),
        saveStatus: document.getElementById('save-status'),
        saveStatusText: document.getElementById('save-status-text'),
        reloadLatestBtn: document.getElementById('reload-latest-btn'),
        invoiceView: document.getElementById('invoice-view'),
        editActions: document.getElementById('edit-actions'),
        editBtn: document.getElementById('edit-btn'),
        editForm: document.getElementById('edit-form'),
        formError: document.getElementById('form-error'),
        editHeader: document.getElementById('edit-header'),
        editItems: document.getElementById('edit-items'),
        addItemBtn: document.getElementById('add-item-btn'),
        editTotals: document.getElementById('edit-totals'),
        editReason: document.getElementById('edit-reason'),
        saveBtn: document.getElementById('save-btn'),
        cancelBtn: document.getElementById('cancel-btn'),
        unsavedNote: document.getElementById('unsaved-note'),
        history: document.getElementById('history'),
        historyStatus: document.getElementById('history-status'),
        historyList: document.getElementById('history-list'),
        compare: document.getElementById('compare'),
        compareFrom: document.getElementById('compare-from'),
        compareTo: document.getElementById('compare-to'),
        compareBtn: document.getElementById('compare-btn'),
        compareStatus: document.getElementById('compare-status'),
        compareWrap: document.getElementById('compare-wrap'),
        compareFromHead: document.getElementById('compare-from-head'),
        compareToHead: document.getElementById('compare-to-head'),
        compareBody: document.getElementById('compare-body')
    };

    // One record per selected file: { file, state, httpStatus, data, startedAt } plus its review state
    // (editing, draft, dirty, saving, save, fieldErrors, versions, versionsState, comparison) - see newRecord().
    var records = [];
    var activeIndex = -1;        // the file shown in the preview and the result panel
    var previewUrl = null;
    var busy = false;
    var batchDone = false;       // after a run, a new selection or Reset is needed (no accidental resubmit)
    var followProcessing = true; // the view follows the running file until the user picks one
    var timer = null;

    // ---------- file selection ----------

    el.input.addEventListener('change', function () {
        selectFiles(el.input.files ? Array.prototype.slice.call(el.input.files) : []);
    });

    function selectFiles(files) {
        resetState();
        if (!files.length) {
            return;
        }

        var problems = [];
        if (files.length > MAX_FILES) {
            problems.push('You selected ' + files.length + ' files. Choose at most ' + MAX_FILES + ' PDFs per batch.');
        }
        files.forEach(function (file) {
            var problem = checkFile(file);
            if (problem) problems.push(problem);
        });
        if (problems.length) {
            // Reject the whole selection so no file is silently skipped.
            el.input.value = '';
            showFileError(problems.join(' '));
            return;
        }

        records = files.map(newRecord);
        renderFileList();
        setActive(0);
        updateControls();
    }

    // Quick browser-side checks only; the backend still validates everything (including the %PDF- signature).
    function checkFile(file) {
        var isPdfName = /\.pdf$/i.test(file.name);
        var isPdfType = !file.type || file.type === 'application/pdf';
        if (!isPdfName || !isPdfType) {
            return '"' + file.name + '" is not a PDF.';
        }
        if (file.size === 0) {
            return '"' + file.name + '" is empty.';
        }
        if (file.size > MAX_BYTES) {
            return '"' + file.name + '" is ' + formatBytes(file.size) + '; the maximum is 10 MB.';
        }
        return null;
    }

    function showFileError(message) {
        el.fileError.textContent = message;
        el.fileError.hidden = false;
    }

    function hideFileError() {
        el.fileError.textContent = '';
        el.fileError.hidden = true;
    }

    // ---------- file list ----------

    function renderFileList() {
        clear(el.fileList);
        el.fileList.hidden = records.length === 0;
        el.info.classList.toggle('file-info--empty', records.length === 0);
        el.summary.textContent = records.length === 0 ? 'No files selected'
            : records.length + (records.length === 1 ? ' file selected' : ' files selected');

        records.forEach(function (record, index) {
            var li = document.createElement('li');
            var button = document.createElement('button');
            button.type = 'button';
            button.className = 'file-row' + (index === activeIndex ? ' is-active' : '');
            if (index === activeIndex) button.setAttribute('aria-current', 'true');
            button.addEventListener('click', function () {
                followProcessing = false;
                setActive(index);
            });

            var text = document.createElement('span');
            text.className = 'file-row__text';
            var name = document.createElement('span');
            name.className = 'file-row__name';
            name.textContent = record.file.name;
            var detail = document.createElement('span');
            detail.className = 'file-row__detail';
            detail.textContent = rowDetail(record);
            text.appendChild(name);
            text.appendChild(detail);

            var badge = document.createElement('span');
            badge.className = 'badge badge--state-' + record.state;
            badge.textContent = STATE_LABELS[record.state];

            button.appendChild(text);
            button.appendChild(badge);
            li.appendChild(button);
            el.fileList.appendChild(li);
        });
    }

    function rowDetail(record) {
        var parts = [formatBytes(record.file.size)];
        if (record.state === 'extracted' || (record.data && record.data.id != null)) {
            parts.push('Invoice ID ' + record.data.id);
        } else if (record.state === 'failed') {
            parts.push(record.httpStatus ? 'HTTP ' + record.httpStatus : 'No response');
        }
        return parts.join(' · ');
    }

    function setActive(index) {
        activeIndex = index;
        renderFileList();
        showPreview(records[index] ? records[index].file : null);
        renderActiveResult();
    }

    // ---------- preview (one object URL at a time) ----------

    function showPreview(file) {
        releasePreview();
        if (!file) {
            return;
        }
        previewUrl = URL.createObjectURL(file);
        el.previewFrame.src = previewUrl;
        el.previewLink.href = previewUrl;
        el.previewFrame.hidden = false;
        el.previewFallback.hidden = false;
        el.previewEmpty.hidden = true;
    }

    function releasePreview() {
        if (previewUrl) {
            URL.revokeObjectURL(previewUrl);
            previewUrl = null;
        }
        el.previewFrame.removeAttribute('src');
        el.previewLink.removeAttribute('href');
        el.previewFrame.hidden = true;
        el.previewFallback.hidden = true;
        el.previewEmpty.hidden = false;
    }

    // ---------- reset (browser state only - nothing is deleted on the server) ----------

    el.resetBtn.addEventListener('click', function () {
        if (busy || anySaving()) {
            return;
        }
        if (!confirmDiscard()) {
            return;
        }
        resetState();
    });

    // Choosing new files replaces the batch, so ask first if a correction is still unsaved.
    el.input.addEventListener('click', function (event) {
        if (!confirmDiscard()) {
            event.preventDefault();
        }
    });

    function resetState() {
        el.input.value = '';
        records = [];
        activeIndex = -1;
        batchDone = false;
        hideFileError();
        releasePreview();
        renderFileList();
        renderSummary();
        el.resultHint.textContent = '';
        showState('empty');
        updateControls();
    }

    // ---------- batch processing ----------

    el.processBtn.addEventListener('click', function () {
        if (busy || batchDone) {
            return; // never send a second batch while one is running, and never resend a finished one
        }
        if (!records.length) {
            showFileError('Please choose at least one PDF file first.');
            return;
        }
        hideFileError();
        busy = true;
        followProcessing = true;
        updateControls();
        startTimer();
        processNext(0);
    });

    // Strictly sequential: the next request starts only after the previous one has settled.
    function processNext(index) {
        if (index >= records.length) {
            busy = false;
            batchDone = true;
            stopTimer();
            updateControls();
            renderSummary();
            return;
        }

        var record = records[index];
        record.state = 'processing';
        record.startedAt = Date.now();
        if (followProcessing) {
            setActive(index);
        } else {
            renderFileList();
            if (index === activeIndex) renderActiveResult();
        }
        renderSummary();

        sendOne(record.file).then(function (result) {
            record.httpStatus = result.status;
            record.data = result.data;
            var extracted = result.ok && result.data && result.data.status === 'EXTRACTED';
            record.state = extracted ? 'extracted' : 'failed';

            renderFileList();
            if (index === activeIndex) renderActiveResult();
            if (extracted) loadVersions(record); // a GET only - never another extraction
            processNext(index + 1); // continue even when this file failed
        });
    }

    // One fresh FormData with exactly one file, sent to the existing single-file endpoint.
    // Always resolves (never rejects) so one failure cannot stop the batch.
    function sendOne(file) {
        var body = new FormData();
        body.append('file', file, file.name); // the browser sets the multipart Content-Type and boundary

        return Task8Session.apiFetch(EXTRACT_URL, { method: 'POST', body: body, headers: { 'Accept': 'application/json' } })
            .then(function (response) {
                return response.text().then(function (text) {
                    return { status: response.status, ok: response.ok, data: parseJson(text) };
                });
            })
            .catch(function () {
                return { status: 0, ok: false, data: null };
            });
    }

    function updateControls() {
        var saving = anySaving();
        el.processBtn.disabled = busy || batchDone || records.length === 0;
        el.resetBtn.disabled = busy || saving;
        el.input.disabled = busy || saving;
        el.label.classList.toggle('is-disabled', busy || saving);
        el.processBtn.textContent = busy ? 'Processing…'
            : batchDone ? 'Processed' : 'Process Selected PDFs';
    }

    function renderSummary() {
        if (!records.length) {
            el.batchSummary.hidden = true;
            el.batchSummary.textContent = '';
            return;
        }
        var counts = { pending: 0, processing: 0, extracted: 0, failed: 0 };
        records.forEach(function (r) { counts[r.state]++; });
        var done = counts.extracted + counts.failed;
        var text = done + ' of ' + records.length + ' processed · '
            + counts.extracted + ' extracted · ' + counts.failed + ' failed';
        if (batchDone) {
            text += '. Choose new files or Reset to run another batch.';
        }
        el.batchSummary.textContent = text;
        el.batchSummary.hidden = false;
    }

    function startTimer() {
        stopTimer();
        timer = setInterval(updateElapsed, 1000);
    }

    function stopTimer() {
        clearInterval(timer);
        timer = null;
    }

    function updateElapsed() {
        var record = records[activeIndex];
        if (record && record.state === 'processing') {
            el.elapsed.textContent = String(Math.floor((Date.now() - record.startedAt) / 1000));
        }
    }

    function parseJson(text) {
        try {
            return text ? JSON.parse(text) : null;
        } catch (e) {
            return null;
        }
    }

    // ---------- rendering: the active file's result ----------

    function renderActiveResult() {
        var record = records[activeIndex];
        if (!record) {
            el.resultHint.textContent = '';
            showState('empty');
            return;
        }
        el.resultHint.textContent = record.file.name;

        if (record.state === 'pending') {
            showState('pending');
        } else if (record.state === 'processing') {
            updateElapsed();
            showState('loading');
        } else if (record.data && record.data.status && record.httpStatus >= 200 && record.httpStatus < 300) {
            renderInvoice(record.data);
            renderReview(record);
        } else {
            renderError(record.httpStatus, record.data);
        }
    }

    function showState(name) {
        var states = document.querySelectorAll('#result [data-state]');
        for (var i = 0; i < states.length; i++) {
            states[i].hidden = states[i].getAttribute('data-state') !== name;
        }
    }

    // Titles follow the backend's GlobalExceptionHandler mapping.
    var ERROR_TITLES = {
        0: 'Could not reach the server',
        400: 'The file was rejected',
        404: 'Not found',
        413: 'The file is too large',
        422: 'Extracted data failed validation',
        500: 'Unexpected server error',
        502: 'Extraction service failed'
    };

    function renderError(status, data) {
        var title = ERROR_TITLES[status] || ('Request failed (HTTP ' + status + ')');
        var message = data && typeof data.message === 'string' && data.message
            ? data.message
            : (status === 0
                ? 'Check that the application is running on this address and try again.'
                : 'The server returned an unreadable response.');

        var note = '';
        if (status === 422) {
            note = 'The model returned data, but the backend\'s checks rejected it, so no invoice values were saved. '
                + 'The upload is recorded with status FAILED.';
        } else if (status === 502) {
            note = 'The upload is recorded with status FAILED. Nothing was retried automatically.';
        } else if (status === 400) {
            note = 'No extraction was attempted.';
        }

        el.errorTitle.textContent = status ? title + ' · HTTP ' + status : title;
        el.errorMessage.textContent = message;
        el.errorNote.textContent = note;
        el.errorNote.hidden = !note;
        el.errorBox.classList.toggle('is-warning', status === 400 || status === 422);
        showState('error');
    }

    // ---------- rendering: invoice ----------

    function renderInvoice(inv) {
        var currency = inv.currency;

        el.statusBadge.textContent = inv.status;
        el.statusBadge.className = 'badge badge--' + inv.status;

        var meta = [];
        if (inv.id != null) meta.push('Invoice ID ' + inv.id);
        if (inv.sourceFileName) meta.push(inv.sourceFileName);
        if (inv.createdAt) meta.push(formatDateTime(inv.createdAt));
        el.invoiceMeta.textContent = meta.join(' · ');

        el.failureReason.hidden = !inv.failureReason;
        el.failureReason.textContent = inv.failureReason || '';

        fillFields(el.invoiceFields, [
            ['Invoice number', inv.invoiceNumber],
            ['Invoice date', formatDate(inv.invoiceDate)],
            ['Seller', inv.sellerName],
            ['Seller tax ID', inv.sellerTaxId],
            ['Buyer', inv.buyerName],
            ['Buyer tax ID', inv.buyerTaxId],
            ['Currency', currency]
        ], 'Not available');

        renderItems(inv.items || [], currency);

        clear(el.invoiceTotals);
        addTotal('Subtotal', formatMoney(inv.subtotal, currency), false);
        // Only shown when the invoice has them; older invoices and invoices without these lines return null.
        if (inv.discountAmount != null) {
            addTotal('Discount', '−' + formatMoney(inv.discountAmount, currency), false);
        }
        if (inv.shippingAmount != null) {
            addTotal('Shipping & handling', formatMoney(inv.shippingAmount, currency), false);
        }
        addTotal('Tax amount', formatMoney(inv.taxAmount, currency), false);
        addTotal('Grand total', formatMoney(inv.totalAmount, currency), true);

        var u = inv.tokenUsage || {};
        fillFields(el.usageFields, [
            ['Model requested', u.modelId],
            ['Model version used', u.modelVersion],
            ['Prompt tokens', formatCount(u.promptTokenCount)],
            ['Output tokens', formatCount(u.candidatesTokenCount)],
            ['Thinking tokens', formatCount(u.thoughtsTokenCount)],
            ['Total tokens', formatCount(u.totalTokenCount)],
            ['Extraction time', formatDuration(u.extractionDurationMs)]
        ], 'Not reported');

        showState('invoice');
    }

    function renderItems(items, currency) {
        clear(el.itemsBody);
        if (!items.length) {
            var row = document.createElement('tr');
            row.className = 'empty-row';
            var cell = document.createElement('td');
            cell.colSpan = 5;
            cell.textContent = 'No line items returned';
            row.appendChild(cell);
            el.itemsBody.appendChild(row);
            return;
        }
        items.forEach(function (item) {
            var tr = document.createElement('tr');
            addCell(tr, item.description, false);
            addCell(tr, formatNumber(item.quantity), true);
            addCell(tr, formatMoney(item.unitPrice, currency), true);
            addCell(tr, item.taxRate == null ? null : formatNumber(item.taxRate) + '%', true);
            addCell(tr, formatMoney(item.lineTotal, currency), true);
            el.itemsBody.appendChild(tr);
        });
    }

    function addCell(tr, value, numeric) {
        var td = document.createElement('td');
        if (numeric) td.className = 'num';
        setValue(td, value, 'Not available');
        tr.appendChild(td);
    }

    function addTotal(label, value, grand) {
        var div = document.createElement('div');
        if (grand) div.className = 'grand';
        var dt = document.createElement('dt');
        dt.textContent = label;
        var dd = document.createElement('dd');
        setValue(dd, value, 'Not available');
        div.appendChild(dt);
        div.appendChild(dd);
        el.invoiceTotals.appendChild(div);
    }

    function fillFields(dl, pairs, missingText) {
        clear(dl);
        pairs.forEach(function (pair) {
            var div = document.createElement('div');
            var dt = document.createElement('dt');
            dt.textContent = pair[0];
            var dd = document.createElement('dd');
            setValue(dd, pair[1], missingText);
            div.appendChild(dt);
            div.appendChild(dd);
            dl.appendChild(div);
        });
    }

    // null/blank becomes a muted placeholder; everything else is plain text.
    function setValue(node, value, missingText) {
        if (value == null || String(value).trim() === '') {
            var span = document.createElement('span');
            span.className = 'missing';
            span.textContent = missingText;
            node.appendChild(span);
        } else {
            node.textContent = String(value);
        }
    }

    function clear(node) {
        while (node.firstChild) node.removeChild(node.firstChild);
    }

    // ---------- review & correction (one state per file, so a batch never mixes them up) ----------

    var INVOICES_URL = 'api/invoices/';

    // Identifiers are plain text inputs: "000000005" must stay "000000005".
    var HEADER_FIELDS = [
        { name: 'invoiceNumber', label: 'Invoice number', required: true, maxLength: 255 },
        { name: 'invoiceDate', label: 'Invoice date', type: 'date' },
        { name: 'sellerName', label: 'Seller', maxLength: 255 },
        { name: 'sellerTaxId', label: 'Seller tax ID', maxLength: 255 },
        { name: 'buyerName', label: 'Buyer', maxLength: 255 },
        { name: 'buyerTaxId', label: 'Buyer tax ID', maxLength: 255 },
        { name: 'currency', label: 'Currency (ISO code, e.g. INR)', maxLength: 3, kind: 'currency' }
    ];
    var TOTAL_FIELDS = [
        { name: 'subtotal', label: 'Subtotal', kind: 'money' },
        { name: 'discountAmount', label: 'Discount (leave empty if none)', kind: 'money' },
        { name: 'shippingAmount', label: 'Shipping & handling (leave empty if none)', kind: 'money' },
        { name: 'taxAmount', label: 'Tax amount', kind: 'money' },
        { name: 'totalAmount', label: 'Grand total', kind: 'money', required: true }
    ];
    var ITEM_FIELDS = [
        { name: 'description', label: 'Description', maxLength: 1000 },
        { name: 'quantity', label: 'Quantity', kind: 'quantity' },
        { name: 'unitPrice', label: 'Unit price', kind: 'money' },
        { name: 'taxRate', label: 'Tax rate %', kind: 'rate' },
        { name: 'lineTotal', label: 'Line total', kind: 'money' }
    ];
    // Same limits as the database columns: numeric(19,2), numeric(19,3), numeric(7,3).
    var DECIMALS = {
        money: { re: /^\d{1,17}(\.\d{1,2})?$/, scale: 2, hint: 'Use a number with up to 2 decimals and a dot, e.g. 1250.50' },
        quantity: { re: /^\d{1,16}(\.\d{1,3})?$/, scale: 3, hint: 'Use a number with up to 3 decimals, e.g. 2.5' },
        rate: { re: /^\d{1,4}(\.\d{1,3})?$/, scale: 3, hint: 'Use a percentage with up to 3 decimals, e.g. 18' }
    };
    var SOURCE_LABELS = {
        LLM_EXTRACTION: 'Original extraction',
        MANUAL_EDIT: 'Corrected',
        LEGACY_BASELINE: 'Legacy baseline (unverified pre-edit state)'
    };
    var FIELD_LABELS = {};
    HEADER_FIELDS.concat(TOTAL_FIELDS).forEach(function (f) { FIELD_LABELS[f.name] = f.label.replace(/ \(.*\)$/, ''); });
    var ITEM_LABELS = {};
    ITEM_FIELDS.forEach(function (f) { ITEM_LABELS[f.name] = f.label; });

    function newRecord(file) {
        return {
            file: file, state: 'pending', httpStatus: null, data: null, startedAt: null,
            editing: false, draft: null, dirty: false, saving: false, save: null, fieldErrors: {},
            versions: null, versionsState: 'idle', comparison: null
        };
    }

    function anySaving() {
        return records.some(function (r) { return r.saving; });
    }

    function anyDirty() {
        return records.some(function (r) { return r.editing && r.dirty; });
    }

    function confirmDiscard() {
        return !anyDirty() || window.confirm('You have unsaved corrections. Discard them?');
    }

    function canEdit(inv) {
        return inv && inv.status === 'EXTRACTED' && inv.id != null && typeof inv.currentVersion === 'number';
    }

    function renderReview(record) {
        var inv = record.data;
        var editable = canEdit(inv);
        renderVersionBar(record);
        renderSaveStatus(record);

        var editing = editable && record.editing;
        el.invoiceView.hidden = editing;
        el.editActions.hidden = !editable || editing;
        el.editBtn.disabled = record.saving;
        el.editForm.hidden = !editing;
        if (editing) {
            renderEditForm(record);
        }
        renderHistory(record);
    }

    function renderVersionBar(record) {
        var inv = record.data;
        if (!canEdit(inv)) {
            el.versionBar.hidden = true;
            return;
        }
        el.versionBar.hidden = false;
        var v = inv.currentVersion;
        var entry = findVersion(record, v);
        if (v === 0) {
            el.versionLabel.textContent = 'No version history yet';
            el.versionMeta.textContent = 'Created before versioning; the first saved correction also keeps the current state.';
        } else {
            el.versionLabel.textContent = 'Version ' + v + (entry ? ' — ' + SOURCE_LABELS[entry.changeSource] : '');
            el.versionMeta.textContent = entry && entry.createdAt ? 'saved ' + formatDateTime(entry.createdAt) : '';
        }
        var doc = inv.sourceDocument;
        el.sourceDoc.hidden = !doc;
        if (doc) {
            el.sourceDocHash.textContent = String(doc.sha256).slice(0, 12) + '…';
            el.sourceDocHash.title = String(doc.sha256);
            // Same-origin relative link to the controlled download endpoint (the id is a server-generated UUID).
            el.sourceDocLink.href = String(doc.url).replace(/^\/+/, '');
        }
    }

    function findVersion(record, number) {
        if (!record.versions) return null;
        for (var i = 0; i < record.versions.length; i++) {
            if (record.versions[i].versionNumber === number) return record.versions[i];
        }
        return null;
    }

    function renderSaveStatus(record) {
        var save = record.save;
        el.saveStatus.hidden = !save;
        el.saveStatus.className = 'save-status' + (save ? ' save-status--' + save.kind : '');
        el.saveStatusText.textContent = save ? save.text : '';
        el.reloadLatestBtn.hidden = !(save && save.kind === 'conflict');
    }

    // ----- the form -----

    el.editBtn.addEventListener('click', function () {
        var record = records[activeIndex];
        if (!record || !canEdit(record.data) || record.saving) return;
        followProcessing = false; // a running batch must not switch the view away from the form
        record.editing = true;
        record.draft = draftFrom(record.data);
        record.dirty = false;
        record.fieldErrors = {};
        record.save = null;
        renderActiveResult();
        var first = el.editHeader.querySelector('input');
        if (first) first.focus();
    });

    el.cancelBtn.addEventListener('click', function () {
        var record = records[activeIndex];
        if (!record || record.saving) return;
        if (record.dirty && !window.confirm('Discard your unsaved edits? Nothing stored on the server is changed.')) return;
        // Browser-only: back to the last version loaded from the server. Nothing is deleted anywhere.
        record.editing = false;
        record.draft = null;
        record.dirty = false;
        record.fieldErrors = {};
        record.save = null;
        renderActiveResult();
    });

    el.addItemBtn.addEventListener('click', function () {
        var record = records[activeIndex];
        if (!record || !record.editing || record.saving) return;
        record.draft.items.push({ description: '', quantity: '', unitPrice: '', taxRate: '', lineTotal: '' });
        markDirty(record);
        renderEditForm(record);
        var rows = el.editItems.querySelectorAll('tr');
        var input = rows.length ? rows[rows.length - 1].querySelector('input') : null;
        if (input) input.focus();
    });

    el.editReason.addEventListener('input', function () {
        var record = records[activeIndex];
        if (record && record.editing) {
            record.draft.changeReason = el.editReason.value;
        }
    });

    el.editForm.addEventListener('submit', function (event) {
        event.preventDefault();
        var record = records[activeIndex];
        if (record) saveCorrection(record);
    });

    el.reloadLatestBtn.addEventListener('click', function () {
        var record = records[activeIndex];
        if (!record || record.saving) return;
        if (record.dirty && !window.confirm('Reload the latest version? Your unsaved edits will be discarded.')) return;
        reloadLatest(record);
    });

    /** Text values for the inputs, taken from the server's response. Amounts are padded, never rounded. */
    function draftFrom(inv) {
        var draft = { changeReason: '', items: [] };
        HEADER_FIELDS.forEach(function (f) { draft[f.name] = inv[f.name] == null ? '' : String(inv[f.name]); });
        TOTAL_FIELDS.forEach(function (f) { draft[f.name] = decimalText(inv[f.name], DECIMALS[f.kind].scale); });
        (inv.items || []).forEach(function (item) {
            var row = {};
            ITEM_FIELDS.forEach(function (f) {
                row[f.name] = f.kind ? decimalText(item[f.name], DECIMALS[f.kind].scale) : (item[f.name] == null ? '' : String(item[f.name]));
            });
            draft.items.push(row);
        });
        return draft;
    }

    // 10000 -> "10000.00", 2.5 -> "2.500". Only appends zeros to the text; never rounds a value.
    function decimalText(value, scale) {
        if (value == null) return '';
        var text = String(value);
        var m = /^(\d+)(?:\.(\d+))?$/.exec(text);
        if (!m) return text;
        var fraction = m[2] || '';
        while (fraction.length < scale) fraction += '0';
        return m[1] + (fraction ? '.' + fraction : '');
    }

    function renderEditForm(record) {
        var draft = record.draft;
        clear(el.editHeader);
        HEADER_FIELDS.forEach(function (f) {
            el.editHeader.appendChild(formField(record, f, f.name, draft[f.name]));
        });
        clear(el.editTotals);
        TOTAL_FIELDS.forEach(function (f) {
            el.editTotals.appendChild(formField(record, f, f.name, draft[f.name]));
        });

        clear(el.editItems);
        if (!draft.items.length) {
            var empty = document.createElement('tr');
            empty.className = 'empty-row';
            var cell = document.createElement('td');
            cell.colSpan = 6;
            cell.textContent = 'No line items. Use “Add line item” if the invoice has any.';
            empty.appendChild(cell);
            el.editItems.appendChild(empty);
        }
        draft.items.forEach(function (item, index) {
            var tr = document.createElement('tr');
            ITEM_FIELDS.forEach(function (f) {
                var td = document.createElement('td');
                var path = 'items[' + index + '].' + f.name;
                td.appendChild(input(record, f, path, item[f.name], 'Line ' + (index + 1) + ' ' + f.label));
                td.appendChild(errorSlot(record, path));
                tr.appendChild(td);
            });
            var removeCell = document.createElement('td');
            var remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'btn btn--ghost btn--small';
            remove.textContent = 'Remove';
            remove.setAttribute('aria-label', 'Remove line ' + (index + 1));
            remove.disabled = record.saving;
            remove.addEventListener('click', function () {
                draft.items.splice(index, 1);
                record.fieldErrors = {}; // positions changed, old item errors no longer match
                markDirty(record);
                renderEditForm(record);
            });
            removeCell.appendChild(remove);
            tr.appendChild(removeCell);
            el.editItems.appendChild(tr);
        });

        el.editReason.value = draft.changeReason || '';
        el.editReason.disabled = record.saving;
        el.addItemBtn.disabled = record.saving;
        el.saveBtn.disabled = record.saving;
        el.cancelBtn.disabled = record.saving;
        el.saveBtn.textContent = record.saving ? 'Saving…' : 'Save correction';
        el.unsavedNote.hidden = !record.dirty;
        renderFormError(record);
    }

    function formField(record, f, path, value) {
        var wrap = document.createElement('label');
        wrap.className = 'form-field';
        var label = document.createElement('span');
        label.className = 'form-field__label';
        label.textContent = f.label + (f.required ? ' *' : '');
        wrap.appendChild(label);
        wrap.appendChild(input(record, f, path, value, null));
        wrap.appendChild(errorSlot(record, path));
        return wrap;
    }

    function input(record, f, path, value, ariaLabel) {
        var node = document.createElement('input');
        node.type = f.type === 'date' ? 'date' : 'text';
        node.value = value == null ? '' : value;   // .value, never HTML
        node.autocomplete = 'off';
        node.disabled = record.saving;
        if (f.maxLength) node.maxLength = f.maxLength;
        if (f.kind && DECIMALS[f.kind]) {
            node.inputMode = 'decimal';
            node.className = 'num';
            node.title = DECIMALS[f.kind].hint;
        }
        if (ariaLabel) node.setAttribute('aria-label', ariaLabel);
        node.setAttribute('data-field', path);
        if (record.fieldErrors[path]) node.setAttribute('aria-invalid', 'true');
        node.addEventListener('input', function () {
            setDraftValue(record.draft, path, node.value);
            if (record.fieldErrors[path]) {
                delete record.fieldErrors[path];
                node.removeAttribute('aria-invalid');
                var slot = node.parentNode.querySelector('.field-msg');
                if (slot) { slot.textContent = ''; slot.hidden = true; }
                renderFormError(record);
            }
            markDirty(record);
        });
        return node;
    }

    function errorSlot(record, path) {
        var slot = document.createElement('span');
        slot.className = 'field-msg';
        slot.textContent = record.fieldErrors[path] || '';
        slot.hidden = !record.fieldErrors[path];
        return slot;
    }

    function setDraftValue(draft, path, value) {
        var m = /^items\[(\d+)\]\.(\w+)$/.exec(path);
        if (m) {
            draft.items[Number(m[1])][m[2]] = value;
        } else {
            draft[path] = value;
        }
    }

    function markDirty(record) {
        record.dirty = true;
        if (record === records[activeIndex]) el.unsavedNote.hidden = false;
    }

    // Errors the form cannot show next to an input (or a summary) go into the box above the form.
    function renderFormError(record) {
        var keys = Object.keys(record.fieldErrors);
        if (!keys.length) {
            el.formError.hidden = true;
            el.formError.textContent = '';
            return;
        }
        el.formError.textContent = keys.length === 1 ? 'Please fix 1 field: ' + record.fieldErrors[keys[0]]
            : 'Please fix ' + keys.length + ' fields (marked below).';
        el.formError.hidden = false;
    }

    /** Quick checks before sending. The server repeats every check (and the arithmetic) and has the final word. */
    function validateDraft(draft) {
        var errors = {};
        function check(f, path, raw) {
            var value = String(raw == null ? '' : raw).trim();
            if (!value) {
                if (f.required) errors[path] = (FIELD_LABELS[f.name] || f.label) + ' is required.';
                return;
            }
            if (f.kind && DECIMALS[f.kind]) {
                if (!DECIMALS[f.kind].re.test(value)) errors[path] = DECIMALS[f.kind].hint + '.';
                else if (f.name === 'quantity' && /^0*(\.0*)?$/.test(value)) errors[path] = 'Quantity must be greater than 0.';
            } else if (f.type === 'date' && !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
                errors[path] = 'Use the date format YYYY-MM-DD.';
            } else if (f.kind === 'currency' && !/^[A-Z]{3}$/.test(value)) {
                errors[path] = 'Use a 3-letter ISO code in capitals, e.g. INR or USD.';
            }
        }
        HEADER_FIELDS.concat(TOTAL_FIELDS).forEach(function (f) { check(f, f.name, draft[f.name]); });
        draft.items.forEach(function (item, i) {
            ITEM_FIELDS.forEach(function (f) { check(f, 'items[' + i + '].' + f.name, item[f.name]); });
        });
        return errors;
    }

    // The complete invoice as text; empty -> null. Amounts stay strings so they reach the server exactly as typed.
    function requestBody(record) {
        var draft = record.draft;
        function value(raw) {
            var v = String(raw == null ? '' : raw).trim();
            return v === '' ? null : v;
        }
        var body = { baseVersion: record.data.currentVersion, changeReason: value(draft.changeReason), items: [] };
        HEADER_FIELDS.concat(TOTAL_FIELDS).forEach(function (f) { body[f.name] = value(draft[f.name]); });
        draft.items.forEach(function (item) {
            var row = {};
            ITEM_FIELDS.forEach(function (f) { row[f.name] = value(item[f.name]); });
            body.items.push(row);
        });
        return body;
    }

    function saveCorrection(record) {
        if (record.saving || !record.editing) return; // no double submit
        var errors = validateDraft(record.draft);
        if (Object.keys(errors).length) {
            record.fieldErrors = errors;
            record.save = { kind: 'error', text: 'Not saved yet: please fix the marked fields.' };
            renderActiveResult();
            return;
        }
        record.saving = true;
        record.fieldErrors = {};
        record.save = { kind: 'info', text: 'Saving…' };
        updateControls();
        renderActiveResult();

        var invoiceId = record.data.id;
        Task8Session.apiFetch(INVOICES_URL + encodeURIComponent(invoiceId), {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            body: JSON.stringify(requestBody(record))
        }).then(function (response) {
            return response.text().then(function (text) {
                return { status: response.status, ok: response.ok, data: parseJson(text) };
            });
        }).catch(function () {
            return { status: 0, ok: false, data: null };
        }).then(function (result) {
            record.saving = false;
            handleSaveResult(record, result);
            updateControls();
            if (record === records[activeIndex]) renderActiveResult();
        });
    }

    function handleSaveResult(record, result) {
        var data = result.data;
        if (result.ok && data && data.invoice) {
            // The server's answer is the truth: show exactly what it stored.
            record.data = data.invoice;
            record.editing = false;
            record.draft = null;
            record.dirty = false;
            record.fieldErrors = {};
            var v = data.invoice.currentVersion;
            record.save = data.changed
                ? { kind: 'ok', text: 'Saved as version ' + v + '. The previous version is kept in the history below.' }
                : { kind: 'info', text: 'Nothing changed, so no new version was created (still version ' + v + ').' };
            record.comparison = null;
            loadVersions(record);
            return;
        }
        // Not saved: the edits stay in the form so the user can fix them and try again.
        var message = data && typeof data.message === 'string' ? data.message : '';
        if (result.status === 409 && data && data.error === 'Version conflict') {
            record.save = { kind: 'conflict', text: 'Not saved. This invoice changed since you opened it; '
                + 'reload the latest version before saving. (' + message + ')' };
        } else if (result.status === 422 || result.status === 400) {
            record.fieldErrors = {};
            (data && data.fieldErrors || []).forEach(function (e) {
                if (e && typeof e.field === 'string') record.fieldErrors[e.field] = String(e.message || 'Invalid value');
            });
            record.save = { kind: 'error', text: 'Not saved: ' + (message || 'the server rejected the data.') };
        } else if (result.status === 0) {
            record.save = { kind: 'error', text: 'Not saved: could not reach the server. Your edits are still here; try again.' };
        } else {
            record.save = { kind: 'error', text: 'Not saved (HTTP ' + result.status + ')' + (message ? ': ' + message : '.') };
        }
    }

    function reloadLatest(record) {
        record.saving = true;
        updateControls();
        renderActiveResult();
        Task8Session.apiFetch(INVOICES_URL + encodeURIComponent(record.data.id), { headers: { 'Accept': 'application/json' } })
            .then(function (response) {
                return response.json().then(function (json) { return { ok: response.ok, data: json }; });
            })
            .catch(function () { return { ok: false, data: null }; })
            .then(function (result) {
                record.saving = false;
                if (result.ok && result.data && result.data.id != null) {
                    record.data = result.data;
                    record.editing = false;
                    record.draft = null;
                    record.dirty = false;
                    record.fieldErrors = {};
                    record.comparison = null;
                    record.save = { kind: 'info', text: 'Reloaded version ' + result.data.currentVersion
                        + '. Your unsaved edits were discarded; open the form again to correct it.' };
                    loadVersions(record);
                } else {
                    record.save = { kind: 'conflict', text: 'Could not reload the latest version. Try again.' };
                }
                updateControls();
                if (record === records[activeIndex]) renderActiveResult();
            });
    }

    // ----- version history -----

    function loadVersions(record) {
        if (!record.data || record.data.id == null || !canEdit(record.data)) return;
        record.versionsState = 'loading';
        if (record === records[activeIndex]) renderHistory(record);
        Task8Session.apiFetch(INVOICES_URL + encodeURIComponent(record.data.id) + '/versions', { headers: { 'Accept': 'application/json' } })
            .then(function (response) {
                return response.json().then(function (json) { return { ok: response.ok, data: json }; });
            })
            .catch(function () { return { ok: false, data: null }; })
            .then(function (result) {
                if (result.ok && Array.isArray(result.data)) {
                    record.versions = result.data;
                    record.versionsState = 'loaded';
                } else {
                    record.versionsState = 'error';
                }
                // Only these two parts: re-rendering the form here would steal focus from the user.
                if (record === records[activeIndex] && record.data && record.data.status) {
                    renderVersionBar(record);
                    renderHistory(record);
                }
            });
    }

    function renderHistory(record) {
        if (!canEdit(record.data)) {
            el.history.hidden = true;
            return;
        }
        el.history.hidden = false;
        clear(el.historyList);
        var state = record.versionsState;
        var versions = record.versions || [];
        el.historyStatus.textContent = state === 'loading' ? 'Loading version history…'
            : state === 'error' ? 'Could not load the version history.'
                : !versions.length ? 'No stored versions yet. The first saved correction will create them.'
                    : 'Newest first. Every version is a complete, read-only snapshot of the invoice data.';

        versions.forEach(function (v) {
            var li = document.createElement('li');
            li.className = 'history-item';
            var head = document.createElement('div');
            head.className = 'history-item__head';
            var chip = document.createElement('span');
            chip.className = 'version-chip version-chip--' + v.changeSource;
            chip.textContent = 'V' + v.versionNumber;
            var title = document.createElement('strong');
            title.textContent = SOURCE_LABELS[v.changeSource] || v.changeSource;
            var when = document.createElement('span');
            when.className = 'meta';
            when.textContent = formatDateTime(v.createdAt) + (v.versionNumber === record.data.currentVersion ? ' · current' : '');
            head.appendChild(chip);
            head.appendChild(title);
            head.appendChild(when);
            li.appendChild(head);

            if (v.changeReason) {
                var reason = document.createElement('p');
                reason.className = 'meta history-item__reason';
                reason.textContent = (v.changeSource === 'MANUAL_EDIT' ? 'Reason: ' : '') + v.changeReason;
                li.appendChild(reason);
            }
            if (v.changeSource === 'LLM_EXTRACTION') {
                var note = document.createElement('p');
                note.className = 'meta history-item__reason';
                note.textContent = 'As extracted from the PDF and validated by the server.';
                li.appendChild(note);
            }
            var changes = v.changedFields || [];
            if (changes.length) {
                var details = document.createElement('details');
                var summary = document.createElement('summary');
                summary.textContent = changes.length + (changes.length === 1 ? ' field changed' : ' fields changed');
                details.appendChild(summary);
                var ul = document.createElement('ul');
                ul.className = 'change-list';
                changes.forEach(function (c) {
                    var item = document.createElement('li');
                    item.textContent = describeChange(c);
                    ul.appendChild(item);
                });
                details.appendChild(ul);
                li.appendChild(details);
            }
            el.historyList.appendChild(li);
        });
        renderCompareControls(record);
    }

    function describeChange(c) {
        var label = fieldLabel(c.field);
        if (c.changeType === 'ADDED') return label + ' added: ' + c.to;
        if (c.changeType === 'REMOVED') return label + ' removed: ' + c.from;
        return label + ': ' + shown(c.from) + ' → ' + shown(c.to);
    }

    function shown(value) {
        return value == null || value === '' ? '(empty)' : String(value);
    }

    // "items[0].quantity" -> "Line 1 · Quantity"
    function fieldLabel(path) {
        var m = /^items\[(\d+)\](?:\.(\w+))?$/.exec(path);
        if (m) return 'Line ' + (Number(m[1]) + 1) + (m[2] ? ' · ' + (ITEM_LABELS[m[2]] || m[2]) : '');
        return FIELD_LABELS[path] || path;
    }

    function renderCompareControls(record) {
        var versions = record.versions || [];
        el.compare.hidden = versions.length < 2;
        if (versions.length < 2) {
            el.compareStatus.hidden = true;
            el.compareWrap.hidden = true;
            return;
        }
        var numbers = versions.map(function (v) { return v.versionNumber; }).sort(function (a, b) { return a - b; });
        var from = record.comparison ? record.comparison.from : numbers[0];
        var to = record.comparison ? record.comparison.to : numbers[numbers.length - 1];
        fillSelect(el.compareFrom, numbers, from);
        fillSelect(el.compareTo, numbers, to);
        renderComparison(record);
    }

    function fillSelect(select, numbers, selected) {
        clear(select);
        numbers.forEach(function (n) {
            var option = document.createElement('option');
            option.value = String(n);
            option.textContent = 'Version ' + n;
            option.selected = n === selected;
            select.appendChild(option);
        });
    }

    el.compareBtn.addEventListener('click', function () {
        var record = records[activeIndex];
        if (!record || !record.data) return;
        var from = Number(el.compareFrom.value);
        var to = Number(el.compareTo.value);
        record.comparison = { from: from, to: to, loading: true, result: null, error: null };
        renderComparison(record);
        Task8Session.apiFetch(INVOICES_URL + encodeURIComponent(record.data.id) + '/versions/compare?from=' + from + '&to=' + to,
            { headers: { 'Accept': 'application/json' } })
            .then(function (response) {
                return response.json().then(function (json) { return { ok: response.ok, data: json }; });
            })
            .catch(function () { return { ok: false, data: null }; })
            .then(function (result) {
                if (!record.comparison || record.comparison.from !== from || record.comparison.to !== to) return;
                record.comparison.loading = false;
                if (result.ok && result.data && Array.isArray(result.data.differences)) {
                    record.comparison.result = result.data.differences;
                } else {
                    record.comparison.error = 'Could not compare these versions.';
                }
                if (record === records[activeIndex]) renderComparison(record);
            });
    });

    function renderComparison(record) {
        var c = record.comparison;
        clear(el.compareBody);
        el.compareWrap.hidden = true;
        el.compareStatus.hidden = !c;
        if (!c) return;
        if (c.loading) { el.compareStatus.textContent = 'Comparing…'; return; }
        if (c.error) { el.compareStatus.textContent = c.error; return; }
        if (!c.result.length) {
            el.compareStatus.textContent = 'Version ' + c.from + ' and version ' + c.to + ' contain identical data.';
            return;
        }
        el.compareStatus.textContent = c.result.length + ' difference' + (c.result.length === 1 ? '' : 's')
            + ' between version ' + c.from + ' and version ' + c.to + '.';
        el.compareFromHead.textContent = 'Version ' + c.from;
        el.compareToHead.textContent = 'Version ' + c.to;
        c.result.forEach(function (d) {
            var tr = document.createElement('tr');
            [fieldLabel(d.field), shown(d.from), shown(d.to)].forEach(function (text) {
                var td = document.createElement('td');
                td.textContent = text;
                tr.appendChild(td);
            });
            el.compareBody.appendChild(tr);
        });
        el.compareWrap.hidden = false;
    }

    // ---------- formatting (null always stays null, never 0) ----------

    function formatMoney(value, currency) {
        if (value == null) return null;
        var options = { minimumFractionDigits: 2, maximumFractionDigits: 4 };
        if (currency && /^[A-Z]{3}$/.test(currency)) {
            try {
                return new Intl.NumberFormat(undefined, {
                    style: 'currency', currency: currency,
                    minimumFractionDigits: 2, maximumFractionDigits: 4
                }).format(value);
            } catch (e) { /* unknown code: fall through to a plain number */ }
        }
        return new Intl.NumberFormat(undefined, options).format(value);
    }

    function formatNumber(value) {
        if (value == null) return null;
        return new Intl.NumberFormat(undefined, { maximumFractionDigits: 4 }).format(value);
    }

    function formatCount(value) {
        return value == null ? null : new Intl.NumberFormat().format(value);
    }

    function formatDuration(ms) {
        if (ms == null) return null;
        return ms < 1000 ? ms + ' ms' : (ms / 1000).toFixed(1) + ' s';
    }

    // "2026-10-06" -> "6 Oct 2026", parsed by hand so the timezone can't shift the day.
    function formatDate(iso) {
        if (!iso) return null;
        var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
        if (!m) return iso;
        var months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
        return Number(m[3]) + ' ' + months[Number(m[2]) - 1] + ' ' + m[1];
    }

    function formatDateTime(iso) {
        var m = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/.exec(iso);
        return m ? formatDate(m[1]) + ', ' + m[2] : iso;
    }

    function formatBytes(bytes) {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
    }

    // Only an ADMIN uses this (legacy) workspace. Vendors and reviewers see an honest placeholder until their
    // own pages exist. This is only what the page SHOWS - the server enforces the same rule on every request.
    Task8Session.requireUser(function (me) {
        if (me.role === 'VENDOR' || me.role === 'REVIEWER') {
            window.location.replace(Task8Session.homeFor(me.role));
            return;
        }
        if (me.role !== 'ADMIN') {
            document.getElementById('role-placeholder-text').textContent = 'You are signed in as '
                + Task8Session.roleLabel(me.role) + '. Your own workspace (owned uploads / review queue) is enabled in a later '
                + 'phase of this project. Existing invoices are visible to administrators only.';
            document.getElementById('role-placeholder').hidden = false;
            return;
        }
        document.getElementById('workspace').hidden = false;
        updateControls();
    });

    window.addEventListener('beforeunload', function (event) {
        if (anyDirty()) {
            event.preventDefault();
            event.returnValue = ''; // the browser shows its own "leave page?" prompt
            return;
        }
        releasePreview();
    });
})();
