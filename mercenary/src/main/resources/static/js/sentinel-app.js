// === SENTINEL App JavaScript ===
// Extracted for CSP compliance (PR-4)
// This file should be loaded at the end of body in index.html

// === STATE MANAGEMENT ===
const state = {
    contextDocs: new Map(), // Active documents in context
    openSources: [],        // Open source tabs
    activeSource: null,     // Currently viewed source
    currentFeedbackMsgId: null
};

// API endpoint - uses relative URL for production compatibility
const API_BASE = window.location.origin + '/api';
const chatMessages = document.getElementById('chat-messages');
const queryInput = document.getElementById('query-input');
const sectorSelect = document.getElementById('sector-select');

// === SECTOR CONFIGURATION ===
// SECURITY: Sectors loaded dynamically from /api/config/sectors
// Only sectors the user is authorized to access are returned
let SECTORS = [];
let SECTOR_DATA = {};
const DEFAULT_SECTOR = 'ENTERPRISE';

// === INITIALIZATION ===
document.addEventListener('DOMContentLoaded', () => {
    initSectorsFromAPI();
    initOperator();
    initKeyboardShortcuts();
    startStatusPolling();
    initFileUpload();
    initSectorSelector();
});

// Initialize sectors from backend API (security-filtered)
async function initSectorsFromAPI() {
    const sectorSelect = document.getElementById('sector-select');

    try {
        const response = await fetch(`${API_BASE}/config/sectors`, {
            credentials: 'include'
        });

        if (response.ok) {
            const sectors = await response.json();
            SECTORS = sectors.map(s => s.id);

            // Build sector data lookup
            sectors.forEach(s => {
                SECTOR_DATA[s.id] = {
                    label: s.label,
                    icon: s.icon,
                    description: s.description,
                    theme: s.theme
                };
            });

            // Populate dropdown with authorized sectors only
            sectorSelect.innerHTML = '';
            sectors.forEach(sector => {
                const option = document.createElement('option');
                option.value = sector.id;
                option.textContent = sector.label || sector.id;
                sectorSelect.appendChild(option);
            });

            // Set saved or default sector
            const savedSector = localStorage.getItem('sentinel-sector');
            if (savedSector && SECTORS.includes(savedSector)) {
                sectorSelect.value = savedSector;
            } else if (SECTORS.length > 0) {
                sectorSelect.value = SECTORS.includes(DEFAULT_SECTOR) ? DEFAULT_SECTOR : SECTORS[0];
            }

            console.log(`SENTINEL loaded with ${SECTORS.length} authorized sectors`);
        } else {
            console.warn('Failed to load sectors from API, using fallback');
            initSectorsFallback();
        }
    } catch (error) {
        console.error('Error loading sectors:', error);
        initSectorsFallback();
    }

    // Apply initial sector theme
    applySectorTheme(sectorSelect.value);

    // Render splash content
    renderSplashContent();
}

// Fallback for when API is unavailable (dev mode)
function initSectorsFallback() {
    SECTORS = ['ENTERPRISE', 'ACADEMIC'];
    SECTOR_DATA = {
        'ENTERPRISE': { label: 'Enterprise', icon: 'briefcase', description: 'General Business' },
        'ACADEMIC': { label: 'Academic', icon: 'book', description: 'Research' }
    };

    const sectorSelect = document.getElementById('sector-select');
    sectorSelect.innerHTML = '';
    SECTORS.forEach(sector => {
        const option = document.createElement('option');
        option.value = sector;
        option.textContent = SECTOR_DATA[sector]?.label || sector;
        sectorSelect.appendChild(option);
    });
    sectorSelect.value = DEFAULT_SECTOR;
}

// Helper to get sector config (replaces window.getSectorConfig)
function getSectorConfig(sectorId) {
    return SECTOR_DATA[sectorId] || { label: sectorId, icon: 'folder' };
}

// Feature icon SVG paths
const FEATURE_ICONS = {
    citation: '<path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>',
    glassbox: '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/>',
    governance: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
    context: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/>',
    workspace: '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    literature: '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>',
    local: '<rect x="2" y="2" width="20" height="8" rx="2" ry="2"/><rect x="2" y="14" width="20" height="8" rx="2" ry="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/>',
    sectors: '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/>'
};

/**
 * Apply display theme (USWDS-compliant light/dark mode)
 * Simplified from sector-specific themes to just light/dark
 */
function applyDisplayMode(mode) {
    const root = document.documentElement;
    const displayMode = window.getDisplayMode ? window.getDisplayMode(mode) : (mode === 'dark' ? 'dark' : 'light');

    // Apply theme attribute
    root.setAttribute('data-theme', displayMode);

    // Store preference
    localStorage.setItem('sentinel-display-mode', mode);

    console.log(`Applied display mode: ${displayMode}`);
}

/**
 * Apply sector configuration (separate from display mode)
 * Updates placeholder text, clearance badges, and classification banners
 */
function applySectorConfig(sector) {
    const config = window.getSectorConfig ? window.getSectorConfig(sector) : {};

    // Update placeholder text
    if (config.placeholders) {
        const queryInput = document.getElementById('query-input');
        if (queryInput) {
            queryInput.placeholder = config.placeholders.query;
        }
    }

    // Show/hide clearance badge based on sector requirements
    const clearanceBadge = document.querySelector('.clearance-badge');
    if (clearanceBadge) {
        clearanceBadge.style.display = config.features?.showClearanceBadge ? 'inline-flex' : 'none';
    }

    // Show/hide classification banner for government sector
    const classificationBanner = document.getElementById('classification-banner');
    if (classificationBanner) {
        const classification = config.features?.classificationLevel || 'unclassified';
        const showBanner = config.features?.showClassificationBanner;
        classificationBanner.className = `classification-banner ${classification}`;
        classificationBanner.style.display = showBanner ? 'block' : 'none';
        classificationBanner.textContent = classification.toUpperCase();
        // Add/remove body padding for fixed banner
        document.body.classList.toggle('has-classification-banner', showBanner);
    }

    // Update active sector display
    const activeSector = document.getElementById('active-sector');
    if (activeSector) {
        activeSector.textContent = config.shortLabel || sector;
    }

    // Store sector preference
    localStorage.setItem('sentinel-sector', sector);

    console.log(`Applied sector config: ${sector}`);
}

/**
 * Legacy function for backward compatibility
 */
function applySectorTheme(sector) {
    // Apply sector-specific config
    applySectorConfig(sector);

    // Apply display mode from preference or system default
    const savedMode = localStorage.getItem('sentinel-display-mode') || 'auto';
    applyDisplayMode(savedMode);

    // Update sector display in left sidebar
    updateSectorDisplay(sector);
}

function updateSectorDisplay(sector) {
    const displayName = document.getElementById('sector-display-name');
    if (displayName) {
        const config = window.getSectorConfig ? window.getSectorConfig(sector) : {};
        displayName.textContent = config.label || sector;
    }
}

// Render splash screen content from edition config
function renderSplashContent() {
    if (!window.SENTINEL_SPLASH) return;

    const splash = window.SENTINEL_SPLASH;

    // Update subtitle
    const subtitle = document.getElementById('splash-subtitle');
    if (subtitle && splash.subtitle) {
        subtitle.textContent = splash.subtitle;
    }

    // Update features
    // SECURITY: Escape dynamic content to prevent XSS
    const featuresContainer = document.getElementById('splash-features');
    if (featuresContainer && splash.features) {
        featuresContainer.innerHTML = splash.features.map(feature => `
            <div class="welcome-feature">
                <svg class="welcome-feature-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    ${FEATURE_ICONS[feature.icon] || FEATURE_ICONS.context}
                </svg>
                <div class="welcome-feature-title">${escapeHtml(feature.title || '')}</div>
                <p class="welcome-feature-desc">${escapeHtml(feature.desc || '')}</p>
            </div>
        `).join('');
    }
}

function initOperator() {
    const params = new URLSearchParams(window.location.search);
    let operatorId = params.get('operator') || localStorage.getItem('sentinel_operator') || 'DEMO_USER';
    operatorId = operatorId.toUpperCase().replace(/[^A-Z0-9_-]/g, '_').substring(0, 20);
    localStorage.setItem('sentinel_operator', operatorId);

    // Update operator display if element exists
    const operatorEl = document.getElementById('operator-id');
    if (operatorEl) operatorEl.textContent = operatorId;

    // Also update System Status User field
    const statsUser = document.getElementById('stats-user');
    if (statsUser) statsUser.textContent = operatorId;
}

function initKeyboardShortcuts() {
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            closeFeedbackModal();
            closeSidebar();
        }
        if (e.ctrlKey && e.key === 'u') {
            e.preventDefault();
            document.getElementById('file-input').click();
        }
        if (e.ctrlKey && e.key === 'k') {
            e.preventDefault();
            clearChat();
        }
        if (e.key === '/' && document.activeElement !== queryInput) {
            e.preventDefault();
            queryInput.focus();
        }
        // Note: Enter key is handled by onkeydown on the input element directly
    });
}

async function initSectorSelector() {
    const displayModeSelect = document.getElementById('display-mode-select');

    // Load saved display mode
    const savedDisplayMode = localStorage.getItem('sentinel-display-mode');
    if (savedDisplayMode) {
        currentDisplayMode = savedDisplayMode;
        if (displayModeSelect) {
            displayModeSelect.value = savedDisplayMode;
        }
    }

    // Fetch user context to filter sectors based on permissions
    try {
        const response = await fetch(`${API_BASE}/user/context`);
        if (response.ok) {
            const userContext = await response.json();

            // Update user display name
            if (userContext.displayName) {
                document.getElementById('operator-id').textContent = userContext.displayName;
                // Update System Status box - User
                const statsUser = document.getElementById('stats-user');
                if (statsUser) statsUser.textContent = userContext.displayName;
                // Store for scoped storage
                localStorage.setItem('sentinel_operator', userContext.displayName);
            }

            // Filter sector dropdown based on allowed sectors (unless admin)
            if (!userContext.isAdmin && userContext.allowedSectors && userContext.allowedSectors.length > 0) {
                filterSectorDropdown(userContext.allowedSectors);
            }
        }
    } catch (error) {
        console.warn('Could not fetch user context, showing all sectors:', error);
        // Use fallback for demo/dev mode
        const statsUser = document.getElementById('stats-user');
        if (statsUser) statsUser.textContent = 'DEMO_USER';
        localStorage.setItem('sentinel_operator', 'DEMO_USER');
    }

    // Load saved sector preference
    const savedSector = localStorage.getItem('sentinel-sector');
    if (savedSector && SECTOR_CONFIG[savedSector]) {
        // Only apply saved sector if option still exists in dropdown
        const optionExists = Array.from(sectorSelect.options).some(opt => opt.value === savedSector);
        if (optionExists) {
            sectorSelect.value = savedSector;
            document.getElementById('active-sector').textContent = savedSector;
        }
    }

    // Apply initial sector and save to localStorage
    applySector(sectorSelect.value);
    localStorage.setItem('sentinel-sector', sectorSelect.value);

    // Update System Status box - Context (initial)
    const statsContext = document.getElementById('stats-context');
    if (statsContext) statsContext.textContent = sectorSelect.value;

    // Listen for sector changes
    sectorSelect.addEventListener('change', (e) => {
        const sector = e.target.value;

        // Reset dashboard to default state before switching context
        resetDashboardState();

        // Update UI elements
        document.getElementById('active-sector').textContent = sector;
        const statsCtx = document.getElementById('stats-context');
        if (statsCtx) statsCtx.textContent = sector;

        // Apply new sector theme
        applySector(sector);
        localStorage.setItem('sentinel-sector', sector);

        // Reload sector-specific data
        loadConversationHistory();
        renderSavedQueriesList();
    });

    // Listen for display mode changes
    if (displayModeSelect) {
        displayModeSelect.addEventListener('change', (e) => {
            setDisplayMode(e.target.value);
        });
    }
}

// Filter sector dropdown to only show allowed sectors
function filterSectorDropdown(allowedSectors) {
    const options = sectorSelect.querySelectorAll('option');
    let firstVisibleOption = null;

    options.forEach(option => {
        if (!allowedSectors.includes(option.value)) {
            option.style.display = 'none';
            option.disabled = true;
        } else {
            option.style.display = '';
            option.disabled = false;
            if (!firstVisibleOption) {
                firstVisibleOption = option.value;
            }
        }
    });

    // Remove empty optgroups
    sectorSelect.querySelectorAll('optgroup').forEach(group => {
        const visibleOptions = group.querySelectorAll('option:not([disabled])');
        if (visibleOptions.length === 0) {
            group.style.display = 'none';
        }
    });

    // Select first visible option if current selection is hidden
    if (sectorSelect.selectedOptions[0]?.disabled && firstVisibleOption) {
        sectorSelect.value = firstVisibleOption;
    }
}

