/**
 * Graph Style Verification E2E Test
 *
 * Validates that the updated graph CSS variables, entity color palette,
 * and rendering are active across ALL sectors and BOTH graph types
 * (Query Results + Entity Network) in light and dark mode.
 *
 * Usage:
 *   node run-ui-graph-styles.js
 *   BASE_URL=http://localhost:8080 SKIP_SEED_DOCS=true node run-ui-graph-styles.js
 */
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = process.env.BASE_URL || 'http://localhost:8080';
const adminUser = process.env.ADMIN_USER || 'admin';
const adminPass = process.env.ADMIN_PASS || process.env.SENTINEL_ADMIN_PASSWORD || process.env.SENTINEL_BOOTSTRAP_ADMIN_PASSWORD || 'Test123!';
const outputJson = process.env.OUTPUT_JSON || path.join(__dirname, 'results_graph_styles.json');
const screenshotDir = process.env.SCREENSHOT_DIR || path.join(__dirname, 'screens');
const skipSeed = String(process.env.SKIP_SEED_DOCS || '').toLowerCase() === 'true';
const testDocsDir = path.resolve(__dirname, '..', '..', 'src', 'test', 'resources', 'test_docs');

const MIN_ACTION_DELAY_MS = 2500;
const ENTITY_TAB_PAUSE_MS = Number.parseInt(process.env.ENTITY_TAB_PAUSE_MS || '3000', 10);

// ---------- Expected values (updated palette) ----------

// Normalize color to lowercase hex for comparison
function normalizeColor(val) {
  if (!val) return null;
  val = val.trim().toLowerCase();
  // Already hex
  if (val.startsWith('#')) return val;
  // rgb(r, g, b)
  const rgbMatch = val.match(/^rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)$/);
  if (rgbMatch) {
    const hex = '#' + [rgbMatch[1], rgbMatch[2], rgbMatch[3]]
      .map(n => parseInt(n, 10).toString(16).padStart(2, '0')).join('');
    return hex;
  }
  return val;
}

// CSS variables that must exist and match the new palette
const EXPECTED_DARK_CSS = {
  '--accent-primary': '#2a6a9e',                  // Steel blue (not #60a5fa)
  '--accent-subtle': true,                        // Must exist (new variable)
  '--accent-glow': true,                          // Must exist (new variable)
  '--accent-border': true,                        // Must exist (new variable)
  '--accent-emphasis': true,                      // Must exist (new variable)
  '--graph-center-node': '#475569',               // Slate steel - command node
  '--graph-source-node': '#4a7c59',               // Muted olive-sage - source docs
  '--graph-entity-node': '#92754c',               // Dim amber-tan - entities
};

const EXPECTED_LIGHT_CSS = {
  '--graph-center-node': '#64748b',               // Slate steel - command node
  '--graph-source-node': '#6b9e7a',               // Muted olive-sage - source docs
  '--graph-entity-node': '#b8976a',               // Dim amber-tan - entities
};

// Unified entity type colors (Okabe-Ito, single source of truth)
const EXPECTED_ENTITY_COLORS = {
  PERSON: '#0077bb',
  ORGANIZATION: '#ee7733',
  LOCATION: '#009988',
  TECHNICAL: '#D55E00',
  TECHNOLOGY: '#D55E00',
  EVENT: '#CC79A7',
  DOCUMENT: '#56B4E9',
  DATE: '#33bbee',
  REFERENCE: '#F0E442',
  DEFAULT: '#999999',
};

// Old palette values that must NOT appear (regression check)
const STALE_VALUES = {
  '--accent-primary-dark-old': '#60a5fa',
  '--accent-hover-dark-old': '#93c5fd',
};

const sectorUploads = {
  ENTERPRISE: ['enterprise_compliance_audit.txt', 'enterprise_transformation.txt'],
  GOVERNMENT: ['defense_diamond_shield.txt', 'defense_cybersecurity.txt'],
  MEDICAL: ['medical_clinical_trial.txt', 'medical_patient_outcomes.txt'],
  FINANCE: ['finance_earnings_q4.txt', 'finance_portfolio_analysis.txt'],
  ACADEMIC: ['academic_research_program.txt', 'academic_publications_review.txt'],
};

const sectorQueries = {
  ENTERPRISE: 'Summarize the Enterprise Compliance Audit report.',
  GOVERNMENT: 'Summarize Operation Diamond Shield.',
  MEDICAL: 'Summarize the clinical trial findings.',
  FINANCE: 'Summarize the Q4 earnings report.',
  ACADEMIC: 'Summarize the research program goals.',
};

