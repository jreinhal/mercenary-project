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
  await page.waitForSelector('#query-input, #auth-modal', { timeout: 60000 });
}

async function loginIfNeeded(page, username, password) {
  const authModal = page.locator('#auth-modal');
  const isHidden = await authModal.evaluate(el => el.classList.contains('hidden'));
  if (isHidden) return { attempted: false, success: true };

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
  }, previousCount, { timeout: 120000 });

  await page.waitForFunction(() => {
    return !document.querySelector('.loading-indicator');
  }, { timeout: 180000 });
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
  await page.setInputFiles('#file-input', filePath);
  await page.waitForFunction(() => {
    const el = document.getElementById('upload-status');
    if (!el) return false;
    const text = el.innerText || '';
    return /ingested|Upload failed|files ingested|failed/i.test(text);
  }, { timeout: 60000 });
  const statusText = (await page.locator('#upload-status').innerText()).trim();
  return statusText;
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

  const loginResult = await loginIfNeeded(page, adminUser, adminPass);
  results.login = loginResult;
  if (loginResult.attempted && !loginResult.success) {
    throw new Error(`Login failed: ${loginResult.error || 'Unknown error'}`);
  }

  await selectSector(page, 'ENTERPRISE');

  const uploadStatus = await uploadFile(page, piiUpload);
  const query = 'List the SSN, email, phone, and address from the PII test record.';
  const queryResult = await runQuery(page, query);

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