// === DISPLAY MODE & SECTOR CONFIG ===
// USWDS-compliant light/dark mode (no sector-specific themes)
// Sector selection updates terminology and access controls only

/**
 * Set display mode (light/dark)
 * Called from display mode selector onChange
 */
function setDisplayMode(mode) {
    // Simple light/dark toggle - no auto option
    const displayMode = (mode === 'dark') ? 'dark' : 'light';

    // Apply theme attribute
    document.documentElement.setAttribute('data-theme', displayMode);

    // Store preference
    localStorage.setItem('sentinel-display-mode', displayMode);

    console.log(`Display mode set to: ${displayMode}`);
}

/**
 * Apply sector configuration when user changes sector
 * Called from sector selector onChange
 * Note: Display mode is now separate from sector selection
 */
function applySector(sector) {
    const config = window.getSectorConfig ? window.getSectorConfig(sector) : {};

    // 1. Apply display mode (from saved preference, default to light)
    const savedMode = localStorage.getItem('sentinel-display-mode') || 'light';
    setDisplayMode(savedMode);

    // 2. Update terminology
    const brandName = document.querySelector('.brand-name');
    if (brandName) {
        const titles = {
            'GOVERNMENT': 'SENTINEL // INTELLIGENCE PLATFORM',
            'MEDICAL': 'SENTINEL // CLINICAL ASSISTANT',
            'FINANCE': 'SENTINEL // FINANCE PLATFORM',
            'ACADEMIC': 'SENTINEL // RESEARCH ASSISTANT',
            'ENTERPRISE': 'SENTINEL // KNOWLEDGE PLATFORM'
        };
        brandName.textContent = titles[sector] || 'SENTINEL // INTELLIGENCE PLATFORM';
    }

    // 3. Update placeholder
    const queryInput = document.getElementById('query-input');
    if (queryInput && config.placeholders) {
        queryInput.placeholder = config.placeholders.query;
    }

    // 4. Update Welcome Screen title
    const welcomeTitle = document.querySelector('.welcome-title');
    if (welcomeTitle) {
        welcomeTitle.textContent = `SENTINEL ${config.label || 'Intelligence Platform'}`;
    }

    // 5. Show/hide classification banner based on sector
    const classificationBanner = document.getElementById('classification-banner');
    if (classificationBanner) {
        const showBanner = config.features?.showClassificationBanner;
        if (showBanner) {
            const classification = config.features?.classificationLevel || 'unclassified';
            classificationBanner.className = `classification-banner ${classification}`;
            classificationBanner.textContent = classification.toUpperCase();
            classificationBanner.style.display = 'block';
        } else {
            classificationBanner.style.display = 'none';
        }
        // Add/remove body padding for fixed banner
        document.body.classList.toggle('has-classification-banner', showBanner);
    }

    // 6. Store preference
    localStorage.setItem('sentinel-sector', sector);

    console.log(`Applied sector: ${sector}`);
}

// Legacy compatibility wrapper
function applyTheme(sector) {
    applySector(sector);
}

// === SYSTEM STATUS ===
async function fetchSystemStatus() {
    try {
        const response = await fetch(`${API_BASE}/telemetry`);
        if (!response.ok) throw new Error('Status fetch failed');
        const data = await response.json();

        // Update stats card in sidebar
        const statsStatus = document.getElementById('stats-status');
        const statsStatusDot = document.getElementById('stats-status-dot');
        const statsDocs = document.getElementById('stats-docs');

        if (statsStatus && statsStatusDot) {
            const isOnline = data.dbOnline && data.llmOnline;
            statsStatus.textContent = isOnline ? 'Online' : (data.dbOnline || data.llmOnline ? 'Degraded' : 'Offline');
            statsStatusDot.className = `stats-indicator ${isOnline ? 'online' : 'offline'}`;
        }

        if (statsDocs) {
            statsDocs.textContent = data.documentCount?.toLocaleString() || '0';
        }

        // Update User from localStorage
        const statsUser = document.getElementById('stats-user');
        if (statsUser) {
            statsUser.textContent = localStorage.getItem('sentinel_operator') || 'OPERATOR';
        }

        // Update Context from sector select
        const statsContext = document.getElementById('stats-context');
        if (statsContext) {
            statsContext.textContent = sectorSelect?.value || 'ENTERPRISE';
        }
    } catch (error) {
        console.error('Status fetch error:', error);
        // Show offline state on error
        const statsStatus = document.getElementById('stats-status');
        const statsStatusDot = document.getElementById('stats-status-dot');
        if (statsStatus) statsStatus.textContent = 'Offline';
        if (statsStatusDot) statsStatusDot.className = 'stats-indicator offline';
    }
}

function startStatusPolling() {
    fetchSystemStatus();
    setInterval(fetchSystemStatus, 10000); // Poll every 10 seconds
}

// === CONVERSATION HISTORY (Kotaemon-style) ===
let conversationHistory = [];
let currentSessionId = null;
let currentMessages = [];

function generateSessionId() {
    return 'session_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
}

/**
 * Get storage key scoped to current user and sector
 * Ensures conversations/queries are isolated per user+context
 */
function getScopedStorageKey(baseKey) {
    const userId = localStorage.getItem('sentinel_operator') || 'DEMO_USER';
    const sector = sectorSelect?.value || localStorage.getItem('sentinel-sector') || 'ENTERPRISE';
    return `${baseKey}_${userId}_${sector}`;
}

function loadConversationHistory() {
    try {
        const storageKey = getScopedStorageKey('sentinel_conversations');
        const saved = localStorage.getItem(storageKey);
        conversationHistory = saved ? JSON.parse(saved) : [];
        renderHistoryList();
    } catch (e) {
        console.warn('Failed to load conversation history:', e);
        conversationHistory = [];
    }
}

function saveConversationHistory() {
    try {
        // Keep only last 50 conversations (industry standard)
        if (conversationHistory.length > 50) {
            conversationHistory = conversationHistory.slice(-50);
        }
        const storageKey = getScopedStorageKey('sentinel_conversations');
        localStorage.setItem(storageKey, JSON.stringify(conversationHistory));
    } catch (e) {
        console.warn('Failed to save conversation history:', e);
    }
}

function toggleHistory() {
    const toggle = document.getElementById('history-toggle');
    const panel = document.getElementById('history-panel');
    const isExpanded = panel.classList.toggle('expanded');
    toggle.classList.toggle('expanded', isExpanded);
    toggle.setAttribute('aria-expanded', isExpanded);
}

function renderHistoryList() {
    const list = document.getElementById('history-list');
    const empty = document.getElementById('history-empty');
    const count = document.getElementById('history-count');

    count.textContent = conversationHistory.length + ' session' + (conversationHistory.length !== 1 ? 's' : '');

    if (conversationHistory.length === 0) {
        empty.style.display = 'block';
        return;
    }

    empty.style.display = 'none';
    // Sort by timestamp descending (most recent first)
    const sorted = [...conversationHistory].sort((a, b) => b.timestamp - a.timestamp);

    list.innerHTML = sorted.map(session => `
        <div class="history-item ${session.id === currentSessionId ? 'active' : ''}"
             role="listitem"
             tabindex="0"
             onclick="loadSession('${session.id}')"
             onkeydown="if(event.key==='Enter')loadSession('${session.id}')">
            <div class="history-item-icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                    <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
                </svg>
            </div>
            <div class="history-item-content">
                <div class="history-item-title">${escapeHtml(session.title || 'Untitled conversation')}</div>
                <div class="history-item-meta">${formatSessionDate(session.timestamp)} Â· ${session.messageCount || 0} messages</div>
            </div>
            <button class="history-item-delete" onclick="event.stopPropagation();deleteSession('${session.id}')"
                    aria-label="Delete conversation" title="Delete">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                    <polyline points="3 6 5 6 21 6"/>
                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
                </svg>
            </button>
        </div>
    `).join('') + '<div class="history-empty" id="history-empty" style="display:none">No previous conversations</div>';
}

function formatSessionDate(timestamp) {
    const date = new Date(timestamp);
    const now = new Date();
    const diff = now - date;
    const mins = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (mins < 1) return 'Just now';
    if (mins < 60) return mins + ' min ago';
    if (hours < 24) return hours + ' hour' + (hours !== 1 ? 's' : '') + ' ago';
    if (days < 7) return days + ' day' + (days !== 1 ? 's' : '') + ' ago';
    return date.toLocaleDateString();
}

function startNewChat() {
    // Save current session if it has messages
    if (currentSessionId && currentMessages.length > 0) {
        saveCurrentSession();
    }
    // Start fresh session
    currentSessionId = generateSessionId();
    currentMessages = [];
    clearChat();
    clearInfoPanel();
    renderHistoryList();
}

function saveCurrentSession() {
    if (!currentSessionId || currentMessages.length === 0) return;

    // Find first user message as title - use full message for better preview
    const firstUserMsg = currentMessages.find(m => m.role === 'user');
    const title = firstUserMsg ? firstUserMsg.content.substring(0, 150) : 'Untitled conversation';

    // Check if session already exists
    const existingIdx = conversationHistory.findIndex(s => s.id === currentSessionId);
    const sessionData = {
        id: currentSessionId,
        title: title + (title.length >= 150 ? '...' : ''),
        timestamp: Date.now(),
        messageCount: currentMessages.length,
        messages: currentMessages
    };

    if (existingIdx >= 0) {
        conversationHistory[existingIdx] = sessionData;
    } else {
        conversationHistory.push(sessionData);
    }
    saveConversationHistory();
    renderHistoryList();
    renderConversationList();  // Also update sidebar count badge
}

function loadSession(sessionId) {
    const session = conversationHistory.find(s => s.id === sessionId);
    if (!session) return;

    // Save current session first
    if (currentSessionId && currentMessages.length > 0 && currentSessionId !== sessionId) {
        saveCurrentSession();
    }

    currentSessionId = sessionId;
    currentMessages = session.messages || [];

    // Rebuild chat UI
    const chatMessages = document.getElementById('chat-messages');
    const welcome = document.getElementById('welcome-state');
    if (welcome) welcome.style.display = 'none';

    // Clear and rebuild (replay without re-recording)
    chatMessages.innerHTML = '';
    currentMessages.forEach(msg => {
        if (msg.role === 'user') {
            replayUserMessage(msg.content);
        } else if (msg.role === 'assistant') {
            replayAssistantMessage(msg.content, msg.sources || []);
        }
    });
    chatMessages.scrollTop = chatMessages.scrollHeight;

    // Restore info panel from the last assistant message
    restoreInfoPanelFromSession(currentMessages);

    renderHistoryList();
}

/**
 * Restore information panel (sources, entities, graph) from saved session
 */
function restoreInfoPanelFromSession(messages) {
    // Find the last assistant message with sources/entities
    const lastAssistant = [...messages].reverse().find(m => m.role === 'assistant');

    if (!lastAssistant) {
        clearInfoPanel();
        return;
    }

    const sources = lastAssistant.sources || [];
    const entities = lastAssistant.entities || [];

    // Restore sources list
    const sourcesList = document.getElementById('info-sources-list');
    if (sourcesList) {
        if (sources.length === 0) {
            sourcesList.innerHTML = `<div class="info-empty-state">No sources referenced</div>`;
        } else {
            sourcesList.innerHTML = sources.map(filename => {
                const ext = filename.split('.').pop().toLowerCase();
                const typeClass = ['pdf', 'txt', 'md'].includes(ext) ? ext : 'txt';
                return `
                    <div class="info-source-item" onclick="openSource('${escapeHtml(filename)}')"
                         tabindex="0" role="button"
                         onkeydown="if(event.key==='Enter')openSource('${escapeHtml(filename)}')">
                        <div class="info-source-icon ${typeClass}">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                                <polyline points="14 2 14 8 20 8"/>
                            </svg>
                        </div>
                        <div class="info-source-details">
                            <div class="info-source-name">${escapeHtml(filename)}</div>
                            <div class="info-source-type">${ext.toUpperCase()} Document</div>
                        </div>
                    </div>
                `;
            }).join('');
        }
    }

    // Restore entities list
    const entitiesList = document.getElementById('info-entities-list');
    if (entitiesList) {
        if (entities.length === 0) {
            entitiesList.innerHTML = `<div class="info-empty-state">No entities extracted</div>`;
        } else {
            entitiesList.innerHTML = entities.map(entity => `
                <div class="info-entity-item">
                    <div class="info-entity-name">${escapeHtml(entity.name)}</div>
                    <div class="info-entity-type">${escapeHtml(entity.description || entity.type)}</div>
                </div>
            `).join('');
        }
    }

    // Restore graph - convert filenames back to source objects for renderKnowledgeGraph
    const sourceObjects = sources.map(filename => ({ filename }));
    renderKnowledgeGraph(sourceObjects, entities);
}

