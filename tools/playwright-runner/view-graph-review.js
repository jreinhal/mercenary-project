/**
 * Query Graph Screenshot Script
 *
 * Submits a query, waits for the response and graph to render,
 * then screenshots the graph area and full page in both dark and light mode.
 *
 * Usage:
 *   node view-graph-review.js
 *   BASE_URL=http://localhost:9090 node view-graph-review.js
 */
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
const SCREENSHOT_DIR = path.join(__dirname, 'screens');
const QUERY_TEXT = 'Provide a detailed summary of all documents';

fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });

function screenshotPath(name) {
  return path.join(SCREENSHOT_DIR, name);
}

async function waitForAppLoaded(page) {
  await page.waitForFunction(() => {
    const sidebar = document.getElementById('sidebar');
    const chat = document.getElementById('chat-messages');
    return (sidebar || chat) && !document.querySelector('.loading-overlay:not(.hidden)');
  }, { timeout: 60000 });
}

async function run() {
  console.log('Launching Chromium (headed) against ' + BASE_URL);
  const browser = await chromium.launch({ channel: 'msedge', headless: false });
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(120000);

  console.log('Step 1: Navigating to app...');
  await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });
  await waitForAppLoaded(page);
  console.log('  App loaded.');

  const authModal = page.locator('#auth-modal');
  const authVisible = await authModal.isVisible({ timeout: 3000 }).catch(() => false);
  if (authVisible) {
    console.log('  Auth modal detected but DEV mode expected -- skipping.');
  }

  await page.waitForTimeout(2000);
  const initialTheme = await page.evaluate(() =>
    document.documentElement.getAttribute('data-theme') || 'dark'
  );
  console.log('  Current theme: ' + initialTheme);

  if (initialTheme !== 'dark') {
    console.log('  Switching to dark mode first...');
    await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'));
    await page.waitForTimeout(500);
  }

  console.log('Step 2: Submitting query...');
  const queryInput = page.locator('#query-input');
  await queryInput.waitFor({ state: 'visible', timeout: 15000 });

  const prevAssistantCount = await page.locator('.message.assistant').count();
  console.log('  Previous assistant messages: ' + prevAssistantCount);

  await queryInput.fill(QUERY_TEXT);
  await page.click('#send-btn');
  console.log('  Query submitted.');

  console.log('Step 3: Waiting for response...');

  await page.waitForFunction((count) => {
    return document.querySelectorAll('.message.assistant').length > count;
  }, prevAssistantCount, { timeout: 120000 });
  console.log('  Assistant message appeared.');

  await page.waitForFunction(() => {
    const li = document.querySelector('.loading-indicator');
    const sd = document.querySelector('.streaming-dot');
    return !li && !sd;
  }, { timeout: 300000 });
  console.log('  Streaming complete.');

  console.log('  Waiting 20s for graph to render...');
  await page.waitForTimeout(20000);

  const graphExists = await page.evaluate(() => {
    const plotly = document.getElementById('plotly-graph');
    const hasSvg = plotly && plotly.querySelector('svg');
    const gc = document.querySelector('.graph-container');
    return { plotlyExists: !!plotly, hasSvg: !!hasSvg, graphContainerExists: !!gc };
  });
  console.log('  Graph status: ' + JSON.stringify(graphExists));

  await page.evaluate(() => {
    const plotTab = document.querySelector('.right-panel-tab[data-tab=\"plot\"]');
    if (plotTab && !plotTab.classList.contains('active')) plotTab.click();
  });
  await page.waitForTimeout(1000);
  console.log('Step 4: Taking dark mode screenshots...');

  const darkFullPath = screenshotPath('graph_fullpage_dark.png');
  await page.screenshot({ path: darkFullPath, fullPage: false });
  console.log('  Saved: ' + darkFullPath);

  const rightPanel = page.locator('.right-panel');
  let rpVis = await rightPanel.isVisible({ timeout: 5000 }).catch(() => false);
  if (rpVis) {
    const darkGraphPath = screenshotPath('graph_panel_dark.png');
    await rightPanel.screenshot({ path: darkGraphPath });
    console.log('  Saved: ' + darkGraphPath);
  } else {
    console.log('  WARNING: Right panel not visible, trying #graph-container...');
    const gc = page.locator('#graph-container');
    const gcVis = await gc.isVisible({ timeout: 3000 }).catch(() => false);
    if (gcVis) {
      const darkGraphPath = screenshotPath('graph_panel_dark.png');
      await gc.screenshot({ path: darkGraphPath });
      console.log('  Saved: ' + darkGraphPath);
    } else {
      console.log('  WARNING: No graph element visible for panel screenshot.');
    }
  }

  // ---- Step 5: Switch to light mode via data-theme attribute ----
  console.log('Step 5: Switching to light mode...');
  await page.evaluate(() => {
    // Use the app's own setTheme if available, otherwise set directly
    document.documentElement.setAttribute('data-theme', 'light');
    localStorage.setItem('sentinel-theme', 'light');
    localStorage.setItem('sentinel-display-mode', 'light');
    var lightBtn = document.getElementById('theme-light');
    var darkBtn = document.getElementById('theme-dark');
    if (lightBtn) lightBtn.classList.add('active');
    if (darkBtn) darkBtn.classList.remove('active');
  });;
  await page.waitForTimeout(2000);

  const currentTheme = await page.evaluate(() =>
    document.documentElement.getAttribute('data-theme')
  );
  console.log('  Current theme after switch: ' + currentTheme);
  await page.waitForTimeout(1000);
  console.log('Step 6: Taking light mode screenshots...');

  const lightFullPath = screenshotPath('graph_fullpage_light.png');
  await page.screenshot({ path: lightFullPath, fullPage: false });
  console.log('  Saved: ' + lightFullPath);

  rpVis = await rightPanel.isVisible({ timeout: 5000 }).catch(() => false);
  if (rpVis) {
    const lightGraphPath = screenshotPath('graph_panel_light.png');
    await rightPanel.screenshot({ path: lightGraphPath });
    console.log('  Saved: ' + lightGraphPath);
  } else {
    console.log('  WARNING: Right panel not visible for light mode.');
    const gc = page.locator('#graph-container');
    const gcVis = await gc.isVisible({ timeout: 3000 }).catch(() => false);
    if (gcVis) {
      const lightGraphPath = screenshotPath('graph_panel_light.png');
      await gc.screenshot({ path: lightGraphPath });
      console.log('  Saved: ' + lightGraphPath);
    }
  }

  console.log('\nAll screenshots captured successfully.');

  const files = fs.readdirSync(SCREENSHOT_DIR).filter(f => f.endsWith('.png'));
  console.log('\nScreenshot files in ' + SCREENSHOT_DIR + ':');
  for (const f of files) {
    const fp = path.join(SCREENSHOT_DIR, f);
    const stat = fs.statSync(fp);
    console.log('  ' + f + ' (' + (stat.size / 1024).toFixed(1) + ' KB)');
  }

  await page.waitForTimeout(3000);
  await context.close();
  await browser.close();
  console.log('\nDone. Browser closed.');
}

run().catch((err) => {
  console.error('Script failed:', err);
  process.exit(1);
});
