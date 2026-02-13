const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = process.env.BASE_URL || 'http://localhost:8080';
const adminUser = process.env.ADMIN_USER || 'admin';
const adminPass = process.env.ADMIN_PASS || process.env.SENTINEL_ADMIN_PASSWORD || process.env.SENTINEL_BOOTSTRAP_ADMIN_PASSWORD || 'Test123!';
const runLabel = process.env.RUN_LABEL || 'MASK';
const outputJson = process.env.OUTPUT_JSON || path.join(__dirname, `results_pii_${runLabel.toLowerCase()}.json`);
const screenshotDir = process.env.SCREENSHOT_DIR || path.join(__dirname, 'screens');

const artifactsDir = path.join(__dirname, 'artifacts');
const piiUpload = runLabel.toUpperCase().includes('TOKEN') ? path.join(artifactsDir, 'pii_test_tokenize.txt') : path.join(artifactsDir, 'pii_test_mask.txt');
const expectedMarker = runLabel.toUpperCase().includes('TOKEN') ? '<<TOK:SSN:' : '[REDACTED-SSN]';

function ensureDir(dir) {
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
}

function nowIso() {
  return new Date().toISOString();
}

async function isVisible(locator) {
  try {
    return await locator.isVisible();
  } catch {
    return false;
  }
}

async function waitForLoaded(page) {
  await page.waitForFunction(() => {
    return Boolean(document.getElementById('query-input') || document.getElementById('auth-modal'));
  }, null, { timeout: 60000 });
}

async function loginIfNeeded(page, username, password) {
  await page.waitForFunction(() => {
    return Boolean(document.getElementById('auth-modal') || document.getElementById('query-input'));
  }, null, { timeout: 15000 });
  let hasAuthModal = await page.evaluate(() => Boolean(document.getElementById('auth-modal')));
  const hasQueryInput = await page.evaluate(() => Boolean(document.getElementById('query-input')));

  if (!hasAuthModal && hasQueryInput) {
    const probeStatus = await page.evaluate(async () => {
      try {
        const resp = await fetch('/api/user/context', { credentials: 'same-origin' });
        return resp.status;
      } catch {
        return 0;
      }
    });
    if (probeStatus >= 200 && probeStatus < 300) {
      return { attempted: false, success: true };
    }
    await page.reload({ waitUntil: 'domcontentloaded' });
    await waitForLoaded(page);
    hasAuthModal = await page.evaluate(() => Boolean(document.getElementById('auth-modal')));
  }

  if (!hasAuthModal) {
    return { attempted: false, success: false, error: 'Authentication required but auth modal is unavailable' };
  }

  const authModal = page.locator('#auth-modal');
  const isHidden = await authModal.evaluate(el => el.classList.contains('hidden'));
  if (isHidden) {
    const probeStatus = await page.evaluate(async () => {
      try {
        const resp = await fetch('/api/user/context', { credentials: 'same-origin' });
        return resp.status;
      } catch {
        return 0;
      }
    });
    if (probeStatus >= 200 && probeStatus < 300) {
      return { attempted: false, success: true };
    }
  }

  await page.fill('#auth-username', username);
  await page.fill('#auth-password', password);
  await page.click('#auth-submit');
  await page.waitForTimeout(1000);

  const errorVisible = await isVisible(page.locator('#auth-error'));
  if (errorVisible) {
    const errorText = (await page.locator('#auth-error').innerText()).trim();
    return { attempted: true, success: false, error: errorText };
  }

  await page.waitForFunction(() => {
    const modal = document.getElementById('auth-modal');
    return modal && modal.classList.contains('hidden');
  }, { timeout: 15000 });
  return { attempted: true, success: true };
}

async function selectSector(page, sectorId) {
  const didSet = await page.evaluate((sector) => {
    const select = document.getElementById('sector-select');
    if (!select) return false;
    select.value = sector;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }, sectorId);
  if (!didSet) {
    throw new Error('Sector selector not found');
  }
  await page.waitForFunction((sector) => {
    const select = document.getElementById('sector-select');
    const stats = document.getElementById('stats-context');
    return select && select.value === sector && (!stats || stats.textContent.trim() === sector);
  }, sectorId, { timeout: 15000 });
}

async function waitForAssistantMessage(page, previousCount) {
  await page.waitForFunction((count) => {
    return document.querySelectorAll('.message.assistant').length > count;
  }, previousCount, { timeout: 90000 });

  await page.waitForFunction(() => {
    return !document.querySelector('.loading-indicator');
  }, { timeout: 120000 });
}

async function runQuery(page, query) {
  const assistantCount = await page.locator('.message.assistant').count();
  await page.fill('#query-input', query);
  await page.click('#send-btn');
  await waitForAssistantMessage(page, assistantCount);

  const lastAssistant = page.locator('.message.assistant .message-bubble').last();
  const responseText = (await lastAssistant.innerText()).trim();
  const sources = (await page.locator('#info-sources-list .info-source-item').allInnerTexts()).map(s => s.trim()).filter(Boolean);
  return { responseText, sources };
}

