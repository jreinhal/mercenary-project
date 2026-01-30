const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = process.env.BASE_URL || 'http://localhost:8080';
const adminUser = process.env.ADMIN_USER || 'admin';
const adminPass = process.env.ADMIN_PASS || process.env.SENTINEL_ADMIN_PASSWORD || process.env.SENTINEL_BOOTSTRAP_ADMIN_PASSWORD || 'Test123!';
const outputJson = process.env.OUTPUT_JSON || path.join(__dirname, 'results_investigate_budget.json');
const screenshotDir = process.env.SCREENSHOT_DIR || path.join(__dirname, 'screens');

const queries = [
  {
    sector: 'ENTERPRISE',
    query: 'What is the total program budget?',
    expected: '$150 Million'
  },
  {
    sector: 'FINANCE',
    query: 'What was total revenue for Q4 2025?',
    expected: '$850 Million'
  },
  {
    sector: 'ACADEMIC',
    query: 'What is the total program budget?',
    expected: '$18.7M'
  }
];

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

async function enableReasoning(page) {
  await page.evaluate(() => {
    const toggle = document.getElementById('show-reasoning');
    if (toggle && !toggle.checked) {
      toggle.checked = true;
      toggle.dispatchEvent(new Event('change', { bubbles: true }));
    }
  });
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

  const message = page.locator('.message.assistant').last();
  const responseText = (await message.locator('.message-bubble').innerText()).trim();
  const sources = (await page.locator('#info-sources-list .info-source-item').allInnerTexts()).map(s => s.trim()).filter(Boolean);

  const reasoningToggle = message.locator('.reasoning-toggle');
  if (await reasoningToggle.count()) {
    await reasoningToggle.click();
  }

  const reasoningSteps = await message.evaluate((el) => {
    const steps = Array.from(el.querySelectorAll('.reasoning-step')).map(step => {
      const label = step.querySelector('.reasoning-step-label')?.innerText?.trim() || '';
      const detail = step.querySelector('.reasoning-step-detail')?.innerText?.trim() || '';
      return { label, detail };
    });
    return steps;
  });

  return { responseText, sources, reasoningSteps };
}

function textIncludes(haystack, needle) {
  if (!needle) return true;
  return haystack.toLowerCase().includes(needle.toLowerCase());
}

async function run() {
  ensureDir(screenshotDir);
  const results = {
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

  await enableReasoning(page);

  for (const item of queries) {
    await selectSector(page, item.sector);
    const result = await runQuery(page, item.query);
    const pass = textIncludes(result.responseText, item.expected);

    const entry = {
      sector: item.sector,
      query: item.query,
      expected: item.expected,
      result,
      pass
    };

    if (!pass) {
      const safeLabel = `investigate_${item.sector}`.replace(/[^a-zA-Z0-9_-]+/g, '_').slice(0, 80);
      const shotPath = path.join(screenshotDir, `${safeLabel}_${Date.now()}.png`);
      await page.screenshot({ path: shotPath, fullPage: true });
      entry.screenshot = shotPath;
    }

    results.tests.push(entry);
  }

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
