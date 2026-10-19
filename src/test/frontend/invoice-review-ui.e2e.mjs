#!/usr/bin/env node
/*
 * Browser tests for the invoice review / correction UI (src/main/resources/static).
 *
 * No npm packages: real headless Chrome (or Edge) driven over the DevTools protocol with Node's built-in
 * WebSocket (Node 22+). Synthetic PDFs only, generated here. Never calls Gemini.
 *
 *   node src/test/frontend/invoice-review-ui.e2e.mjs
 *       "mock" mode: serves the static files plus a scenario-controlled mock API, and checks every UI path
 *       (sequential batch, editing, save, 422/409/network errors, double submit, XSS, isolation, history...).
 *
 *   TASK8_E2E_EMAIL=... TASK8_E2E_PASSWORD=... node src/test/frontend/invoice-review-ui.e2e.mjs --base=http://127.0.0.1:PORT
 *       "real" mode: drives the real Spring Boot backend. Only for the pgtest JUnit test
 *       (FrontendAgainstRealBackendPgIntegrationTest), which runs the app on the TEST database with the
 *       LLM mocked. Never point this at the live application.
 *
 * Env: CHROME_PATH to choose the browser. Exit code 0 = all checks passed.
 */
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';

const HERE = dirname(fileURLToPath(import.meta.url));
const STATIC_DIR = resolve(HERE, '../../main/resources/static');
const BASE_ARG = process.argv.find((a) => a.startsWith('--base='));
const MODE = BASE_ARG ? 'real' : 'mock';

// ------------------------------------------------------------------ tiny test runner

const results = [];
async function check(name, fn) {
    try {
        await fn();
        results.push({ name, ok: true });
        console.log('  PASS  ' + name);
    } catch (e) {
        results.push({ name, ok: false, error: e });
        console.log('  FAIL  ' + name + '\n        ' + (e && e.stack ? e.stack.split('\n').slice(0, 3).join('\n        ') : e));
    }
}
function assert(condition, message) {
    if (!condition) throw new Error(message);
}
function assertEq(actual, expected, message) {
    const a = JSON.stringify(actual);
    const b = JSON.stringify(expected);
    if (a !== b) throw new Error(message + '\n          expected ' + b + '\n          actual   ' + a);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ------------------------------------------------------------------ Chrome over CDP

function findBrowser() {
    const candidates = [process.env.CHROME_PATH,
        'C:/Program Files/Google/Chrome/Application/chrome.exe',
        'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
        'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
        'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
        '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
        '/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser'];
    return candidates.find((p) => p && existsSync(p));
}

async function launchBrowser(profileDir) {
    const path = findBrowser();
    if (!path) throw new Error('No Chrome/Edge found. Set CHROME_PATH.');
    const proc = spawn(path, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
        '--disable-extensions', '--remote-debugging-port=0', '--user-data-dir=' + profileDir, 'about:blank'],
    { stdio: ['ignore', 'ignore', 'pipe'] });
    const wsUrl = await new Promise((resolveUrl, reject) => {
        let buf = '';
        const timer = setTimeout(() => reject(new Error('Browser did not start: ' + buf)), 20000);
        proc.stderr.on('data', (d) => {
            buf += d;
            const m = /DevTools listening on (ws:\/\/[^\s]+)/.exec(buf);
            if (m) { clearTimeout(timer); resolveUrl(m[1]); }
        });
    });
    const port = new URL(wsUrl).port;
    const targets = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json();
    const page = targets.find((t) => t.type === 'page');
    return { proc, pageWsUrl: page.webSocketDebuggerUrl };
}

class Cdp {
    constructor(url) {
        this.nextId = 1;
        this.pending = new Map();
        this.listeners = [];
        this.ws = new WebSocket(url);
        this.ready = new Promise((r, j) => { this.ws.onopen = r; this.ws.onerror = j; });
        this.ws.onmessage = (event) => {
            const msg = JSON.parse(event.data);
            if (msg.id && this.pending.has(msg.id)) {
                const { resolveCall, rejectCall } = this.pending.get(msg.id);
                this.pending.delete(msg.id);
                if (msg.error) rejectCall(new Error(msg.error.message)); else resolveCall(msg.result);
            } else if (msg.method) {
                this.listeners.forEach((l) => l(msg.method, msg.params));
            }
        };
    }
    send(method, params = {}) {
        const id = this.nextId++;
        this.ws.send(JSON.stringify({ id, method, params }));
        return new Promise((resolveCall, rejectCall) => this.pending.set(id, { resolveCall, rejectCall }));
    }
    on(listener) { this.listeners.push(listener); }
}