// ---------- Helpers ----------

function ensureDir(dir) { fs.mkdirSync(dir, { recursive: true }); }
function nowIso() { return new Date().toISOString(); }

async function isVisible(locator) {
  try {
    return await locator.isVisible({ timeout: 2000 });
  } catch {
    return false;
  }
}

function setHidden(el, hidden) { if (el) el.classList.toggle('hidden', hidden); }

async function waitForLoaded(page) {
  await page.waitForFunction(() => {
    const sidebar = document.getElementById('sidebar');
    const chat = document.getElementById('chat-messages');
    return (sidebar || chat) && !document.querySelector('.loading-overlay:not(.hidden)');
  }, { timeout: 120000 });
}

async function loginIfNeeded(page, user, pass) {
  const modal = page.locator('#auth-modal');
  const visible = await isVisible(modal);
  if (!visible) return { attempted: false, success: true };
  try {
    await page.fill('#auth-username', user, { timeout: 5000 });
    await page.fill('#auth-password', pass, { timeout: 5000 });
    await page.click('#auth-submit');
    await page.waitForFunction(() => {
      const m = document.getElementById('auth-modal');
      return !m || m.classList.contains('hidden') || window.getComputedStyle(m).display === 'none';
    }, { timeout: 30000 });
    return { attempted: true, success: true };
  } catch (err) {
    return { attempted: true, success: false, error: err.message };
  }
}

async function selectSector(page, sectorId) {
  await page.evaluate((sector) => {
    const select = document.getElementById('sector-select');
    if (!select) return;
    select.value = sector;
    select.dispatchEvent(new Event('change', { bubbles: true }));
  }, sectorId);
  await page.waitForFunction((sector) => {
    const select = document.getElementById('sector-select');
    return select && select.value === sector;
  }, sectorId, { timeout: 15000 });
}

async function uploadFile(page, filePath) {
  await page.setInputFiles('#file-input', filePath);
  await page.waitForFunction(() => {
    const el = document.getElementById('upload-status');
    if (!el) return false;
    return /ingested|Upload failed|files ingested|failed/i.test(el.innerText || '');
  }, { timeout: 60000 });
  await page.waitForTimeout(MIN_ACTION_DELAY_MS);
}

async function ensureDeepAnalysis(page) {
  const btn = page.locator('#deep-analysis-btn');
  if (!(await isVisible(btn))) return { enabled: false };
  const pressed = await btn.getAttribute('aria-pressed');
  if (pressed !== 'true') {
    await btn.click();
    await page.waitForTimeout(300);
  }
  return { enabled: true };
}

async function switchGraphTab(page, tabName) {
  await page.evaluate((t) => {
    const btn = document.querySelector(`.graph-subtab[data-graph-tab="${t}"]`);
    if (btn) btn.click();
  }, tabName);
  await page.waitForTimeout(500);
}

async function setEntityGraphMode(page, mode) {
  await page.evaluate((m) => {
    const btn = document.querySelector(`.entity-mode-btn[data-entity-graph-mode="${m}"]`);
    if (btn) btn.click();
  }, mode);
  await page.waitForTimeout(400);
}

async function waitForAssistantMessage(page, previousCount) {
  await page.waitForFunction((count) => {
    return document.querySelectorAll('.message.assistant').length > count;
  }, previousCount, { timeout: 300000 });
  await page.waitForFunction(() => {
    return !document.querySelector('.loading-indicator');
  }, { timeout: 420000 });
}

async function runQuery(page, query) {
  const authModalVisible = await isVisible(page.locator('#auth-modal'));
  if (authModalVisible) {
    await loginIfNeeded(page, adminUser, adminPass);
  }
  const assistantCount = await page.locator('.message.assistant').count();
  await page.fill('#query-input', query);
  await page.click('#send-btn');
  await waitForAssistantMessage(page, assistantCount);
  await page.waitForTimeout(MIN_ACTION_DELAY_MS);
}

async function seedSectorDocuments(page) {
  for (const [sector, files] of Object.entries(sectorUploads)) {
    await selectSector(page, sector);
    for (const fileName of files) {
      await uploadFile(page, path.join(testDocsDir, fileName));
    }
  }
  await page.waitForTimeout(5000);
}

// ---------- CSS Variable Extraction ----------

async function getCssVariables(page, variables) {
  return page.evaluate((vars) => {
    const style = getComputedStyle(document.documentElement);
    const result = {};
    for (const v of vars) {
      const val = style.getPropertyValue(v).trim();
      result[v] = val || null;
    }
    return result;
  }, variables);
}