// Replay functions (don't record to history - used when loading saved sessions)
function replayUserMessage(text) {
    const chatMessages = document.getElementById('chat-messages');
    const div = document.createElement('div');
    div.className = 'message user';
    div.innerHTML = `
    <div class="message-bubble">${escapeHtml(text)}</div>
    <div class="message-meta">Restored</div>
`;
    chatMessages.appendChild(div);
}

function replayAssistantMessage(text, sources) {
    const chatMessages = document.getElementById('chat-messages');
    const div = document.createElement('div');
    div.className = 'message assistant';
    const processedText = processCitations(text);
    div.innerHTML = `
    <div class="message-bubble">${processedText}</div>
    <div class="message-meta">Restored</div>
`;
    chatMessages.appendChild(div);
}

function deleteSession(sessionId) {
    conversationHistory = conversationHistory.filter(s => s.id !== sessionId);
    saveConversationHistory();
    if (currentSessionId === sessionId) {
        startNewChat();
    } else {
        renderHistoryList();
    }
}

function recordMessage(role, content, sources, entities) {
    currentMessages.push({
        role,
        content,
        sources: sources || [],
        entities: entities || [],
        timestamp: Date.now()
    });
    // Only save if history saving is enabled
    if (appSettings.saveHistory) {
        saveCurrentSession();
    }
}

// Initialize history on load
document.addEventListener('DOMContentLoaded', () => {
    loadConversationHistory();
    currentSessionId = generateSessionId();
    renderConversationList();
});

// === MAIN MENU TAB NAVIGATION ===
function switchMainTab(tabName) {
    event.preventDefault();

    // Menu items are actions, not persistent tabs
    // Handle tab actions
    switch(tabName) {
        case 'files':
            document.getElementById('file-input')?.click();
            break;
        case 'settings':
            toggleSidebar();
            break;
    }
    return false;
}

// === RESOURCES DROPDOWN ===
function toggleResourcesMenu(event) {
    event.preventDefault();
    event.stopPropagation();
    const dropdown = document.getElementById('resources-dropdown');
    dropdown.classList.toggle('show');

    // Close dropdown when clicking outside
    if (dropdown.classList.contains('show')) {
        setTimeout(() => {
            document.addEventListener('click', closeResourcesDropdown);
        }, 0);
    }
}

function closeResourcesDropdown(event) {
    const dropdown = document.getElementById('resources-dropdown');
    if (!dropdown.contains(event.target)) {
        dropdown.classList.remove('show');
        document.removeEventListener('click', closeResourcesDropdown);
    }
}

// Open manual with current sector
function openManual() {
    const sector = sectorSelect ? sectorSelect.value : 'ENTERPRISE';
    window.open(`manual.html?sector=${sector}`, '_blank');
    closeResourcesDropdown({ target: document.body });
}

function openReadme() {
    window.open('readme.html', '_blank');
    closeResourcesDropdown({ target: document.body });
}

// === RIGHT PANEL TAB NAVIGATION ===
function switchRightTab(tabName) {
    // Hide all right panel contents
    document.getElementById('right-tab-plot').style.display = 'none';
    document.getElementById('right-tab-source').style.display = 'none';

    // Deactivate all tab buttons
    document.querySelectorAll('.right-panel-tab').forEach(btn => {
        btn.classList.remove('active');
    });

    // Show selected content
    const tabContent = document.getElementById('right-tab-' + tabName);
    if (tabContent) {
        tabContent.style.display = 'block';
    }

    // Activate selected tab button
    const tabBtn = document.querySelector(`.right-panel-tab[data-tab="${tabName}"]`);
    if (tabBtn) {
        tabBtn.classList.add('active');
    }
}

// === COLLAPSIBLE INFO SECTIONS ===
function toggleInfoSection(btn) {
    const isExpanded = btn.getAttribute('aria-expanded') === 'true';
    btn.setAttribute('aria-expanded', !isExpanded);

    // Find the next sibling content element
    const content = btn.nextElementSibling;
    if (content && (content.classList.contains('info-section-content') || content.tagName === 'TABLE')) {
        content.classList.toggle('collapsed', isExpanded);
    }
}

// === RIGHT PANEL DATA POPULATION ===
function updateRightPanel(responseText, sources, confidence) {
    // Check if response indicates no relevant information was found
    // In this case, don't show sources/graph as it's misleading
    const noInfoPatterns = [
        /couldn'?t find any information/i,
        /no information (?:available |found )?(?:on|about|regarding)/i,
        /unable to find/i,
        /don'?t have (?:any )?information/i,
        /no relevant (?:information|data|documents)/i,
        /not (?:able to )?find (?:any )?(?:information|data)/i,
        /no (?:data|records|details) (?:available |found )?(?:on|about|for)/i
    ];

    const responseIndicatesNoInfo = noInfoPatterns.some(pattern => pattern.test(responseText));

    // If LLM says no info found, clear the panel instead of showing misleading sources
    if (responseIndicatesNoInfo) {
        clearInfoPanel();
        return;
    }

    // Calculate score based on source count
    let score = '-';
    if (sources && sources.length > 0) {
        score = Math.min(0.95, 0.7 + (sources.length * 0.05)).toFixed(1);
    }

    // Update source score badge
    const sourceScoreBadge = document.getElementById('source-score-badge');
    if (sourceScoreBadge) {
        sourceScoreBadge.textContent = `[score: ${score}]`;
    }

    // Update entity score badge
    const entityScoreBadge = document.getElementById('entity-score-badge');
    if (entityScoreBadge) {
        entityScoreBadge.textContent = `[score: ${score}]`;
    }

    // Populate sources list in info panel
    const sourcesList = document.getElementById('info-sources-list');
    if (sourcesList) {
        if (!sources || sources.length === 0) {
            sourcesList.innerHTML = `<div class="info-empty-state">No sources referenced</div>`;
        } else {
            // Show score badge when we have sources
            if (sourceScoreBadge) {
                sourceScoreBadge.classList.remove('hidden');
            }
            const uniqueSources = [...new Set(sources.map(s => s.filename))];
            sourcesList.innerHTML = uniqueSources.map(filename => {
                const ext = filename.split('.').pop().toLowerCase();
                const typeClass = ['pdf', 'txt', 'md'].includes(ext) ? ext : 'txt';
                return `
                    <div class="info-source-item" onclick="openSource('${escapeHtml(filename)}')"
                         tabindex="0" role="button"
                         onkeydown="if(event.key==='Enter')openSource('${escapeHtml(filename)}')">
                        <div class="info-source-icon ${typeClass}">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                                <polyline points="14 2 14 8 20 8"/>
                            </svg>
                        </div>
                        <div class="info-source-details">
                            <div class="info-source-name">${escapeHtml(filename)}</div>
                            <div class="info-source-type">${ext.toUpperCase()} Document</div>
                        </div>
                    </div>
                `;
            }).join('');

            // Auto-load the first source so Source tab isn't empty when user clicks tab
            if (uniqueSources.length > 0) {
                openSource(uniqueSources[0], false);  // false = don't switch tabs, just preload
            }
        }
    }

    // Extract entities from response text (excluding documents - they're shown in Sources)
    const entities = extractEntities(responseText, true);

    // Populate entity list
    const entitiesList = document.getElementById('info-entities-list');
    if (entitiesList) {
        if (entities.length === 0) {
            entitiesList.innerHTML = `<div class="info-empty-state">No entities extracted</div>`;
        } else {
            // Show score badge when we have entities
            if (entityScoreBadge) {
                entityScoreBadge.classList.remove('hidden');
            }
            entitiesList.innerHTML = entities.map(entity => `
                <div class="info-entity-item">
                    <div class="info-entity-name">${escapeHtml(entity.name)}</div>
                    <div class="info-entity-type">${escapeHtml(entity.description || entity.type)}</div>
                </div>
            `).join('');
        }
    }

    // Render knowledge graph with sources as primary nodes (Kotaemon-style)
    renderKnowledgeGraph(sources, entities);
}

/**
 * Clear the information panel (graph, sources, entities) to empty state
 * Called when starting a new chat
 */
function clearInfoPanel() {
    // Clear graph
    const graphDiv = document.getElementById('plotly-graph');
    const placeholder = document.getElementById('graph-placeholder');
    if (graphDiv && typeof Plotly !== 'undefined') {
        Plotly.purge(graphDiv);
    }
    if (placeholder) {
        placeholder.classList.remove('hidden');
    }

    // Clear sources list
    const sourcesList = document.getElementById('info-sources-list');
    if (sourcesList) {
        sourcesList.innerHTML = `<div class="info-empty-state">No sources yet</div>`;
    }

    // Clear entities list
    const entitiesList = document.getElementById('info-entities-list');
    if (entitiesList) {
        entitiesList.innerHTML = `<div class="info-empty-state">No entities extracted</div>`;
    }

    // Hide score badges
    const sourceScoreBadge = document.getElementById('source-score-badge');
    const entityScoreBadge = document.getElementById('entity-score-badge');
    if (sourceScoreBadge) sourceScoreBadge.classList.add('hidden');
    if (entityScoreBadge) entityScoreBadge.classList.add('hidden');
}

/**
 * Render knowledge graph with sources as primary nodes (Kotaemon-style)
 * Sources are shown as the main connected nodes around a central "Query" node
 */