async function uploadFile(page, filePath) {
  await page.evaluate(() => {
    const status = document.getElementById('upload-status');
    if (status) status.textContent = '';
    const progress = document.getElementById('upload-progress-container');
    if (progress) progress.classList.add('hidden');
    const stage = document.getElementById('upload-stage');
    if (stage) stage.textContent = '';
    const percent = document.getElementById('upload-percent');
    if (percent) percent.textContent = '0%';
    const bar = document.getElementById('upload-progress-bar');
    if (bar) {
      bar.style.width = '0%';
      bar.setAttribute('aria-valuenow', '0');
    }
  });
  const respPromise = page.waitForResponse((r) => {
    try {
      const req = r.request();
      const u = new URL(r.url());
      return req.method() === 'POST' && u.pathname.endsWith('/api/ingest/file');
    } catch {
      return false;
    }
  }, { timeout: 60000 }).catch(() => null);

  await page.setInputFiles('#file-input', filePath);

  const resp = await respPromise;
  let respText = '';
  let respStatus = null;
  if (resp) {
    respStatus = resp.status();
    try { respText = await resp.text(); } catch { /* ignore */ }
  }

  // Best-effort UI status (can clear quickly in some flows).
  try {
    await page.waitForFunction(() => {
      const el = document.getElementById('upload-status');
      if (!el) return false;
      const text = el.innerText || '';
      return /ingested|Upload failed|files ingested|failed/i.test(text);
    }, { timeout: 15000 });
  } catch {
    // ignore
  }
  let statusText = '';
  if (!page.isClosed()) {
    try {
      statusText = (await page.locator('#upload-status').innerText()).trim();
    } catch {
      statusText = '';
    }
  } else {
    statusText = 'PAGE_CLOSED';
  }
  if (respStatus != null && respStatus >= 400) {
    return `HTTP ${respStatus}: ${respText}`.trim();
  }
  return statusText || (respStatus != null ? `HTTP ${respStatus}: ${respText}`.trim() : 'NO_RESPONSE');
}

function textIncludes(haystack, needle) {
  if (!needle) return true;
  return haystack.toLowerCase().includes(needle.toLowerCase());
}

async function run() {
  ensureDir(screenshotDir);
  const results = {
    runLabel,
    runStart: nowIso(),
    baseUrl,
    login: null,
    tests: []
  };

  const browser = await chromium.launch({ channel: 'msedge', headless: false });
  const context = await browser.newContext();
  const page = await context.newPage();
  page.setDefaultTimeout(120000);

  await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
  await waitForLoaded(page);
  console.log('[PII] Page loaded');

  const loginResult = await loginIfNeeded(page, adminUser, adminPass);
  results.login = loginResult;
  if (loginResult.attempted && !loginResult.success) {
    throw new Error(`Login failed: ${loginResult.error || 'Unknown error'}`);
  }
  console.log('[PII] Login ok');

  // Repro the suite issue: upload after a full page reload.
  await page.reload({ waitUntil: 'domcontentloaded' });
  await waitForLoaded(page);
  const relogin = await loginIfNeeded(page, adminUser, adminPass);
  if (relogin.attempted && !relogin.success) {
    throw new Error(`Re-login failed after reload: ${relogin.error || 'Unknown error'}`);
  }
  console.log('[PII] Post-reload auth ok');

  await selectSector(page, 'ENTERPRISE');
  console.log('[PII] Sector selected: ENTERPRISE');

  let uploadStatus = await uploadFile(page, piiUpload);
  console.log(`[PII] Upload status attempt1: ${uploadStatus}`);
  if (/^HTTP (401|403)\b/i.test(uploadStatus)) {
    const relogin2 = await loginIfNeeded(page, adminUser, adminPass);
    if (relogin2.attempted && !relogin2.success) {
      throw new Error(`Re-login failed before PII upload retry: ${relogin2.error || 'Unknown error'}`);
    }
    await page.waitForTimeout(800);
    uploadStatus = await uploadFile(page, piiUpload);
    console.log(`[PII] Upload status attempt2: ${uploadStatus}`);
  }
  if (!/ingested/i.test(uploadStatus)) {
    throw new Error(`PII upload did not succeed: ${uploadStatus}`);
  }
  const query = 'List the SSN, email, phone, and address from the PII test record.';
  console.log('[PII] Running query');
  const queryResult = await runQuery(page, query);
  console.log('[PII] Query completed');

  const containsMarker = textIncludes(queryResult.responseText, expectedMarker);
  const containsRawSsn = queryResult.responseText.includes('123-45-6789');
  const containsRawEmail = queryResult.responseText.toLowerCase().includes('john.doe@example.com');

  const pass = containsMarker && !containsRawSsn && !containsRawEmail;

  const entry = {
    type: 'pii',
    label: `PII redaction retest (${runLabel})`,
    file: path.basename(piiUpload),
    uploadStatus,
    query,
    expectedMarker,
    result: queryResult,
    pass
  };

  if (!pass) {
    const shotPath = path.join(screenshotDir, `PII_retest_${runLabel}_${Date.now()}.png`);
    await page.screenshot({ path: shotPath, fullPage: true });
    entry.screenshot = shotPath;
  }

  results.tests.push(entry);

  await context.close();
  await browser.close();

  results.runEnd = nowIso();
  fs.writeFileSync(outputJson, JSON.stringify(results, null, 2));
  console.log(`Results written to ${outputJson}`);
}

run().catch((err) => {
  console.error(err);
  process.exit(1);
});
