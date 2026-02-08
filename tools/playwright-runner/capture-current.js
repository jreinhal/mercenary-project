const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = process.env.BASE_URL || 'http://localhost:8080';
const screenshotDir = path.join(__dirname, 'screens');
fs.mkdirSync(screenshotDir, { recursive: true });

(async () => {
    const browser = await chromium.connectOverCDP('http://localhost:9222').catch(() => null);

    if (!browser) {
        // Fall back to opening a new browser and navigating to the app
        console.log('Cannot connect to existing browser, opening new one...');
        const b = await chromium.launch({ headless: false, channel: 'msedge' });
        const page = await b.newPage({ viewport: { width: 1920, height: 1080 } });
        await page.goto(baseUrl, { waitUntil: 'networkidle', timeout: 15000 });
        await page.waitForTimeout(1000);
        await page.screenshot({ path: path.join(screenshotDir, 'current_state.png') });
        console.log('Saved: current_state.png');
        await b.close();
    } else {
        const contexts = browser.contexts();
        const pages = contexts.length > 0 ? contexts[0].pages() : [];
        if (pages.length > 0) {
            const page = pages[0];
            await page.screenshot({ path: path.join(screenshotDir, 'current_state.png') });
            console.log('Saved: current_state.png');
        }
        await browser.close();
    }
})();