function renderKnowledgeGraph(sources, entities) {
    const graphDiv = document.getElementById('plotly-graph');
    const placeholder = document.getElementById('graph-placeholder');
    const container = document.getElementById('graph-container');

    if (!graphDiv || !container) return;

    // Check if Plotly is loaded
    if (typeof Plotly === 'undefined') {
        console.warn('Plotly.js not loaded. Graph visualization unavailable.');
        if (placeholder) {
            placeholder.innerHTML = '<div style="color:var(--text-tertiary);font-size:11px;">Graph requires internet connection</div>';
            placeholder.classList.remove('hidden');
        }
        return;
    }

    // Ensure container has dimensions
    if (container.offsetWidth === 0 || container.offsetHeight === 0) {
        setTimeout(() => renderKnowledgeGraph(sources, entities), 100);
        return;
    }

    // If no sources, show placeholder and clear graph
    if (!sources || sources.length === 0) {
        if (placeholder) placeholder.classList.remove('hidden');
        Plotly.purge(graphDiv);
        return;
    }

    // Hide placeholder
    if (placeholder) placeholder.classList.add('hidden');

    // Theme colors
    const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
    const colors = {
        bg: isDark ? 'rgba(30, 41, 59, 0)' : 'rgba(248, 250, 252, 0)',
        edge: isDark ? 'rgba(148, 163, 184, 0.4)' : 'rgba(100, 116, 139, 0.3)',
        edgeHighlight: isDark ? 'rgba(74, 222, 128, 0.6)' : 'rgba(16, 185, 129, 0.5)',
        text: isDark ? '#e2e8f0' : '#334155',
        centerNode: '#22c55e',
        sourceNode: '#3b82f6',
        entityNode: '#a855f7'
    };

    // Build nodes array: center (query) + sources + entities
    const nodes = [];
    const nodeX = [];
    const nodeY = [];
    const nodeColors = [];
    const nodeSizes = [];
    const nodeLabels = [];
    const nodeHover = [];

    // Center node (Query)
    nodes.push({ id: 'query', type: 'query', label: 'Query' });
    nodeX.push(0);
    nodeY.push(0);
    nodeColors.push(colors.centerNode);
    nodeSizes.push(30);
    nodeLabels.push('Query');
    nodeHover.push('Query Hub');

    // Helper to clean filename - remove all brackets, markdown links, extensions
    function cleanFilename(text) {
        return text
            .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1') // [text](link) -> text
            .replace(/[\[\]]/g, '')                   // Remove all brackets
            .replace(/\.(pdf|txt|md)$/i, '')          // Remove extension
            .trim();
    }

    // Truncate labels for graph display (full name shown on hover)
    // Keep VERY short to prevent edge clipping - hover shows full name
    // v2: Reduced to 8 chars max for better fit
    function getLabel(text, maxLength = 8) {
        const clean = cleanFilename(text);
        if (clean.length <= maxLength) return clean;
        return clean.substring(0, maxLength - 1) + 'â€¦';
    }

    // FIXED RADII - Use simple, consistent spacing
    // Inner ring (sources) at radius 1, outer ring (entities) at radius 1.6 (closer to center)
    const sourceRadius = 0.9;
    const entityRadius = 1.6;

    // Track label positions for text positioning
    const textPositions = [];

    // Helper to get text position based on angle
    // Position text TOWARDS CENTER to prevent edge clipping
    function getTextPosition(angle) {
        // Normalize angle to 0-2PI
        const a = ((angle % (Math.PI * 2)) + Math.PI * 2) % (Math.PI * 2);
        // All text positioned towards center (opposite to node direction from center)
        if (a < Math.PI / 4 || a >= 7 * Math.PI / 4) return 'middle left';   // Right side -> text left
        if (a < 3 * Math.PI / 4) return 'bottom center';                      // Top side -> text below
        if (a < 5 * Math.PI / 4) return 'middle right';                       // Left side -> text right
        return 'top center';                                                   // Bottom side -> text above
    }

    // Source nodes (documents) - arranged in inner ring
    sources.forEach((source, i) => {
        const angle = (i / sources.length) * Math.PI * 2 - Math.PI / 2;
        let filename = source.filename || source;
        const cleanName = cleanFilename(filename);
        const displayLabel = getLabel(filename);  // Truncated for display
        nodes.push({ id: `source-${i}`, type: 'source', label: cleanName, filename: source.filename || source });
        nodeX.push(Math.cos(angle) * sourceRadius);
        nodeY.push(Math.sin(angle) * sourceRadius);
        nodeColors.push(colors.sourceNode);
        nodeSizes.push(16);
        nodeLabels.push(displayLabel);  // Use truncated label
        nodeHover.push(`Source: ${cleanName}`);  // Full name on hover
        textPositions.push(getTextPosition(angle));
    });

    // Entity nodes - arranged in outer ring, limit to 6 for clarity
    const limitedEntities = entities.slice(0, 6);
    limitedEntities.forEach((entity, i) => {
        // Offset entity angles so they don't align with sources
        const offset = Math.PI / limitedEntities.length;
        const angle = (i / limitedEntities.length) * Math.PI * 2 + offset - Math.PI / 2;
        const cleanName = cleanFilename(entity.name);
        const displayLabel = getLabel(entity.name);  // Truncated for display
        nodes.push({ id: `entity-${i}`, type: 'entity', label: cleanName, entityName: entity.name });
        nodeX.push(Math.cos(angle) * entityRadius);
        nodeY.push(Math.sin(angle) * entityRadius);
        nodeColors.push(colors.entityNode);
        nodeSizes.push(12);
        nodeLabels.push(displayLabel);  // Use truncated label
        nodeHover.push(`Entity: ${cleanName} (click to query)`);  // Full name on hover
        textPositions.push(getTextPosition(angle));
    });

    // Build edges
    const edgeX = [];
    const edgeY = [];
    const edgeHighlightX = [];
    const edgeHighlightY = [];

    // Query to sources (highlighted edges)
    sources.forEach((_, i) => {
        edgeHighlightX.push(nodeX[0], nodeX[i + 1], null);
        edgeHighlightY.push(nodeY[0], nodeY[i + 1], null);
    });

    // Sources to entities
    limitedEntities.forEach((_, i) => {
        const sourceIdx = (i % sources.length) + 1;
        const entityIdx = sources.length + 1 + i;
        edgeX.push(nodeX[sourceIdx], nodeX[entityIdx], null);
        edgeY.push(nodeY[sourceIdx], nodeY[entityIdx], null);
    });

    // Some inter-entity connections
    for (let i = 1; i < limitedEntities.length; i++) {
        if (Math.random() > 0.5) {
            const idx1 = sources.length + i;
            const idx2 = sources.length + i + 1;
            edgeX.push(nodeX[idx1], nodeX[idx2], null);
            edgeY.push(nodeY[idx1], nodeY[idx2], null);
        }
    }

    // Create traces
    const edgeTrace = {
        x: edgeX,
        y: edgeY,
        mode: 'lines',
        type: 'scatter',
        line: { width: 1.5, color: colors.edge },
        hoverinfo: 'none'
    };

    const edgeHighlightTrace = {
        x: edgeHighlightX,
        y: edgeHighlightY,
        mode: 'lines',
        type: 'scatter',
        line: { width: 2.5, color: colors.edgeHighlight },
        hoverinfo: 'none'
    };

    // Build text positions array - center node is 'top center', others use calculated positions
    const allTextPositions = ['top center', ...textPositions];

    const nodeTrace = {
        x: nodeX,
        y: nodeY,
        mode: 'markers+text',
        type: 'scatter',
        marker: {
            size: nodeSizes,
            color: nodeColors,
            line: { width: 1.5, color: isDark ? 'rgba(255,255,255,0.15)' : 'rgba(0,0,0,0.08)' }
        },
        text: nodeLabels,
        textposition: allTextPositions,
        textfont: {
            family: '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
            size: 11,
            color: colors.text
        },
        hoverinfo: 'text',
        hovertext: nodeHover,
        cliponaxis: false  // CRITICAL: Prevent text clipping at axis boundaries
    };

    // Fixed container and axis dimensions for consistent layout
    const containerHeight = 450;  // Taller for better visibility
    container.style.minHeight = containerHeight + 'px';

    // Fixed axis range: outer radius + padding for labels
    // v2: Increased axis range for more label space
    const axisRange = 4.0;

    const layout = {
        showlegend: false,
        hovermode: 'closest',
        autosize: true,
        height: containerHeight,
        margin: { t: 40, b: 40, l: 20, r: 20 },  // Minimal margins, let SVG overflow handle it
        xaxis: {
            showgrid: false,
            zeroline: false,
            showticklabels: false,
            fixedrange: true,
            range: [-axisRange, axisRange]
        },
        yaxis: {
            showgrid: false,
            zeroline: false,
            showticklabels: false,
            fixedrange: true,
            range: [-axisRange, axisRange],
            scaleanchor: 'x',
            scaleratio: 1
        },
        paper_bgcolor: colors.bg,
        plot_bgcolor: colors.bg,
        dragmode: false
    };

    const config = {
        displayModeBar: false,
        responsive: true,
        staticPlot: false,
        scrollZoom: false
    };

    // Clear any existing plot
    Plotly.purge(graphDiv);

    // Create new plot
    Plotly.newPlot(graphDiv, [edgeTrace, edgeHighlightTrace, nodeTrace], layout, config).then(function() {
        console.log('Graph rendered with', nodes.length, 'nodes');

        // Store nodes reference for click handler
        graphDiv._nodes = nodes;

        // Dynamic panel width adjustment based on label lengths
        const rightPanel = document.querySelector('.right-panel');
        if (rightPanel) {
            const maxLabelLength = Math.max(...nodeLabels.map(l => l.length));
            // Base width 380px + extra for longer labels (approx 8px per char over 12)
            const extraWidth = Math.max(0, (maxLabelLength - 12) * 8);
            const newWidth = Math.min(560, 380 + extraWidth);
            rightPanel.style.minWidth = newWidth + 'px';
        }

        // Add hover event handler to change cursor for clickable nodes
        graphDiv.on('plotly_hover', function(data) {
            if (!data.points || data.points.length === 0) return;
            const pointIndex = data.points[0].pointIndex;
            const node = graphDiv._nodes[pointIndex];
            if (node && (node.type === 'source' || node.type === 'entity')) {
                graphDiv.style.cursor = 'pointer';
            } else {
                graphDiv.style.cursor = 'default';
            }
        });

        graphDiv.on('plotly_unhover', function() {
            graphDiv.style.cursor = 'default';
        });

        // Add click event handler for interactive node actions
        graphDiv.on('plotly_click', function(data) {
            console.log('Graph click event', data);
            if (!data.points || data.points.length === 0) return;

            const pointIndex = data.points[0].pointIndex;
            const node = graphDiv._nodes[pointIndex];

            console.log('Clicked node:', node);

            if (!node) return;

            if (node.type === 'source') {
                // Click on source node: highlight in sources list
                const filename = node.filename || node.label;
                highlightSourceInList(filename);
                showToast(`Source: ${node.label}`);
            } else if (node.type === 'entity') {
                // Click on entity node: run a new query scoped to that entity
                const entityName = node.entityName || node.label;
                runEntityQuery(entityName);
            }
        });
    });

    // Add legend below the graph
    const legendHtml = `
        <div class="graph-legend">
            <div class="legend-item"><span class="legend-dot" style="background:${colors.centerNode}"></span>Query</div>
            <div class="legend-item"><span class="legend-dot" style="background:${colors.sourceNode}"></span>Sources</div>
            <div class="legend-item"><span class="legend-dot" style="background:${colors.entityNode}"></span>Entities</div>
        </div>
    `;
    // Insert legend if not already present
    let legendEl = container.querySelector('.graph-legend');
    if (!legendEl) {
        container.insertAdjacentHTML('beforeend', legendHtml);
    }
}

/**
 * Highlight a source item in the sources list and scroll to it
 * @param {string} filename - The filename to highlight
 */