async function setTheme(page, theme) {
  await page.evaluate((t) => {
    document.documentElement.setAttribute('data-theme', t);
  }, theme);
  await page.waitForTimeout(300);
}

async function getTheme(page) {
  return page.evaluate(() => document.documentElement.getAttribute('data-theme') || 'light');
}

// ---------- Graph Style Checks ----------

async function checkQueryGraphStyles(page) {
  const errors = [];
  const hasSvg = await page.evaluate(() => Boolean(document.querySelector('#plotly-graph svg')));
  if (!hasSvg) {
    errors.push('Query graph SVG not rendered');
    return { pass: false, errors, details: {} };
  }

  const details = await page.evaluate(() => {
    const svg = document.querySelector('#plotly-graph svg');
    if (!svg) return null;

    const queryNode = svg.querySelector('.graph-node--query .graph-node-dot');
    const sourceNode = svg.querySelector('.graph-node--source .graph-node-dot');
    const entityNode = svg.querySelector('.graph-node--entity .graph-node-dot');

    function getFill(el) {
      if (!el) return null;
      const computed = window.getComputedStyle(el);
      return computed.fill || el.getAttribute('fill') || null;
    }

    const labels = Array.from(svg.querySelectorAll('.graph-label'));
    const edges = Array.from(svg.querySelectorAll('.graph-edge'));
    const nodes = Array.from(svg.querySelectorAll('.graph-node'));

    return {
      queryNodeFill: getFill(queryNode),
      sourceNodeFill: getFill(sourceNode),
      entityNodeFill: getFill(entityNode),
      labelCount: labels.length,
      edgeCount: edges.length,
      nodeCount: nodes.length,
      nodeTypes: nodes.map(n => {
        const cls = Array.from(n.classList).find(c => c.startsWith('graph-node--'));
        return cls ? cls.replace('graph-node--', '') : 'unknown';
      }),
      hasLegend: Boolean(document.querySelector('.graph-legend')),
    };
  });

  if (!details) {
    errors.push('Could not read query graph details');
    return { pass: false, errors, details: {} };
  }

  // Validate node fills use CSS variables (not hardcoded stale values)
  if (details.queryNodeFill && details.queryNodeFill.includes('96, 165, 250')) {
    errors.push(`Query node fill uses stale color: ${details.queryNodeFill}`);
  }
  if (details.sourceNodeFill && details.sourceNodeFill.includes('96, 165, 250')) {
    errors.push(`Source node fill uses stale color: ${details.sourceNodeFill}`);
  }

  if (details.nodeCount === 0) {
    errors.push('No nodes in query graph');
  }
  if (details.labelCount === 0) {
    errors.push('No labels in query graph');
  }

  return { pass: errors.length === 0, errors, details };
}

async function checkEntityGraphStyles(page) {
  const errors = [];

  // Switch to entity tab
  await switchGraphTab(page, 'entity');
  await setEntityGraphMode(page, 'context');
  if (ENTITY_TAB_PAUSE_MS > 0) {
    await page.waitForTimeout(ENTITY_TAB_PAUSE_MS);
  }

  const hasCanvas = await page.evaluate(() =>
    Boolean(document.querySelector('#entity-graph canvas, #entity-graph svg'))
  );
  const nodeCount = await page.evaluate(() => {
    const el = document.getElementById('entity-node-count');
    return el ? parseInt(el.textContent, 10) : 0;
  });

  if (!hasCanvas && nodeCount > 0) {
    errors.push('Entity graph has nodes but no canvas/svg rendered');
  }

  // Check the entity color palette is the unified Okabe-Ito one
  const colorCheck = await page.evaluate((expectedColors) => {
    if (typeof entityTypeColors === 'undefined') return { found: false };
    const mismatches = [];
    for (const [type, expected] of Object.entries(expectedColors)) {
      const actual = entityTypeColors[type];
      if (!actual) {
        mismatches.push({ type, expected, actual: 'MISSING' });
      } else if (actual.toLowerCase() !== expected.toLowerCase()) {
        mismatches.push({ type, expected, actual });
      }
    }
    // Check no duplicate definition (entityColors should === entityTypeColors)
    const usesUnified = typeof entityColors !== 'undefined' ? entityColors === entityTypeColors : null;
    return { found: true, mismatches, usesUnified, palette: entityTypeColors };
  }, EXPECTED_ENTITY_COLORS);

  if (!colorCheck.found) {
    errors.push('entityTypeColors not defined on page');
  } else {
    if (colorCheck.mismatches && colorCheck.mismatches.length > 0) {
      for (const m of colorCheck.mismatches) {
        errors.push(`Entity color mismatch: ${m.type} expected=${m.expected} actual=${m.actual}`);
      }
    }
    // entityColors should be the same object reference as entityTypeColors (unified palette)
    if (colorCheck.usesUnified === false) {
      errors.push('entityColors is NOT the unified entityTypeColors (duplicate palette detected)');
    }
  }

  // Check rendered node colors in force-graph
  const renderedColors = await page.evaluate(() => {
    const graph = (typeof entity2DGraph !== 'undefined') ? entity2DGraph : null;
    if (!graph || !graph.graphData) return null;
    const data = graph.graphData() || {};
    const nodes = Array.isArray(data.nodes) ? data.nodes : [];
    return nodes.slice(0, 20).map(n => ({
      type: (n.type || n.entityType || 'UNKNOWN').toUpperCase(),
      color: n.color || null,
    }));
  });

  if (renderedColors && renderedColors.length > 0) {
    for (const node of renderedColors) {
      const expectedColor = EXPECTED_ENTITY_COLORS[node.type] || EXPECTED_ENTITY_COLORS.DEFAULT;
      if (node.color && node.color.toLowerCase() !== expectedColor.toLowerCase()) {
        errors.push(`Node type=${node.type} rendered color=${node.color} expected=${expectedColor}`);
      }
    }
  }

  await switchGraphTab(page, 'query');
  return { pass: errors.length === 0, errors, nodeCount, hasCanvas, colorCheck, renderedColors };
}