class Page {
    constructor(cdp) {
        this.cdp = cdp;
        this.errors = [];
        this.dialogs = [];
        this.acceptDialogs = true;
        cdp.on((method, params) => {
            if (method === 'Runtime.exceptionThrown') this.errors.push(params.exceptionDetails.exception
                ? params.exceptionDetails.exception.description : params.exceptionDetails.text);
            if (method === 'Runtime.consoleAPICalled' && params.type === 'error') {
                this.errors.push('console.error: ' + params.args.map((a) => a.value || a.description).join(' '));
            }
            if (method === 'Page.javascriptDialogOpening') {
                this.dialogs.push(params.message);
                cdp.send('Page.handleJavaScriptDialog', { accept: this.acceptDialogs });
            }
        });
    }
    async init() {
        await this.cdp.send('Page.enable');
        await this.cdp.send('Runtime.enable');
        await this.cdp.send('DOM.enable');
        // Count object URLs so the test can prove they are released.
        await this.cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: `
            window.__urls = { created: 0, revoked: 0 };
            const c = URL.createObjectURL.bind(URL), r = URL.revokeObjectURL.bind(URL);
            URL.createObjectURL = (o) => { window.__urls.created++; return c(o); };
            URL.revokeObjectURL = (u) => { window.__urls.revoked++; return r(u); };` });
    }
    async goto(url) {
        const loaded = new Promise((r) => {
            const l = (method) => { if (method === 'Page.loadEventFired') r(); };
            this.cdp.on(l);
        });
        await this.cdp.send('Page.navigate', { url });
        await loaded;
    }
    async eval(expression) {
        const res = await this.cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
        if (res.exceptionDetails) throw new Error('eval failed: ' + expression + ' -> '
            + (res.exceptionDetails.exception ? res.exceptionDetails.exception.description : res.exceptionDetails.text));
        return res.result.value;
    }
    async waitFor(expression, what, timeout = 15000) {
        const end = Date.now() + timeout;
        while (Date.now() < end) {
            try {
                if (await this.eval(expression)) return;
            } catch (e) {
                // the page is navigating (e.g. login redirect); try again
            }
            await sleep(50);
        }
        let where = '';
        try {
            where = await this.eval(`location.pathname + ' | ' + (document.body ? document.body.innerText.replace(/\\s+/g, ' ').slice(0, 160) : '')`);
        } catch (e) {
            where = '(page not readable)';
        }
        throw new Error('Timed out waiting for ' + (what || expression) + ' [at ' + where + ']');
    }
    async setFiles(selector, files) {
        const { root } = await this.cdp.send('DOM.getDocument', { depth: 0 });
        const { nodeId } = await this.cdp.send('DOM.querySelector', { nodeId: root.nodeId, selector });
        await this.cdp.send('DOM.setFileInputFiles', { nodeId, files });
    }
    click(selector) {
        return this.eval(`(function(){ var n = document.querySelector(${JSON.stringify(selector)});
            if (!n) throw new Error('no element ${selector.replace(/'/g, '')}'); n.click(); return true; })()`);
    }
    /** Types into an input the way a user does (value + input event). */
    type(selector, value) {
        return this.eval(`(function(){ var n = document.querySelector(${JSON.stringify(selector)});
            if (!n) throw new Error('no input'); n.value = ${JSON.stringify(value)};
            n.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
    }
    text(selector) {
        return this.eval(`(function(){ var n = document.querySelector(${JSON.stringify(selector)}); return n ? n.textContent : null; })()`);
    }
    visible(selector) {
        return this.eval(`(function(){ var n = document.querySelector(${JSON.stringify(selector)});
            return !!n && !n.closest('[hidden]') && n.getClientRects().length > 0; })()`);
    }
}

// ------------------------------------------------------------------ synthetic PDFs

function writeSyntheticPdfs(dir, names) {
    return names.map((name) => {
        const path = join(dir, name);
        writeFileSync(path, '%PDF-1.4\n% synthetic UI test document ' + name + '\n%%EOF\n');
        return path;
    });
}

// ------------------------------------------------------------------ mock API (mock mode only)

const XSS_TEXT = '<img src=x onerror="window.__xss=1"><script>window.__xss=2</script>';

const MOCK_CSRF = 'mock-csrf-token';
const MOCK_EMAIL = 'admin@example.test';
const MOCK_PASSWORD = 'mock password for the ui test';

function startMockServer() {
    const state = {
        invoices: new Map(), versions: new Map(), nextId: 101,
        inFlightExtract: 0, maxInFlightExtract: 0, extractOrder: [], puts: [],
        nextPut: null, putDelayMs: 0, legacyNext: false, networkDown: false,
        auth: { role: 'ADMIN' }, writes: 0, csrfMissing: [],
        batches: [], batchUploads: [], batchInFlight: 0, batchMaxInFlight: 0
    };

    function newInvoice(fileName, size) {
        const id = state.nextId++;
        const docId = randomUUID();
        const xss = fileName.startsWith('xss');
        const inv = {
            id, status: 'EXTRACTED', invoiceNumber: '000000005', invoiceDate: '2026-10-06',
            sellerName: xss ? XSS_TEXT : 'Synthetic Seller ' + fileName, sellerTaxId: '6000000001',
            buyerName: 'Synthetic Buyer', buyerTaxId: null, currency: 'INR',
            subtotal: 10500, discountAmount: null, shippingAmount: null, taxAmount: 1890, totalAmount: 12390,
            sourceFileName: fileName, sourceFileSize: size, failureReason: null,
            createdAt: '2026-10-16T11:00:00', updatedAt: '2026-10-16T11:00:00',
            items: [
                { description: xss ? XSS_TEXT : 'Laptop Stand', quantity: 2, unitPrice: 5000, taxRate: 18, lineTotal: 10000 },
                { description: 'USB-C Cable', quantity: 1, unitPrice: 500, taxRate: 18, lineTotal: 500 }],
            tokenUsage: { modelId: null, modelVersion: null, promptTokenCount: null, candidatesTokenCount: null,
                thoughtsTokenCount: null, totalTokenCount: null, extractionDurationMs: null },
            currentVersion: 1,
            sourceDocument: { id: docId, sha256: 'ab'.repeat(32), sizeBytes: size, contentType: 'application/pdf',
                originalFileName: fileName, createdAt: '2026-10-16T11:00:00', url: '/api/documents/' + docId }
        };
        const versions = [{ invoiceId: id, versionNumber: 1, changeSource: 'LLM_EXTRACTION', changeReason: null,
            changedBy: null, createdAt: '2026-10-16T11:00:00', sourceDocument: inv.sourceDocument, changedFields: [] }];
        if (state.legacyNext) {
            state.legacyNext = false;
            inv.currentVersion = 0;
            inv.sourceDocument = null;
            versions.length = 0;
        }
        state.invoices.set(id, inv);
        state.versions.set(id, versions);
        return inv;
    }

    const HEADER = ['invoiceNumber', 'invoiceDate', 'sellerName', 'sellerTaxId', 'buyerName', 'buyerTaxId', 'currency'];
    const AMOUNTS = ['subtotal', 'discountAmount', 'shippingAmount', 'taxAmount', 'totalAmount'];
    const ITEM = ['description', 'quantity', 'unitPrice', 'taxRate', 'lineTotal'];
    const asNumber = (v) => (v == null ? null : Number(v)); // what Jackson would serialize for a BigDecimal

    function applyPut(inv, body) {
        const next = JSON.parse(JSON.stringify(inv));
        HEADER.forEach((f) => { next[f] = body[f]; });
        AMOUNTS.forEach((f) => { next[f] = asNumber(body[f]); });
        next.items = body.items.map((i) => ({ description: i.description, quantity: asNumber(i.quantity),
            unitPrice: asNumber(i.unitPrice), taxRate: asNumber(i.taxRate), lineTotal: asNumber(i.lineTotal) }));
        const changes = [];
        HEADER.concat(AMOUNTS).forEach((f) => {
            if (JSON.stringify(inv[f]) !== JSON.stringify(next[f])) changes.push({ field: f, changeType: 'CHANGED', from: inv[f] == null ? null : String(inv[f]), to: next[f] == null ? null : String(next[f]) });
        });
        for (let i = 0; i < Math.max(inv.items.length, next.items.length); i++) {
            if (!inv.items[i]) changes.push({ field: 'items[' + i + ']', changeType: 'ADDED', from: null, to: next.items[i].description });
            else if (!next.items[i]) changes.push({ field: 'items[' + i + ']', changeType: 'REMOVED', from: inv.items[i].description, to: null });
            else ITEM.forEach((f) => {
                if (JSON.stringify(inv.items[i][f]) !== JSON.stringify(next.items[i][f])) {
                    changes.push({ field: 'items[' + i + '].' + f, changeType: 'CHANGED', from: String(inv.items[i][f]), to: String(next.items[i][f]) });
                }
            });
        }
        return { next, changes };
    }

    function json(res, status, body) {
        res.writeHead(status, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(body));
    }

    const server = createServer((req, res) => {
        let raw = Buffer.alloc(0);
        req.on('data', (d) => { raw = Buffer.concat([raw, d]); });
        req.on('end', async () => {
            const url = new URL(req.url, 'http://x');
            const p = url.pathname;
            let m;
            // Like Spring Security: a readable XSRF-TOKEN cookie, and every API write must echo it in X-XSRF-TOKEN.
            res.setHeader('Set-Cookie', 'XSRF-TOKEN=' + MOCK_CSRF + '; Path=/');
            if (req.method !== 'GET' && req.method !== 'HEAD' && p.startsWith('/api/')) {
                state.writes++;
                if (req.headers['x-xsrf-token'] !== MOCK_CSRF) {
                    state.csrfMissing.push(req.method + ' ' + p);
                    return json(res, 403, { status: 403, error: 'Forbidden', message: 'Missing or invalid CSRF token.', path: p });
                }
            }
            if (req.method === 'GET' && p === '/api/auth/me') {
                return state.auth
                    ? json(res, 200, { id: 1, email: MOCK_EMAIL, displayName: 'Mock ' + state.auth.role, role: state.auth.role })
                    : json(res, 401, { status: 401, error: 'Unauthorized', message: 'Please log in.', path: p });
            }
            if (req.method === 'POST' && p === '/api/auth/login') {
                const body = JSON.parse(raw.toString('utf8'));
                if (body.email === MOCK_EMAIL && body.password === MOCK_PASSWORD) {
                    state.auth = { role: 'ADMIN' };
                    return json(res, 200, { id: 1, email: MOCK_EMAIL, displayName: 'Mock ADMIN', role: 'ADMIN' });
                }
                return json(res, 401, { status: 401, error: 'Unauthorized', message: 'Invalid email or password.', path: p });
            }
            if (req.method === 'POST' && p === '/api/auth/logout') {
                state.auth = null;
                res.writeHead(204);
                return res.end();
            }
            if (p.startsWith('/api/') && !state.auth) {
                return json(res, 401, { status: 401, error: 'Unauthorized', message: 'Please log in.', path: p });
            }
            if (req.method === 'GET' && p === '/api/categories') {
                return json(res, 200, [{ code: 'ELECTRONICS', displayName: 'Electronics', active: true }]);
            }
            if (p === '/api/batches' && req.method === 'POST') {
                const body = JSON.parse(raw.toString('utf8'));
                const batch = { id: 900 + state.batches.length, label: body.label || null, status: 'OPEN', source: 'LOCAL',
                    defaultCategoryCode: body.defaultCategoryCode, acceptedCount: 0, rejectedCount: 0,
                    createdAt: '2026-10-19T12:00:00', closedAt: null, invoices: [], rejections: [] };
                state.batches.push(batch);
                return json(res, 201, batch);
            }
            if (p === '/api/batches' && req.method === 'GET') return json(res, 200, state.batches);
            if ((m = /^\/api\/batches\/(\d+)\/files$/.exec(p)) && req.method === 'POST') {
                const batch = state.batches.find((b) => b.id === Number(m[1]));
                const text = raw.toString('latin1');
                const fileName = /filename="([^"]+)"/.exec(text)[1];
                const pathMatch = /name="relativePath"\r\n\r\n([^\r]*)/.exec(text);
                state.batchInFlight++;
                state.batchMaxInFlight = Math.max(state.batchMaxInFlight, state.batchInFlight);
                state.batchUploads.push({ fileName, relativePath: pathMatch ? pathMatch[1] : null });
                await sleep(80);
                state.batchInFlight--;
                if (fileName.startsWith('reject')) {
                    batch.rejectedCount++;
                    batch.rejections.push({ fileName, code: 'INVALID_FILE', message: 'File content is not a valid PDF' });
                    return json(res, 422, { fileName, accepted: false, code: 'INVALID_FILE', message: 'File content is not a valid PDF' });
                }
                batch.acceptedCount++;
                const invoiceId = 5000 + state.batchUploads.length;
                batch.invoices.push({ id: invoiceId, fileName, status: 'QUEUED', queuePosition: batch.invoices.length + 1,
                    failureReason: null, createdAt: '2026-10-19T12:00:01' });
                return json(res, 202, { fileName, accepted: true, invoiceId, status: 'QUEUED', message: 'Accepted and queued for extraction.' });
            }
            if ((m = /^\/api\/batches\/(\d+)\/close$/.exec(p)) && req.method === 'POST') {
                const batch = state.batches.find((b) => b.id === Number(m[1]));
                batch.status = 'CLOSED';
                return json(res, 200, batch);
            }
            if ((m = /^\/api\/batches\/(\d+)$/.exec(p)) && req.method === 'GET') {
                const batch = state.batches.find((b) => b.id === Number(m[1]));
                const counts = {};
                batch.invoices.forEach((i) => { counts[i.status] = (counts[i.status] || 0) + 1; });
                return json(res, 200, { batch, statusCounts: counts, invoices: batch.invoices, rejections: batch.rejections });
            }
            if (req.method === 'POST' && p === '/api/invoices/extract') {
                const fileName = /filename="([^"]+)"/.exec(raw.toString('latin1'))[1];
                state.inFlightExtract++;
                state.maxInFlightExtract = Math.max(state.maxInFlightExtract, state.inFlightExtract);
                state.extractOrder.push(fileName);
                await sleep(250);
                state.inFlightExtract--;
                if (fileName.startsWith('fail')) {
                    return json(res, 422, { timestamp: '2026-10-16T11:00:00', status: 422, error: 'Invoice validation failed',
                        message: 'sum of line totals (1) does not match subtotal (2)', path: p });
                }
                return json(res, 201, newInvoice(fileName, raw.length));
            }
            if ((m = /^\/api\/invoices\/(\d+)$/.exec(p))) {
                const inv = state.invoices.get(Number(m[1]));
                if (!inv) return json(res, 404, { status: 404, error: 'Invoice not found', message: 'Invoice not found', path: p });
                if (req.method === 'GET') return json(res, 200, inv);
                if (req.method === 'PUT') {
                    const body = JSON.parse(raw.toString('utf8'));
                    state.puts.push({ id: inv.id, body });
                    if (state.putDelayMs) await sleep(state.putDelayMs);
                    // Simulated outage: every PUT (including any automatic browser retry) loses its connection.
                    if (state.networkDown) { req.socket.destroy(); return; }
                    const scripted = state.nextPut;
                    state.nextPut = null;
                    if (scripted && scripted.status) return json(res, scripted.status, scripted.body);
                    if (body.baseVersion !== inv.currentVersion) {
                        return json(res, 409, { status: 409, error: 'Version conflict', path: p, message: 'This invoice changed since '
                            + 'you opened it (you edited version ' + body.baseVersion + ', the latest is version ' + inv.currentVersion + ').' });
                    }
                    const { next, changes } = applyPut(inv, body);
                    if (!changes.length) return json(res, 200, { changed: false, invoice: inv });
                    const versions = state.versions.get(inv.id);
                    if (inv.currentVersion === 0) {
                        versions.unshift({ invoiceId: inv.id, versionNumber: 1, changeSource: 'LEGACY_BASELINE', changedBy: null,
                            changeReason: 'Pre-edit state ... NOT a verified original LLM extraction.', createdAt: '2026-10-16T11:01:00',
                            sourceDocument: null, changedFields: [] });
                        inv.currentVersion = 1;
                    }
                    next.currentVersion = inv.currentVersion + 1;
                    versions.unshift({ invoiceId: inv.id, versionNumber: next.currentVersion, changeSource: 'MANUAL_EDIT',
                        changeReason: body.changeReason, changedBy: null, createdAt: '2026-10-16T11:0' + next.currentVersion + ':00',
                        sourceDocument: inv.sourceDocument, changedFields: changes });
                    state.invoices.set(inv.id, next);
                    return json(res, 200, { changed: true, invoice: next });
                }
            }
            if ((m = /^\/api\/invoices\/(\d+)\/versions$/.exec(p))) return json(res, 200, state.versions.get(Number(m[1])) || []);
            if ((m = /^\/api\/invoices\/(\d+)\/versions\/compare$/.exec(p))) {
                const from = url.searchParams.get('from');
                const to = url.searchParams.get('to');
                return json(res, 200, { invoiceId: Number(m[1]), fromVersion: Number(from), toVersion: Number(to),
                    differences: [{ field: 'invoiceNumber', changeType: 'CHANGED', from: '000000005', to: '000000006' }] });
            }
            if (/^\/api\/documents\//.test(p)) {
                res.writeHead(200, { 'Content-Type': 'application/pdf' });
                return res.end('%PDF-1.4 mock');
            }
            // static files
            const file = resolve(STATIC_DIR, '.' + (p === '/' ? '/index.html' : p));
            if (!file.startsWith(STATIC_DIR) || !existsSync(file)) { res.writeHead(404); return res.end(); }
            const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };
            res.writeHead(200, { 'Content-Type': types[extname(file)] || 'application/octet-stream' });
            res.end(readFileSync(file));
        });
    });
    return new Promise((r) => server.listen(0, '127.0.0.1', () => r({ server, state, base: 'http://127.0.0.1:' + server.address().port })));
}

// ------------------------------------------------------------------ scenarios

const ACTIVE = (i) => `#file-list li:nth-child(${i}) .file-row`;
const HEADER_INPUT = (name) => `#edit-header input[data-field="${name}"]`;
const TOTAL_INPUT = (name) => `#edit-totals input[data-field="${name}"]`;
const ITEM_INPUT = (i, name) => `#edit-items input[data-field="items[${i}].${name}"]`;

async function processBatch(page, files) {
    await page.setFiles('#file-input', files);
    await page.waitFor(`document.querySelectorAll('#file-list li').length === ${files.length}`, 'file list');
    await page.click('#process-btn');
    await page.waitFor(`document.getElementById('process-btn').textContent === 'Processed'`, 'batch to finish', 30000);
}

async function openEditor(page) {
    await page.waitFor(`!document.getElementById('edit-actions').hidden`, 'edit button');
    await page.click('#edit-btn');
    await page.waitFor(`!document.getElementById('edit-form').hidden`, 'edit form');
}

async function mockScenarios(page, base, state, tmp) {
    const [okA, failB, okC] = writeSyntheticPdfs(tmp, ['ok-a.pdf', 'fail-b.pdf', 'ok-c.pdf']);
    state.auth = null;
    await page.goto(base + '/');

    await check('signed-out visitor is sent to the login page; the workspace is never shown', async () => {
        await page.waitFor(`location.pathname.endsWith('/login.html') && !!document.querySelector('#login-form[data-ready]')`, 'login page');
    });

    await check('wrong password: the server message is shown as text and the password field is cleared', async () => {
        await page.type('#login-email', MOCK_EMAIL);
        await page.type('#login-password', 'wrong password');
        await page.click('#login-btn');
        await page.waitFor(`!document.getElementById('login-error').hidden`, 'login error');
        assertEq(await page.text('#login-error'), 'Invalid email or password.', 'message');
        assertEq(await page.eval(`document.getElementById('login-password').value`), '', 'password cleared');
    });

    await check('sign-in sends the CSRF header and lands an ADMIN on the administration page', async () => {
        await page.type('#login-password', MOCK_PASSWORD);
        await page.click('#login-btn');
        await page.waitFor(`location.pathname.endsWith('/admin.html') && !document.getElementById('admin-page').hidden`, 'admin page');
        assertEq(await page.text('#user-role'), 'Administrator', 'role label');
        assertEq(state.csrfMissing, [], 'writes without the CSRF header');
    });

    await check('the ADMIN opens the legacy single-file workspace from the admin page', async () => {
        await page.goto(base + '/');
        await page.waitFor(`!document.getElementById('workspace').hidden`, 'workspace');
        assertEq(await page.text('#user-name'), 'Mock ADMIN', 'user name');
    });

    await check('batch of 3 is sent strictly one at a time, in order, and one failure does not stop the others', async () => {
        await processBatch(page, [okA, failB, okC]);
        assertEq(state.maxInFlightExtract, 1, 'parallel extraction requests');
        assertEq(state.extractOrder, ['ok-a.pdf', 'fail-b.pdf', 'ok-c.pdf'], 'order');
        const badges = await page.eval(`[].map.call(document.querySelectorAll('#file-list .badge'), b => b.textContent)`);
        assertEq(badges, ['Extracted', 'Failed', 'Extracted'], 'per-file states');
    });

    await check('failed file shows its own error and no edit controls', async () => {
        await page.click(ACTIVE(2));
        await page.waitFor(`!document.querySelector('[data-state="error"]').hidden`);
        assert((await page.text('#error-title')).includes('422'), 'error title');
        assert(!(await page.visible('#edit-btn')), 'edit button must not be visible for a failed file');
    });

    await check('extracted file shows "Version 1 — Original extraction", the stored PDF and its history', async () => {
        await page.click(ACTIVE(1));
        await page.waitFor(`document.getElementById('version-label').textContent.indexOf('Original extraction') > 0`, 'version label');
        assertEq(await page.text('#version-label'), 'Version 1 — Original extraction', 'label');
        const href = await page.eval(`document.getElementById('source-doc-link').getAttribute('href')`);
        assert(/^api\/documents\/[0-9a-f-]{36}$/.test(href), 'stored PDF link is relative to the controlled endpoint: ' + href);
        assertEq(await page.eval(`document.querySelectorAll('#history-list > li').length`), 1, 'history entries');
        // The preview still shows the LOCAL file (object URL), not the server copy.
        assert((await page.eval(`document.getElementById('preview-frame').src`)).startsWith('blob:'), 'preview is the local object URL');
    });

    await check('edit form is filled from the server response; identifiers keep leading zeros; amounts are text', async () => {
        await openEditor(page);
        assertEq(await page.eval(`document.querySelector('${HEADER_INPUT('invoiceNumber')}').value`), '000000005', 'invoice number');
        assertEq(await page.eval(`document.querySelector('${HEADER_INPUT('sellerTaxId')}').value`), '6000000001', 'seller tax id');
        assertEq(await page.eval(`document.querySelector('${HEADER_INPUT('invoiceNumber')}').type`), 'text', 'identifier input type');
        assertEq(await page.eval(`document.querySelector('${TOTAL_INPUT('subtotal')}').value`), '10500.00', 'subtotal padded, not rounded');
        assertEq(await page.eval(`document.querySelector('${ITEM_INPUT(0, 'quantity')}').value`), '2.000', 'quantity');
        assertEq(await page.eval(`document.querySelector('${TOTAL_INPUT('discountAmount')}').value`), '', 'null discount stays empty');
        assert(!(await page.visible('#invoice-view')), 'read-only view is hidden while editing');
    });

    await check('client-side check blocks a 3-decimal amount next to the field, without sending anything', async () => {
        const before = state.puts.length;
        await page.type(ITEM_INPUT(1, 'unitPrice'), '500.555');
        await page.click('#save-btn');
        await page.waitFor(`document.querySelector('${ITEM_INPUT(1, 'unitPrice')}').getAttribute('aria-invalid') === 'true'`);
        const msg = await page.eval(`document.querySelector('${ITEM_INPUT(1, 'unitPrice')}').parentNode.querySelector('.field-msg').textContent`);
        assert(msg.includes('2 decimals'), 'field message: ' + msg);
        assertEq(state.puts.length, before, 'no request was sent');
        await page.type(ITEM_INPUT(1, 'unitPrice'), '500.00');
        assert(!(await page.eval(`document.querySelector('${ITEM_INPUT(1, 'unitPrice')}').hasAttribute('aria-invalid')`)), 'error clears on input');
    });

    await check('save sends the complete invoice with baseVersion, as text, and only allowed fields', async () => {
        await page.type(HEADER_INPUT('invoiceNumber'), '000000006');
        await page.type(HEADER_INPUT('sellerName'), 'Corrected Seller');
        await page.type('#edit-reason', 'Number misread');
        assert(await page.visible('#unsaved-note'), 'unsaved indicator');
        await page.click('#save-btn');
        await page.waitFor(`document.getElementById('version-label').textContent === 'Version 2 — Corrected'`, 'version 2 label');
        const put = state.puts[state.puts.length - 1];
        assertEq(put.id, 101, 'invoice id');
        assertEq(Object.keys(put.body).sort(), ['baseVersion', 'buyerName', 'buyerTaxId', 'changeReason', 'currency',
            'discountAmount', 'invoiceDate', 'invoiceNumber', 'items', 'sellerName', 'sellerTaxId', 'shippingAmount',
            'subtotal', 'taxAmount', 'totalAmount'], 'request keys (allowlist)');
        assertEq(put.body.baseVersion, 1, 'baseVersion');
        assertEq(put.body.invoiceNumber, '000000006', 'leading zeros sent as text');
        assertEq(put.body.subtotal, '10500.00', 'amount sent as text');
        assertEq(put.body.discountAmount, null, 'empty -> null');
        assertEq(put.body.items.length, 2, 'complete item list');
        assertEq(Object.keys(put.body.items[0]).sort(), ['description', 'lineTotal', 'quantity', 'taxRate', 'unitPrice'], 'item keys');
        assertEq(put.body.changeReason, 'Number misread', 'reason');
    });

    await check('after saving: server data shown, form closed, success message, history V2 + V1 with changes', async () => {
        assert(await page.visible('#invoice-view'), 'view visible again');
        assert(!(await page.visible('#edit-form')), 'form closed');
        assert((await page.text('#save-status-text')).includes('Saved as version 2'), 'success message');
        const fields = await page.eval(`[].map.call(document.querySelectorAll('#invoice-fields dd'), d => d.textContent)`);
        assertEq(fields[0], '000000006', 'view shows the saved invoice number');
        await page.waitFor(`document.querySelectorAll('#history-list > li').length === 2`, 'two history entries');
        const chips = await page.eval(`[].map.call(document.querySelectorAll('#history-list .version-chip'), c => c.textContent)`);
        assertEq(chips, ['V2', 'V1'], 'newest first');
        const changes = await page.eval(`[].map.call(document.querySelectorAll('#history-list > li:first-child .change-list li'), c => c.textContent)`);
        assert(changes.some((c) => c === 'Invoice number: 000000005 → 000000006'), 'change list: ' + JSON.stringify(changes));
    });

    await check('compare V1 with V2 shows the differences', async () => {
        await page.waitFor(`!document.getElementById('compare').hidden`, 'compare controls');
        await page.eval(`document.getElementById('compare-from').value = '1'; document.getElementById('compare-to').value = '2'; true`);
        await page.click('#compare-btn');
        await page.waitFor(`!document.getElementById('compare-wrap').hidden`, 'comparison table');
        const row = await page.eval(`[].map.call(document.querySelectorAll('#compare-body tr:first-child td'), t => t.textContent)`);
        assertEq(row, ['Invoice number', '000000005', '000000006'], 'diff row');
    });

    await check('double-clicking Save sends exactly one request and shows "Saving…"', async () => {
        state.putDelayMs = 600;
        await openEditor(page);
        await page.type(HEADER_INPUT('buyerName'), 'Buyer v3');
        const before = state.puts.length;
        await page.eval(`(function(){ var b = document.getElementById('save-btn'); b.click(); b.click(); b.click(); return true; })()`);
        assertEq(await page.text('#save-btn'), 'Saving…', 'button text while saving');
        assert(await page.eval(`document.getElementById('save-btn').disabled`), 'button disabled while saving');
        assert(await page.eval(`document.getElementById('reset-btn').disabled`), 'reset disabled while saving');
        await page.waitFor(`document.getElementById('version-label').textContent === 'Version 3 — Corrected'`, 'version 3');
        assertEq(state.puts.length - before, 1, 'requests sent');
        state.putDelayMs = 0;
    });

    await check('server 422 shows field errors next to the inputs and keeps the edits', async () => {
        await openEditor(page);
        await page.type(TOTAL_INPUT('totalAmount'), '99999.00');
        state.nextPut = { status: 422, body: { status: 422, error: 'Invoice validation failed', path: '/api/invoices/101',
            message: 'subtotal + taxAmount (12390.00) does not match totalAmount (99999.00)',
            fieldErrors: [{ field: 'totalAmount', message: 'subtotal + taxAmount (12390.00) does not match totalAmount (99999.00)' }] } };
        await page.click('#save-btn');
        await page.waitFor(`document.querySelector('${TOTAL_INPUT('totalAmount')}') && document.querySelector('${TOTAL_INPUT('totalAmount')}').getAttribute('aria-invalid') === 'true'`, 'field error');
        assert((await page.text('#save-status-text')).startsWith('Not saved'), 'not-saved message');
        assertEq(await page.eval(`document.querySelector('${TOTAL_INPUT('totalAmount')}').value`), '99999.00', 'edit kept');
        assert(await page.visible('#edit-form'), 'form still open');
        assertEq(await page.text('#version-label'), 'Version 3 — Corrected', 'version unchanged');
    });

    await check('network failure is never shown as saved; retry then saves exactly once', async () => {
        await page.type(TOTAL_INPUT('totalAmount'), '12390.00');
        await page.type(HEADER_INPUT('buyerName'), 'Buyer v4');
        state.networkDown = true;
        const versionsBefore = state.versions.get(101).length;
        await page.click('#save-btn');
        await page.waitFor(`document.getElementById('save-status-text').textContent.indexOf('could not reach the server') > 0`, 'network message');
        assertEq(await page.text('#version-label'), 'Version 3 — Corrected', 'still version 3');
        assert(await page.visible('#edit-form'), 'edits kept for retry');
        assertEq(state.versions.get(101).length, versionsBefore, 'no version created while the network was down');
        state.networkDown = false;
        const before = state.puts.length;
        await page.click('#save-btn');
        await page.waitFor(`document.getElementById('version-label').textContent === 'Version 4 — Corrected'`, 'version 4 after retry');
        assertEq(state.puts.length - before, 1, 'one request on retry');
    });

    await check('stale version: 409 shows the reload message; reload loads the latest version', async () => {
        await openEditor(page);
        await page.type(HEADER_INPUT('sellerName'), 'My late edit');
        // Someone else saves version 5 in the meantime.
        const inv = state.invoices.get(101);
        state.invoices.set(101, Object.assign({}, inv, { currentVersion: 5, buyerName: 'Saved by someone else' }));
        state.versions.get(101).unshift({ invoiceId: 101, versionNumber: 5, changeSource: 'MANUAL_EDIT', changeReason: null,
            changedBy: null, createdAt: '2026-10-16T11:09:00', sourceDocument: inv.sourceDocument, changedFields: [] });
        await page.click('#save-btn');
        await page.waitFor(`!document.getElementById('reload-latest-btn').hidden`, 'reload button');
        assert((await page.text('#save-status-text')).includes('reload the latest version before saving'), 'conflict message');
        assertEq(await page.eval(`document.querySelector('${HEADER_INPUT('sellerName')}').value`), 'My late edit', 'edit kept until reload');
        page.dialogs.length = 0;
        await page.click('#reload-latest-btn');
        await page.waitFor(`document.getElementById('version-label').textContent === 'Version 5 — Corrected'`, 'version 5 after reload');
        assert(page.dialogs.length === 1, 'asked before discarding the edits');
        assert(!(await page.visible('#edit-form')), 'form closed after reload');
    });

    await check('Cancel only discards local edits (asks first, sends nothing)', async () => {
        const before = state.puts.length;
        await openEditor(page);
        await page.type(HEADER_INPUT('sellerName'), 'Will be cancelled');
        page.dialogs.length = 0;
        await page.click('#cancel-btn');
        await page.waitFor(`document.getElementById('edit-form').hidden`, 'form closed');
        assertEq(page.dialogs.length, 1, 'confirmation asked');
        assertEq(state.puts.length, before, 'no request');
        const fields = await page.eval(`[].map.call(document.querySelectorAll('#invoice-fields dd'), d => d.textContent)`);
        assert(fields.indexOf('Will be cancelled') < 0, 'view shows server data');
    });

    await check('add and remove line items', async () => {
        await openEditor(page);
        await page.click('#add-item-btn');
        assertEq(await page.eval(`document.querySelectorAll('#edit-items tr').length`), 3, 'row added');
        await page.click('#edit-items tr:nth-child(3) button');
        assertEq(await page.eval(`document.querySelectorAll('#edit-items tr').length`), 2, 'row removed');
        await page.click('#edit-items tr:nth-child(2) button');
        await page.type(TOTAL_INPUT('subtotal'), '10000.00');
        await page.type(TOTAL_INPUT('taxAmount'), '1800.00');
        await page.type(TOTAL_INPUT('totalAmount'), '11800.00');
        await page.click('#save-btn');
        await page.waitFor(`document.getElementById('version-label').textContent === 'Version 6 — Corrected'`, 'version 6');
        assertEq(state.puts[state.puts.length - 1].body.items.length, 1, 'one item sent');
        assertEq(await page.eval(`document.querySelectorAll('#items-body tr').length`), 1, 'view shows one item');
    });

    await check('each file keeps its own result, version and unsaved draft', async () => {
        await openEditor(page);
        await page.type(HEADER_INPUT('buyerName'), 'Draft for file A');
        await page.click(ACTIVE(3));
        await page.waitFor(`document.getElementById('version-label').textContent === 'Version 1 — Original extraction'`, 'file C at V1');
        assert(!(await page.visible('#edit-form')), 'file C is not in edit mode');
        assertEq(await page.text('#result-hint'), 'ok-c.pdf', 'file C shown');
        await page.click(ACTIVE(1));
        await page.waitFor(`!document.getElementById('edit-form').hidden`, 'file A back in edit mode');
        assertEq(await page.eval(`document.querySelector('${HEADER_INPUT('buyerName')}').value`), 'Draft for file A', 'draft kept');
        assertEq(await page.text('#version-label'), 'Version 6 — Corrected', 'file A version');
    });

    await check('Reset asks before discarding unsaved edits and releases every object URL', async () => {
        page.dialogs.length = 0;
        page.acceptDialogs = false;
        await page.click('#reset-btn');
        assertEq(page.dialogs.length, 1, 'asked');
        assertEq(await page.eval(`document.querySelectorAll('#file-list li').length`), 3, 'not reset when declined');
        page.acceptDialogs = true;
        await page.click('#reset-btn');
        await page.waitFor(`document.querySelectorAll('#file-list li').length === 0`, 'reset');
        const urls = await page.eval('window.__urls');
        assert(urls.created > 0 && urls.created === urls.revoked, 'object URLs created/revoked: ' + JSON.stringify(urls));
    });

    await check('untrusted invoice text is shown as text, never run as HTML', async () => {
        const [xss] = writeSyntheticPdfs(tmp, ['xss-d.pdf']);
        await processBatch(page, [xss]);
        await page.waitFor(`!document.querySelector('[data-state="invoice"]').hidden`, 'invoice');
        await openEditor(page);
        await page.type(HEADER_INPUT('buyerName'), 'x');
        await page.click('#save-btn');
        await page.waitFor(`document.querySelectorAll('#history-list > li').length === 2`, 'history');
        assertEq(await page.eval('window.__xss === undefined'), true, 'no script ran');
        assertEq(await page.eval(`document.querySelectorAll('#result img, #result script').length`), 0, 'no injected elements');
        const seller = await page.eval(`[].map.call(document.querySelectorAll('#invoice-fields dd'), d => d.textContent)[2]`);
        assertEq(seller, XSS_TEXT, 'shown literally');
    });

    await check('legacy invoice (no history yet) is labelled honestly and sends baseVersion 0', async () => {
        state.legacyNext = true;
        page.acceptDialogs = true;
        await page.click('#reset-btn');
        const [legacy] = writeSyntheticPdfs(tmp, ['ok-legacy.pdf']);
        await processBatch(page, [legacy]);
        await page.waitFor(`document.getElementById('version-label').textContent === 'No version history yet'`, 'legacy label');
        assert(!(await page.visible('#source-doc')), 'no stored PDF claimed for a legacy invoice');
        await openEditor(page);
        await page.type(HEADER_INPUT('sellerName'), 'Legacy fixed');
        await page.click('#save-btn');
        await page.waitFor(`document.getElementById('version-label').textContent === 'Version 2 — Corrected'`, 'version 2');
        assertEq(state.puts[state.puts.length - 1].body.baseVersion, 0, 'baseVersion 0');
        const titles = await page.eval(`[].map.call(document.querySelectorAll('#history-list strong'), s => s.textContent)`);
        assertEq(titles, ['Corrected', 'Legacy baseline (unverified pre-edit state)'], 'history labels');
    });

    await check('phone width (375px): no horizontal page scroll with the form open', async () => {
        await page.cdp.send('Emulation.setDeviceMetricsOverride', { width: 375, height: 800, deviceScaleFactor: 1, mobile: false });
        await openEditor(page);
        await sleep(100);
        const widths = await page.eval(`({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth, inner: window.innerWidth, vv: window.visualViewport && window.visualViewport.width,
            offenders: [].filter.call(document.querySelectorAll('body *'), function (n) {
                var r = n.getBoundingClientRect(); return r.width > 0 && r.right > document.documentElement.clientWidth + 1
                    && !(n.parentElement && n.parentElement.closest('.table-wrap')) && !n.closest('[hidden]');
            }).slice(0, 8).map(function (n) { return n.tagName + (n.id ? '#' + n.id : '') + '.' + n.className + ' w=' + Math.round(n.getBoundingClientRect().width); }) , containers: ['body','.site-header','main','.panel:nth-child(1)','.panel:nth-child(2)','#result','#edit-form','#history','.picker','.actions'].map(function (q) { var n = document.querySelector(q); return n ? q + ' sw=' + n.scrollWidth + ' cw=' + n.clientWidth + ' w=' + Math.round(n.getBoundingClientRect().width) : q + ' none'; }) })`);
        assert(widths.scroll <= widths.client, 'page scrolls horizontally: ' + JSON.stringify(widths));
        await page.cdp.send('Emulation.clearDeviceMetricsOverride');
        page.acceptDialogs = true;
        await page.click('#cancel-btn');
    });

    await check('every API write (extract, save) carried the X-XSRF-TOKEN header', async () => {
        assert(state.writes > 5, 'expected several writes, got ' + state.writes);
        assertEq(state.csrfMissing, [], 'writes without the CSRF header');
    });

    await check('a VENDOR opening the admin workspace is sent to the vendor upload page', async () => {
        state.auth = { role: 'VENDOR' };
        page.acceptDialogs = true;
        await page.goto(base + '/');
        await page.waitFor(`location.pathname.endsWith('/vendor.html') && !document.getElementById('vendor-page').hidden`, 'vendor page');
        assertEq(await page.text('#user-role'), 'Vendor', 'role label');
    });

    await check('vendor batch: files go one per request; non-PDF and sub-folder files are skipped; rejections are shown', async () => {
        const [v1, v2, rejectMe, notPdf] = writeSyntheticPdfs(tmp, ['vendor-1.pdf', 'vendor-2.pdf', 'reject-me.pdf', 'notes.txt']);
        await page.type('#batch-label', 'October');
        await page.click('#new-batch-btn');
        await page.waitFor(`!document.getElementById('upload-area').hidden`, 'upload area');
        await page.setFiles('#files-input', [v1, v2, rejectMe, notPdf]);
        await page.waitFor(`document.getElementById('upload-summary').textContent.indexOf('queued') >= 0`, 'uploads to finish', 20000);
        assertEq(state.batchMaxInFlight, 1, 'parallel uploads');
        assertEq(state.batchUploads.map((u) => u.fileName), ['vendor-1.pdf', 'vendor-2.pdf', 'reject-me.pdf'], 'uploaded files (txt skipped)');
        const states = await page.eval(`[].map.call(document.querySelectorAll('#upload-list li'), li => li.className.replace('upload-list__item ', ''))`);
        assertEq(states, ['is-queued', 'is-queued', 'is-rejected', 'is-skipped'], 'row states');
        assertEq(state.csrfMissing, [], 'uploads without the CSRF header');
    });

    await check('vendor finishes the batch and sees its invoices with queue positions', async () => {
        await page.click('#close-batch-btn');
        await page.waitFor(`!document.getElementById('batch-detail').hidden && document.querySelectorAll('#detail-body tr').length === 2`, 'detail');
        assertEq(state.batches[0].status, 'CLOSED', 'batch closed on the server');
        const cells = await page.eval(`[].map.call(document.querySelectorAll('#detail-body tr td:nth-child(4)'), c => c.textContent)`);
        assertEq(cells, ['#1', '#2'], 'queue positions');
        assert((await page.text('#rejections-list')).includes('reject-me.pdf'), 'rejected file listed');
    });

    await check('Log out ends the session and returns to the login page', async () => {
        await page.click('#logout-btn');
        await page.waitFor(`location.pathname.endsWith('/login.html')`, 'login page');
        assertEq(state.auth, null, 'server-side session cleared');
    });
}

async function realScenarios(page, base, tmp) {
    // The JUnit test makes the mocked extractor return a synthetic invoice with invoice number 000000005.
    const [pdf] = writeSyntheticPdfs(tmp, ['real-backend-synthetic.pdf']);
    const email = process.env.TASK8_E2E_EMAIL;
    const password = process.env.TASK8_E2E_PASSWORD;
    if (!email || !password) throw new Error('real mode needs TASK8_E2E_EMAIL / TASK8_E2E_PASSWORD (a TEST-database user)');
    await page.goto(base + '/');

    await check('[real backend] signed-out visitor is sent to the login page and can sign in', async () => {
        await page.waitFor(`location.pathname.endsWith('/login.html') && !!document.querySelector('#login-form[data-ready]')`, 'login page');
        await page.type('#login-email', email);
        await page.type('#login-password', password);
        await page.click('#login-btn');
        await page.waitFor(`location.pathname.endsWith('/admin.html')`, 'admin page after login', 20000);
        await page.goto(base + '/');
        await page.waitFor(`!document.getElementById('workspace').hidden`, 'workspace', 20000);
    });

    await check('[real backend] the login cookie is HttpOnly: page JavaScript cannot read it', async () => {
        const cookies = await page.eval(`document.cookie`);
        assert(!cookies.includes('TASK8_AUTH'), 'TASK8_AUTH visible to JavaScript: ' + cookies);
        assert(cookies.includes('XSRF-TOKEN='), 'XSRF-TOKEN cookie expected');
    });

    await check('[real backend] extraction shows V1 and the stored PDF is served with the same bytes', async () => {
        await processBatch(page, [pdf]);
        await page.waitFor(`document.getElementById('version-label').textContent === 'Version 1 — Original extraction'`, 'V1');
        const href = await page.eval(`document.getElementById('source-doc-link').getAttribute('href')`);
        const status = await page.eval(`fetch(${JSON.stringify(href)}).then(r => r.status + ' ' + r.headers.get('content-type'))`);
        assertEq(status, '200 application/pdf', 'stored PDF response');
        const text = await page.eval(`fetch(${JSON.stringify(href)}).then(r => r.text())`);
        assert(text.includes('real-backend-synthetic.pdf'), 'served bytes are the uploaded synthetic PDF');
    });

    await check('[real backend] correction from the form is accepted and becomes V2', async () => {
        await openEditor(page);
        assertEq(await page.eval(`document.querySelector('${HEADER_INPUT('invoiceNumber')}').value`), '000000005', 'leading zeros');
        await page.type(HEADER_INPUT('invoiceNumber'), '000000006');
        await page.type(ITEM_INPUT(0, 'description'), 'Laptop Stand (corrected in UI)');
        await page.type('#edit-reason', 'UI end-to-end test');
        await page.click('#save-btn');
        await page.waitFor(`document.getElementById('version-label').textContent === 'Version 2 — Corrected'`, 'V2', 20000);
        await page.waitFor(`document.querySelectorAll('#history-list > li').length === 2`, 'history');
        const changes = await page.eval(`[].map.call(document.querySelectorAll('#history-list > li:first-child .change-list li'), c => c.textContent)`);
        assertEq(changes, ['Invoice number: 000000005 → 000000006', 'Line 1 · Description: Laptop Stand → Laptop Stand (corrected in UI)'], 'changed fields');
    });

    await check('[real backend] invalid totals come back as a field error, nothing saved', async () => {
        await openEditor(page);
        await page.type(TOTAL_INPUT('totalAmount'), '1.00');
        await page.click('#save-btn');
        await page.waitFor(`document.querySelector('${TOTAL_INPUT('totalAmount')}') && document.querySelector('${TOTAL_INPUT('totalAmount')}').getAttribute('aria-invalid') === 'true'`, 'field error', 20000);
        assertEq(await page.text('#version-label'), 'Version 2 — Corrected', 'still V2');
        page.acceptDialogs = true;
        await page.click('#cancel-btn');
    });

    await check('[real backend] a save based on V1 is refused without CSRF (403) and with CSRF as stale (409)', async () => {
        const id = await page.eval(`document.getElementById('invoice-meta').textContent.match(/Invoice ID (\\d+)/)[1]`);
        const status = await page.eval(`fetch('api/invoices/${id}', { method: 'PUT', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ baseVersion: 1, invoiceNumber: 'X', totalAmount: '1.00', items: [] }) }).then(r => r.status)`);
        assertEq(status, 403, 'save without the CSRF header must be refused');
        const stale = await page.eval(`fetch('api/invoices/${id}', { method: 'PUT', headers: { 'Content-Type': 'application/json',
            'X-XSRF-TOKEN': Task8Session.readCookie('XSRF-TOKEN') },
            body: JSON.stringify({ baseVersion: 1, invoiceNumber: 'X', totalAmount: '1.00', items: [] }) }).then(r => r.status)`);
        assertEq(stale, 409, 'stale save status');
    });

    await check('[real backend] compare V1 -> V2', async () => {
        await page.eval(`document.getElementById('compare-from').value = '1'; document.getElementById('compare-to').value = '2'; true`);
        await page.click('#compare-btn');
        await page.waitFor(`!document.getElementById('compare-wrap').hidden`, 'comparison', 20000);
        const rows = await page.eval(`[].map.call(document.querySelectorAll('#compare-body tr'), r => [].map.call(r.cells, c => c.textContent).join(' | '))`);
        assertEq(rows, ['Invoice number | 000000005 | 000000006', 'Line 1 · Description | Laptop Stand | Laptop Stand (corrected in UI)'], 'diff');
    });
}

async function loginAs(page, base, email, password, landing) {
    // Log out first (from whatever app page is open): otherwise login.html would forward the old session.
    await page.eval(`(function () { var m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
        return fetch('/api/auth/logout', { method: 'POST', headers: { 'X-XSRF-TOKEN': m ? decodeURIComponent(m[1]) : '' } })
            .then(function (r) { return r.status; }); })()`);
    await page.goto(base + '/login.html');
    await page.waitFor(`location.pathname.endsWith('/login.html') && !!document.querySelector('#login-form[data-ready]')`, 'login form');
    await page.type('#login-email', email);
    await page.type('#login-password', password);
    await page.click('#login-btn');
    await page.waitFor(`location.pathname.endsWith('/${landing}')`, landing, 20000);
}

/** Vendor uploads -> (worker extracts) -> admin assigns -> reviewer corrects + submits -> admin approves, all in the UI. */
async function realWorkflowScenarios(page, base, tmp) {
    const vendor = process.env.TASK8_E2E_VENDOR_EMAIL;
    const reviewer = process.env.TASK8_E2E_REVIEWER_EMAIL;
    const admin = process.env.TASK8_E2E_EMAIL;
    const password = process.env.TASK8_E2E_PASSWORD;
    const category = process.env.TASK8_E2E_CATEGORY;
    if (!vendor || !reviewer || !category) {
        console.log('  (workflow scenarios skipped: no vendor/reviewer test users given)');
        return;
    }
    const [pdf, fake] = writeSyntheticPdfs(tmp, ['workflow-synthetic.pdf', 'not-a-pdf.pdf']);
    writeFileSync(fake, 'this is not a PDF');
    let invoiceId;

    await check('[workflow] vendor creates a batch, uploads one per request, a fake PDF is rejected by the server', async () => {
        await loginAs(page, base, vendor, password, 'vendor.html');
        await page.waitFor(`!document.getElementById('vendor-page').hidden`, 'vendor page');
        await page.type('#batch-label', 'UI workflow');
        await page.click('#new-batch-btn');
        await page.waitFor(`!document.getElementById('upload-area').hidden`, 'upload area');
        await page.setFiles('#files-input', [pdf, fake]);
        await page.waitFor(`document.getElementById('upload-summary').textContent.indexOf('queued') >= 0`, 'uploads', 30000);
        const states = await page.eval(`[].map.call(document.querySelectorAll('#upload-list li'), li => li.className.replace('upload-list__item ', ''))`);
        assertEq(states, ['is-queued', 'is-rejected'], 'row states');
        invoiceId = await page.eval(`Number(document.querySelector('#upload-list li').textContent.match(/invoice (\\d+)/)[1])`);
        await page.click('#close-batch-btn');
        // The JUnit test runs the worker in the background (mocked LLM); the batch view shows the new status.
        await page.waitFor(`[].some.call(document.querySelectorAll('#detail-body tr'), r => r.textContent.indexOf('Waiting for review') >= 0)`,
            'extraction to finish', 45000);
    });

    await check('[workflow] admin sets the category and assigns the reviewer', async () => {
        await loginAs(page, base, admin, password, 'admin.html');
        await page.waitFor(`!!document.querySelector('.route-pick[value="${invoiceId}"]')`, 'invoice in routing', 20000);
        await page.waitFor(`!!document.querySelector('#route-category option[value="${category}"]')`, 'category option');
        await page.eval(`document.querySelector('.route-pick[value="${invoiceId}"]').checked = true; document.getElementById('route-category').value = '${category}'; true`);
        await page.click('#set-category-btn');
        await page.waitFor(`document.getElementById('admin-message').textContent.indexOf('Category set') >= 0`, 'category set');
        // The table is rebuilt after the change: wait for the NEW row that shows the category.
        await page.waitFor(`(function () { var cb = document.querySelector('.route-pick[value="${invoiceId}"]');
            return !!cb && cb.closest('tr').textContent.indexOf('${category}') >= 0; })()`, 'routing reloaded with the category');
        await page.waitFor(`[].some.call(document.querySelectorAll('#route-reviewer option'), o => o.value !== '')`, 'reviewer option');
        await page.eval(`document.querySelector('.route-pick[value="${invoiceId}"]').checked = true;
            var sel = document.getElementById('route-reviewer'); sel.value = [].filter.call(sel.options, o => o.textContent === ${JSON.stringify(process.env.TASK8_E2E_REVIEWER_NAME)})[0].value; true`);
        await page.click('#assign-btn');
        await page.waitFor(`document.getElementById('routing-results').textContent.indexOf('Assigned.') >= 0`, 'assigned');
    });

    await check('[workflow] reviewer sees the reason, cannot submit, corrects the total (V2) and submits V2', async () => {
        await loginAs(page, base, reviewer, password, 'reviewer.html');
        await page.waitFor(`!!document.querySelector('.open-btn[data-id="${invoiceId}"]')`, 'assigned invoice', 20000);
        await page.click(`.open-btn[data-id="${invoiceId}"]`);
        await page.waitFor(`document.getElementById('reasons').textContent.indexOf('TOTAL_MISMATCH') >= 0`, 'reasons');
        assert((await page.eval(`document.getElementById('pdf-frame').getAttribute('src')`)).startsWith('api/documents/'), 'pdf frame');
        await page.click('#submit-btn');
        await page.waitFor(`document.getElementById('detail-message').textContent.indexOf('fails automatic checks') >= 0`, 'submit refused');
        await page.type('#f-totalAmount', '12390.00'); // 10500.00 + 1890.00 tax
        await page.click('#save-btn');
        await page.waitFor(`document.getElementById('detail-message').textContent.indexOf('Saved as version 2') >= 0`, 'saved V2', 20000);
        await page.waitFor(`document.getElementById('checks').textContent.indexOf('All automatic checks pass') >= 0`, 'checks pass');
        await page.click('#submit-btn');
        await page.waitFor(`document.getElementById('detail-message').textContent.indexOf('Version 2 was submitted') >= 0`, 'submitted', 20000);
    });

    await check('[workflow] admin reviews the V1 -> V2 changes and approves version 2', async () => {
        await loginAs(page, base, admin, password, 'admin.html');
        await page.waitFor(`[].some.call(document.querySelectorAll('#approvals-body tr'), r => r.textContent.indexOf('V2') >= 0)`, 'approval row', 20000);
        await page.eval(`[].filter.call(document.querySelectorAll('#approvals-body tr'), r => r.cells[0] && r.cells[0].textContent === '${invoiceId}')[0].querySelector('button').click(); true`);
        await page.waitFor(`document.querySelectorAll('#approval-diff tr').length > 1`, 'diff');
        assert((await page.text('#approval-diff')).includes('totalAmount'), 'diff lists the corrected total');
        await page.click('#approve-btn');
        await page.waitFor(`document.getElementById('admin-message').textContent.indexOf('is now Approved') >= 0`, 'approved', 20000);
    });
}

// ------------------------------------------------------------------ main

async function main() {
    const tmp = mkdtempSync(join(tmpdir(), 'invoice-ui-e2e-'));
    const profile = join(tmp, 'browser-profile');
    let browser;
    let mock;
    try {
        let base;
        if (MODE === 'mock') {
            mock = await startMockServer();
            base = mock.base;
        } else {
            base = BASE_ARG.slice('--base='.length).replace(/\/+$/, '');
        }
        console.log('Invoice review UI tests (' + MODE + ' mode) against ' + base);
        browser = await launchBrowser(profile);
        const cdp = new Cdp(browser.pageWsUrl);
        await cdp.ready;
        const page = new Page(cdp);
        await page.init();

        if (MODE === 'mock') await mockScenarios(page, base, mock.state, tmp);
        else {
            await realScenarios(page, base, tmp);
            await realWorkflowScenarios(page, base, tmp);
        }

        await check('no JavaScript errors or console errors on the page', async () => {
            assertEq(page.errors, [], 'page errors');
        });
        cdp.ws.close();
    } finally {
        if (browser) browser.proc.kill();
        if (mock) mock.server.close();
        await sleep(300);
        try { rmSync(tmp, { recursive: true, force: true }); } catch (e) { /* browser may still hold a file */ }
    }
    const failed = results.filter((r) => !r.ok);
    console.log('\n' + (results.length - failed.length) + ' passed, ' + failed.length + ' failed (' + results.length + ' checks)');
    process.exit(failed.length ? 1 : 0);
}

main().catch((e) => {
    console.error(e);
    process.exit(2);
});