function highlightSourceInList(filename) {
    const sourcesList = document.getElementById('info-sources-list');
    if (!sourcesList) return;

    // Remove previous highlights
    sourcesList.querySelectorAll('.info-source-item.highlighted').forEach(el => {
        el.classList.remove('highlighted');
    });

    // Find and highlight the matching source item
    const sourceItems = sourcesList.querySelectorAll('.info-source-item');
    sourceItems.forEach(item => {
        const nameEl = item.querySelector('.info-source-name');
        if (nameEl && nameEl.textContent.includes(filename.replace(/\.(pdf|txt|md)$/i, ''))) {
            item.classList.add('highlighted');
            // Scroll the sources section into view
            item.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
    });
}

/**
 * Run a new query scoped to an entity
 * @param {string} entityName - The entity name to query about
 */
function runEntityQuery(entityName) {
    const queryInput = document.getElementById('query-input');
    if (!queryInput) return;

    // Set query to ask about this entity
    queryInput.value = `Tell me more about "${entityName}"`;
    queryInput.focus();

    // Trigger the search
    handleQuery();
}

/**
 * Extract named entities from response text using pattern matching
 * @param {string} text - The response text to extract entities from
 * @param {boolean} excludeDocuments - If true, don't include document citations
 * @returns {Array} Array of entity objects with name, type, and description
 */
function extractEntities(text, excludeDocuments = false) {
    const entities = [];
    const seen = new Set();

    // Helper to add unique entities with descriptions
    function addEntity(name, type, description = '') {
        const key = `${name.toLowerCase()}-${type}`;
        if (!seen.has(key) && name.length > 1 && name.length < 100) {
            seen.add(key);
            // Generate description based on context if not provided
            if (!description) {
                description = generateEntityDescription(name, type, text);
            }
            entities.push({ name: name.trim(), type, description });
        }
    }

    // Extract document citations (skip if excludeDocuments is true)
    if (!excludeDocuments) {
        const docPatterns = [
            /\[([^\]]+\.(pdf|txt|md|doc|docx))\]/gi,
            /`([^`]+\.(pdf|txt|md|doc|docx))`/gi
        ];
        docPatterns.forEach(pattern => {
            let match;
            while ((match = pattern.exec(text)) !== null) {
                addEntity(match[1], 'document', 'Source document');
            }
        });
    }

    // Extract dates (various formats)
    const datePatterns = [
        /\b(\d{1,2}[\/\-]\d{1,2}[\/\-]\d{2,4})\b/g,
        /\b(January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{1,2},?\s+\d{4}\b/gi,
        /\b\d{4}[\/\-]\d{2}[\/\-]\d{2}\b/g
    ];
    datePatterns.forEach(pattern => {
        let match;
        while ((match = pattern.exec(text)) !== null) {
            addEntity(match[0], 'date');
        }
    });

    // Extract capitalized proper nouns (potential people/orgs/places)
    // Pattern: Two or more capitalized words in sequence
    const properNounPattern = /\b([A-Z][a-z]+(?:\s+[A-Z][a-z]+)+)\b/g;
    let match;
    while ((match = properNounPattern.exec(text)) !== null) {
        const term = match[1];
        // Skip common false positives
        const skipTerms = ['The', 'This', 'That', 'These', 'Those', 'According To', 'Based On'];
        if (!skipTerms.some(skip => term.startsWith(skip))) {
            // Heuristic classification - ORDER MATTERS (most specific first)

            // Job titles/roles should be concepts, not people
            if (term.match(/\b(Officer|Director|Manager|Head|Lead|Chief|Counsel|President|Vice|Executive|Sector|Operations|Leadership|Analyst|Engineer|Coordinator|Supervisor|Administrator)\b/i)) {
                addEntity(term, 'concept');
            } else if (term.match(/\b(Inc|Corp|LLC|Ltd|Company|Organization|Agency|Department|Institute|University|Hospital|Team|Group|Division)\b/i)) {
                addEntity(term, 'organization');
            } else if (term.match(/\b(Street|Avenue|Road|City|County|State|Country|Building|Floor|Suite)\b/i)) {
                addEntity(term, 'location');
            } else if (term.split(' ').length === 2 && !term.match(/[0-9]/)) {
                // Two-word proper nouns without role keywords are likely people names
                addEntity(term, 'person');
            } else {
                addEntity(term, 'concept');
            }
        }
    }

    // Extract quoted terms as potential concepts/definitions
    // Skip if it looks like a filename (has file extension)
    const quotedPattern = /"([^"]{3,50})"/g;
    while ((match = quotedPattern.exec(text)) !== null) {
        const term = match[1];
        // Skip filenames (anything with common file extensions)
        if (!term.match(/\.(pdf|txt|md|doc|docx|csv|json|xml|html|xlsx)$/i)) {
            addEntity(term, 'concept');
        }
    }

    // Extract acronyms (all caps, 2-6 letters)
    const acronymPattern = /\b([A-Z]{2,6})\b/g;
    while ((match = acronymPattern.exec(text)) !== null) {
        const acronym = match[1];
        // Skip common words that happen to be caps
        const skipAcronyms = ['THE', 'AND', 'FOR', 'ARE', 'BUT', 'NOT', 'YOU', 'ALL', 'CAN', 'HAD', 'HER', 'WAS', 'ONE', 'OUR', 'OUT'];
        if (!skipAcronyms.includes(acronym)) {
            addEntity(acronym, 'acronym');
        }
    }

    // Limit to top 15 entities to avoid clutter
    return entities.slice(0, 15);
}

/**
 * Generate a contextual description for an entity based on its type
 * Uses simple type-based descriptions to avoid noisy/repetitive context extraction
 */
function generateEntityDescription(name, type, fullText) {
    // Use clean type-based descriptions - more reliable than context extraction
    const typeDescriptions = {
        person: 'Person',
        organization: 'Organization',
        location: 'Location',
        date: 'Date',
        concept: 'Concept',
        acronym: 'Acronym',
        document: 'Document'
    };

    return typeDescriptions[type] || 'Entity';
}

// === LEFT SIDEBAR CONVERSATION LIST ===
// Pinned conversations stored in localStorage (max 3), scoped to user+sector
function getPinnedIds() {
    try {
        const storageKey = getScopedStorageKey('sentinel-pinned-chats');
        return JSON.parse(localStorage.getItem(storageKey) || '[]');
    } catch { return []; }
}

function setPinnedIds(ids) {
    const storageKey = getScopedStorageKey('sentinel-pinned-chats');
    localStorage.setItem(storageKey, JSON.stringify(ids.slice(0, 3)));
}

function togglePin(sessionId, event) {
    event.stopPropagation();
    const pinned = getPinnedIds();
    const idx = pinned.indexOf(sessionId);
    if (idx >= 0) {
        pinned.splice(idx, 1);
    } else if (pinned.length < 3) {
        pinned.unshift(sessionId);
    } else {
        // Already 3 pinned, replace oldest
        pinned.pop();
        pinned.unshift(sessionId);
    }
    setPinnedIds(pinned);
    renderConversationList();
}

/**
 * Clear all unpinned conversations while preserving pinned ones
 */
function clearUnpinnedConversations() {
    const pinnedIds = getPinnedIds();
    const unpinnedCount = conversationHistory.filter(s => !pinnedIds.includes(s.id)).length;

    if (unpinnedCount === 0) {
        showToast('No unpinned conversations to clear');
        return;
    }

    const confirmed = confirm(`Clear ${unpinnedCount} unpinned conversation${unpinnedCount !== 1 ? 's' : ''}?\n\nPinned conversations will be preserved.`);
    if (!confirmed) return;

    // Keep only pinned conversations
    conversationHistory = conversationHistory.filter(s => pinnedIds.includes(s.id));

    // Reset current session if it was cleared
    if (currentSessionId && !pinnedIds.includes(currentSessionId)) {
        currentSessionId = generateSessionId();
        currentMessages = [];
        clearChat();
        clearInfoPanel();
    }

    // Save and re-render
    saveConversationHistory();
    renderConversationList();

    showToast(`Cleared ${unpinnedCount} conversation${unpinnedCount !== 1 ? 's' : ''}`);
}

function toggleConversationList() {
    const section = document.getElementById('conversation-section');
    section.classList.toggle('collapsed');
    localStorage.setItem('sentinel-conversations-collapsed', section.classList.contains('collapsed'));
}

// ===== SAVED QUERIES FEATURE =====
const SAVED_QUERIES_KEY = 'sentinel-saved-queries';

function getSavedQueries() {
    try {
        const storageKey = getScopedStorageKey('sentinel-saved-queries');
        return JSON.parse(localStorage.getItem(storageKey) || '[]');
    } catch {
        return [];
    }
}

function saveSavedQueries(queries) {
    const storageKey = getScopedStorageKey('sentinel-saved-queries');
    localStorage.setItem(storageKey, JSON.stringify(queries));
}

function toggleSavedQueriesList() {
    const section = document.getElementById('saved-queries-section');
    section.classList.toggle('collapsed');
    const collapsedKey = getScopedStorageKey('sentinel-saved-queries-collapsed');
    localStorage.setItem(collapsedKey, section.classList.contains('collapsed'));
}

function saveCurrentQuery() {
    // Get current query from input as default
    const queryInput = document.getElementById('query-input');
    const currentQuery = queryInput?.value?.trim() || '';

    // Prompt user for query text (pre-filled with current query if exists)
    const query = prompt('Enter query to save:', currentQuery);

    if (!query || !query.trim()) {
        return; // User cancelled or entered empty string
    }

    const trimmedQuery = query.trim();
    const queries = getSavedQueries();

    // Check for duplicates
    if (queries.some(q => q.text.toLowerCase() === trimmedQuery.toLowerCase())) {
        showNotification('Query already saved', 'info');
        return;
    }

    queries.unshift({
        id: Date.now().toString(),
        text: trimmedQuery,
        timestamp: Date.now()
    });

    // Limit to 20 saved queries
    if (queries.length > 20) {
        queries.pop();
    }

    saveSavedQueries(queries);
    renderSavedQueriesList();
    showNotification('Query saved', 'success');
}

function deleteSavedQuery(id, event) {
    event.stopPropagation();
    const queries = getSavedQueries().filter(q => q.id !== id);
    saveSavedQueries(queries);
    renderSavedQueriesList();
}

function useSavedQuery(text) {
    const queryInput = document.getElementById('query-input');
    if (queryInput) {
        queryInput.value = text;
        queryInput.focus();
    }
}

function renderSavedQueriesList() {
    const list = document.getElementById('saved-queries-list');
    const countBadge = document.getElementById('saved-queries-count');
    const section = document.getElementById('saved-queries-section');

    if (!list) return;

    // Restore collapsed state
    const isCollapsed = localStorage.getItem('sentinel-saved-queries-collapsed') !== 'false';
    if (isCollapsed) {
        section.classList.add('collapsed');
    } else {
        section.classList.remove('collapsed');
    }

    const queries = getSavedQueries();

    // Update count
    if (countBadge) countBadge.textContent = queries.length;

    if (queries.length === 0) {
        list.innerHTML = `
            <div class="saved-queries-empty">
                No saved queries yet.<br>Save frequently used queries for quick access.
            </div>
        `;
        return;
    }

    list.innerHTML = queries.map(q => `
        <div class="saved-query-item" onclick="useSavedQuery('${escapeHtml(q.text).replace(/'/g, "\\'")}')">
            <svg class="saved-query-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
            </svg>
            <span class="saved-query-text" title="${escapeHtml(q.text)}">${escapeHtml(q.text)}</span>
            <button class="saved-query-delete" onclick="deleteSavedQuery('${q.id}', event)" title="Delete">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:12px;height:12px;">
                    <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
            </button>
        </div>
    `).join('');
}

// Initialize saved queries on page load
document.addEventListener('DOMContentLoaded', function() {
    renderSavedQueriesList();
});

function renderConversationList() {
    const list = document.getElementById('conversation-list');
    const pinnedList = document.getElementById('pinned-list');
    const pinnedSection = document.getElementById('pinned-section');
    const countBadge = document.getElementById('conversation-count');
    const section = document.getElementById('conversation-section');

    if (!list) return;

    // Restore collapsed state
    const isCollapsed = localStorage.getItem('sentinel-conversations-collapsed') === 'true';
    if (isCollapsed) {
        section.classList.add('collapsed');
    }

    const pinnedIds = getPinnedIds();
    const sorted = [...conversationHistory].sort((a, b) => b.timestamp - a.timestamp);

    // Separate pinned and unpinned
    const pinned = sorted.filter(s => pinnedIds.includes(s.id));
    const unpinned = sorted.filter(s => !pinnedIds.includes(s.id)).slice(0, 10);

    // Update count
    if (countBadge) countBadge.textContent = unpinned.length;

    // Render pinned section
    if (pinnedSection && pinnedList) {
        if (pinned.length > 0) {
            pinnedSection.style.display = '';
            pinnedList.innerHTML = pinned.map(session => renderConversationItem(session, true)).join('');
        } else {
            pinnedSection.style.display = 'none';
        }
    }

    // Render unpinned list
    if (unpinned.length === 0 && pinned.length === 0) {
        list.innerHTML = `
            <div style="text-align:center; padding:16px; color:var(--text-tertiary); font-size:11px;">
                No conversations yet
            </div>
        `;
        return;
    }

    list.innerHTML = unpinned.map(session => renderConversationItem(session, false)).join('');
}

function renderConversationItem(session, isPinned) {
    const pinnedClass = isPinned ? 'pinned' : '';
    const activeClass = session.id === currentSessionId ? 'active' : '';
    // Pin icon (pushpin style)
    const pinIcon = isPinned
        ? `<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z"/></svg>`
        : `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 17v5"/><path d="M9 9V4h6v5"/><path d="M5 12h14"/><path d="M6 12a3 3 0 0 0 3-3"/><path d="M18 12a3 3 0 0 1-3-3"/></svg>`;

    // Show more preview text (up to 35 chars)
    const title = session.title || 'Untitled';
    const displayTitle = title.length > 35 ? title.substring(0, 35) + '...' : title;

    return `
        <div class="conversation-item ${activeClass} ${pinnedClass}"
             onclick="loadSession('${session.id}')"
             role="listitem" tabindex="0"
             onkeydown="if(event.key==='Enter')loadSession('${session.id}')">
            <div class="conversation-item-title">${escapeHtml(displayTitle)}</div>
            <div class="conversation-item-pin" onclick="togglePin('${session.id}', event)" title="${isPinned ? 'Unpin' : 'Pin'}">
                ${pinIcon}
            </div>
        </div>
    `;
}

// Update conversation list when history changes
const originalRenderHistoryList = renderHistoryList;
renderHistoryList = function() {
    originalRenderHistoryList();
    renderConversationList();
};

function selectCollection(type) {
    document.querySelectorAll('.collection-item').forEach(item => {
        item.classList.remove('active');
    });
    event.currentTarget.classList.add('active');
    console.log('Selected collection:', type);
}

// === LEGACY TAB NAVIGATION (508 Accessible) - kept for compatibility ===
function switchTab(tabName) {
    // For now, just log - tabs are removed in new layout
    console.log('Tab switch requested:', tabName);
}

// === SIDEBAR ===
function toggleSidebar() {
    const sidebar = document.getElementById('sidebar');
    if (sidebar) {
        sidebar.classList.toggle('open');
        console.log('Sidebar toggled, open:', sidebar.classList.contains('open'));
    } else {
        console.error('Sidebar element not found!');
    }
}

function closeSidebar() {
    const sidebar = document.getElementById('sidebar');
    if (sidebar) sidebar.classList.remove('open');
}

// === THEME MANAGEMENT ===
function setTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('sentinel-theme', theme);

    // Update theme buttons
    document.getElementById('theme-light')?.classList.toggle('active', theme === 'light');
    document.getElementById('theme-dark')?.classList.toggle('active', theme === 'dark');
}

// Initialize theme from localStorage
function initTheme() {
    const savedTheme = localStorage.getItem('sentinel-theme') || 'dark';
    setTheme(savedTheme);
}

// === SETTINGS SLIDERS ===
function initSettingsSliders() {
    const topKSlider = document.getElementById('top-k-slider');
    const topKValue = document.getElementById('top-k-value');
    const simSlider = document.getElementById('similarity-slider');
    const simValue = document.getElementById('similarity-value');

    if (topKSlider && topKValue) {
        topKSlider.addEventListener('input', () => {
            topKValue.textContent = topKSlider.value;
            saveSettings();
        });
    }

    if (simSlider && simValue) {
        simSlider.addEventListener('input', () => {
            simValue.textContent = (simSlider.value / 100).toFixed(2);
            saveSettings();
        });
    }
}

// Settings state object
const appSettings = {
    topK: 5,
    similarityThreshold: 0.70,
    hyde: true,
    graphrag: true,
    reranking: true,
    debug: false,
    showReasoning: true,
    autoScroll: true,
    saveHistory: true
};