async function checkCssVariables(page, theme) {
  const errors = [];
  await setTheme(page, theme);

  const expected = theme === 'dark' ? EXPECTED_DARK_CSS : EXPECTED_LIGHT_CSS;
  const varNames = Object.keys(expected);
  const actual = await getCssVariables(page, varNames);

  for (const [varName, expectedVal] of Object.entries(expected)) {
    const actualVal = actual[varName];
    if (expectedVal === true) {
      // Just check existence
      if (!actualVal) {
        errors.push(`${theme}: CSS var ${varName} is missing`);
      }
    } else {
      if (!actualVal) {
        errors.push(`${theme}: CSS var ${varName} is missing`);
      } else if (normalizeColor(actualVal) !== normalizeColor(expectedVal)) {
        errors.push(`${theme}: ${varName} expected=${expectedVal} actual=${actualVal}`);
      }
    }
  }

  // Regression check: stale values must not appear
  if (theme === 'dark') {
    const accentPrimary = normalizeColor(actual['--accent-primary'] || '');
    for (const [label, staleVal] of Object.entries(STALE_VALUES)) {
      if (accentPrimary === normalizeColor(staleVal)) {
        errors.push(`${theme}: STALE value detected for --accent-primary: ${actual['--accent-primary']} (matches ${label})`);
      }
    }
  }

  return { pass: errors.length === 0, errors, actual };
}

// ---------- Main ----------

