/*
 * Admin page: dashboard, reviewer routing, approvals (pinned to the submitted version), failed jobs, reopen,
 * users and categories. Every action is a single API call; the server checks the role, the status and the version
 * and answers 4xx with a message, which is shown as text.
 */
(function () {
    'use strict';

    var U = Task8Ui;
    var h = U.h;
    var $ = function (id) { return document.getElementById(id); };

    var reviewers = [];
    var openApproval = null;

    Task8Session.requireRole('ADMIN', function () {
        $('admin-page').hidden = false;
        loadAll();
    });

    $('dash-days').addEventListener('change', loadDashboard);

    function loadAll() {
        loadDashboard();
        loadUsers().then(function () { loadCategories(); loadRouting(); });
        loadApprovals();
        loadFailed();
        loadDecided();
    }

    function say(text, isError) {
        U.message($('admin-message'), text, isError);
        if (text) window.scrollTo(0, 0);
    }

    // ---------------------------------------------------------------- dashboard

    function loadDashboard() {
        U.api('GET', 'api/dashboard/admin?days=' + $('dash-days').value).then(function (r) {
            var body = $('dash-body');
            U.clear(body);
            if (!r.ok) { body.appendChild(h('p', { className: 'notice notice--bad' }, U.errorText(r))); return; }
            var d = r.data;
            var c = d.statusCounts || {};
            body.appendChild(h('p', { className: 'meta' }, 'Business days ' + d.periodFirstDay + ' to ' + d.periodLastDay
                + ' (' + d.timeZone + '). Legacy single-file invoices are not counted here (' + d.legacyInvoices + ').'));
            body.appendChild(h('div', { className: 'stat-grid' }, [
                U.stat('Received today', d.receivedToday), U.stat('Queued', c.QUEUED || 0), U.stat('Extracting', c.PROCESSING || 0),
                U.stat('Waiting (retry / daily reset)', d.queue.waitingForRetryOrDailyReset), U.stat('Failed', c.FAILED || 0),
                U.stat('Needs review', c.NEEDS_REVIEW || 0), U.stat('Waiting for approval', c.PENDING_APPROVAL || 0),
                U.stat('Approved', c.APPROVED || 0), U.stat('Rejected', c.REJECTED || 0)]));

            var q = d.queue.quota;
            body.appendChild(h('h3', { className: 'subhead' }, 'Queue and Gemini pacing'));
            var idle = !d.queue.workerEnabled ? 'The background worker is switched off (INVOICE_WORKER_ENABLED).'
                : !q.configured ? 'Worker idle: ' + q.configurationProblem
                    : q.pausedUntil ? 'Paused: daily Gemini quota reached until ' + U.dateTime(q.pausedUntil) + ' (server time).' : null;
            if (idle) body.appendChild(h('p', { className: 'notice' }, idle));
            body.appendChild(h('div', { className: 'stat-grid' }, [
                U.stat('Model', q.model),
                U.stat('Requests last minute', q.requestsLastMinute + (q.rpmLimit ? ' / ' + q.rpmLimit : '')),
                U.stat('Input tokens last minute', q.inputTokensLastMinute + (q.tpmLimit ? ' / ' + q.tpmLimit : '')),
                U.stat('Requests since daily reset', q.requestsSinceDailyReset + (q.rpdLimit ? ' / ' + q.rpdLimit : '')),
                U.stat('Next daily reset (server time)', U.dateTime(q.nextDailyReset)),
                U.stat('Oldest queued since', U.dateTime(d.queue.oldestQueuedSince))]));
            body.appendChild(h('p', { className: 'meta' }, 'Counts are this application\'s own Gemini calls; Google counts per '
                + 'project, so other users of the same project are not visible here. The daily quota resets at midnight Pacific time.'));

            body.appendChild(h('h3', { className: 'subhead' }, 'Reviewer workload'));
            body.appendChild(U.table(['Reviewer', 'Needs review', 'Waiting for approval'], d.reviewerWorkload.map(function (w) {
                return h('tr', {}, [h('td', {}, w.displayName), h('td', { className: 'num' }, String(w.NEEDS_REVIEW || 0)),
                    h('td', { className: 'num' }, String(w.PENDING_APPROVAL || 0))]);
            })));

            var quality = d.quality;
            body.appendChild(h('h3', { className: 'subhead' }, 'Quality (deterministic checks and human edits - not accuracy)'));
            body.appendChild(h('div', { className: 'stat-grid' }, [
                U.stat('Passed all automatic checks', U.percent(quality.automaticChecksPassed.rate) + ' of '
                    + quality.automaticChecksPassed.extracted),
                U.stat('Correction rate (fields edited)', U.percent(quality.correctionRate.rate) + ' of '
                    + quality.correctionRate.fieldsCompared + ' fields'),
                U.stat('Median extraction time', quality.medianExtractionSeconds.value === null ? '— (needs 20 calls)'
                    : quality.medianExtractionSeconds.value.toFixed(1) + ' s'),
                U.stat('Approved in period', d.decisionsInPeriod.APPROVED), U.stat('Rejected in period', d.decisionsInPeriod.REJECTED)]));
            var defs = h('ul', { className: 'reason-list' }, [
                h('li', {}, quality.automaticChecksPassed.definition), h('li', {}, quality.correctionRate.definition),
                h('li', {}, quality.accuracy.definition), h('li', {}, d.decisionsInPeriod.definition)]);
            body.appendChild(defs);
            var reasons = Object.keys(quality.reviewReasons.counts);
            if (reasons.length) {
                body.appendChild(U.table(['Review reason', 'Count'], reasons.map(function (code) {
                    return h('tr', {}, [h('td', {}, code), h('td', { className: 'num' }, String(quality.reviewReasons.counts[code]))]);
                })));
            }

            body.appendChild(h('h3', { className: 'subhead' }, 'Gemini usage (tokens as reported by Gemini)'));
            body.appendChild(U.table(['Day', 'Model', 'Calls', 'Errors', 'Prompt tokens', 'Output tokens', 'Total tokens'],
                d.geminiUsage.byDayAndModel.map(function (u) {
                    return h('tr', {}, [h('td', {}, String(u.day)), h('td', {}, u.model), h('td', { className: 'num' }, String(u.calls)),
                        h('td', { className: 'num' }, String(u.errors)), h('td', { className: 'num' }, String(u.promptTokens)),
                        h('td', { className: 'num' }, String(u.outputTokens)), h('td', { className: 'num' }, String(u.totalTokens))]);
                })));
            body.appendChild(U.table(['Vendor (user id)', 'Calls', 'Reported tokens'], d.usageByVendor.map(function (u) {
                return h('tr', {}, [h('td', {}, u.vendorUserId === null ? 'legacy uploads' : String(u.vendorUserId)),
                    h('td', { className: 'num' }, String(u.calls)), h('td', { className: 'num' }, String(u.reportedTotalTokens))]);
            })));
        });
    }

    // ---------------------------------------------------------------- routing

    function loadRouting() {
        U.api('GET', 'api/invoices?status=NEEDS_REVIEW&size=200').then(function (r) {
            var body = $('routing-body');
            U.clear(body);
            if (!r.ok) return;
            if (!r.data.length) { body.appendChild(h('p', { className: 'meta' }, 'Nothing needs review.')); return; }
            body.appendChild(U.table(['', 'Invoice', 'File', 'Category', 'Reviewer', 'Reasons'], r.data.map(function (inv) {
                return h('tr', {}, [h('td', {}, h('input', { type: 'checkbox', className: 'route-pick', value: String(inv.id),
                        'aria-label': 'Select invoice ' + inv.id })),
                    h('td', {}, String(inv.id)), h('td', {}, inv.fileName || ''), h('td', {}, inv.categoryCode || '—'),
                    h('td', {}, reviewerName(inv.assignedReviewerId)), h('td', { className: 'num' }, String(inv.reviewReasonCount))]);
            })));
        });
    }

    function selectedIds() {
        return Array.prototype.map.call(document.querySelectorAll('.route-pick:checked'), function (c) { return Number(c.value); });
    }

    $('set-category-btn').addEventListener('click', function () {
        var ids = selectedIds();
        var code = $('route-category').value;
        Promise.all(ids.map(function (id) {
            return U.api('PUT', 'api/admin/invoices/' + id + '/category', { categoryCode: code || null });
        })).then(function (results) {
            var failed = results.filter(function (r) { return !r.ok; });
            say(failed.length ? U.errorText(failed[0]) : 'Category set on ' + results.length + ' invoice(s).', failed.length > 0);
            loadRouting();
        });
    });

    $('assign-btn').addEventListener('click', function () {
        var reviewerId = $('route-reviewer').value;
        U.api('POST', 'api/admin/assignments', { invoiceIds: selectedIds(), reviewerId: reviewerId ? Number(reviewerId) : null })
            .then(function (r) {
                var list = $('routing-results');
                U.clear(list);
                if (!r.ok) { say(U.errorText(r), true); return; }
                r.data.forEach(function (x) { list.appendChild(h('li', {}, 'Invoice ' + x.invoiceId + ': ' + x.message)); });
                loadRouting();
                loadDashboard();
            });
    });

    // ---------------------------------------------------------------- approvals

    function loadApprovals() {
        U.api('GET', 'api/invoices?status=PENDING_APPROVAL&size=200').then(function (r) {
            var body = $('approvals-body');
            U.clear(body);
            if (!r.ok) return;
            if (!r.data.length) { body.appendChild(h('p', { className: 'meta' }, 'Nothing is waiting for approval.')); return; }
            body.appendChild(U.table(['Invoice', 'File', 'Submitted version', 'Reviewer', ''], r.data.map(function (inv) {
                return h('tr', {}, [h('td', {}, String(inv.id)), h('td', {}, inv.fileName || ''),
                    h('td', {}, 'V' + inv.submittedVersion), h('td', {}, reviewerName(inv.assignedReviewerId)),
                    h('td', {}, h('button', { type: 'button', className: 'btn btn--ghost btn--small',
                        onClick: function () { showApproval(inv); } }, 'Review'))]);
            })));
        });
    }

    function showApproval(inv) {
        openApproval = inv;
        $('approval-detail').hidden = false;
        $('approval-title').textContent = 'Invoice ' + inv.id + ' — submitted version V' + inv.submittedVersion;
        $('decision-reason').value = '';
        U.api('GET', 'api/invoices/' + inv.id + '/versions/' + inv.submittedVersion).then(function (r) {
            var fields = $('approval-fields');
            U.clear(fields);
            if (!r.ok) return;
            var s = r.data.snapshot;
            if (r.data.sourceDocument) $('approval-pdf').href = 'api/documents/' + encodeURIComponent(r.data.sourceDocument.id);
            fields.appendChild(U.table(['Field', 'Value'], ['invoiceNumber', 'invoiceDate', 'sellerName', 'buyerName', 'currency',
                'subtotal', 'discountAmount', 'shippingAmount', 'taxAmount', 'totalAmount'].map(function (f) {
                return h('tr', {}, [h('td', {}, f), h('td', {}, s[f] === null || s[f] === undefined ? '—' : String(s[f]))]);
            })));
        });
        U.api('GET', 'api/invoices/' + inv.id + '/versions/compare?from=1&to=' + inv.submittedVersion).then(function (r) {
            var diff = $('approval-diff');
            U.clear(diff);
            if (!r.ok) return;
            if (!r.data.differences.length) { diff.appendChild(h('p', { className: 'meta' }, 'No changes: the extraction was submitted as is.')); return; }
            diff.appendChild(U.table(['Field', 'Extraction (V1)', 'Submitted'], r.data.differences.map(function (c) {
                return h('tr', {}, [h('td', {}, c.field), h('td', {}, c.from === null ? '—' : c.from), h('td', {}, c.to === null ? '—' : c.to)]);
            })));
        });
    }

    function decide(action, needsVersion) {
        if (!openApproval) return;
        var body = { reason: $('decision-reason').value };
        if (needsVersion) body.version = openApproval.submittedVersion;
        U.api('POST', 'api/invoices/' + openApproval.id + '/' + action, body).then(function (r) {
            if (!r.ok) { say(U.errorText(r), true); return; }
            say('Invoice ' + openApproval.id + ' is now ' + U.statusLabel(r.data.status) + '.');
            $('approval-detail').hidden = true;
            openApproval = null;
            loadApprovals();
            loadDecided();
            loadRouting();
            loadDashboard();
        });
    }

    $('approve-btn').addEventListener('click', function () { decide('approve', true); });
    $('reject-btn').addEventListener('click', function () { decide('reject', true); });
    $('send-back-btn').addEventListener('click', function () { decide('send-back', false); });

    // ---------------------------------------------------------------- failed / decided

    function loadFailed() {
        actionList('FAILED', 'failed-body', 'Requeue', 'requeue', 'Nothing has failed.');
    }

    function loadDecided() {
        U.api('GET', 'api/invoices?status=APPROVED&size=100').then(function (approved) {
            U.api('GET', 'api/invoices?status=REJECTED&size=100').then(function (rejected) {
                renderActionTable('decided-body', (approved.data || []).concat(rejected.data || []), 'Reopen', 'reopen',
                    'Nothing approved or rejected yet.');
            });
        });
    }

    function actionList(status, target, label, action, empty) {
        U.api('GET', 'api/invoices?status=' + status + '&size=200').then(function (r) {
            renderActionTable(target, r.ok ? r.data : [], label, action, empty);
        });
    }

    /** One row per invoice with a reason box and an action button (requeue / reopen need a reason). */
    function renderActionTable(target, rows, label, action, empty) {
        var body = $(target);
        U.clear(body);
        if (!rows.length) { body.appendChild(h('p', { className: 'meta' }, empty)); return; }
        body.appendChild(U.table(['Invoice', 'File', 'Status', 'Reason', ''], rows.map(function (inv) {
            var reason = h('input', { type: 'text', maxlength: '500', 'aria-label': 'Reason for invoice ' + inv.id });
            return h('tr', {}, [h('td', {}, String(inv.id)), h('td', {}, inv.fileName || ''), h('td', {}, U.badge(inv.status)),
                h('td', {}, reason),
                h('td', {}, h('button', { type: 'button', className: 'btn btn--ghost btn--small', onClick: function () {
                    U.api('POST', 'api/invoices/' + inv.id + '/' + action, { reason: reason.value }).then(function (r) {
                        if (!r.ok) { say(U.errorText(r), true); return; }
                        say('Invoice ' + inv.id + ' is now ' + U.statusLabel(r.data.status) + '.');
                        loadFailed();
                        loadDecided();
                        loadRouting();
                        loadDashboard();
                    });
                } }, label))]);
        })));
    }

    // ---------------------------------------------------------------- users

    function loadUsers() {
        return U.api('GET', 'api/admin/users').then(function (r) {
            var body = $('users-body');
            U.clear(body);
            if (!r.ok) return;
            reviewers = r.data.filter(function (u) { return u.role === 'REVIEWER' && u.enabled; });
            var select = $('route-reviewer');
            U.clear(select);
            select.appendChild(h('option', { value: '' }, '— unassign —'));
            reviewers.forEach(function (u) { select.appendChild(h('option', { value: String(u.id) }, u.displayName)); });

            body.appendChild(U.table(['Id', 'Email', 'Name', 'Role', 'Enabled', 'Categories (reviewers)'], r.data.map(function (u) {
                var role = h('select', { 'aria-label': 'Role of user ' + u.id }, ['VENDOR', 'REVIEWER', 'ADMIN'].map(function (x) {
                    var o = h('option', { value: x }, x);
                    if (x === u.role) o.selected = true;
                    return o;
                }));
                role.addEventListener('change', function () {
                    U.api('PUT', 'api/admin/users/' + u.id + '/role', { role: role.value }).then(after);
                });
                var enabled = h('button', { type: 'button', className: 'btn btn--ghost btn--small', onClick: function () {
                    U.api('PUT', 'api/admin/users/' + u.id + '/enabled', { enabled: !u.enabled }).then(after);
                } }, u.enabled ? 'Disable' : 'Enable');
                var cats = h('td', {}, u.role === 'REVIEWER' ? categoryEditor(u) : '—');
                return h('tr', {}, [h('td', {}, String(u.id)), h('td', {}, u.email), h('td', {}, u.displayName),
                    h('td', {}, role), h('td', {}, [u.enabled ? 'yes ' : 'no ', enabled]), cats]);
            })));
        });
    }

    function categoryEditor(user) {
        var box = h('span', { className: 'category-editor' });
        U.api('GET', 'api/admin/users/' + user.id + '/categories').then(function (mine) {
            U.api('GET', 'api/admin/categories').then(function (all) {
                if (!mine.ok || !all.ok) return;
                all.data.filter(function (c) { return c.active; }).forEach(function (c) {
                    var cb = h('input', { type: 'checkbox', value: c.code, 'aria-label': c.displayName + ' for ' + user.displayName });
                    cb.checked = mine.data.indexOf(c.code) >= 0;
                    box.appendChild(h('label', { className: 'meta' }, [cb, ' ' + c.code + ' ']));
                });
                box.appendChild(h('button', { type: 'button', className: 'btn btn--ghost btn--small', onClick: function () {
                    var codes = Array.prototype.map.call(box.querySelectorAll('input:checked'), function (i) { return i.value; });
                    U.api('PUT', 'api/admin/users/' + user.id + '/categories', { categories: codes }).then(after);
                } }, 'Save'));
            });
        });
        return box;
    }

    function after(r) {
        if (!r.ok) { say(U.errorText(r), true); return; }
        say('Saved.');
        loadUsers().then(loadRouting);
    }

    $('new-user-form').addEventListener('submit', function (event) {
        event.preventDefault();
        U.api('POST', 'api/admin/users', { email: $('nu-email').value, displayName: $('nu-name').value,
            role: $('nu-role').value, password: $('nu-password').value }).then(function (r) {
            $('nu-password').value = '';
            if (!r.ok) { say(U.errorText(r), true); return; }
            $('nu-email').value = '';
            $('nu-name').value = '';
            say('User ' + r.data.email + ' created.');
            loadUsers();
        });
    });

    function reviewerName(id) {
        if (id === null || id === undefined) return '— not assigned —';
        for (var i = 0; i < reviewers.length; i++) if (reviewers[i].id === id) return reviewers[i].displayName;
        return 'user ' + id;
    }

    // ---------------------------------------------------------------- categories

    function loadCategories() {
        U.api('GET', 'api/admin/categories').then(function (r) {
            var body = $('categories-body');
            U.clear(body);
            if (!r.ok) return;
            var select = $('route-category');
            U.clear(select);
            select.appendChild(h('option', { value: '' }, '— none —'));
            r.data.filter(function (c) { return c.active; }).forEach(function (c) {
                select.appendChild(h('option', { value: c.code }, c.displayName + ' (' + c.code + ')'));
            });
            body.appendChild(U.table(['Code', 'Name', 'Active', ''], r.data.map(function (c) {
                return h('tr', {}, [h('td', {}, c.code), h('td', {}, c.displayName), h('td', {}, c.active ? 'yes' : 'no'),
                    h('td', {}, h('button', { type: 'button', className: 'btn btn--ghost btn--small', onClick: function () {
                        U.api('PUT', 'api/admin/categories/' + encodeURIComponent(c.code) + '/active', { active: !c.active })
                            .then(function (x) { if (!x.ok) say(U.errorText(x), true); loadCategories(); });
                    } }, c.active ? 'Deactivate' : 'Activate'))]);
            })));
        });
    }

    $('new-category-form').addEventListener('submit', function (event) {
        event.preventDefault();
        U.api('POST', 'api/admin/categories', { code: $('nc-code').value, displayName: $('nc-name').value }).then(function (r) {
            if (!r.ok) { say(U.errorText(r), true); return; }
            $('nc-code').value = '';
            $('nc-name').value = '';
            say('Category ' + r.data.code + ' added.');
            loadCategories();
            loadUsers();
        });
    });
})();