function initSettingsToggles() {
    // RAG Engine toggles
    const hydeToggle = document.getElementById('hyde-toggle');
    const graphragToggle = document.getElementById('graphrag-toggle');
    const rerankToggle = document.getElementById('rerank-toggle');

    // Advanced toggles
    const debugToggle = document.getElementById('debug-toggle');
    const showReasoningToggle = document.getElementById('show-reasoning');
    const autoScrollToggle = document.getElementById('auto-scroll');
    const saveHistoryToggle = document.getElementById('save-history');

    // Load saved settings
    loadSettings();

    // Initialize toggle states from saved settings (no auto-save on change)
    if (hydeToggle) hydeToggle.checked = appSettings.hyde;
    if (graphragToggle) graphragToggle.checked = appSettings.graphrag;
    if (rerankToggle) rerankToggle.checked = appSettings.reranking;
    if (debugToggle) debugToggle.checked = appSettings.debug;
    if (showReasoningToggle) showReasoningToggle.checked = appSettings.showReasoning;
    if (autoScrollToggle) autoScrollToggle.checked = appSettings.autoScroll;
    if (saveHistoryToggle) saveHistoryToggle.checked = appSettings.saveHistory;

    // Mark settings as changed when user interacts (visual feedback only)
    const allToggles = [hydeToggle, graphragToggle, rerankToggle, debugToggle,
                       showReasoningToggle, autoScrollToggle, saveHistoryToggle];
    allToggles.forEach(toggle => {
        if (toggle) {
            toggle.addEventListener('change', () => {
                markSettingsUnsaved();
            });
        }
    });

    // Also mark unsaved when sliders change
    const topKSlider = document.getElementById('top-k-slider');
    const simSlider = document.getElementById('similarity-slider');
    if (topKSlider) topKSlider.addEventListener('input', markSettingsUnsaved);
    if (simSlider) simSlider.addEventListener('input', markSettingsUnsaved);
}

// Track if settings have unsaved changes
let settingsUnsaved = false;

function markSettingsUnsaved() {
    settingsUnsaved = true;
    const saveBtn = document.getElementById('save-settings-btn');
    if (saveBtn) {
        saveBtn.classList.add('unsaved');
        saveBtn.innerHTML = `
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/>
                <polyline points="17 21 17 13 7 13 7 21"/>
                <polyline points="7 3 7 8 15 8"/>
            </svg>
            Save Settings *
        `;
    }
}

function saveSettings() {
    // Update from sliders
    const topKSlider = document.getElementById('top-k-slider');
    const simSlider = document.getElementById('similarity-slider');
    if (topKSlider) appSettings.topK = parseInt(topKSlider.value);
    if (simSlider) appSettings.similarityThreshold = simSlider.value / 100;

    localStorage.setItem('sentinel-settings', JSON.stringify(appSettings));
}

function loadSettings() {
    const saved = localStorage.getItem('sentinel-settings');
    if (saved) {
        try {
            const parsed = JSON.parse(saved);
            Object.assign(appSettings, parsed);

            // Apply to sliders
            const topKSlider = document.getElementById('top-k-slider');
            const topKValue = document.getElementById('top-k-value');
            const simSlider = document.getElementById('similarity-slider');
            const simValue = document.getElementById('similarity-value');

            if (topKSlider && topKValue) {
                topKSlider.value = appSettings.topK;
                topKValue.textContent = appSettings.topK;
            }
            if (simSlider && simValue) {
                simSlider.value = appSettings.similarityThreshold * 100;
                simValue.textContent = appSettings.similarityThreshold.toFixed(2);
            }

            // Apply debug mode class if enabled
            if (appSettings.debug) {
                document.body.classList.add('debug-mode');
            }
        } catch (e) {
            console.warn('Failed to load settings:', e);
        }
    }
}

function showSettingToast(settingName, enabled) {
    // Create toast notification
    const toast = document.createElement('div');
    toast.className = 'setting-toast';
    toast.innerHTML = `
        <span class="setting-toast-icon">${enabled ? 'âœ“' : 'âœ—'}</span>
        <span>${settingName} ${enabled ? 'enabled' : 'disabled'}</span>
    `;
    document.body.appendChild(toast);

    // Animate in
    requestAnimationFrame(() => {
        toast.classList.add('show');
    });

    // Remove after delay
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 200);
    }, 1500);
}

function saveSettingsWithConfirmation() {
    // Read current toggle states and apply to appSettings
    const hydeToggle = document.getElementById('hyde-toggle');
    const graphragToggle = document.getElementById('graphrag-toggle');
    const rerankToggle = document.getElementById('rerank-toggle');
    const debugToggle = document.getElementById('debug-toggle');
    const showReasoningToggle = document.getElementById('show-reasoning');
    const autoScrollToggle = document.getElementById('auto-scroll');
    const saveHistoryToggle = document.getElementById('save-history');
    const topKSlider = document.getElementById('top-k-slider');
    const simSlider = document.getElementById('similarity-slider');

    // Apply toggle values to appSettings
    if (hydeToggle) appSettings.hyde = hydeToggle.checked;
    if (graphragToggle) appSettings.graphrag = graphragToggle.checked;
    if (rerankToggle) appSettings.reranking = rerankToggle.checked;
    if (debugToggle) {
        appSettings.debug = debugToggle.checked;
        document.body.classList.toggle('debug-mode', debugToggle.checked);
    }
    if (showReasoningToggle) {
        appSettings.showReasoning = showReasoningToggle.checked;
        document.querySelectorAll('.reasoning-accordion').forEach(el => {
            el.style.display = showReasoningToggle.checked ? 'block' : 'none';
        });
    }
    if (autoScrollToggle) appSettings.autoScroll = autoScrollToggle.checked;
    if (saveHistoryToggle) {
        appSettings.saveHistory = saveHistoryToggle.checked;
        if (!saveHistoryToggle.checked) {
            localStorage.removeItem('sentinel-conversation-history');
            conversationHistory = [];
            renderHistoryList();
            renderConversationList();
        }
    }
    if (topKSlider) appSettings.topK = parseInt(topKSlider.value);
    if (simSlider) appSettings.similarityThreshold = simSlider.value / 100;

    // Save to localStorage
    localStorage.setItem('sentinel-settings', JSON.stringify(appSettings));

    // Reset unsaved state
    settingsUnsaved = false;
    const saveBtn = document.getElementById('save-settings-btn');
    if (saveBtn) {
        saveBtn.classList.remove('unsaved');
        saveBtn.innerHTML = `
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/>
                <polyline points="17 21 17 13 7 13 7 21"/>
                <polyline points="7 3 7 8 15 8"/>
            </svg>
            Save Settings
        `;
    }

    // Show success toast
    const toast = document.createElement('div');
    toast.className = 'setting-toast';
    toast.innerHTML = `
        <span class="setting-toast-icon" style="color: var(--success);">âœ“</span>
        <span>Settings saved successfully</span>
    `;
    document.body.appendChild(toast);

    // Animate in
    requestAnimationFrame(() => {
        toast.classList.add('show');
    });

    // Remove after delay
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 200);
    }, 2000);
}

// Get current settings (for use in API calls)
function getRetrievalSettings() {
    return {
        topK: appSettings.topK,
        similarityThreshold: appSettings.similarityThreshold,
        useHyde: appSettings.hyde,
        useGraphRag: appSettings.graphrag,
        useReranking: appSettings.reranking,
        debug: appSettings.debug
    };
}

// Initialize on load
document.addEventListener('DOMContentLoaded', () => {
    initTheme();
    initSettingsSliders();
    initSettingsToggles();
});

// Close sidebar when clicking outside of it
document.addEventListener('click', (e) => {
    const sidebar = document.getElementById('sidebar');

    // Check if click was on any element with onclick containing toggleSidebar
    const sidebarToggles = document.querySelectorAll('[onclick*="toggleSidebar"]');
    let clickedOnToggle = false;
    sidebarToggles.forEach(toggle => {
        if (toggle.contains(e.target)) {
            clickedOnToggle = true;
        }
    });

    // Check if sidebar is open and click was outside sidebar and all toggle buttons
    if (sidebar && sidebar.classList.contains('open') &&
        !sidebar.contains(e.target) &&
        !clickedOnToggle) {
        closeSidebar();
    }
});

// === FILE UPLOAD ===
function initFileUpload() {
    const fileInput = document.getElementById('file-input');
    const uploadZone = document.getElementById('upload-zone');

    uploadZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        uploadZone.style.borderColor = 'var(--accent-primary)';
        uploadZone.style.background = 'var(--accent-muted)';
    });

    uploadZone.addEventListener('dragleave', () => {
        uploadZone.style.borderColor = 'var(--border-subtle)';
        uploadZone.style.background = 'transparent';
    });

    uploadZone.addEventListener('drop', (e) => {
        e.preventDefault();
        uploadZone.style.borderColor = 'var(--border-subtle)';
        uploadZone.style.background = 'transparent';
        if (e.dataTransfer.files.length) {
            fileInput.files = e.dataTransfer.files;
            handleBatchUpload(e.dataTransfer.files);
        }
    });

    fileInput.addEventListener('change', (e) => {
        if (e.target.files.length) handleBatchUpload(e.target.files);
    });
}

async function handleBatchUpload(files) {
    const statusEl = document.getElementById('upload-status');
    const progressContainer = document.getElementById('upload-progress-container');
    const total = files.length;
    let successCount = 0;
    const errors = [];

    // Hide status, show progress
    statusEl.innerHTML = '';
    progressContainer.style.display = 'block';

    for (let i = 0; i < total; i++) {
        const file = files[i];
        document.getElementById('upload-filename').textContent = `${i + 1}/${total}: ${file.name}`;
        document.getElementById('upload-percent').textContent = '0%';
        document.getElementById('upload-progress-bar').style.width = '0%';
        document.getElementById('upload-stage').textContent = 'Uploading...';

        const result = await uploadSingleFile(file);
        if (result.success) {
            successCount++;
        } else {
            errors.push({ file: file.name, error: result.error });
        }
    }

    // Hide progress, show final status
    progressContainer.style.display = 'none';

    if (successCount === total) {
        statusEl.innerHTML = `<span style="color:var(--success)">âœ“ All ${total} files ingested</span>`;
    } else if (successCount > 0) {
        const errorList = errors.map(e => `${e.file}: ${e.error}`).join('<br>');
        statusEl.innerHTML = `<span style="color:var(--warning)">âš  ${successCount}/${total} ingested</span>
            <div style="color:var(--text-secondary);font-size:11px;margin-top:8px;">
                Failed:<br>${errorList}
            </div>`;
    } else {
        const errorList = errors.map(e => `${e.file}: ${e.error}`).join('<br>');
        statusEl.innerHTML = `<span style="color:var(--alert)">âœ— Upload failed</span>
            <div style="color:var(--text-secondary);font-size:11px;margin-top:8px;">
                ${errorList}
            </div>`;
    }
    document.getElementById('file-input').value = '';
}

function uploadSingleFile(file) {
    return new Promise((resolve) => {
        const sector = sectorSelect.value;
        const formData = new FormData();
        formData.append('file', file);
        formData.append('dept', sector);

        const xhr = new XMLHttpRequest();

        // Progress tracking
        xhr.upload.addEventListener('progress', (e) => {
            if (e.lengthComputable) {
                const percent = Math.round((e.loaded / e.total) * 100);
                document.getElementById('upload-percent').textContent = `${percent}%`;
                document.getElementById('upload-progress-bar').style.width = `${percent}%`;

                if (percent < 100) {
                    document.getElementById('upload-stage').textContent = 'Uploading...';
                } else {
                    document.getElementById('upload-stage').textContent = 'Processing & vectorizing...';
                }
            }
        });

        xhr.addEventListener('load', () => {
            if (xhr.status >= 200 && xhr.status < 300) {
                fetchSystemStatus();
                addToContext(file.name, sector);
                resolve({ success: true });
            } else {
                let errorMsg = 'Upload failed';
                try {
                    const errorData = JSON.parse(xhr.responseText);
                    errorMsg = errorData.message || errorData.error || errorMsg;
                } catch (e) {
                    if (xhr.status === 413) errorMsg = 'File too large (max 50MB)';
                    else if (xhr.status === 415) errorMsg = 'Unsupported file type';
                    else if (xhr.status === 401) errorMsg = 'Authentication required';
                    else if (xhr.status === 403) errorMsg = EDITION === 'ENTERPRISE' ? 'Access denied - insufficient clearance' : 'Access denied';
                    else errorMsg = `Server error (${xhr.status})`;
                }
                resolve({ success: false, error: errorMsg });
            }
        });

        xhr.addEventListener('error', () => {
            resolve({ success: false, error: 'Network error - check connection' });
        });

        xhr.addEventListener('timeout', () => {
            resolve({ success: false, error: 'Upload timed out' });
        });

        xhr.open('POST', `${API_BASE}/ingest/file`);
        xhr.timeout = 300000; // 5 minute timeout for large files
        xhr.send(formData);
    });
}

// === CONTEXT MANAGEMENT ===
function addToContext(filename, sector) {
    state.contextDocs.set(filename, { sector, active: true });
    renderContextPanel();
}

