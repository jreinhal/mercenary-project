/**
 * Screenshot the Entity Network graph in light mode at multiple zoom levels.
 */
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = process.env.BASE_URL || 'http://localhost:8080';
const screenshotDir = path.join(__dirname, 'screens');
fs.mkdirSync(screenshotDir, { recursive: true });

(async () => {
    const browser = await chromium.launch({ headless: false, channel: 'msedge' });
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });

    console.log('Navigating to app...');
    await page.goto(baseUrl, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(2000);

    // Switch to light mode
    console.log('Switching to light mode...');
    await page.evaluate(() => {
        document.documentElement.setAttribute('data-theme', 'light');
        localStorage.setItem('sentinel-theme', 'light');
        localStorage.setItem('sentinel-display-mode', 'light');
    });
    await page.waitForTimeout(500);

    // Submit a query to populate entities
    console.log('Submitting query...');
    const input = await page.$('#query-input');
    if (input) {
        await input.fill('What are the key organizations and people mentioned in the documents?');
        const sendBtn = await page.$('#send-btn');
        if (sendBtn) await sendBtn.click();
    }

    // Wait for response to stream
    console.log('Waiting for response to complete...');
    await page.waitForTimeout(20000);

    // Screenshot query results graph first
    await page.screenshot({ path: path.join(screenshotDir, 'entity_step1_query_light.png'), fullPage: false });
    console.log('Saved: entity_step1_query_light.png');

    // Click Entity Network tab using data attribute
    console.log('Clicking Entity Network tab...');
    const entityTabBtn = await page.$('button[data-graph-tab="entity"]');
    if (entityTabBtn) {
        // Scroll it into view and force-click
        await entityTabBtn.scrollIntoViewIfNeeded();
        await page.waitForTimeout(300);
        await entityTabBtn.click({ force: true });
        console.log('Clicked Entity Network tab');
    } else {
        console.log('Entity Network tab button not found, trying JS click...');
        await page.evaluate(() => {
            const btn = document.querySelector('button[data-graph-tab="entity"]');
            if (btn) btn.click();
        });
    }

    // Wait for entity graph to render
    await page.waitForTimeout(5000);

    // Screenshot the full page showing entity graph
    await page.screenshot({ path: path.join(screenshotDir, 'entity_fullpage_light.png'), fullPage: false });
    console.log('Saved: entity_fullpage_light.png');

    // Screenshot the right panel
    const rightPanel = await page.$('.right-panel');
    if (rightPanel) {
        await rightPanel.screenshot({ path: path.join(screenshotDir, 'entity_panel_light.png') });
        console.log('Saved: entity_panel_light.png');
    }

    // Screenshot entity graph container
    const entityContainer = await page.$('#entity-explorer-container');
    if (entityContainer) {
        const isVisible = await entityContainer.evaluate(el => !el.classList.contains('hidden'));
        console.log(`Entity explorer visible: ${isVisible}`);
        if (isVisible) {
            await entityContainer.screenshot({ path: path.join(screenshotDir, 'entity_graph_light.png') });
            console.log('Saved: entity_graph_light.png');
        }
    }

    // Try switching to Sector mode for more entities
    console.log('Trying Sector mode...');
    await page.evaluate(() => {
        const btns = document.querySelectorAll('.entity-graph-mode button');
        for (const b of btns) {
            if (b.textContent.trim().toLowerCase() === 'sector') {
                b.click();
                return true;
            }
        }
        return false;
    });
    await page.waitForTimeout(5000);

    // Screenshot sector mode
    await page.screenshot({ path: path.join(screenshotDir, 'entity_sector_light.png'), fullPage: false });
    console.log('Saved: entity_sector_light.png');

    const entityContainer2 = await page.$('#entity-explorer-container');
    if (entityContainer2) {
        const isVisible = await entityContainer2.evaluate(el => !el.classList.contains('hidden'));
        if (isVisible) {
            await entityContainer2.screenshot({ path: path.join(screenshotDir, 'entity_graph_sector_light.png') });
            console.log('Saved: entity_graph_sector_light.png');
        }
    }

    // Gather info about what's on screen
    const debugInfo = await page.evaluate(() => {
        const container = document.querySelector('#entity-explorer-container');
        const canvas = document.querySelector('.entity-graph canvas');
        const svg = document.querySelector('.entity-graph svg');
        const graphSubtabs = Array.from(document.querySelectorAll('.graph-subtab')).map(t => ({
            text: t.textContent.trim(),
            active: t.classList.contains('active'),
            visible: t.offsetParent !== null
        }));
        return {
            containerHidden: container?.classList.contains('hidden'),
            hasCanvas: !!canvas,
            hasSvg: !!svg,
            canvasSize: canvas ? { w: canvas.width, h: canvas.height } : null,
            subtabs: graphSubtabs,
            theme: document.documentElement.getAttribute('data-theme'),
            nodeCount: document.querySelector('#entity-node-count')?.textContent,
            edgeCount: document.querySelector('#entity-edge-count')?.textContent,
        };
    });
    console.log('\nDebug info:', JSON.stringify(debugInfo, null, 2));

    // List screenshots
    const files = fs.readdirSync(screenshotDir).filter(f => f.startsWith('entity_'));
    console.log('\nScreenshots:');
    files.forEach(f => {
        const stats = fs.statSync(path.join(screenshotDir, f));
        console.log(`  ${f} (${(stats.size / 1024).toFixed(1)} KB)`);
    });

    await browser.close();
    console.log('\nDone.');
})();
