const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = process.env.BASE_URL || 'http://localhost:8080';
const adminUser = process.env.ADMIN_USER || 'admin';
const adminPass = process.env.ADMIN_PASS || process.env.SENTINEL_ADMIN_PASSWORD || process.env.SENTINEL_BOOTSTRAP_ADMIN_PASSWORD || 'Test123!';
const scenario = (process.env.SCENARIO || 'HYDE').toUpperCase();
const outputJson = process.env.OUTPUT_JSON || path.join(__dirname, `results_flags_${scenario.toLowerCase()}.json`);
const screenshotDir = process.env.SCREENSHOT_DIR || path.join(__dirname, 'screens');

const scenarios = {
  HYDE: {
    label: 'HyDE enabled',
    tests: [
      { sector: 'ENTERPRISE', query: 'That one report about the budget', expectSources: true },
      { sector: 'ENTERPRISE', query: 'Remember the document about technology initiatives', expectSources: true },
      { sector: 'ENTERPRISE', query: 'The thing with the transformation roadmap', expectText: 'Transformation' }
    ]
  },
  SELFRAG: {
    label: 'SelfRAG enabled',
    tests: [
      { sector: 'ENTERPRISE', query: 'What is the total program budget?', expectText: '$150 Million' },
      { sector: 'FINANCE', query: 'What was total revenue for Q4 2025?', expectText: '$850 Million' },
      { sector: 'ACADEMIC', query: 'What is the total program budget?', expectText: '$18.7M' }
    ]
  },
  AGENTIC: {
    label: 'Agentic enabled',
    tests: [
      { sector: 'ENTERPRISE', query: 'How does the transformation roadmap affect vendor strategy for 2026?', expectText: 'vendor' },
      { sector: 'GOVERNMENT', query: 'How do zero-trust measures relate to Operation Diamond Shield objectives?', expectText: 'zero' }
    ]
  },
  QUCORAG: {
    label: 'QuCoRAG enabled',
    tests: [
      { sector: 'ACADEMIC', query: 'Summarize the NAISR-2024 program, key publications, and funding sources.', expectText: 'NAISR-2024' },
      { sector: 'ENTERPRISE', query: 'What is the total program budget?', expectText: '$150 Million' }
    ]
  },
  CRAG: {
    label: 'CRAG enabled',
    tests: [
      { sector: 'FINANCE', query: 'What was total revenue for Q4 2025?', expectText: '$850 Million' },
      { sector: 'GOVERNMENT', query: 'Who was the Exercise Director?', expectText: 'Colonel James Morrison' }
    ]
  },
  HYDE_QUCORAG: {
    label: 'HyDE + QuCoRAG enabled',
    tests: [
      { sector: 'ENTERPRISE', query: 'Remember the document about technology initiatives', expectSources: true },
      { sector: 'ACADEMIC', query: 'Summarize the NAISR-2024 program, key publications, and funding sources.', expectText: 'NAISR-2024' }
    ]
  },
  CRAG_SELFRAG: {
    label: 'CRAG + SelfRAG enabled',
    tests: [
      { sector: 'ENTERPRISE', query: 'What is the total program budget?', expectText: '$150 Million' },
      { sector: 'FINANCE', query: 'What was total revenue for Q4 2025?', expectText: '$850 Million' }
    ]
  },
  AGENTIC_HYDE: {
    label: 'Agentic + HyDE enabled',
    tests: [
      { sector: 'ENTERPRISE', query: 'Remember how the transformation roadmap affected vendor planning', expectSources: true },
      { sector: 'GOVERNMENT', query: 'Remember the chain of events affecting compliance', expectSources: true }
    ]
  },
  CRAG_QUCORAG: {
    label: 'CRAG + QuCoRAG enabled',
    tests: [
      { sector: 'FINANCE', query: 'What was total revenue for Q4 2025?', expectText: '$850 Million' },
      { sector: 'ACADEMIC', query: 'What is the total program budget?', expectText: '$18.7M' }
    ]
  }
};

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
  const placeholderVisible = await isVisible(page.locator('#graph-placeholder'));

  return { responseText, sources, placeholderVisible };
}

function textIncludes(haystack, needle) {
  if (!needle) return true;
  return haystack.toLowerCase().includes(needle.toLowerCase());
}

async function run() {
  ensureDir(screenshotDir);
  const scenarioConfig = scenarios[scenario];
  if (!scenarioConfig) {
    throw new Error(`Unknown scenario: ${scenario}`);
  }

  const results = {
    scenario,
    scenarioLabel: scenarioConfig.label,
    runStart: nowIso(),
    baseUrl,
    login: null,
    sectorOptions: [],
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

  await page.waitForSelector('#sector-select', { timeout: 15000 });
  results.sectorOptions = await page.locator('#sector-select option').allInnerTexts();

  for (const test of scenarioConfig.tests) {
    await selectSector(page, test.sector);
    const result = await runQuery(page, test.query);

    let pass = true;
    if (typeof test.expectSources === 'boolean') {
      pass = pass && (test.expectSources ? result.sources.length > 0 : result.sources.length === 0);
    }
    if (test.expectText) {
      pass = pass && textIncludes(result.responseText, test.expectText);
    }

    const entry = {
      sector: test.sector,
      query: test.query,
      expectedText: test.expectText || null,
      expectSources: typeof test.expectSources === 'boolean' ? test.expectSources : null,
      result,
      pass
    };

    if (!pass) {
      const safeLabel = `${scenario}_${test.sector}`.replace(/[^a-zA-Z0-9_-]+/g, '_').slice(0, 80);
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