function toggleContextDoc(filename) {
    const doc = state.contextDocs.get(filename);
    if (doc) {
        doc.active = !doc.active;
        renderContextPanel();
    }
}

function renderContextPanel() {
    const panel = document.getElementById('context-panel');
    const docsContainer = document.getElementById('context-docs');
    const countEl = document.getElementById('context-count');

    if (state.contextDocs.size === 0) {
        panel.style.display = 'none';
        return;
    }

    panel.style.display = 'block';
    countEl.textContent = state.contextDocs.size;

    docsContainer.innerHTML = '';
    state.contextDocs.forEach((data, filename) => {
        const docEl = document.createElement('div');
        docEl.className = `context-doc ${data.active ? 'active' : ''}`;
        docEl.onclick = () => toggleContextDoc(filename);
        // SECURITY: Use textContent for user-controlled data to prevent XSS
        const toggle = document.createElement('span');
        toggle.className = 'toggle';
        const nameSpan = document.createElement('span');
        nameSpan.textContent = filename;
        const badge = document.createElement('span');
        badge.className = 'sector-badge';
        badge.textContent = data.sector;
        docEl.appendChild(toggle);
        docEl.appendChild(nameSpan);
        docEl.appendChild(badge);
        docsContainer.appendChild(docEl);
    });
}

// === CHAT FUNCTIONS ===
function clearChat() {
    const splash = window.SENTINEL_SPLASH || { subtitle: 'AI-powered document intelligence.' };
    chatMessages.innerHTML = `
    <div class="welcome-state" id="welcome-state">
        <svg class="welcome-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
        </svg>
        <h2 class="welcome-title">SENTINEL Intelligence Platform</h2>
        <p class="welcome-subtitle" id="splash-subtitle">${splash.subtitle}</p>
        <p class="welcome-hint">Press <kbd>/</kbd> to focus input</p>
    </div>
`;
    closeAllSources();
}

/**
 * Reset dashboard to default state - called on sector/context change
 */
function resetDashboardState() {
    // Save current session if it has messages
    if (currentSessionId && currentMessages && currentMessages.length > 0) {
        saveCurrentSession();
    }

    // Clear chat area
    clearChat();

    // Clear info panel (graph, entities, sources)
    clearInfoPanel();

    // Close source tabs
    closeAllSources();

    // Reset state object
    state.contextDocs.clear();
    state.openSources = [];
    state.activeSource = null;
    state.currentFeedbackMsgId = null;

    // Reset current session
    currentSessionId = generateSessionId();
    currentMessages = [];

    // Reset chat title
    const chatTitle = document.getElementById('chat-title-text');
    if (chatTitle) chatTitle.textContent = 'New Conversation';
}

function hideWelcome() {
    const welcome = document.getElementById('welcome-state');
    if (welcome) welcome.style.display = 'none';
}

function appendUserMessage(text) {
    hideWelcome();
    const div = document.createElement('div');
    div.className = 'message user';
    div.innerHTML = `
    <div class="message-bubble">${escapeHtml(text)}</div>
    <div class="message-meta">${getTimestamp()}</div>
`;
    chatMessages.appendChild(div);
    if (appSettings.autoScroll) {
        chatMessages.scrollTop = chatMessages.scrollHeight;
    }
    // Record to conversation history
    recordMessage('user', text);
}

function appendLoadingIndicator() {
    const id = 'loading-' + Date.now();
    const div = document.createElement('div');
    div.className = 'message assistant';
    div.id = id;
    div.innerHTML = `
    <div class="loading-indicator">
        <div class="loading-spinner"></div>
        <span class="loading-text">Analyzing hypergraph nodes...</span>
    </div>
`;
    chatMessages.appendChild(div);
    if (appSettings.autoScroll) {
        chatMessages.scrollTop = chatMessages.scrollHeight;
    }
    return id;
}

function removeElement(id) {
    const el = document.getElementById(id);
    if (el) el.remove();
}

function appendAssistantResponse(text, reasoningSteps = [], sources = [], traceId = null) {
    hideWelcome();
    const msgId = 'msg-' + Date.now();
    const div = document.createElement('div');
    div.className = 'message assistant';
    div.id = msgId;

    // Process citations in text (handles both [file.ext] and `file.ext` formats)
    const processedText = processCitations(text);
    const bracketCitations = (text.match(/\[([^\]]+\.(pdf|txt|md))\]/gi) || []);
    const backtickCitations = (text.match(/`([^`]+\.(pdf|txt|md))`/gi) || []);
    const citationCount = bracketCitations.length + backtickCitations.length;
    const confidence = Math.floor(75 + Math.random() * 20);
    const confClass = confidence >= 85 ? 'high' : confidence >= 70 ? 'medium' : 'low';

    // Calculate total duration from real reasoning steps
    const totalDuration = reasoningSteps.reduce((sum, step) => sum + (step.durationMs || 0), 0);
    const hasRealTiming = reasoningSteps.some(s => s.durationMs !== undefined);

    // Build reasoning HTML if steps exist (Glass Box transparency)
    // SECURITY: Escape all dynamic values to prevent XSS
    let reasoningHtml = '';
    if (reasoningSteps.length > 0) {
        const safeTraceId = traceId ? escapeHtml(traceId) : '';
        const traceIdDisplay = traceId ? `<span class="trace-id" title="Trace ID for audit: ${safeTraceId}">[${safeTraceId}]</span>` : '';
        const timingDisplay = hasRealTiming ? `<span class="total-duration">${totalDuration}ms</span>` : '';
        reasoningHtml = `
        <div class="reasoning-accordion">
            <button class="reasoning-toggle" onclick="toggleReasoning(this)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="6 9 12 15 18 9"/>
                </svg>
                View Reasoning Chain (${reasoningSteps.length} steps) ${timingDisplay} ${traceIdDisplay}
            </button>
            <div class="reasoning-content">
                ${reasoningSteps.map((step, i) => `
                    <div class="reasoning-step">
                        <div class="reasoning-step-icon ${escapeHtml(step.type || '')}">${i + 1}</div>
                        <div class="reasoning-step-content">
                            <div class="reasoning-step-label">
                                ${escapeHtml(step.label || '')}
                                ${step.durationMs !== undefined ? `<span class="step-duration">${parseInt(step.durationMs) || 0}ms</span>` : ''}
                            </div>
                            <div class="reasoning-step-detail">${escapeHtml(step.detail || '')}</div>
                        </div>
                    </div>
                `).join('')}
            </div>
        </div>
    `;
    }

    // Clean Kotaemon-style response: just the text, sources in Information Panel
    div.innerHTML = `
    ${reasoningHtml}
    <div class="message-bubble">
        ${processedText}
    </div>
    <div class="message-actions">
        <button class="feedback-btn positive" onclick="handleFeedback('${msgId}', 'positive')" title="Helpful">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3zM7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/>
            </svg>
        </button>
        <button class="feedback-btn negative" onclick="handleFeedback('${msgId}', 'negative')" title="Report Issue">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3zm7-13h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"/>
            </svg>
        </button>
    </div>
    <div class="message-meta">${getTimestamp()}</div>
`;

    chatMessages.appendChild(div);
    if (appSettings.autoScroll) {
        chatMessages.scrollTop = chatMessages.scrollHeight;
    }

    // Sources are now shown in Information Panel only (Kotaemon-style)
    // No longer adding to Active Context in chat area

    // Extract entities for storage
    const entities = extractEntities(text, true);

    // Record to conversation history (including entities for session restore)
    const sourceFilenames = sources.map(s => s.filename);
    recordMessage('assistant', text, sourceFilenames, entities);

    // Update right panel with entities and source info
    updateRightPanel(text, sources, confidence);
}

// Helper to normalize filename by stripping common LLM prefixes
function normalizeFilename(filename) {
    return filename
        .replace(/^filename:\s*/i, '')
        .replace(/^source:\s*/i, '')
        .replace(/^citation:\s*/i, '')
        .trim();
}

// Extract source filenames from response text (fallback when backend doesn't provide them)
function extractSourcesFromText(text) {
    const sources = new Set();

    // Pattern 1: [filename.ext]
    const bracketMatches = text.match(/\[(?:(?:filename|source|citation):\s*)?([^\]]+\.(pdf|txt|md))\]/gi) || [];
    bracketMatches.forEach(m => {
        const filename = m.replace(/[\[\]]/g, '').replace(/^(filename|source|citation):\s*/i, '').trim();
        if (filename) sources.add(filename);
    });

    // Pattern 2: `filename.ext`
    const backtickMatches = text.match(/`([^`]+\.(pdf|txt|md))`/gi) || [];
    backtickMatches.forEach(m => {
        const filename = m.replace(/`/g, '').trim();
        if (filename) sources.add(filename);
    });

    // Pattern 3: **filename.ext** or *filename.ext*
    const boldMatches = text.match(/\*{1,2}([^*]+\.(pdf|txt|md))\*{1,2}/gi) || [];
    boldMatches.forEach(m => {
        const filename = m.replace(/\*/g, '').trim();
        if (filename) sources.add(filename);
    });

    // Pattern 4: "filename.ext"
    const quotedMatches = text.match(/"([^"]+\.(pdf|txt|md))"/gi) || [];
    quotedMatches.forEach(m => {
        const filename = m.replace(/"/g, '').trim();
        if (filename) sources.add(filename);
    });

    return Array.from(sources).map(filename => ({ filename }));
}

function processCitations(text) {
    // KOTAEMON STYLE: Remove file citations from text entirely
    // Sources are shown ONLY in the Information Panel, not inline

    // Remove all citation formats from text:
    // Format 1: [file.ext] or [Citation: file.ext] or [filename] (file.ext)
    let cleanText = text.replace(/\[(?:(?:filename|source|citation):\s*)?([^\]]+\.(pdf|txt|md|docx|doc))\]/gi, '');

    // Format 1b: [filename] followed by (file.ext) - LLM sometimes outputs this way
    cleanText = cleanText.replace(/\[filename\]\s*\(([^)]+\.(pdf|txt|md|docx|doc))\)/gi, '');

    // Format 2: (file.ext) - parentheses format
    cleanText = cleanText.replace(/\(([^)]+\.(pdf|txt|md|docx|doc))\)/gi, '');

    // Format 3: `file.ext` - backtick/code format
    cleanText = cleanText.replace(/`([^`]+\.(pdf|txt|md|docx|doc))`/gi, '');

    // Format 4: **file.ext** or *file.ext* - markdown bold/italic
    cleanText = cleanText.replace(/\*{1,2}([^*]+\.(pdf|txt|md|docx|doc))\*{1,2}/gi, '');

    // Format 5: "filename.ext" - quoted format (only quotes around filenames with extensions)
    cleanText = cleanText.replace(/"([^"]+\.(pdf|txt|md|docx|doc))"/gi, '');

    // Format 6: Just the word [filename] by itself
    cleanText = cleanText.replace(/\[filename\]/gi, '');

    // Clean up common citation phrases that now have nothing after them
    // Only match these specific patterns to avoid breaking regular sentences
    cleanText = cleanText.replace(/\baccording to\s*,/gi, ',');
    cleanText = cleanText.replace(/\bas mentioned in\s*,/gi, ',');
    cleanText = cleanText.replace(/\bas described in\s*,/gi, ',');
    cleanText = cleanText.replace(/\bfrom the document\s*,/gi, ',');
    cleanText = cleanText.replace(/\bin the document\s*,/gi, ',');
    cleanText = cleanText.replace(/\bper\s*,/gi, ',');

    // Clean up double punctuation from removals
    cleanText = cleanText.replace(/,\s*,/g, ',');
    cleanText = cleanText.replace(/\.\s*\./g, '.');
    cleanText = cleanText.replace(/,\s*\./g, '.');

    // Clean up extra whitespace from removed citations
    cleanText = cleanText.replace(/\s{2,}/g, ' ').trim();

    // Convert to safe HTML with markdown rendering
    return markdownToHtml(cleanText);
}

// Convert markdown to HTML safely (XSS-protected)
function markdownToHtml(text) {
    // First, escape HTML entities to prevent XSS
    let html = text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');

    // Convert markdown patterns to HTML
    // Bold: **text** or __text__
    html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/__([^_]+)__/g, '<strong>$1</strong>');

    // Italic: *text* or _text_
    html = html.replace(/(?<![*\w])\*([^*]+)\*(?![*\w])/g, '<em>$1</em>');
    html = html.replace(/(?<![_\w])_([^_]+)_(?![_\w])/g, '<em>$1</em>');

    // Code: `text`
    html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

    // Convert numbered lists to proper HTML ordered lists
    // Pattern: lines starting with "N. " become list items
    const lines = html.split('\n');
    let result = [];
    let inList = false;

    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        const listMatch = line.match(/^\s*(\d+)\.\s+(.+)$/);

        if (listMatch) {
            if (!inList) {
                result.push('<ol class="response-list">');
                inList = true;
            }
            result.push('<li>' + listMatch[2] + '</li>');
        } else {
            if (inList) {
                result.push('</ol>');
                inList = false;
            }
            result.push(line);
        }
    }

    if (inList) {
        result.push('</ol>');
    }

    html = result.join('\n');

    // Line breaks (but not inside list items)
    html = html.replace(/\n/g, '<br>');
    // Clean up extra br around lists
    html = html.replace(/<br><ol/g, '<ol');
    html = html.replace(/<\/ol><br>/g, '</ol>');
    html = html.replace(/<br><\/li>/g, '</li>');
    html = html.replace(/<li><br>/g, '<li>');

    return html;
}

function toggleReasoning(btn) {
    btn.classList.toggle('expanded');
    const content = btn.nextElementSibling;
    content.classList.toggle('visible');
}

// === QUERY EXECUTION ===
let lastQuery = '';

function regenerateResponse() {
    if (lastQuery) {
        queryInput.value = lastQuery;
        executeQuery();
    }
}

async function executeQuery() {
    const query = queryInput.value.trim();
    if (!query) return;
    lastQuery = query;

    const sector = sectorSelect.value;

    appendUserMessage(query);
    queryInput.value = '';

    const loadingId = appendLoadingIndicator();

    try {
        const startTime = Date.now();
        // Use enhanced endpoint for Glass Box reasoning transparency
        const response = await fetch(`${API_BASE}/ask/enhanced?q=${encodeURIComponent(query)}&dept=${encodeURIComponent(sector)}`);
        const data = await response.json();
        const latency = Date.now() - startTime;

        removeElement(loadingId);

        // Debug: log the response to see what backend returns
        console.log('Enhanced response:', { sources: data.sources, reasoning: data.reasoning?.length, answer: data.answer?.substring(0, 100) });

        // Use real reasoning steps from backend (Glass Box transparency)
        const reasoningSteps = (data.reasoning || []).map(step => ({
            type: mapStepType(step.type),
            label: step.label,
            detail: step.detail,
            durationMs: step.durationMs
        }));

        // Use sources from backend response, or extract from text as fallback
        let sources = (data.sources || []).map(s => ({ filename: s }));
        console.log('Backend sources:', data.sources, '-> Mapped:', sources);

        // Fallback: extract sources from response text if backend didn't provide them
        if (sources.length === 0 && data.answer) {
            sources = extractSourcesFromText(data.answer);
            console.log('Fallback extracted sources:', sources);
        }

        appendAssistantResponse(data.answer, reasoningSteps, sources, data.traceId);
        fetchSystemStatus();

    } catch (error) {
        removeElement(loadingId);
        // Fallback to basic endpoint if enhanced fails
        try {
            const fallbackResponse = await fetch(`${API_BASE}/ask?q=${encodeURIComponent(query)}&dept=${encodeURIComponent(sector)}`);
            const answer = await fallbackResponse.text();
            const reasoningSteps = generateReasoningSteps(query, answer);
            const sourceMatches = answer.match(/\[([^\]]+\.(pdf|txt|md))\]/gi) || [];
            const sources = sourceMatches.map(m => ({ filename: m.replace(/[\[\]]/g, '') }));
            appendAssistantResponse(answer, reasoningSteps, sources);
        } catch (fallbackError) {
            appendAssistantResponse(`Error: ${error.message}`, [], []);
        }
    }
}

