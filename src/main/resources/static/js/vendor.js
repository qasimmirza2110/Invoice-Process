/*
 * Vendor page: start a batch, add PDFs (files or a folder), finish the batch, watch the progress.
 *
 * - Files are sent ONE PER REQUEST (POST api/batches/{id}/files), strictly one after another.
 * - The checks here (PDF, 10 MB, top-level folder files, 100 per batch) only save time: the server applies the
 *   same rules to every request and is the one that decides.
 * - Progress is read from the server (polling every 10 s), never invented by a client-side timer.
 * - Every value from the server is written with textContent.
 */
(function () {
    'use strict';

    var MAX_FILES = 100;
    var MAX_BYTES = 10 * 1024 * 1024;
    var POLL_MS = 10000;

    var el = {
        page: document.getElementById('vendor-page'),
        newBatchForm: document.getElementById('new-batch-form'),
        batchLabel: document.getElementById('batch-label'),
        batchCategory: document.getElementById('batch-category'),
        newBatchBtn: document.getElementById('new-batch-btn'),
        uploadArea: document.getElementById('upload-area'),
        currentBatchLabel: document.getElementById('current-batch-label'),
        filesInput: document.getElementById('files-input'),
        folderInput: document.getElementById('folder-input'),
        uploadList: document.getElementById('upload-list'),
        closeBatchBtn: document.getElementById('close-batch-btn'),
        uploadSummary: document.getElementById('upload-summary'),
        batchesBody: document.getElementById('batches-body'),
        batchesEmpty: document.getElementById('batches-empty'),
        detail: document.getElementById('batch-detail'),
        detailTitle: document.getElementById('detail-title'),
        detailCounts: document.getElementById('detail-counts'),
        detailBody: document.getElementById('detail-body'),
        rejectionsTitle: document.getElementById('rejections-title'),
        rejectionsList: document.getElementById('rejections-list')
    };

    var STATUS_LABELS = {
        QUEUED: 'Queued', PROCESSING: 'Extracting', NEEDS_REVIEW: 'Waiting for review', PENDING_APPROVAL: 'Waiting for approval',
        APPROVED: 'Approved', REJECTED: 'Rejected', FAILED: 'Failed', EXTRACTED: 'Extracted'
    };

    var currentBatch = null;   // the open batch we are uploading into
    var acceptedInBatch = 0;
    var uploading = false;
    var shownBatchId = null;   // the batch whose detail is shown
    var pollTimer = null;

    Task8Session.requireRole('VENDOR', function () {
        el.page.hidden = false;
        loadSummary();
        loadCategories();
        loadBatches();
    });

    /** Own numbers only (the server scopes them): last 7 business days in Asia/Kolkata. */
    function loadSummary() {
        Task8Session.apiFetch('api/dashboard/vendor?days=7').then(readJson).then(function (result) {
            if (!result.ok) return;
            var d = result.data;
            var c = d.statusCounts || {};
            var box = document.getElementById('summary');
            clear(box);
            [['Received today', d.receivedToday], ['Received this week', d.receivedInPeriod], ['Queued', c.QUEUED || 0],
                ['Being reviewed', (c.NEEDS_REVIEW || 0) + (c.PENDING_APPROVAL || 0)], ['Approved', c.APPROVED || 0],
                ['Failed', c.FAILED || 0], ['Gemini calls', d.geminiUsage.calls], ['Tokens reported', d.geminiUsage.reportedTotalTokens]]
                .forEach(function (s) {
                    var stat = document.createElement('div');
                    stat.className = 'stat';
                    var label = document.createElement('div');
                    label.className = 'stat__label';
                    label.textContent = s[0];
                    var value = document.createElement('div');
                    value.className = 'stat__value';
                    value.textContent = String(s[1]);
                    stat.appendChild(label);
                    stat.appendChild(value);
                    box.appendChild(stat);
                });
            document.getElementById('summary-note').textContent = 'Business days in ' + d.timeZone;
        });
    }

    // ---------- start / finish a batch ----------

    el.newBatchForm.addEventListener('submit', function (event) {
        event.preventDefault();
        el.newBatchBtn.disabled = true;
        Task8Session.apiFetch('api/batches', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ label: el.batchLabel.value, defaultCategoryCode: el.batchCategory.value || null })
        }).then(readJson).then(function (result) {
            el.newBatchBtn.disabled = false;
            if (!result.ok) {
                alertText(result.data && result.data.message ? result.data.message : 'Could not start the batch.');
                return;
            }
            currentBatch = result.data;
            acceptedInBatch = 0;
            clear(el.uploadList);
            el.uploadSummary.textContent = '';
            el.currentBatchLabel.textContent = 'Adding files to batch ' + currentBatch.id
                + (currentBatch.label ? ' (' + currentBatch.label + ')' : '') + '.';
            el.uploadArea.hidden = false;
            el.newBatchForm.hidden = true;
            loadBatches();
        });
    });

    el.closeBatchBtn.addEventListener('click', function () {
        if (!currentBatch || uploading) return;
        el.closeBatchBtn.disabled = true;
        Task8Session.apiFetch('api/batches/' + currentBatch.id + '/close', { method: 'POST' }).then(function () {
            el.closeBatchBtn.disabled = false;
            var closedId = currentBatch.id;
            currentBatch = null;
            el.uploadArea.hidden = true;
            el.newBatchForm.hidden = false;
            el.batchLabel.value = '';
            loadBatches();
            showBatch(closedId);
        });
    });

    // ---------- choosing files ----------

    el.filesInput.addEventListener('change', function () {
        queueFiles(toArray(el.filesInput.files), false);
        el.filesInput.value = '';
    });

    el.folderInput.addEventListener('change', function () {
        queueFiles(toArray(el.folderInput.files), true);
        el.folderInput.value = '';
    });

    /** Pre-checks each file (the server repeats them), then uploads the acceptable ones one by one. */
    function queueFiles(files, fromFolder) {
        if (!currentBatch || uploading) return;
        var toUpload = [];
        files.forEach(function (file) {
            var path = file.webkitRelativePath || '';
            var row = addRow(fromFolder && path ? path : file.name);
            if (fromFolder && path.split('/').length > 2) {
                setRow(row, 'skipped', 'Skipped: inside a sub-folder (top-level PDFs only)');
            } else if (!/\.pdf$/i.test(file.name)) {
                setRow(row, 'skipped', 'Skipped: not a .pdf file');
            } else if (file.size > MAX_BYTES) {
                setRow(row, 'skipped', 'Skipped: larger than 10 MB');
            } else if (acceptedInBatch + toUpload.length >= MAX_FILES) {
                setRow(row, 'skipped', 'Skipped: a batch holds at most ' + MAX_FILES + ' files');
            } else {
                setRow(row, 'waiting', 'Waiting…');
                toUpload.push({ file: file, path: fromFolder ? path : null, row: row });
            }
        });
        uploadNext(toUpload, 0);
    }

    function uploadNext(list, index) {
        if (index >= list.length) {
            uploading = false;
            el.closeBatchBtn.disabled = false;
            el.uploadSummary.textContent = acceptedInBatch + ' file(s) queued in this batch.';
            loadBatches();
            return;
        }
        uploading = true;
        el.closeBatchBtn.disabled = true;
        var item = list[index];
        setRow(item.row, 'uploading', 'Uploading…');
        var body = new FormData();
        body.append('file', item.file, item.file.name);
        if (item.path) body.append('relativePath', item.path);

        Task8Session.apiFetch('api/batches/' + currentBatch.id + '/files', { method: 'POST', body: body })
            .then(readJson)
            .catch(function () { return { status: 0, ok: false, data: null }; })
            .then(function (result) {
                if (result.status === 202) {
                    acceptedInBatch++;
                    setRow(item.row, 'queued', 'Queued as invoice ' + result.data.invoiceId);
                } else if (result.data && result.data.message) {
                    setRow(item.row, 'rejected', 'Rejected: ' + result.data.message);
                } else {
                    setRow(item.row, 'rejected', 'Upload failed (HTTP ' + result.status + ')');
                }
                uploadNext(list, index + 1); // continue even when one file failed
            });
    }

    // ---------- batches list and detail (polling) ----------

    function loadBatches() {
        Task8Session.apiFetch('api/batches').then(readJson).then(function (result) {
            if (!result.ok) return;
            clear(el.batchesBody);
            el.batchesEmpty.hidden = result.data.length > 0;
            result.data.forEach(function (b) {
                var tr = document.createElement('tr');
                var idCell = document.createElement('td');
                var link = document.createElement('button');
                link.type = 'button';
                link.className = 'link-btn';
                link.textContent = 'Batch ' + b.id;
                link.addEventListener('click', function () { showBatch(b.id); });
                idCell.appendChild(link);
                tr.appendChild(idCell);
                cell(tr, b.label || '—');
                cell(tr, b.status === 'OPEN' ? 'Open' : 'Finished');
                cell(tr, String(b.acceptedCount), true);
                cell(tr, String(b.rejectedCount), true);
                cell(tr, formatDateTime(b.createdAt));
                el.batchesBody.appendChild(tr);
            });
        });
    }

    function showBatch(batchId) {
        shownBatchId = batchId;
        refreshDetail();
    }

    function refreshDetail() {
        clearTimeout(pollTimer);
        if (shownBatchId == null) return;
        Task8Session.apiFetch('api/batches/' + shownBatchId).then(readJson).then(function (result) {
            if (!result.ok) return;
            var d = result.data;
            el.detail.hidden = false;
            el.detailTitle.textContent = 'Batch ' + d.batch.id + (d.batch.label ? ' — ' + d.batch.label : '');
            var parts = [];
            Object.keys(d.statusCounts).forEach(function (s) { parts.push((STATUS_LABELS[s] || s) + ': ' + d.statusCounts[s]); });
            el.detailCounts.textContent = parts.join(' · ') || 'No invoices yet';

            clear(el.detailBody);
            var busy = false;
            d.invoices.forEach(function (inv) {
                if (inv.status === 'QUEUED' || inv.status === 'PROCESSING') busy = true;
                var tr = document.createElement('tr');
                cell(tr, String(inv.id));
                cell(tr, inv.fileName);
                var statusCell = document.createElement('td');
                var badge = document.createElement('span');
                badge.className = 'badge badge--' + inv.status;
                badge.textContent = STATUS_LABELS[inv.status] || inv.status;
                statusCell.appendChild(badge);
                tr.appendChild(statusCell);
                cell(tr, inv.queuePosition ? '#' + inv.queuePosition : '—');
                cell(tr, inv.failureReason || '');
                el.detailBody.appendChild(tr);
            });

            clear(el.rejectionsList);
            el.rejectionsTitle.hidden = d.rejections.length === 0;
            d.rejections.forEach(function (r) {
                var li = document.createElement('li');
                li.textContent = r.fileName + ' — ' + r.message;
                el.rejectionsList.appendChild(li);
            });

            if (busy) pollTimer = setTimeout(refreshDetail, POLL_MS); // stop polling once nothing is moving
        });
    }

    // ---------- small helpers ----------

    function loadCategories() {
        Task8Session.apiFetch('api/categories').then(readJson).then(function (result) {
            if (!result.ok) return;
            result.data.forEach(function (c) {
                var option = document.createElement('option');
                option.value = c.code;
                option.textContent = c.displayName;
                el.batchCategory.appendChild(option);
            });
        });
    }

    function addRow(name) {
        var li = document.createElement('li');
        var label = document.createElement('span');
        label.className = 'upload-list__name';
        label.textContent = name;
        var state = document.createElement('span');
        state.className = 'upload-list__state';
        li.appendChild(label);
        li.appendChild(state);
        el.uploadList.appendChild(li);
        return li;
    }

    function setRow(li, kind, text) {
        li.className = 'upload-list__item is-' + kind;
        li.querySelector('.upload-list__state').textContent = text;
    }

    function cell(tr, text, numeric) {
        var td = document.createElement('td');
        if (numeric) td.className = 'num';
        td.textContent = text;
        tr.appendChild(td);
    }

    function readJson(response) {
        return response.text().then(function (text) {
            var data = null;
            try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
            return { status: response.status, ok: response.ok, data: data };
        });
    }

    function alertText(text) {
        el.uploadSummary.textContent = text;
    }

    function clear(node) {
        while (node.firstChild) node.removeChild(node.firstChild);
    }

    function toArray(list) {
        return list ? Array.prototype.slice.call(list) : [];
    }

    function formatDateTime(iso) {
        var m = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/.exec(iso || '');
        return m ? m[1] + ' ' + m[2] : (iso || '');
    }
})();