async function run() {
  ensureDir(screenshotDir);
  const results = {
    runLabel: 'GRAPH_STYLES',
    runStart: nowIso(),
    baseUrl,
    tests: [],
  };

  const browser = await chromium.launch({ channel: 'msedge', headless: false });
  const context = await browser.newContext();
  const page = await context.newPage();
  page.setDefaultTimeout(240000);

  await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
  await waitForLoaded(page);

  const loginResult = await loginIfNeeded(page, adminUser, adminPass);
  if (loginResult.attempted && !loginResult.success) {
    throw new Error(`Login failed: ${loginResult.error}`);
  }

  await page.waitForSelector('#sector-select', { timeout: 15000 });
  const deepAnalysis = await ensureDeepAnalysis(page);

  async function screenshot(label) {
    const safe = label.replace(/[^a-zA-Z0-9_-]+/g, '_').slice(0, 80);
    const file = path.join(screenshotDir, `graphstyle_${safe}_${Date.now()}.png`);
    await page.screenshot({ path: file, fullPage: true });
    return file;
  }

  async function recordTest(entry) {
    if (!entry.pass) {
      entry.screenshot = await screenshot(entry.label);
    }
    results.tests.push(entry);
    const icon = entry.pass ? '\u2705' : '\u274C';
    console.log(`${icon} ${entry.label}${entry.pass ? '' : ' — ' + (entry.errors || []).join('; ')}`);
    try {
      fs.writeFileSync(outputJson, JSON.stringify(results, null, 2));
    } catch (err) {
      console.warn('Failed to write interim results:', err.message);
    }
  }

  // ========== Phase 1: CSS Variable Checks ==========
  console.log('\n=== Phase 1: CSS Variable Verification ===\n');

  const originalTheme = await getTheme(page);

  for (const theme of ['dark', 'light']) {
    const cssCheck = await checkCssVariables(page, theme);
    await recordTest({
      type: 'css',
      label: `CSS variables — ${theme} mode`,
      theme,
      ...cssCheck,
    });
  }

  // Restore original theme
  await setTheme(page, originalTheme);

  // ========== Phase 2: Seed Documents ==========
  if (!skipSeed) {
    console.log('\n=== Phase 2: Seeding Documents ===\n');
    await seedSectorDocuments(page);
  } else {
    console.log('\n=== Phase 2: Skipping seed (SKIP_SEED_DOCS=true) ===\n');
  }

  // ========== Phase 3: Per-Sector Graph Verification ==========
  console.log('\n=== Phase 3: Per-Sector Graph Style Verification ===\n');

  for (const [sector, query] of Object.entries(sectorQueries)) {
    console.log(`--- ${sector} ---`);
    await selectSector(page, sector);

    // Run a retrieval query to populate graphs
    await runQuery(page, query);

    // Check query graph (light mode)
    const queryGraphLight = await checkQueryGraphStyles(page);
    await recordTest({
      type: 'query-graph',
      label: `${sector} query graph — light`,
      sector,
      theme: 'light',
      ...queryGraphLight,
    });

    // Check entity graph (light mode)
    if (deepAnalysis.enabled) {
      const entityGraphLight = await checkEntityGraphStyles(page);
      await recordTest({
        type: 'entity-graph',
        label: `${sector} entity graph — light`,
        sector,
        theme: 'light',
        ...entityGraphLight,
      });
    }

    // Switch to dark mode and re-check
    await setTheme(page, 'dark');
    await page.waitForTimeout(500);

    const queryGraphDark = await checkQueryGraphStyles(page);
    await recordTest({
      type: 'query-graph',
      label: `${sector} query graph — dark`,
      sector,
      theme: 'dark',
      ...queryGraphDark,
    });

    if (deepAnalysis.enabled) {
      const entityGraphDark = await checkEntityGraphStyles(page);
      await recordTest({
        type: 'entity-graph',
        label: `${sector} entity graph — dark`,
        sector,
        theme: 'dark',
        ...entityGraphDark,
      });
    }

    // Restore light mode for next sector
    await setTheme(page, 'light');
    await page.waitForTimeout(300);

    // Take a verification screenshot per sector
    await screenshot(`${sector}_graphs`);
  }

  // ========== Phase 4: Sector-wide Entity Graph ==========
  if (deepAnalysis.enabled) {
    console.log('\n=== Phase 4: Sector-wide Entity Graph ===\n');
    for (const sector of Object.keys(sectorQueries)) {
      await selectSector(page, sector);
      await switchGraphTab(page, 'entity');
      await setEntityGraphMode(page, 'sector');
      await page.waitForTimeout(ENTITY_TAB_PAUSE_MS);

      const sectorWideCheck = await checkEntityGraphStyles(page);
      // Override mode in the check since we set sector mode
      await recordTest({
        type: 'entity-graph-sector',
        label: `${sector} sector-wide entity graph`,
        sector,
        mode: 'sector',
        ...sectorWideCheck,
      });
      await switchGraphTab(page, 'query');
    }
  }

  // ========== Summary ==========
  const failed = results.tests.filter(t => !t.pass);
  const passed = results.tests.filter(t => t.pass);

  console.log(`\n========================================`);
  console.log(`  RESULTS: ${passed.length} passed, ${failed.length} failed out of ${results.tests.length} total`);
  console.log(`========================================\n`);

  if (failed.length > 0) {
    console.log('FAILURES:');
    for (const f of failed) {
      console.log(`  ${f.label}: ${(f.errors || []).join('; ')}`);
    }
  }

  results.runEnd = nowIso();
  results.summary = {
    total: results.tests.length,
    passed: passed.length,
    failed: failed.length,
  };
  fs.writeFileSync(outputJson, JSON.stringify(results, null, 2));
  console.log(`\nResults written to ${outputJson}`);

  await context.close();
  await browser.close();

  if (failed.length > 0) {
    process.exit(1);
  }
}

run().catch((err) => {
  console.error('Graph style verification FAILED:', err);
  process.exit(1);
});