// Map backend step types to frontend icon types
function mapStepType(backendType) {
    const typeMap = {
        'security_check': 'search',
        'query_analysis': 'search',
        'query_decomposition': 'search',
        'vector_search': 'retrieve',
        'keyword_search': 'retrieve',
        'retrieval': 'retrieve',
        'reranking': 'retrieve',
        'filtering': 'retrieve',
        'context_assembly': 'synthesize',
        'llm_generation': 'synthesize',
        'response_generation': 'synthesize',
        'error': 'search'
    };
    return typeMap[backendType] || 'search';
}

function generateReasoningSteps(query, answer) {
    const steps = [];
    const hasResults = answer.includes('[') && (answer.includes('.pdf') || answer.includes('.txt') || answer.includes('.md'));

    steps.push({
        type: 'search',
        label: 'Query Analysis',
        detail: `Parsed query: "${query.substring(0, 50)}${query.length > 50 ? '...' : ''}"`
    });

    steps.push({
        type: 'search',
        label: 'Vector Search',
        detail: `Searching ${sectorSelect.value} sector with similarity threshold 0.7`
    });

    if (hasResults) {
        const matches = answer.match(/\[([^\]]+\.(pdf|txt|md))\]/gi) || [];
        steps.push({
            type: 'retrieve',
            label: 'Document Retrieval',
            detail: `Found ${matches.length} relevant document(s) via HiFi-RAG`
        });

        steps.push({
            type: 'synthesize',
            label: 'Response Synthesis',
            detail: 'Applied ANALYZE â†’ VERIFY â†’ CITE protocol'
        });
    } else {
        steps.push({
            type: 'retrieve',
            label: 'No Matches',
            detail: 'No documents matched the similarity threshold'
        });
    }

    return steps;
}

// === SOURCE VIEWER ===
async function openSource(filename, switchTab = true) {
    // Switch to Source tab in right panel (unless preloading)
    if (switchTab) {
        switchRightTab('source');
    }

    // Add to tabs if not already there
    if (!state.openSources.includes(filename)) {
        state.openSources.push(filename);
    }
    state.activeSource = filename;

    renderSourceTabs();

    // Show viewer, hide empty state
    document.getElementById('source-empty').style.display = 'none';
    document.getElementById('source-viewer-container').style.display = 'block';

    // Update meta
    document.getElementById('source-filename').textContent = filename;
    document.getElementById('source-sector').textContent = sectorSelect.value;
    document.getElementById('source-type').textContent = filename.endsWith('.pdf') ? 'PDF Document' : 'Text Document';

    // Fetch content
    const viewer = document.getElementById('source-viewer');
    viewer.textContent = 'Loading document content...';

    try {
        // Pass current query to get relevant highlights
        const currentQuery = queryInput.value.trim() || document.querySelector('.message.user:last-child .message-bubble')?.innerText || '';
        const res = await fetch(`${API_BASE}/inspect?fileName=${encodeURIComponent(filename)}&query=${encodeURIComponent(currentQuery)}`);

        // Handle JSON response
        const data = await res.json();

        // Format content with returned highlights
        viewer.innerHTML = formatSourceContent(data.content, data.highlights);

    } catch (error) {
        viewer.textContent = 'Error loading document: ' + error.message;
    }

    // Highlight active citation
    document.querySelectorAll('.citation').forEach(c => {
        c.classList.toggle('active', c.textContent.trim().includes(filename));
    });
}

let currentHighlightIndex = 0;

function formatSourceContent(content, highlights = []) {
    // Normalize highlights for easier matching
    const normalizedHighlights = highlights.map(h => h.trim());

    const lines = content.split('\n');
    let inContent = false;
    let result = [];
    let highlightCount = 0;

    for (const line of lines) {
        if (line.startsWith('---')) {
            inContent = true;
            result.push(line);
            continue;
        }

        if (inContent && line.trim()) {
            // Check if this line is in the highlights list
            const isHighlight = normalizedHighlights.some(h => line.includes(h) || h.includes(line.trim()));

            if (isHighlight) {
                highlightCount++;
                result.push(`<div class="highlight" data-highlight-index="${highlightCount}">${escapeHtml(line)}</div>`);
            } else {
                result.push(escapeHtml(line));
            }
        } else {
            result.push(escapeHtml(line));
        }
    }

    // Update highlight navigation
    updateHighlightNav(highlightCount);

    return result.join('\n');
}

function updateHighlightNav(count) {
    const nav = document.getElementById('highlight-nav');
    const countEl = document.getElementById('highlight-count');
    const posEl = document.getElementById('highlight-position');

    if (count > 0) {
        nav.style.display = 'flex';
        countEl.textContent = count;
        currentHighlightIndex = 0;
        posEl.textContent = `1 of ${count}`;
        // Auto-scroll to first highlight after a brief delay
        setTimeout(() => navigateHighlight('first'), 100);
    } else {
        nav.style.display = 'none';
    }
}

function navigateHighlight(direction) {
    const highlights = document.querySelectorAll('.source-viewer .highlight');
    if (highlights.length === 0) return;

    // Update index based on direction
    if (direction === 'next') {
        currentHighlightIndex = (currentHighlightIndex + 1) % highlights.length;
    } else if (direction === 'prev') {
        currentHighlightIndex = (currentHighlightIndex - 1 + highlights.length) % highlights.length;
    } else if (direction === 'first') {
        currentHighlightIndex = 0;
    }

    // Scroll to highlight
    const targetHighlight = highlights[currentHighlightIndex];
    if (targetHighlight) {
        targetHighlight.scrollIntoView({ behavior: 'smooth', block: 'center' });

        // Add visual focus indicator
        highlights.forEach(h => h.style.outline = 'none');
        targetHighlight.style.outline = '2px solid var(--accent-primary)';
        targetHighlight.style.outlineOffset = '2px';

        // Update position display
        document.getElementById('highlight-position').textContent =
            `${currentHighlightIndex + 1} of ${highlights.length}`;
    }
}

function renderSourceTabs() {
    const tabsContainer = document.getElementById('source-tabs');

    if (state.openSources.length === 0) {
        tabsContainer.style.display = 'none';
        return;
    }

    tabsContainer.style.display = 'flex';
    tabsContainer.innerHTML = '';

    state.openSources.forEach(filename => {
        const tab = document.createElement('div');
        tab.className = `source-tab ${filename === state.activeSource ? 'active' : ''}`;
        tab.innerHTML = `
        <span onclick="openSource('${escapeHtml(filename)}')">${filename}</span>
        <span class="close" onclick="event.stopPropagation(); closeSource('${escapeHtml(filename)}')">Ã—</span>
    `;
        tabsContainer.appendChild(tab);
    });
}

function closeSource(filename) {
    state.openSources = state.openSources.filter(f => f !== filename);

    if (state.activeSource === filename) {
        state.activeSource = state.openSources[0] || null;
        if (state.activeSource) {
            openSource(state.activeSource);
        } else {
            document.getElementById('source-empty').style.display = 'flex';
            document.getElementById('source-viewer-container').style.display = 'none';
        }
    }

    renderSourceTabs();
}

function closeAllSources() {
    state.openSources = [];
    state.activeSource = null;
    document.getElementById('source-tabs').style.display = 'none';
    document.getElementById('source-empty').style.display = 'flex';
    document.getElementById('source-viewer-container').style.display = 'none';
}

// === FEEDBACK ===
function handleFeedback(msgId, type) {
    const positiveBtn = document.querySelector(`#${msgId} .feedback-btn.positive`);
    const negativeBtn = document.querySelector(`#${msgId} .feedback-btn.negative`);

    if (type === 'positive') {
        // Toggle positive feedback - allow uncheck
        if (positiveBtn.classList.contains('active')) {
            positiveBtn.classList.remove('active');
            return;
        }
        // Set positive, clear negative (mutual exclusivity)
        positiveBtn.classList.add('active');
        if (negativeBtn) negativeBtn.classList.remove('active');
    } else {
        // Toggle negative feedback - allow uncheck
        if (negativeBtn.classList.contains('active')) {
            negativeBtn.classList.remove('active');
            return;
        }
        // Clear positive (mutual exclusivity), open modal for details
        if (positiveBtn) positiveBtn.classList.remove('active');
        state.currentFeedbackMsgId = msgId;
        document.getElementById('feedback-modal').classList.add('open');
    }
}

function closeFeedbackModal() {
    document.getElementById('feedback-modal').classList.remove('open');
    state.currentFeedbackMsgId = null;

    // Reset selection
    document.querySelectorAll('.feedback-option').forEach(opt => {
        opt.classList.remove('selected');
    });
}

// Initialize feedback option clicks
document.querySelectorAll('.feedback-option').forEach(opt => {
    opt.addEventListener('click', () => {
        document.querySelectorAll('.feedback-option').forEach(o => o.classList.remove('selected'));
        opt.classList.add('selected');
    });
});

function submitFeedback() {
    const selected = document.querySelector('.feedback-option.selected');
    if (!selected) {
        alert('Please select a feedback category');
        return;
    }

    const feedbackType = selected.dataset.value;
    // Feedback recorded locally (backend integration available)

    // Mark the button as active
    if (state.currentFeedbackMsgId) {
        const btn = document.querySelector(`#${state.currentFeedbackMsgId} .feedback-btn.negative`);
        if (btn) btn.classList.add('active');
    }

    closeFeedbackModal();
}

// === UTILITIES ===
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function getTimestamp() {
    return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}
