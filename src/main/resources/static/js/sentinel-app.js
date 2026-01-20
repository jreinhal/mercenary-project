        const state = {
            contextDocs: new Map(),
            openSources: [],
            activeSource: null,
            currentFeedbackMsgId: null,
            messageIndex: new Map()
        };

        const API_BASE = window.location.origin + '/api';
        const authState = { authenticated: true, pending: false };
        let csrfTokenCache = '';
        let csrfTokenUnavailable = false;

        let chatMessages, queryInput, sectorSelect;
        const DEV_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);

        function isDevBuild() {
            if (DEV_HOSTS.has(window.location.hostname)) return true;
            if (document.documentElement.dataset.env === 'dev') return true;
            return window.SENTINEL_DEV === true;
        }

        function enableDomIdLookupWarnings() {
            if (!isDevBuild()) return;
            if (document.__sentinelGetByIdWrapped) return;
            document.__sentinelGetByIdWrapped = true;

            const originalGetById = document.getElementById.bind(document);
            const warnedIds = new Set();

            document.getElementById = function(id) {
                const element = originalGetById(id);
                if (!element && typeof id === 'string' && id && !warnedIds.has(id)) {
                    warnedIds.add(id);
                    console.warn(`[SENTINEL][dev] Missing DOM ID lookup: ${id}`);
                }
                return element;
            };
        }

        function runDomIdSanityCheck() {
            if (!isDevBuild()) return;
            const requiredIds = [
                'auth-error', 'auth-modal', 'auth-password', 'auth-submit', 'auth-username',
                'auto-scroll', 'chat-messages', 'chat-title-text', 'classification-banner',
                'context-count', 'context-docs', 'context-panel', 'conversation-count',
                'conversation-list', 'conversation-section', 'debug-toggle', 'entity-score-badge',
                'feedback-modal', 'file-input', 'graph-container', 'graph-placeholder',
                'graphrag-toggle', 'highlight-count', 'highlight-nav', 'highlight-position',
                'hyde-toggle', 'info-entities-list', 'info-sources-list', 'pinned-list',
                'pinned-section', 'plotly-graph', 'query-input', 'rerank-toggle',
                'resources-dropdown', 'right-tab-plot', 'right-tab-source', 'save-history',
                'save-settings-btn', 'saved-queries-count', 'saved-queries-list',
                'saved-queries-section', 'sector-select', 'show-reasoning', 'sidebar',
                'similarity-slider', 'similarity-value', 'source-empty', 'source-filename',
                'source-score-badge', 'source-sector', 'source-tabs', 'source-type',
                'source-viewer', 'source-viewer-container', 'splash-features',
                'splash-subtitle', 'stats-context', 'stats-docs', 'stats-status',
                'stats-status-dot', 'stats-user', 'theme-dark', 'theme-light',
                'top-k-slider', 'top-k-value', 'upload-filename', 'upload-percent',
                'upload-progress-bar', 'upload-progress-container', 'upload-stage',
                'upload-status', 'upload-zone', 'welcome-state'
            ];

            const missing = requiredIds.filter(id => !document.getElementById(id));
            if (missing.length) {
                console.warn(`[SENTINEL][dev] Missing DOM IDs (${missing.length}): ${missing.join(', ')}`);
            }
        }

        function getCookieValue(name) {
            const cookie = document.cookie;
            if (!cookie) return '';
            const parts = cookie.split(';');
            for (let i = 0; i < parts.length; i++) {
                const part = parts[i].trim();
                if (part.startsWith(name + '=')) {
                    return decodeURIComponent(part.substring(name.length + 1));
                }
            }
            return '';
        }

        function getCsrfToken() {
            return csrfTokenCache || getCookieValue('XSRF-TOKEN');
        }

        async function ensureCsrfToken() {
            const existing = getCsrfToken();
            if (existing) {
                csrfTokenCache = existing;
                return existing;
            }
            if (csrfTokenUnavailable) {
                return '';
            }

            try {
                const response = await fetch(`${API_BASE}/auth/csrf`, {
                    credentials: 'same-origin'
                });
                if (response.ok) {
                    const data = await response.json();
                    if (data && data.token) {
                        csrfTokenCache = data.token;
                        return csrfTokenCache;
                    }
                } else if (response.status === 404) {
                    csrfTokenUnavailable = true;
                }
            } catch (e) {
            }

            return '';
        }

        function setAuthError(message) {
            const errorEl = document.getElementById('auth-error');
            if (!errorEl) return;
            if (message) {
                errorEl.textContent = message;
                errorEl.classList.remove('hidden');
            } else {
                errorEl.textContent = '';
                errorEl.classList.add('hidden');
            }
        }

        function showAuthModal(message) {
            authState.authenticated = false;
            const modal = document.getElementById('auth-modal');
            if (!modal) return;
            setAuthError(message || '');
            modal.classList.remove('hidden');
            const usernameInput = document.getElementById('auth-username');
            if (usernameInput) {
                usernameInput.focus();
            }
        }

        function hideAuthModal() {
            const modal = document.getElementById('auth-modal');
            if (!modal) return;
            modal.classList.add('hidden');
            setAuthError('');
        }

        function authError() {
            const error = new Error('auth');
            error.code = 'auth';
            return error;
        }

        async function guardedFetch(url, options) {
            const response = await fetch(url, options);
            if (response.status === 401) {
                showAuthModal('Sign in to continue.');
                throw authError();
            }
            return response;
        }

        async function submitAuth() {
            if (authState.pending) return;
            const usernameInput = document.getElementById('auth-username');
            const passwordInput = document.getElementById('auth-password');
            const username = usernameInput ? usernameInput.value.trim() : '';
            const password = passwordInput ? passwordInput.value : '';

            if (!username || !password) {
                setAuthError('Username and password are required.');
                return;
            }

            authState.pending = true;
            const submitBtn = document.getElementById('auth-submit');
            if (submitBtn) submitBtn.disabled = true;

            const headers = { 'Content-Type': 'application/json' };
            const csrfToken = await ensureCsrfToken();
            if (csrfToken) {
                headers['X-XSRF-TOKEN'] = csrfToken;
            }

            try {
                const response = await fetch(`${API_BASE}/auth/login`, {
                    method: 'POST',
                    headers,
                    credentials: 'same-origin',
                    body: JSON.stringify({ username, password })
                });

                if (!response.ok) {
                    let message = 'Authentication failed';
                    try {
                        const data = await response.json();
                        if (data && data.error) message = data.error;
                    } catch (e) {
                    }
                    setAuthError(message);
                    return;
                }

                authState.authenticated = true;
                if (passwordInput) passwordInput.value = '';
                hideAuthModal();
                await initSectorsFromAPI();
                await initSectorSelector();
            } catch (error) {
                setAuthError('Unable to reach server.');
            } finally {
                authState.pending = false;
                if (submitBtn) submitBtn.disabled = false;
            }
        }

        function setHidden(el, hidden) {
            if (!el) return;
            el.classList.toggle('hidden', hidden);
        }

        function setText(el, text) {
            if (!el) return;
            el.textContent = text;
        }

        function setProgressValue(progressEl, percent) {
            if (!progressEl) return;
            if (progressEl.tagName === 'PROGRESS') {
                progressEl.value = percent;
            } else {
                progressEl.setAttribute('aria-valuenow', String(percent));
            }
        }

        function setUploadStatus(statusEl, status, message, details) {
            if (!statusEl) return;
            statusEl.textContent = '';

            const messageEl = document.createElement('div');
            messageEl.className = `upload-status upload-status--${status}`;
            messageEl.textContent = message;
            statusEl.appendChild(messageEl);

            if (details && details.length) {
                const detailEl = document.createElement('div');
                detailEl.className = 'upload-status-detail';
                details.forEach(detail => {
                    const line = document.createElement('div');
                    line.textContent = detail;
                    detailEl.appendChild(line);
                });
                statusEl.appendChild(detailEl);
            }
        }
        let SECTORS = [];
        let SECTOR_DATA = {};
        const DEFAULT_SECTOR = 'ENTERPRISE';

        document.addEventListener('DOMContentLoaded', () => {
            chatMessages = document.getElementById('chat-messages');
            queryInput = document.getElementById('query-input');
            sectorSelect = document.getElementById('sector-select');

            enableDomIdLookupWarnings();
            initEventDelegation();
            initSectorsFromAPI();
            initOperator();
            initKeyboardShortcuts();
            startStatusPolling();
            initFileUpload();
            initSectorSelector();
            runDomIdSanityCheck();
        });

        function initEventDelegation() {
            document.addEventListener('click', (e) => {
                const el = e.target.closest('[data-action]');
                if (!el) return;

                e.preventDefault();
                e.stopPropagation();

                const action = el.dataset.action;

                switch (action) {
                    case 'closeFeedbackModal': closeFeedbackModal(); break;
                    case 'authSubmit': submitAuth(); break;
                    case 'submitFeedback': submitFeedback(); break;
                    case 'toggleSidebar': toggleSidebar(); break;
                    case 'setTheme': setTheme(el.dataset.theme); break;
                    case 'saveSettingsWithConfirmation': saveSettingsWithConfirmation(); break;
                    case 'switchMainTab': switchMainTab(el.dataset.tab); break;
                    case 'toggleResourcesMenu': toggleResourcesMenu(e); break;
                    case 'openManual': openManual(); break;
                    case 'openReadme': openReadme(); break;
                    case 'startNewChat': startNewChat(); break;
                    case 'toggleConversationList': toggleConversationList(); break;
                    case 'clearUnpinnedConversations': clearUnpinnedConversations(); break;
                    case 'toggleSavedQueriesList': toggleSavedQueriesList(); break;
                    case 'saveCurrentQuery': saveCurrentQuery(); break;
                    case 'triggerFileInput': document.getElementById('file-input').click(); break;
                    case 'clearChat': clearChat(); break;
                    case 'executeQuery': executeQuery(); break;
                    case 'regenerateResponse': regenerateResponse(); break;
                    case 'switchRightTab': switchRightTab(el.dataset.tab); break;
                    case 'toggleInfoSection': toggleInfoSection(el); break;
                    case 'navigateHighlight': navigateHighlight(el.dataset.direction); break;
                    case 'toggleReasoning': toggleReasoning(el); break;
                    case 'handleFeedback': handleFeedback(el.dataset.msgId, el.dataset.type); break;
                    case 'openSource': openSource(el.dataset.filename); break;
                    case 'closeSource': closeSource(el.dataset.filename); break;
                    case 'loadSession': loadSession(el.dataset.sessionId); break;
                    case 'deleteSession': e.stopPropagation(); deleteSession(el.dataset.sessionId); break;
                    case 'togglePin': e.stopPropagation(); togglePin(el.dataset.sessionId, e); break;
                    case 'useSavedQuery': useSavedQuery(el.dataset.text, el); break;
                    case 'deleteSavedQuery': deleteSavedQuery(el.dataset.queryId, e); break;
                    default: console.warn('Unknown action:', action);
                }
            });

            const queryInput = document.getElementById('query-input');
            if (queryInput) {
                queryInput.addEventListener('keydown', (e) => {
                    if (e.key === 'Enter') {
                        e.preventDefault();
                        executeQuery();
                    }
                });
            }
        }

        async function initSectorsFromAPI() {
            const sectorSelect = document.getElementById('sector-select');

            try {
                const response = await guardedFetch(`${API_BASE}/config/sectors`, {
                    credentials: 'include'
                });

                if (response.ok) {
                    authState.authenticated = true;
                    const sectors = await response.json();
                    SECTORS = sectors.map(s => s.id);

                    sectors.forEach(s => {
                        SECTOR_DATA[s.id] = {
                            label: s.label,
                            icon: s.icon,
                            description: s.description,
                            theme: s.theme
                        };
                    });

                    sectorSelect.innerHTML = '';
                    sectors.forEach(sector => {
                        const option = document.createElement('option');
                        option.value = sector.id;
                        option.textContent = sector.label || sector.id;
                        sectorSelect.appendChild(option);
                    });

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
                if (error && error.code === 'auth') {
                    return;
                }
                console.error('Error loading sectors:', error);
                initSectorsFallback();
            }

            applySectorTheme(sectorSelect.value);

            renderSplashContent();
        }

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

        function getSectorConfig(sectorId) {
            return SECTOR_DATA[sectorId] || { label: sectorId, icon: 'folder' };
        }

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

        function applyDisplayMode(mode) {
            const root = document.documentElement;
            const displayMode = window.getDisplayMode ? window.getDisplayMode(mode) : (mode === 'light' ? 'light' : 'dark');

            root.setAttribute('data-theme', displayMode);

            localStorage.setItem('sentinel-display-mode', displayMode);
            localStorage.setItem('sentinel-theme', displayMode);

            console.log(`Applied display mode: ${displayMode}`);
        }

        function applySectorConfig(sector) {
            const config = window.getSectorConfig ? window.getSectorConfig(sector) : {};

            if (config.placeholders) {
                const queryInput = document.getElementById('query-input');
                if (queryInput) {
                    queryInput.placeholder = config.placeholders.query;
                }
            }

            const clearanceBadge = document.querySelector('.clearance-badge');
            if (clearanceBadge) {
                setHidden(clearanceBadge, !config.features?.showClearanceBadge);
            }

            const classificationBanner = document.getElementById('classification-banner');
            if (classificationBanner) {
                const classification = config.features?.classificationLevel || 'unclassified';
                const showBanner = config.features?.showClassificationBanner;
                classificationBanner.className = `classification-banner ${classification}${showBanner ? '' : ' hidden'}`;
                classificationBanner.textContent = classification.toUpperCase();
                document.body.classList.toggle('has-classification-banner', showBanner);
            }

            localStorage.setItem('sentinel-sector', sector);

            console.log(`Applied sector config: ${sector}`);
        }

        function applySectorTheme(sector) {
            applySectorConfig(sector);

            const savedMode = localStorage.getItem('sentinel-theme') || localStorage.getItem('sentinel-display-mode') || 'dark';
            applyDisplayMode(savedMode);

            updateSectorDisplay(sector);
        }

        function updateSectorDisplay(sector) {
            const statsContext = document.getElementById('stats-context');
            if (statsContext) {
                statsContext.textContent = sector;
            }
        }

        function renderSplashContent() {
            const splash = window.SENTINEL_SPLASH || {
                subtitle: 'AI-powered document intelligence for secure environments',
                features: [
                    { icon: 'search', title: 'Semantic Search', description: 'Find information across all your documents' },
                    { icon: 'shield', title: 'Secure & Private', description: 'All processing happens locally' },
                    { icon: 'zap', title: 'Fast Answers', description: 'Get instant responses with citations' }
                ]
            };

            const subtitle = document.getElementById('splash-subtitle');
            if (subtitle && splash.subtitle) {
                subtitle.textContent = splash.subtitle;
            }

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

            const statsUser = document.getElementById('stats-user');
            if (statsUser) statsUser.textContent = operatorId;
        }

        function initKeyboardShortcuts() {
            document.addEventListener('keydown', (e) => {
                if (e.key === 'Escape') {
                    closeFeedbackModal();
                    closeSidebar();
                }
                if (e.key === 'Enter') {
                    const authModal = document.getElementById('auth-modal');
                    if (authModal && !authModal.classList.contains('hidden')) {
                        e.preventDefault();
                        submitAuth();
                        return;
                    }
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
            });
        }

        async function initSectorSelector() {

            const savedDisplayMode = localStorage.getItem('sentinel-display-mode');
            if (savedDisplayMode) {
                currentDisplayMode = savedDisplayMode;
            }

            try {
                const response = await guardedFetch(`${API_BASE}/user/context`);
                if (response.ok) {
                    authState.authenticated = true;
                    hideAuthModal();
                    const userContext = await response.json();

                    if (userContext.displayName) {
                        setText(document.getElementById('stats-user'), userContext.displayName);
                        localStorage.setItem('sentinel_operator', userContext.displayName);
                    }

                    if (!userContext.isAdmin && userContext.allowedSectors && userContext.allowedSectors.length > 0) {
                        filterSectorDropdown(userContext.allowedSectors);
                    }
                }
            } catch (error) {
                if (error && error.code === 'auth') {
                    return;
                }
                console.warn('Could not fetch user context, showing all sectors:', error);
                const statsUser = document.getElementById('stats-user');
                if (statsUser) statsUser.textContent = 'DEMO_USER';
                localStorage.setItem('sentinel_operator', 'DEMO_USER');
            }

            const savedSector = localStorage.getItem('sentinel-sector');
            if (savedSector) {
                const optionExists = Array.from(sectorSelect.options).some(opt => opt.value === savedSector);
                if (optionExists) {
                    sectorSelect.value = savedSector;
                }
            }

            applySector(sectorSelect.value);
            localStorage.setItem('sentinel-sector', sectorSelect.value);

            const statsContext = document.getElementById('stats-context');
            if (statsContext) statsContext.textContent = sectorSelect.value;

            sectorSelect.addEventListener('change', (e) => {
                const sector = e.target.value;

                resetDashboardState();
                setText(document.getElementById('stats-context'), sector);

                applySector(sector);
                localStorage.setItem('sentinel-sector', sector);

                loadConversationHistory();
                renderSavedQueriesList();
            });
        }

        function filterSectorDropdown(allowedSectors) {
            const options = sectorSelect.querySelectorAll('option');
            let firstVisibleOption = null;

            options.forEach(option => {
                if (!allowedSectors.includes(option.value)) {
                    option.hidden = true;
                    option.disabled = true;
                } else {
                    option.hidden = false;
                    option.disabled = false;
                    if (!firstVisibleOption) {
                        firstVisibleOption = option.value;
                    }
                }
            });

            sectorSelect.querySelectorAll('optgroup').forEach(group => {
                const visibleOptions = group.querySelectorAll('option:not([disabled])');
                if (visibleOptions.length === 0) {
                    group.hidden = true;
                } else {
                    group.hidden = false;
                }
            });

            if (sectorSelect.selectedOptions[0]?.disabled && firstVisibleOption) {
                sectorSelect.value = firstVisibleOption;
            }
        }


        function setDisplayMode(mode) {
            const displayMode = (mode === 'light') ? 'light' : 'dark';

            document.documentElement.setAttribute('data-theme', displayMode);

            localStorage.setItem('sentinel-display-mode', displayMode);
            localStorage.setItem('sentinel-theme', displayMode);

            console.log(`Display mode set to: ${displayMode}`);
        }

        function applySector(sector) {
            const config = window.getSectorConfig ? window.getSectorConfig(sector) : {};

            const savedMode = localStorage.getItem('sentinel-theme') || localStorage.getItem('sentinel-display-mode') || 'dark';
            setDisplayMode(savedMode);

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

            const queryInput = document.getElementById('query-input');
            if (queryInput && config.placeholders) {
                queryInput.placeholder = config.placeholders.query;
            }

            const welcomeTitle = document.querySelector('.welcome-title');
            if (welcomeTitle) {
                welcomeTitle.textContent = `SENTINEL ${config.label || 'Intelligence Platform'}`;
            }

            const classificationBanner = document.getElementById('classification-banner');
            if (classificationBanner) {
                const showBanner = config.features?.showClassificationBanner;
                const classification = config.features?.classificationLevel || 'unclassified';
                classificationBanner.className = `classification-banner ${classification}${showBanner ? '' : ' hidden'}`;
                classificationBanner.textContent = classification.toUpperCase();
                document.body.classList.toggle('has-classification-banner', showBanner);
            }

            localStorage.setItem('sentinel-sector', sector);

            // Update the System Status context display immediately
            const statsContext = document.getElementById('stats-context');
            if (statsContext) {
                statsContext.textContent = sector;
            }

            console.log(`Applied sector: ${sector}`);
        }

        function applyTheme(sector) {
            applySector(sector);
        }

        async function fetchSystemStatus() {
            try {
                const response = await guardedFetch(`${API_BASE}/telemetry`);
                if (!response.ok) throw new Error('Status fetch failed');
                const data = await response.json();

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

                const statsUser = document.getElementById('stats-user');
                if (statsUser) {
                    statsUser.textContent = localStorage.getItem('sentinel_operator') || 'OPERATOR';
                }

                const statsContext = document.getElementById('stats-context');
                if (statsContext) {
                    // Read from localStorage for immediate update, fallback to sectorSelect, then default
                    const savedSector = localStorage.getItem('sentinel-sector');
                    statsContext.textContent = savedSector || sectorSelect?.value || 'ENTERPRISE';
                }
            } catch (error) {
                if (error && error.code === 'auth') {
                    return;
                }
                console.error('Status fetch error:', error);
                const statsStatus = document.getElementById('stats-status');
                const statsStatusDot = document.getElementById('stats-status-dot');
                if (statsStatus) statsStatus.textContent = 'Offline';
                if (statsStatusDot) statsStatusDot.className = 'stats-indicator offline';
            }
        }

        function startStatusPolling() {
            fetchSystemStatus();
            setInterval(fetchSystemStatus, 10000);
        }

        let conversationHistory = [];
        let currentSessionId = null;
        let currentMessages = [];

        function generateSessionId() {
            return 'session_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
        }

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
                if (conversationHistory.length > 50) {
                    conversationHistory = conversationHistory.slice(-50);
                }
                const storageKey = getScopedStorageKey('sentinel_conversations');
                localStorage.setItem(storageKey, JSON.stringify(conversationHistory));
            } catch (e) {
                console.warn('Failed to save conversation history:', e);
            }
        }

        function renderHistoryList() {
            renderConversationList();
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
            if (currentSessionId && currentMessages.length > 0) {
                saveCurrentSession();
            }
            currentSessionId = generateSessionId();
            currentMessages = [];
            clearChat();
            clearInfoPanel();
            renderHistoryList();
        }

        function saveCurrentSession() {
            if (!currentSessionId || currentMessages.length === 0) return;

            const firstUserMsg = currentMessages.find(m => m.role === 'user');
            const title = firstUserMsg ? firstUserMsg.content.substring(0, 150) : 'Untitled conversation';

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
            renderConversationList();
        }

        function loadSession(sessionId) {
            const session = conversationHistory.find(s => s.id === sessionId);
            if (!session) return;

            if (currentSessionId && currentMessages.length > 0 && currentSessionId !== sessionId) {
                saveCurrentSession();
            }

            currentSessionId = sessionId;
            currentMessages = session.messages || [];

            const chatMessages = document.getElementById('chat-messages');
            const welcome = document.getElementById('welcome-state');
            setHidden(welcome, true);

            chatMessages.innerHTML = '';
            currentMessages.forEach(msg => {
                if (msg.role === 'user') {
                    replayUserMessage(msg.content);
                } else if (msg.role === 'assistant') {
                    replayAssistantMessage(msg.content, msg.sources || []);
                }
            });
            chatMessages.scrollTop = chatMessages.scrollHeight;

            restoreInfoPanelFromSession(currentMessages);

            renderHistoryList();
        }

        function restoreInfoPanelFromSession(messages) {
            const lastAssistant = [...messages].reverse().find(m => m.role === 'assistant');

            if (!lastAssistant) {
                clearInfoPanel();
                return;
            }

            const sources = lastAssistant.sources || [];
            const entities = lastAssistant.entities || [];
            lastQueryMeta = lastAssistant.meta || {};
            lastResponseText = lastAssistant.content || '';

            const sourcesList = document.getElementById('info-sources-list');
            if (sourcesList) {
                if (sources.length === 0) {
                    sourcesList.innerHTML = `<div class="info-empty-state">No sources referenced</div>`;
                } else {
                    sourcesList.innerHTML = sources.map(filename => {
                        const ext = filename.split('.').pop().toLowerCase();
                        const typeClass = ['pdf', 'txt', 'md'].includes(ext) ? ext : 'txt';
                        return `
                            <div class="info-source-item" data-action="openSource" data-filename="${escapeHtml(filename)}"
                                 tabindex="0" role="button"
                                 tabindex="0">
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

            const sourceObjects = sources.map(filename => ({ filename }));
            renderKnowledgeGraph(sourceObjects, entities);
        }

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

        function recordMessage(role, content, sources, entities, meta) {
            currentMessages.push({
                role,
                content,
                sources: sources || [],
                entities: entities || [],
                meta: meta || null,
                timestamp: Date.now()
            });
            if (appSettings.saveHistory) {
                saveCurrentSession();
            }
        }

        document.addEventListener('DOMContentLoaded', () => {
            loadConversationHistory();
            currentSessionId = generateSessionId();
            renderConversationList();
        });

        function switchMainTab(tabName) {
            event.preventDefault();

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

        function toggleResourcesMenu(event) {
            event.preventDefault();
            event.stopPropagation();
            const dropdown = document.getElementById('resources-dropdown');
            dropdown.classList.toggle('show');

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

        function openManual() {
            const sector = sectorSelect ? sectorSelect.value : 'ENTERPRISE';
            window.open(`manual.html?sector=${sector}`, '_blank');
            closeResourcesDropdown({ target: document.body });
        }

        function openReadme() {
            window.open('readme.html', '_blank');
            closeResourcesDropdown({ target: document.body });
        }

        function switchRightTab(tabName) {
            const plotTab = document.getElementById('right-tab-plot');
            const sourceTab = document.getElementById('right-tab-source');

            setHidden(plotTab, true);
            setHidden(sourceTab, true);

            document.querySelectorAll('.right-panel-tab').forEach(btn => {
                btn.classList.remove('active');
            });

            const tabContent = document.getElementById('right-tab-' + tabName);
            if (tabContent) {
                setHidden(tabContent, false);
            }

            const tabBtn = document.querySelector(`.right-panel-tab[data-tab="${tabName}"]`);
            if (tabBtn) {
                tabBtn.classList.add('active');
            }
        }

        function toggleInfoSection(btn) {
            const isExpanded = btn.getAttribute('aria-expanded') === 'true';
            btn.setAttribute('aria-expanded', !isExpanded);

            const content = btn.nextElementSibling;
            if (content && (content.classList.contains('info-section-content') || content.tagName === 'TABLE')) {
                content.classList.toggle('collapsed', isExpanded);
            }
        }

        function setRightPanelNoInfo(isNoInfo) {
            const plotTab = document.getElementById('right-tab-plot');
            const graphContainer = document.getElementById('graph-container');
            const infoSections = plotTab ? plotTab.querySelectorAll('.info-section') : [];

            setHidden(graphContainer, isNoInfo);
            infoSections.forEach(section => setHidden(section, isNoInfo));
        }

        function updateRightPanel(responseText, sources, confidence, metrics) {
            const noInfoPatterns = [
                /couldn'?t find any information/i,
                /no information (?:available |found )?(?:on|about|regarding)/i,
                /unable to find/i,
                /don'?t have (?:any )?information/i,
                /no relevant (?:information|data|documents)/i,
                /no relevant records found/i,
                /no internal records found/i,
                /no specific (?:information|data|metrics)/i,
                /not (?:able to )?find (?:any )?(?:information|data)/i,
                /no (?:data|records|details) (?:available |found )?(?:on|about|for)/i,
                /response timeout/i,
                /system is taking longer than expected/i,
                /an error occurred/i,
                /unable to process your request/i
            ];

            const safeResponse = responseText || '';
            const responseIndicatesNoInfo = noInfoPatterns.some(pattern => pattern.test(safeResponse));
            const metricsIndicateNoInfo = metrics && (metrics.errorCode ||
                (metrics.answerable === false && !metrics.excerptFallbackApplied));

            lastResponseText = safeResponse;

            if (responseIndicatesNoInfo || metricsIndicateNoInfo) {
                setRightPanelNoInfo(true);
                clearInfoPanel();
                return;
            }
            setRightPanelNoInfo(false);

            let score = '-';
            if (sources && sources.length > 0) {
                score = Math.min(0.95, 0.7 + (sources.length * 0.05)).toFixed(1);
            }

            const sourceScoreBadge = document.getElementById('source-score-badge');
            if (sourceScoreBadge) {
                sourceScoreBadge.textContent = `[score: ${score}]`;
            }

            const entityScoreBadge = document.getElementById('entity-score-badge');
            if (entityScoreBadge) {
                entityScoreBadge.textContent = `[score: ${score}]`;
            }

            const sourcesList = document.getElementById('info-sources-list');
            if (sourcesList) {
                if (!sources || sources.length === 0) {
                    sourcesList.innerHTML = `<div class="info-empty-state">No sources referenced</div>`;
                } else {
                    if (sourceScoreBadge) {
                        sourceScoreBadge.classList.remove('hidden');
                    }
                    const uniqueSources = [...new Set(sources.map(s => {
                        if (typeof s === 'string') return s;
                        return s.filename || s.source || s.name;
                    }).filter(Boolean))];
                    sourcesList.innerHTML = uniqueSources.map(filename => {
                        const ext = filename.split('.').pop().toLowerCase();
                        const typeClass = ['pdf', 'txt', 'md'].includes(ext) ? ext : 'txt';
                        return `
                            <div class="info-source-item" data-action="openSource" data-filename="${escapeHtml(filename)}"
                                 tabindex="0" role="button"
                                 tabindex="0">
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

                    if (uniqueSources.length > 0) {
                        openSource(uniqueSources[0], false);
                    }
                }
            }

            const entities = extractEntities(responseText, true);

            const entitiesList = document.getElementById('info-entities-list');
            if (entitiesList) {
                if (entities.length === 0) {
                    entitiesList.innerHTML = `<div class="info-empty-state">No entities extracted</div>`;
                } else {
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

            renderKnowledgeGraph(sources, entities);
        }


        function clearInfoPanel() {
            const graphDiv = document.getElementById('plotly-graph');
            const placeholder = document.getElementById('graph-placeholder');
            const placeholderText = placeholder ? placeholder.querySelector('.graph-placeholder-text') : null;
            const container = document.getElementById('graph-container');

            if (graphDiv) {
                graphDiv.innerHTML = '';
            }
            if (container) {
                const legendEl = container.querySelector('.graph-legend');
                if (legendEl) legendEl.remove();
                const tooltipEl = container.querySelector('.graph-tooltip');
                if (tooltipEl) tooltipEl.classList.remove('visible');
            }
            setHidden(placeholder, false);
            if (placeholderText) {
                placeholderText.textContent = 'Knowledge graph will appear here';
            }

            const sourcesList = document.getElementById('info-sources-list');
            if (sourcesList) {
                sourcesList.innerHTML = '<div class="info-empty-state">No sources yet</div>';
            }

            const entitiesList = document.getElementById('info-entities-list');
            if (entitiesList) {
                entitiesList.innerHTML = '<div class="info-empty-state">No entities extracted</div>';
            }

            const sourceScoreBadge = document.getElementById('source-score-badge');
            const entityScoreBadge = document.getElementById('entity-score-badge');
            if (sourceScoreBadge) sourceScoreBadge.classList.add('hidden');
            if (entityScoreBadge) entityScoreBadge.classList.add('hidden');
        }

        function getRetrievalMode(meta) {
            if (meta && (meta.routingDecision === 'NO_RETRIEVAL' || meta.routingDecision === 'SYSTEM_TIME')) {
                return 'Direct (No Retrieval)';
            }
            if (appSettings.graphrag) {
                return 'Hybrid (Graph+Vector)';
            }
            return 'Vector';
        }

        function formatSignalFlags(signals) {
            if (!signals || typeof signals !== 'object') return '';
            const active = Object.entries(signals)
                .filter(([_, value]) => value === true)
                .map(([key]) => key);
            return active.length ? active.join(', ') : '';
        }

        function buildQueryMeta(queryText, metrics, reasoningSteps) {
            const durations = (reasoningSteps || []).filter(step => typeof step.durationMs === 'number');
            const retrievalMs = durations
                .filter(step => step.type === 'retrieve')
                .reduce((sum, step) => sum + step.durationMs, 0);
            const totalMs = typeof metrics?.latencyMs === 'number'
                ? metrics.latencyMs
                : durations.reduce((sum, step) => sum + step.durationMs, 0);
            const activeFileCount = typeof metrics?.activeFileCount === 'number'
                ? metrics.activeFileCount
                : null;

            return {
                queryText: queryText || '',
                sector: sectorSelect?.value || '',
                retrievalMode: getRetrievalMode(metrics),
                routingDecision: metrics?.routingDecision || '',
                routingReason: metrics?.routingReason || metrics?.routingDetail || '',
                signalFlags: formatSignalFlags(metrics?.signals),
                routingConfidence: metrics?.routingConfidence,
                topK: appSettings.topK,
                retrievalMs: retrievalMs || null,
                latencyMs: totalMs || null,
                documentsRetrieved: metrics?.documentsRetrieved,
                subQueries: metrics?.subQueries,
                activeFileCount
            };
        }

        function formatDuration(value) {
            if (value === null || value === undefined) return '';
            return `${Math.round(value)}ms`;
        }

        function formatConfidence(value) {
            if (value === null || value === undefined || Number.isNaN(value)) return '';
            return Number(value).toFixed(2);
        }

        function formatDate(value) {
            if (!value) return '';
            const date = new Date(value);
            if (Number.isNaN(date.getTime())) return String(value);
            return date.toISOString().slice(0, 10);
        }

        function formatTimeAgo(timestamp) {
            if (!timestamp) return '';
            const date = new Date(timestamp);
            if (Number.isNaN(date.getTime())) return '';
            const diff = Date.now() - date.getTime();
            const mins = Math.floor(diff / 60000);
            const hours = Math.floor(diff / 3600000);
            const days = Math.floor(diff / 86400000);

            if (mins < 1) return 'Just now';
            if (mins < 60) return `${mins} min ago`;
            if (hours < 24) return `${hours} hour${hours !== 1 ? 's' : ''} ago`;
            if (days < 7) return `${days} day${days !== 1 ? 's' : ''} ago`;
            return date.toLocaleDateString();
        }

        const UNAVAILABLE_VALUES = new Set(['unavailable', 'n/a', 'na', 'none']);

        function addRow(rows, key, value, opts = {}) {
            if (value === null || value === undefined) return;
            if (typeof value === 'string') {
                const trimmed = value.trim();
                if (!trimmed) return;
                if (UNAVAILABLE_VALUES.has(trimmed.toLowerCase())) return;
            }
            rows.push({
                key,
                value: String(value),
                className: opts.className || '',
                valueClass: opts.valueClass || ''
            });
        }

        function buildTooltipHtml(title, rows) {
            const safeTitle = escapeHtml(title);
            const rowHtml = rows.map(row => `
                <div class="graph-tooltip-row${row.className ? ` ${row.className}` : ''}">
                    <span class="graph-tooltip-key">${escapeHtml(row.key)}</span>
                    <span class="graph-tooltip-value${row.valueClass ? ` ${row.valueClass}` : ''}">${escapeHtml(row.value)}</span>
                </div>
            `).join('');
            return `<div class="graph-tooltip-title">${safeTitle}</div>${rowHtml}`;
        }

        function getGraphTooltip(container) {
            if (!container) return null;
            let tooltip = container.querySelector('.graph-tooltip');
            if (!tooltip) {
                tooltip = document.createElement('div');
                tooltip.className = 'graph-tooltip';
                container.appendChild(tooltip);
            }
            return tooltip;
        }

        function positionGraphTooltip(tooltip, containerRect, clientX, clientY, anchorRect) {
            if (!tooltip || !containerRect) return;
            const padding = 8;
            const gap = 16;

            if (anchorRect) {
                const tooltipWidth = tooltip.offsetWidth;
                const tooltipHeight = tooltip.offsetHeight;
                const anchorLeft = anchorRect.left - containerRect.left;
                const anchorRight = anchorRect.right - containerRect.left;
                const anchorTop = anchorRect.top - containerRect.top;
                const anchorBottom = anchorRect.bottom - containerRect.top;
                const nodeCenterX = (anchorLeft + anchorRight) / 2;
                const nodeCenterY = (anchorTop + anchorBottom) / 2;
                const maxLeft = containerRect.width - tooltipWidth - padding;
                const maxTop = containerRect.height - tooltipHeight - padding;
                const clamp = (value, min, max) => Math.min(Math.max(value, min), max);
                const candidates = [
                    { name: 'above', left: nodeCenterX - (tooltipWidth / 2), top: anchorTop - tooltipHeight - gap },
                    { name: 'below', left: nodeCenterX - (tooltipWidth / 2), top: anchorBottom + gap },
                    { name: 'right', left: anchorRight + gap, top: nodeCenterY - (tooltipHeight / 2) },
                    { name: 'left', left: anchorLeft - tooltipWidth - gap, top: nodeCenterY - (tooltipHeight / 2) }
                ];

                const placementHint = tooltip.dataset.placementHint;
                if (placementHint === 'horizontal') {
                    candidates.sort((a, b) => {
                        const order = { right: 0, left: 1, below: 2, above: 3 };
                        return (order[a.name] ?? 9) - (order[b.name] ?? 9);
                    });
                } else if (placementHint === 'vertical') {
                    candidates.sort((a, b) => {
                        const order = { above: 0, below: 1, right: 2, left: 3 };
                        return (order[a.name] ?? 9) - (order[b.name] ?? 9);
                    });
                }

                const evaluated = candidates.map(candidate => {
                    const left = clamp(candidate.left, padding, maxLeft);
                    const top = clamp(candidate.top, padding, maxTop);
                    const right = left + tooltipWidth;
                    const bottom = top + tooltipHeight;
                    const overlapX = Math.max(0, Math.min(right, anchorRight) - Math.max(left, anchorLeft));
                    const overlapY = Math.max(0, Math.min(bottom, anchorBottom) - Math.max(top, anchorTop));
                    const overlapArea = overlapX * overlapY;
                    const inBounds = left >= padding && top >= padding &&
                        right <= containerRect.width - padding &&
                        bottom <= containerRect.height - padding;
                    return { left, top, overlapArea, inBounds, name: candidate.name };
                });

                let best = evaluated.find(item => item.overlapArea === 0 && item.inBounds);
                if (!best) {
                    best = evaluated.sort((a, b) => {
                        if (a.overlapArea !== b.overlapArea) return a.overlapArea - b.overlapArea;
                        if (a.inBounds !== b.inBounds) return a.inBounds ? -1 : 1;
                        return 0;
                    })[0];
                }

                if (best && best.overlapArea > 0) {
                    const corners = [
                        { name: 'corner-tl', left: padding, top: padding },
                        { name: 'corner-tr', left: containerRect.width - tooltipWidth - padding, top: padding },
                        { name: 'corner-bl', left: padding, top: containerRect.height - tooltipHeight - padding },
                        { name: 'corner-br', left: containerRect.width - tooltipWidth - padding, top: containerRect.height - tooltipHeight - padding }
                    ].map(candidate => {
                        const left = clamp(candidate.left, padding, maxLeft);
                        const top = clamp(candidate.top, padding, maxTop);
                        const right = left + tooltipWidth;
                        const bottom = top + tooltipHeight;
                        const overlapX = Math.max(0, Math.min(right, anchorRight) - Math.max(left, anchorLeft));
                        const overlapY = Math.max(0, Math.min(bottom, anchorBottom) - Math.max(top, anchorTop));
                        const overlapArea = overlapX * overlapY;
                        const inBounds = left >= padding && top >= padding &&
                            right <= containerRect.width - padding &&
                            bottom <= containerRect.height - padding;
                        return { left, top, overlapArea, inBounds };
                    });
                    const bestCorner = corners.find(item => item.overlapArea === 0 && item.inBounds)
                        || corners.sort((a, b) => a.overlapArea - b.overlapArea)[0];
                    if (bestCorner && bestCorner.overlapArea <= best.overlapArea) {
                        best = bestCorner;
                    }
                }

                tooltip.style.left = `${best.left}px`;
                tooltip.style.top = `${best.top}px`;
                return;
            }

            const offset = 12;
            let left = ((clientX ?? containerRect.left) - containerRect.left) + offset;
            let top = ((clientY ?? containerRect.top) - containerRect.top) + offset;
            const maxLeft = containerRect.width - tooltip.offsetWidth - padding;
            const maxTop = containerRect.height - tooltip.offsetHeight - padding;
            left = Math.max(padding, Math.min(left, maxLeft));
            top = Math.max(padding, Math.min(top, maxTop));
            tooltip.style.left = `${left}px`;
            tooltip.style.top = `${top}px`;
        }

        function showGraphTooltip(container, html, clientX, clientY, anchorRect, placementHint) {
            const tooltip = getGraphTooltip(container);
            if (!tooltip) return;
            if (placementHint) {
                tooltip.dataset.placementHint = placementHint;
            } else {
                delete tooltip.dataset.placementHint;
            }
            tooltip.innerHTML = html;
            tooltip.classList.add('visible');
            const rect = container.getBoundingClientRect();
            if (anchorRect || (clientX !== null && clientY !== null)) {
                positionGraphTooltip(tooltip, rect, clientX, clientY, anchorRect);
            }
        }

        function hideGraphTooltip(container) {
            const tooltip = getGraphTooltip(container);
            if (!tooltip) return;
            tooltip.classList.remove('visible');
        }

        function escapeRegExp(value) {
            return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        }

        function countEntityMentions(name) {
            if (!lastResponseText || !name) return 0;
            const pattern = new RegExp(`\\b${escapeRegExp(name)}\\b`, 'gi');
            return (lastResponseText.match(pattern) || []).length;
        }

        function estimateEntityConfidence(name) {
            const count = countEntityMentions(name);
            if (count >= 3) return 0.9;
            if (count === 2) return 0.75;
            if (count === 1) return 0.6;
            return null;
        }

        function pickSnippet(data) {
            if (!data) return '';
            const highlights = Array.isArray(data.highlights) ? data.highlights : [];
            if (highlights.length > 0) {
                return highlights[0].trim();
            }
            if (typeof data.content === 'string') {
                const lines = data.content.split('\n');
                const firstLine = lines.find(line => line.trim() && !line.startsWith('---'));
                return firstLine ? firstLine.trim() : '';
            }
            return '';
        }

        async function fetchSourceSnippet(filename, queryText) {
            if (!filename) return '';
            if (sourceSnippetCache.has(filename)) return sourceSnippetCache.get(filename);
            if (sourceSnippetInFlight.has(filename)) return sourceSnippetInFlight.get(filename);

            const safeQuery = queryText || lastQuery || '';
            const dept = sectorSelect?.value || '';
            const deptParam = dept ? `&dept=${encodeURIComponent(dept)}` : '';
            const fetchPromise = (async () => {
                try {
                    const res = await guardedFetch(`${API_BASE}/inspect?fileName=${encodeURIComponent(filename)}&query=${encodeURIComponent(safeQuery)}${deptParam}`);
                    const data = await res.json();
                    let snippet = pickSnippet(data);
                    snippet = snippet.replace(/\s+/g, ' ').trim();
                    if (snippet.length > 360) {
                        snippet = snippet.slice(0, 357) + '...';
                    }
                    sourceSnippetCache.set(filename, snippet);
                    return snippet;
                } catch {
                    sourceSnippetCache.set(filename, '');
                    return '';
                } finally {
                    sourceSnippetInFlight.delete(filename);
                }
            })();

            sourceSnippetInFlight.set(filename, fetchPromise);
            return fetchPromise;
        }


        function renderKnowledgeGraph(sources, entities) {
            const graphDiv = document.getElementById('plotly-graph');
            const placeholder = document.getElementById('graph-placeholder');
            const placeholderText = placeholder ? placeholder.querySelector('.graph-placeholder-text') : null;
            const container = document.getElementById('graph-container');

            if (!graphDiv || !container) return;

            if (container.offsetWidth === 0 || container.offsetHeight === 0) {
                setTimeout(() => renderKnowledgeGraph(sources, entities), 100);
                return;
            }

            if (!sources || sources.length === 0) {
                if (graphDiv) graphDiv.innerHTML = '';
                if (container) {
                    const legendEl = container.querySelector('.graph-legend');
                    if (legendEl) legendEl.remove();
                }
                setHidden(placeholder, false);
                if (placeholderText) placeholderText.textContent = 'Knowledge graph will appear here';
                return;
            }

            // Deduplicate sources by filename
            const seenFilenames = new Set();
            sources = sources.filter(s => {
                const filename = (typeof s === 'string' ? s : (s.filename || s.source || s.name || '')).toLowerCase();
                if (seenFilenames.has(filename)) return false;
                seenFilenames.add(filename);
                return true;
            });

            setHidden(placeholder, true);

            // Calculate source count early (needed for viewBox scaling)
            const sourceCount = Math.min(sources.length, 4);

            const viewBoxMin = -14.0;
            const viewBoxMax = 14.0;
            const viewBoxSize = viewBoxMax - viewBoxMin;
            const labelBounds = {
                min: viewBoxMin + 0.3,
                max: viewBoxMax - 0.3
            };
            const labelOverflowX = 6.0;
            const labelBoundsX = {
                min: labelBounds.min - labelOverflowX,
                max: labelBounds.max + labelOverflowX
            };
            const queryText = lastQuery
                || document.querySelector('.message.user:last-child .message-bubble')?.innerText?.trim()
                || '';
            const queryMeta = (lastQueryMeta && lastQueryMeta.queryText)
                ? lastQueryMeta
                : buildQueryMeta(queryText, null, []);
            const tooltipState = { activeNodeId: null, clientX: null, clientY: null, anchorEl: null, placementHint: '' };

            function shortLabel(text, max = 40) {
                const clean = text
                    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
                    .replace(/[\[\]]/g, '')
                    .replace(/\.(pdf|txt|md)$/i, '')
                    .replace(/-/g, ' ')
                    .trim();
                if (clean.length <= max) return clean;
                const words = clean.split(' ');
                let result = '';
                for (const word of words) {
                    if ((result + ' ' + word).trim().length <= max - 1) {
                        result = (result + ' ' + word).trim();
                    } else {
                        break;
                    }
                }
                return result || clean.substring(0, max - 1);
            }

            const fixedPositions = [
                { x: 0, y: 8.5, textPos: 'top center' },
                { x: 8.5, y: 0, textPos: 'right center' },
                { x: 0, y: -8.5, textPos: 'bottom center' },
                { x: -8.5, y: 0, textPos: 'left center' }
            ];

            const nodes = [];
            const queryRows = [];
            const intentLabel = queryMeta.routingDecision === 'NO_RETRIEVAL'
                ? 'Conversational'
                : queryMeta.routingDecision === 'SYSTEM_TIME'
                    ? 'System'
                    : 'Informational';
            const scopeLabel = queryMeta.activeFileCount
                ? `${queryMeta.activeFileCount} uploaded file${queryMeta.activeFileCount === 1 ? '' : 's'}`
                : 'All sector documents';
            addRow(queryRows, 'Query', queryMeta.queryText);
            addRow(queryRows, 'Intent', intentLabel);
            addRow(queryRows, 'Sector', queryMeta.sector);
            addRow(queryRows, 'Scope', scopeLabel);
            addRow(queryRows, 'Retrieval', queryMeta.retrievalMode);
            addRow(queryRows, 'Routing', queryMeta.routingDecision);
            addRow(queryRows, 'Routing reason', queryMeta.routingReason);
            addRow(queryRows, 'Signals', queryMeta.signalFlags);
            addRow(queryRows, 'Routing conf', formatConfidence(queryMeta.routingConfidence));
            addRow(queryRows, 'TopK (UI)', queryMeta.topK);
            addRow(queryRows, 'Retrieval time', formatDuration(queryMeta.retrievalMs));
            addRow(queryRows, 'Total time', formatDuration(queryMeta.latencyMs));
            addRow(queryRows, 'Docs retrieved', queryMeta.documentsRetrieved);
            addRow(queryRows, 'Sources shown', sourceCount);
            addRow(queryRows, 'Sub-queries', queryMeta.subQueries);

            nodes.push({
                id: 'query',
                type: 'query',
                label: 'Query',
                tooltipTitle: 'Query Node',
                tooltipRows: queryRows,
                x: 0,
                y: 0,
                radius: 1.0,
                textPos: 'bottom center'
            });

            for (let i = 0; i < sourceCount; i++) {
                const source = sources[i];
                const pos = fixedPositions[i];
                const filename = source.filename || source.source || source.name || source;
                const label = shortLabel(filename);
                const ext = filename.split('.').pop()?.toUpperCase() || 'DOC';
                const similarityRaw = source.similarity ?? source.score ?? source.relevance ?? source.confidence ?? null;
                const similarity = (similarityRaw !== null && similarityRaw !== undefined && !Number.isNaN(Number(similarityRaw)))
                    ? formatConfidence(Number(similarityRaw))
                    : '';
                const chunkCount = source.chunkCount ?? source.chunks ?? source.chunk_count ?? null;
                const lastUpdated = formatDate(source.lastUpdated ?? source.updatedAt ?? source.lastModified ?? source.modifiedAt ?? '');
                const sourceRows = [];
                addRow(sourceRows, 'Source', filename);
                addRow(sourceRows, 'Type', `${ext} document`);
                addRow(sourceRows, 'Sector', queryMeta.sector);
                addRow(sourceRows, 'Rank', `#${i + 1} of ${appSettings.topK}`);
                addRow(sourceRows, 'Retrieval', queryMeta.retrievalMode);
                addRow(sourceRows, 'Similarity', similarity);
                addRow(sourceRows, 'Chunks', chunkCount);
                addRow(sourceRows, 'Last updated', lastUpdated);
                addRow(sourceRows, 'Doc ID', source.docId ?? source.documentId ?? source.id ?? '');
                addRow(sourceRows, 'Classification', source.classification ?? source.clearance ?? '');
                addRow(sourceRows, 'Owner', source.owner ?? source.author ?? source.preparedBy ?? '');
                addRow(sourceRows, 'Retrieved', (queryMeta.documentsRetrieved !== null && queryMeta.documentsRetrieved !== undefined)
                    ? `${queryMeta.documentsRetrieved} docs`
                    : '');
                addRow(sourceRows, 'Top passage', 'Loading...', { valueClass: 'graph-tooltip-value--snippet' });
                addRow(sourceRows, 'Action', 'Click node to open source', { valueClass: 'graph-tooltip-value--action' });

                nodes.push({
                    id: `source-${i}`,
                    type: 'source',
                    label,
                    tooltipTitle: 'Source Node',
                    tooltipRows: sourceRows,
                    filename,
                    x: pos.x,
                    y: pos.y,
                    radius: 0.65,
                    textPos: pos.textPos
                });
            }

            const entityPositions = [
                { x: 5.5, y: 5.5, textPos: 'top right' },
                { x: -5.5, y: -5.5, textPos: 'bottom left' }
            ];

            const preferredEntities = entities.filter(e =>
                e.type === 'person' || e.type === 'organization'
            );
            const fallbackEntities = entities.filter(e =>
                e.type !== 'document' && !preferredEntities.includes(e)
            );
            const goodEntities = [...preferredEntities, ...fallbackEntities].slice(0, 2);

            goodEntities.forEach((entity, i) => {
                const pos = entityPositions[i];
                const label = shortLabel(entity.name, 12);
                const entityType = entity.type || 'entity';
                const entityDefinition = entity.description && entity.description !== entityType
                    ? entity.description
                    : '';
                const mentionCount = countEntityMentions(entity.name);
                const entityRows = [];
                addRow(entityRows, 'Entity', entity.name);
                addRow(entityRows, 'Type', entityType);
                addRow(entityRows, 'Definition', entityDefinition);
                addRow(entityRows, 'Confidence', formatConfidence(estimateEntityConfidence(entity.name)));
                addRow(entityRows, 'Mentions', mentionCount ? `${mentionCount} in answer` : '');
                addRow(entityRows, 'Sources', (queryMeta.documentsRetrieved !== null && queryMeta.documentsRetrieved !== undefined)
                    ? `${queryMeta.documentsRetrieved} docs`
                    : 'Answer text');
                addRow(entityRows, 'Action', 'Click node to ask about entity', { valueClass: 'graph-tooltip-value--action' });

                nodes.push({
                    id: `entity-${i}`,
                    type: 'entity',
                    label,
                    tooltipTitle: 'Entity Node',
                    tooltipRows: entityRows,
                    entityName: entity.name,
                    x: pos.x,
                    y: pos.y,
                    radius: 0.35,
                    textPos: pos.textPos
                });
            });

            const edges = [];
            for (let i = 1; i <= sourceCount; i++) {
                edges.push([0, i]);
            }
            goodEntities.forEach((_, i) => {
                const entityIdx = sourceCount + 1 + i;
                const sourceIdx = (i % sourceCount) + 1;
                edges.push([sourceIdx, entityIdx]);
            });

            graphDiv.innerHTML = '';

            const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            svg.setAttribute('class', 'graph-svg');
            svg.setAttribute('viewBox', `${viewBoxMin} ${viewBoxMin} ${viewBoxSize} ${viewBoxSize}`);
            svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
            svg.setAttribute('role', 'img');
            svg.setAttribute('aria-label', 'Knowledge graph');
            graphDiv.appendChild(svg);

            const edgesGroup = document.createElementNS('http://www.w3.org/2000/svg', 'g');
            edgesGroup.setAttribute('class', 'graph-edges');
            edges.forEach(([fromIdx, toIdx], edgeIndex) => {
                const from = nodes[fromIdx];
                const to = nodes[toIdx];
                if (!from || !to) return;
                const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
                line.setAttribute('x1', from.x);
                line.setAttribute('y1', from.y);
                line.setAttribute('x2', to.x);
                line.setAttribute('y2', to.y);
                line.setAttribute('class', 'graph-edge');
                const length = Math.hypot(to.x - from.x, to.y - from.y);
                line.style.setProperty('--edge-length', length.toFixed(3));
                line.style.setProperty('--edge-delay', `${Math.min(edgeIndex, 6) * 0.06}s`);
                line.addEventListener('animationend', () => {
                    line.style.setProperty('--edge-dash', '0.5 0.5');
                }, { once: true });
                edgesGroup.appendChild(line);
            });
            svg.appendChild(edgesGroup);

            const textOffsets = {
                'top center': { dx: 0, dy: -0.9, anchor: 'middle', baseline: 'baseline' },
                'bottom center': { dx: 0, dy: 0.95, anchor: 'middle', baseline: 'hanging' },
                'middle right': { dx: 0.95, dy: 0, anchor: 'start', baseline: 'middle' },
                'middle left': { dx: -0.95, dy: 0, anchor: 'end', baseline: 'middle' },
                'top right': { dx: 0.85, dy: -0.75, anchor: 'start', baseline: 'baseline' },
                'top left': { dx: -0.85, dy: -0.75, anchor: 'end', baseline: 'baseline' },
                'bottom right': { dx: 0.85, dy: 0.75, anchor: 'start', baseline: 'hanging' },
                'bottom left': { dx: -0.85, dy: 0.75, anchor: 'end', baseline: 'hanging' }
            };

            const nodesGroup = document.createElementNS('http://www.w3.org/2000/svg', 'g');
            nodesGroup.setAttribute('class', 'graph-nodes');
            svg.appendChild(nodesGroup);

            const nodeBoxes = nodes.map(node => ({
                x: node.x - node.radius - 0.25,
                y: node.y - node.radius - 0.25,
                width: (node.radius + 0.25) * 2,
                height: (node.radius + 0.25) * 2
            }));
            const labelBoxes = [];

            function boxesOverlap(a, b, padding = 0.15) {
                return !(
                    (a.x + a.width + padding) < b.x ||
                    (b.x + b.width + padding) < a.x ||
                    (a.y + a.height + padding) < b.y ||
                    (b.y + b.height + padding) < a.y
                );
            }

            function withinBounds(box) {
                return (
                    box.x >= labelBoundsX.min &&
                    box.y >= labelBounds.min &&
                    (box.x + box.width) <= labelBoundsX.max &&
                    (box.y + box.height) <= labelBounds.max
                );
            }

            function getMaxLabelWidth(labelX, anchor) {
                if (anchor === 'start') {
                    return labelBoundsX.max - labelX;
                }
                if (anchor === 'end') {
                    return labelX - labelBoundsX.min;
                }
                return Math.min(labelX - labelBoundsX.min, labelBoundsX.max - labelX) * 2;
            }

            function fitLabelTextToWidth(labelEl, maxWidth, allowTruncate) {
                // Always allow labels - don't truncate them
                // The SVG will handle overflow with the expanded viewBox
                return true;
            }

            function getCandidateKeys(node, preferredKey) {
                const keys = [];
                const pushKey = (key) => {
                    if (key && !keys.includes(key)) {
                        keys.push(key);
                    }
                };
                pushKey(preferredKey);
                const horizontal = Math.abs(node.x) >= Math.abs(node.y);
                if (horizontal) {
                    if (node.x >= 0) {
                        ['middle left', 'top left', 'bottom left', 'top center', 'bottom center', 'middle right', 'top right', 'bottom right']
                            .forEach(pushKey);
                    } else {
                        ['middle right', 'top right', 'bottom right', 'top center', 'bottom center', 'middle left', 'top left', 'bottom left']
                            .forEach(pushKey);
                    }
                } else {
                    if (node.y >= 0) {
                        ['bottom center', 'bottom left', 'bottom right', 'middle right', 'middle left', 'top center', 'top left', 'top right']
                            .forEach(pushKey);
                    } else {
                        ['top center', 'top left', 'top right', 'middle right', 'middle left', 'bottom center', 'bottom left', 'bottom right']
                            .forEach(pushKey);
                    }
                }
                return keys;
            }

            function placeLabel(labelEl, node, preferredKey) {
                const baseText = labelEl.textContent || '';
                labelEl.style.display = '';
                const candidateKeys = getCandidateKeys(node, preferredKey);

                const tryPlace = (allowTruncate) => {
                    for (const key of candidateKeys) {
                        const offset = textOffsets[key];
                        if (!offset) continue;
                        labelEl.textContent = baseText;
                        const labelX = node.x + offset.dx;
                        const labelY = node.y + offset.dy;
                        labelEl.setAttribute('x', labelX);
                        labelEl.setAttribute('y', labelY);
                        labelEl.setAttribute('text-anchor', offset.anchor);
                        labelEl.setAttribute('dominant-baseline', offset.baseline);

                        const maxWidth = getMaxLabelWidth(labelX, offset.anchor) - 0.1;
                        if (!fitLabelTextToWidth(labelEl, maxWidth, allowTruncate)) {
                            continue;
                        }

                        let box;
                        try {
                            box = labelEl.getBBox();
                        } catch {
                            continue;
                        }

                        if (!withinBounds(box)) {
                            continue;
                        }

                        const overlapsLabel = labelBoxes.some(existing => boxesOverlap(box, existing));
                        if (overlapsLabel) continue;
                        const overlapsNode = nodeBoxes.some(existing => boxesOverlap(box, existing));
                        if (overlapsNode) continue;

                        labelBoxes.push({
                            x: box.x,
                            y: box.y,
                            width: box.width,
                            height: box.height
                        });
                        return true;
                    }
                    return false;
                };

                if (tryPlace(false)) return;
                if (tryPlace(true)) return;

                // Force-place labels for query and source nodes even if they overlap
                // Only hide labels for entity nodes when placement fails
                if (node.type === 'entity') {
                    labelEl.style.display = 'none';
                } else {
                    // Force place at preferred position for query/source nodes
                    const offset = textOffsets[preferredKey] || textOffsets['bottom center'];
                    const labelX = node.x + offset.dx;
                    const labelY = node.y + offset.dy;
                    labelEl.setAttribute('x', labelX);
                    labelEl.setAttribute('y', labelY);
                    labelEl.setAttribute('text-anchor', offset.anchor);
                    labelEl.setAttribute('dominant-baseline', offset.baseline);
                    // Truncate to fit
                    const maxWidth = getMaxLabelWidth(labelX, offset.anchor) - 0.1;
                    fitLabelTextToWidth(labelEl, maxWidth, true);
                }
            }

            function buildTooltipHtmlForNode(node, snippet) {
                if (!node.tooltipRows || !node.tooltipTitle) {
                    return buildTooltipHtml(node.label || 'Node', []);
                }
                if (node.type === 'source') {
                    const rows = node.tooltipRows.map(row => {
                        if (row.key === 'Top passage') {
                            const nextValue = snippet ?? row.value;
                            if (!nextValue || String(nextValue).toLowerCase() === 'unavailable') {
                                return null;
                            }
                            return {
                                key: row.key,
                                value: nextValue,
                                className: row.className || '',
                                valueClass: row.valueClass || ''
                            };
                        }
                        return row;
                    }).filter(Boolean);
                    return buildTooltipHtml(node.tooltipTitle, rows);
                }
                return buildTooltipHtml(node.tooltipTitle, node.tooltipRows);
            }

            function showNodeTooltip(event, node, group) {
                if (!container) return;
                const anchorElement = group?.querySelector('.graph-node-dot') || null;
                tooltipState.activeNodeId = node.id;
                tooltipState.clientX = event?.clientX ?? null;
                tooltipState.clientY = event?.clientY ?? null;
                tooltipState.anchorEl = anchorElement;
                tooltipState.placementHint = node.type === 'query' ? 'vertical' : '';
                const anchorRect = anchorElement?.getBoundingClientRect() || null;

                let snippet = '';
                if (node.type === 'source') {
                    snippet = sourceSnippetCache.get(node.filename) || 'Loading...';
                }
                const html = buildTooltipHtmlForNode(node, snippet);
                if (anchorRect) {
                    showGraphTooltip(container, html, null, null, anchorRect, tooltipState.placementHint);
                } else if (event?.clientX && event?.clientY) {
                    showGraphTooltip(container, html, event.clientX, event.clientY, null, tooltipState.placementHint);
                }

                if (node.type === 'source' && node.filename && !sourceSnippetCache.has(node.filename)) {
                    fetchSourceSnippet(node.filename, queryMeta.queryText).then(snippetText => {
                        if (tooltipState.activeNodeId !== node.id) return;
                        const updatedHtml = buildTooltipHtmlForNode(node, snippetText || 'Unavailable');
                        const tooltip = getGraphTooltip(container);
                        if (tooltip && tooltip.classList.contains('visible')) {
                            const nextAnchor = tooltipState.anchorEl?.getBoundingClientRect() || anchorRect;
                            showGraphTooltip(container, updatedHtml, tooltipState.clientX, tooltipState.clientY, nextAnchor, tooltipState.placementHint);
                        }
                    });
                }
            }

            function moveNodeTooltip(event) {
                if (!container || !event) return;
                tooltipState.clientX = event.clientX;
                tooltipState.clientY = event.clientY;
                const tooltip = getGraphTooltip(container);
                if (tooltip && tooltip.classList.contains('visible')) {
                    const anchorRect = tooltipState.anchorEl?.getBoundingClientRect() || null;
                    positionGraphTooltip(tooltip, container.getBoundingClientRect(), event.clientX, event.clientY, anchorRect);
                }
            }

            function hideNodeTooltip() {
                tooltipState.activeNodeId = null;
                tooltipState.anchorEl = null;
                hideGraphTooltip(container);
            }

            const typeOrder = { source: 0, entity: 1, query: 2 };
            const renderNodes = [...nodes].sort((a, b) => (typeOrder[a.type] ?? 9) - (typeOrder[b.type] ?? 9));

            renderNodes.forEach((node, nodeIndex) => {
                const group = document.createElementNS('http://www.w3.org/2000/svg', 'g');
                group.setAttribute('class', `graph-node graph-node--${node.type}${node.type !== 'query' ? ' is-clickable' : ''}`);
                group.style.setProperty('--graph-node-delay', `${0.12 + Math.min(nodeIndex, 6) * 0.06}s`);

                const circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
                circle.setAttribute('cx', node.x);
                circle.setAttribute('cy', node.y);
                circle.setAttribute('r', node.radius);
                circle.setAttribute('class', 'graph-node-dot');
                group.appendChild(circle);

                const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
                label.setAttribute('class', 'graph-label');
                label.textContent = node.label;
                group.appendChild(label);

                circle.addEventListener('mouseenter', (event) => showNodeTooltip(event, node, group));
                circle.addEventListener('mousemove', moveNodeTooltip);
                circle.addEventListener('mouseleave', hideNodeTooltip);
                group.addEventListener('focus', () => showNodeTooltip(null, node, group));
                group.addEventListener('blur', hideNodeTooltip);

                if (node.type === 'source') {
                    group.setAttribute('role', 'button');
                    group.setAttribute('tabindex', '0');
                    group.addEventListener('click', (event) => {
                        event.preventDefault();
                        event.stopPropagation();
                        if (!node.filename) return;
                        openSource(node.filename, true);
                        highlightSourceInList(node.filename);
                    });
                    group.addEventListener('keydown', (event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault();
                            if (!node.filename) return;
                            openSource(node.filename, true);
                            highlightSourceInList(node.filename);
                        }
                    });
                } else if (node.type === 'entity') {
                    group.setAttribute('role', 'button');
                    group.setAttribute('tabindex', '0');
                    group.addEventListener('click', () => {
                        runEntityQuery(node.entityName || node.label);
                    });
                    group.addEventListener('keydown', (event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault();
                            runEntityQuery(node.entityName || node.label);
                        }
                    });
                }

                nodesGroup.appendChild(group);
                placeLabel(label, node, node.textPos);
            });

            svg.querySelectorAll('title').forEach(el => el.remove());
            renderGraphLegend(container);
        }

        function renderGraphLegend(container) {
            if (!container) return;
            const existing = container.querySelector('.graph-legend');
            if (existing) existing.remove();

            const legend = document.createElement('div');
            legend.className = 'graph-legend';

            const items = [
                { label: 'Query', dotClass: 'legend-dot legend-dot--query' },
                { label: 'Source', dotClass: 'legend-dot legend-dot--source' },
                { label: 'Entity', dotClass: 'legend-dot legend-dot--entity' }
            ];

            items.forEach(item => {
                const entry = document.createElement('div');
                entry.className = 'legend-item';

                const dot = document.createElement('span');
                dot.className = item.dotClass;

                const text = document.createElement('span');
                text.textContent = item.label;

                entry.appendChild(dot);
                entry.appendChild(text);
                legend.appendChild(entry);
            });

            container.appendChild(legend);
        }

        function highlightSourceInList(filename) {
            const sourcesList = document.getElementById('info-sources-list');
            if (!sourcesList) return;

            sourcesList.querySelectorAll('.info-source-item.highlighted').forEach(el => {
                el.classList.remove('highlighted');
            });

            const sourceItems = sourcesList.querySelectorAll('.info-source-item');
            sourceItems.forEach(item => {
                const nameEl = item.querySelector('.info-source-name');
                if (nameEl && nameEl.textContent.includes(filename.replace(/\.(pdf|txt|md)$/i, ''))) {
                    item.classList.add('highlighted');
                    item.scrollIntoView({ behavior: 'smooth', block: 'center' });
                }
            });
        }

        function runEntityQuery(entityName) {
            const queryInput = document.getElementById('query-input');
            if (!queryInput) return;

            queryInput.value = `Tell me more about "${entityName}"`;
            queryInput.focus();

            handleQuery();
        }

        function extractEntities(text, excludeDocuments = false) {
            const entities = [];
            const seen = new Set();

            function addEntity(name, type, description = '') {
                const key = `${name.toLowerCase()}-${type}`;
                if (!seen.has(key) && name.length > 1 && name.length < 100) {
                    seen.add(key);
                    if (!description) {
                        description = generateEntityDescription(name, type, text);
                    }
                    entities.push({ name: name.trim(), type, description });
                }
            }

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

            const properNounPattern = /\b([A-Z][a-z]+(?:\s+[A-Z][a-z]+)+)\b/g;
            let match;
            while ((match = properNounPattern.exec(text)) !== null) {
                const term = match[1];
                const skipTerms = ['The', 'This', 'That', 'These', 'Those', 'According To', 'Based On'];
                if (!skipTerms.some(skip => term.startsWith(skip))) {
                    if (term.match(/\b(Officer|Director|Manager|Head|Lead|Chief|Counsel|President|Vice|Executive|Sector|Operations|Leadership|Analyst|Engineer|Coordinator|Supervisor|Administrator|Committee|Team|Staff|Budget|Financial)\b/i)) {
                        continue;
                    }

                    if (term.match(/\b(Inc|Corp|LLC|Ltd|Company|Organization|Agency|Department|Institute|University|Hospital|Group|Division)\b/i)) {
                        addEntity(term, 'organization');
                    } else if (term.match(/\b(Street|Avenue|Road|City|County|State|Country|Building|Floor|Suite)\b/i)) {
                        addEntity(term, 'location');
                    } else if (term.split(' ').length === 2 && !term.match(/[0-9]/)) {
                        addEntity(term, 'person');
                    }
                }
            }

            const quotedPattern = /"([^"]{3,40})"/g;
            while ((match = quotedPattern.exec(text)) !== null) {
                const term = match[1].trim();
                if (term.match(/\.(pdf|txt|md|doc|docx|csv|json|xml|html|xlsx)$/i)) continue;
                if (term.includes(',')) continue;
                if (term[0] !== term[0].toUpperCase()) continue;
                if (term.match(/^(The|This|That|It|A|An|What|How|Why|When|Where|Which)\b/i)) continue;
                if (term.split(/\s+/).length > 3) continue;
                addEntity(term, 'concept');
            }

            const acronymPattern = /\b([A-Z]{2,6})\b/g;
            while ((match = acronymPattern.exec(text)) !== null) {
                const acronym = match[1];
                const skipAcronyms = ['THE', 'AND', 'FOR', 'ARE', 'BUT', 'NOT', 'YOU', 'ALL', 'CAN', 'HAD', 'HER', 'WAS', 'ONE', 'OUR', 'OUT'];
                if (!skipAcronyms.includes(acronym)) {
                    addEntity(acronym, 'acronym');
                }
            }

            return entities.slice(0, 15);
        }

        function generateEntityDescription(name, type, fullText) {
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
                pinned.pop();
                pinned.unshift(sessionId);
            }
            setPinnedIds(pinned);
            renderConversationList();
        }

        function clearUnpinnedConversations() {
            const pinnedIds = getPinnedIds();
            const unpinnedCount = conversationHistory.filter(s => !pinnedIds.includes(s.id)).length;

            if (unpinnedCount === 0) {
                showToast('No unpinned conversations to clear');
                return;
            }

            const confirmed = confirm(`Clear ${unpinnedCount} unpinned conversation${unpinnedCount !== 1 ? 's' : ''}?\n\nPinned conversations will be preserved.`);
            if (!confirmed) return;

            conversationHistory = conversationHistory.filter(s => pinnedIds.includes(s.id));

            if (currentSessionId && !pinnedIds.includes(currentSessionId)) {
                currentSessionId = generateSessionId();
                currentMessages = [];
                clearChat();
                clearInfoPanel();
            }

            saveConversationHistory();
            renderConversationList();

            showToast(`Cleared ${unpinnedCount} conversation${unpinnedCount !== 1 ? 's' : ''}`);
        }

        function toggleConversationList() {
            const section = document.getElementById('conversation-section');
            if (!section) return;
            section.classList.toggle('collapsed');
            const isCollapsed = section.classList.contains('collapsed');
            localStorage.setItem('sentinel-conversations-collapsed', isCollapsed);
            updateConversationExpansionState(!isCollapsed);
        }

        function updateConversationExpansionState(isExpanded) {
            const workspace = document.querySelector('.workspace');
            if (!workspace) return;
            workspace.classList.toggle('conversation-expanded', isExpanded);
        }

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
            const queryInput = document.getElementById('query-input');
            const currentQuery = queryInput?.value?.trim() || '';

            const query = prompt('Enter query to save:', currentQuery);

            if (!query || !query.trim()) {
                return;
            }

            const trimmedQuery = query.trim();
            const queries = getSavedQueries();

            if (queries.some(q => q.text.toLowerCase() === trimmedQuery.toLowerCase())) {
                showNotification('Query already saved', 'info');
                return;
            }

            queries.unshift({
                id: Date.now().toString(),
                text: trimmedQuery,
                timestamp: Date.now()
            });

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

        function useSavedQuery(text, element) {
            const queryInput = document.getElementById('query-input');
            if (queryInput) {
                let nextText = (text || '').trim();
                if (!nextText && element) {
                    const fallback = element.querySelector('.saved-query-text')?.textContent || '';
                    nextText = fallback.trim();
                }
                if (!nextText) {
                    showNotification('Saved query is empty', 'warning');
                    return;
                }
                queryInput.value = nextText;
                queryInput.dispatchEvent(new Event('input', { bubbles: true }));
                queryInput.focus();
            }
        }

        function renderSavedQueriesList() {
            const list = document.getElementById('saved-queries-list');
            const countBadge = document.getElementById('saved-queries-count');
            const section = document.getElementById('saved-queries-section');

            if (!list) return;

            const isCollapsed = localStorage.getItem('sentinel-saved-queries-collapsed') !== 'false';
            if (isCollapsed) {
                section.classList.add('collapsed');
            } else {
                section.classList.remove('collapsed');
            }

            const queries = getSavedQueries();

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
                <div class="saved-query-item" data-action="useSavedQuery" data-text="${escapeHtml(q.text)}">
                    <svg class="saved-query-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
                    </svg>
                    <span class="saved-query-text" title="${escapeHtml(q.text)}">${escapeHtml(q.text)}</span>
                    <button class="saved-query-delete" data-action="deleteSavedQuery" data-query-id="${q.id}" title="Delete">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="icon-xs">
                            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                        </svg>
                    </button>
                </div>
            `).join('');
        }

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

            // Default to collapsed unless explicitly set to 'false'
            const isCollapsed = localStorage.getItem('sentinel-conversations-collapsed') !== 'false';
            section.classList.toggle('collapsed', isCollapsed);
            updateConversationExpansionState(!isCollapsed);

            const pinnedIds = getPinnedIds();
            const sorted = [...conversationHistory].sort((a, b) => b.timestamp - a.timestamp);

            const pinned = sorted.filter(s => pinnedIds.includes(s.id));
            const unpinned = sorted.filter(s => !pinnedIds.includes(s.id)).slice(0, 10);

            if (countBadge) countBadge.textContent = unpinned.length;

            if (pinnedSection && pinnedList) {
                if (pinned.length > 0) {
                    setHidden(pinnedSection, false);
                    pinnedList.innerHTML = pinned.map(session => renderConversationItem(session, true)).join('');
                } else {
                    setHidden(pinnedSection, true);
                }
            }

            if (unpinned.length === 0 && pinned.length === 0) {
                list.innerHTML = '<div class="conversation-empty">No conversations yet</div>';
                return;
            }

            list.innerHTML = unpinned.map(session => renderConversationItem(session, false)).join('');
        }

        function renderConversationItem(session, isPinned) {
            const pinnedClass = isPinned ? 'pinned' : '';
            const activeClass = session.id === currentSessionId ? 'active' : '';
            const pinIcon = isPinned
                ? `<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z"/></svg>`
                : `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 17v5"/><path d="M9 9V4h6v5"/><path d="M5 12h14"/><path d="M6 12a3 3 0 0 0 3-3"/><path d="M18 12a3 3 0 0 1-3-3"/></svg>`;

            const title = session.title || 'Untitled';
            const displayTitle = title.length > 50 ? title.substring(0, 50) + '...' : title;
            const messages = session.messages || [];
            const lastAssistant = [...messages].reverse().find(m => m.role === 'assistant');
            const lastUser = [...messages].reverse().find(m => m.role === 'user');
            const previewRaw = (lastAssistant?.content || lastUser?.content || '').replace(/\s+/g, ' ').trim();
            const previewText = previewRaw.length > 120 ? previewRaw.substring(0, 117) + '...' : previewRaw;
            const messageCount = session.messageCount ?? messages.length ?? 0;
            const timeAgo = formatTimeAgo(session.timestamp);

            return `
                <div class="conversation-item ${activeClass} ${pinnedClass}"
                     data-action="loadSession" data-session-id="${session.id}"
                     role="listitem" tabindex="0">
                    <div class="conversation-item-row">
                        <div class="conversation-item-title">${escapeHtml(displayTitle)}</div>
                        <div class="conversation-item-pin" data-action="togglePin" data-session-id="${session.id}" title="${isPinned ? 'Unpin' : 'Pin'}">
                            ${pinIcon}
                        </div>
                    </div>
                    ${previewText ? `<div class="conversation-item-preview">${escapeHtml(previewText)}</div>` : ''}
                    <div class="conversation-item-meta">
                        <span>${escapeHtml(timeAgo)}</span>
                        <span>${messageCount} msg${messageCount === 1 ? '' : 's'}</span>
                    </div>
                </div>
            `;
        }

        function selectCollection(type) {
            document.querySelectorAll('.collection-item').forEach(item => {
                item.classList.remove('active');
            });
            event.currentTarget.classList.add('active');
            console.log('Selected collection:', type);
        }

        function switchTab(tabName) {
            console.log('Tab switch requested:', tabName);
        }

        function toggleSidebar() {
            const sidebar = document.getElementById('sidebar');
            if (sidebar) {
                const isOpening = !sidebar.classList.contains('open');
                sidebar.classList.toggle('open');
                setSettingsToggleState(sidebar.classList.contains('open'));

                if (isOpening) {
                    const currentTheme = document.documentElement.getAttribute('data-theme') || 'dark';
                    document.getElementById('theme-light')?.classList.toggle('active', currentTheme === 'light');
                    document.getElementById('theme-dark')?.classList.toggle('active', currentTheme === 'dark');
                }
            } else {
                console.error('Sidebar element not found!');
            }
        }

        function closeSidebar() {
            const sidebar = document.getElementById('sidebar');
            if (sidebar) {
                sidebar.classList.remove('open');
                setSettingsToggleState(false);
            }
        }

        function setSettingsToggleState(isOpen) {
            document.querySelectorAll('[data-action="toggleSidebar"]').forEach(el => {
                el.classList.toggle('active', isOpen);
            });
        }

        function setTheme(theme) {
            document.documentElement.setAttribute('data-theme', theme);
            localStorage.setItem('sentinel-theme', theme);
            localStorage.setItem('sentinel-display-mode', theme);

            document.getElementById('theme-light')?.classList.toggle('active', theme === 'light');
            document.getElementById('theme-dark')?.classList.toggle('active', theme === 'dark');
        }

        function initTheme() {
            const savedTheme = localStorage.getItem('sentinel-theme') || 'dark';
            setTheme(savedTheme);
        }

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
            const hydeToggle = document.getElementById('hyde-toggle');
            const graphragToggle = document.getElementById('graphrag-toggle');
            const rerankToggle = document.getElementById('rerank-toggle');

            const debugToggle = document.getElementById('debug-toggle');
            const showReasoningToggle = document.getElementById('show-reasoning');
            const autoScrollToggle = document.getElementById('auto-scroll');
            const saveHistoryToggle = document.getElementById('save-history');

            loadSettings();

            if (hydeToggle) hydeToggle.checked = appSettings.hyde;
            if (graphragToggle) graphragToggle.checked = appSettings.graphrag;
            if (rerankToggle) rerankToggle.checked = appSettings.reranking;
            if (debugToggle) debugToggle.checked = appSettings.debug;
            if (showReasoningToggle) showReasoningToggle.checked = appSettings.showReasoning;
            if (autoScrollToggle) autoScrollToggle.checked = appSettings.autoScroll;
            if (saveHistoryToggle) saveHistoryToggle.checked = appSettings.saveHistory;

            const allToggles = [hydeToggle, graphragToggle, rerankToggle, debugToggle,
                               showReasoningToggle, autoScrollToggle, saveHistoryToggle];
            allToggles.forEach(toggle => {
                if (toggle) {
                    toggle.addEventListener('change', () => {
                        markSettingsUnsaved();
                    });
                }
            });

            const topKSlider = document.getElementById('top-k-slider');
            const simSlider = document.getElementById('similarity-slider');
            if (topKSlider) topKSlider.addEventListener('input', markSettingsUnsaved);
            if (simSlider) simSlider.addEventListener('input', markSettingsUnsaved);
        }

        function initInfoTooltips() {
            document.querySelectorAll('.info-tooltip').forEach(tooltip => {
                const content = tooltip.querySelector('.info-tooltip-content');
                if (!content) return;

                const handleEnter = () => positionInfoTooltip(tooltip, content);
                const handleLeave = () => resetInfoTooltip(content);

                tooltip.addEventListener('mouseenter', handleEnter);
                tooltip.addEventListener('focusin', handleEnter);
                tooltip.addEventListener('mouseleave', handleLeave);
                tooltip.addEventListener('focusout', handleLeave);
            });
        }

        function positionInfoTooltip(tooltip, content) {
            const rect = tooltip.getBoundingClientRect();
            const contentRect = content.getBoundingClientRect();
            const gap = 10;
            const margin = 12;

            let top = rect.top - contentRect.height - gap;
            let placement = 'above';
            if (top < margin) {
                top = rect.bottom + gap;
                placement = 'below';
            }

            let left = rect.left + (rect.width / 2) - (contentRect.width / 2);
            const maxLeft = Math.max(margin, window.innerWidth - contentRect.width - margin);
            left = Math.min(Math.max(left, margin), maxLeft);

            const arrowPadding = 16;
            const arrowLeft = Math.min(
                Math.max(rect.left + (rect.width / 2) - left, arrowPadding),
                contentRect.width - arrowPadding
            );

            content.style.position = 'fixed';
            content.style.left = `${Math.round(left)}px`;
            content.style.top = `${Math.round(top)}px`;
            content.style.right = 'auto';
            content.style.bottom = 'auto';
            content.style.setProperty('--tooltip-arrow-left', `${Math.round(arrowLeft)}px`);
            content.dataset.placement = placement;
        }

        function resetInfoTooltip(content) {
            content.style.position = '';
            content.style.left = '';
            content.style.top = '';
            content.style.right = '';
            content.style.bottom = '';
            content.style.removeProperty('--tooltip-arrow-left');
            delete content.dataset.placement;
        }

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

                    if (appSettings.debug) {
                        document.body.classList.add('debug-mode');
                    }
                } catch (e) {
                    console.warn('Failed to load settings:', e);
                }
            }
        }
        function showSettingToast(settingName, enabled) {
            const toast = document.createElement('div');
            toast.className = 'setting-toast';
            toast.innerHTML = `
                <span class="setting-toast-icon ${enabled ? 'setting-toast-icon--on' : 'setting-toast-icon--off'}">${enabled ? 'OK' : 'OFF'}</span>
                <span>${settingName} ${enabled ? 'enabled' : 'disabled'}</span>
            `;
            document.body.appendChild(toast);

            requestAnimationFrame(() => {
                toast.classList.add('show');
            });

            setTimeout(() => {
                toast.classList.remove('show');
                setTimeout(() => toast.remove(), 200);
            }, 1500);
        }

        function saveSettingsWithConfirmation() {
            const hydeToggle = document.getElementById('hyde-toggle');
            const graphragToggle = document.getElementById('graphrag-toggle');
            const rerankToggle = document.getElementById('rerank-toggle');
            const debugToggle = document.getElementById('debug-toggle');
            const showReasoningToggle = document.getElementById('show-reasoning');
            const autoScrollToggle = document.getElementById('auto-scroll');
            const saveHistoryToggle = document.getElementById('save-history');
            const topKSlider = document.getElementById('top-k-slider');
            const simSlider = document.getElementById('similarity-slider');

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
                    setHidden(el, !showReasoningToggle.checked);
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

            localStorage.setItem('sentinel-settings', JSON.stringify(appSettings));

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

            const toast = document.createElement('div');
            toast.className = 'setting-toast';
            toast.innerHTML = `
                <span class="setting-toast-icon setting-toast-icon--on">OK</span>
                <span>Settings saved successfully</span>
            `;
            document.body.appendChild(toast);

            requestAnimationFrame(() => {
                toast.classList.add('show');
            });

            setTimeout(() => {
                toast.classList.remove('show');
                setTimeout(() => toast.remove(), 200);
            }, 2000);
        }

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

        document.addEventListener('DOMContentLoaded', () => {
            initTheme();
            initSettingsSliders();
            initSettingsToggles();
            initInfoTooltips();
        });

        document.addEventListener('click', (e) => {
            const sidebar = document.getElementById('sidebar');

            const clickedOnToggle = Boolean(e.target.closest('[data-action="toggleSidebar"], .sidebar-toggle'));

            if (sidebar && sidebar.classList.contains('open') &&
                !sidebar.contains(e.target) &&
                !clickedOnToggle) {
                closeSidebar();
            }
        });

        function initFileUpload() {
            const fileInput = document.getElementById('file-input');
            const uploadZone = document.getElementById('upload-zone');

            if (!fileInput || !uploadZone) return;

            uploadZone.addEventListener('dragover', (e) => {
                e.preventDefault();
                uploadZone.classList.add('upload-zone--active');
            });

            uploadZone.addEventListener('dragleave', () => {
                uploadZone.classList.remove('upload-zone--active');
            });

            uploadZone.addEventListener('drop', (e) => {
                e.preventDefault();
                uploadZone.classList.remove('upload-zone--active');
                if (e.dataTransfer.files.length) {
                    fileInput.files = e.dataTransfer.files;
                    handleBatchUpload(e.dataTransfer.files);
                }
            });

            fileInput.addEventListener('change', (e) => {
                if (e.target.files.length) handleBatchUpload(e.target.files);
            });
        }

        let uploadStatusTimer = null;
        function scheduleUploadStatusClear(statusEl, delayMs) {
            if (!statusEl) return;
            if (uploadStatusTimer) {
                clearTimeout(uploadStatusTimer);
            }
            uploadStatusTimer = setTimeout(() => {
                statusEl.textContent = '';
            }, delayMs);
        }

        async function handleBatchUpload(files) {
            const statusEl = document.getElementById('upload-status');
            const progressContainer = document.getElementById('upload-progress-container');
            const filenameEl = document.getElementById('upload-filename');
            const percentEl = document.getElementById('upload-percent');
            const progressBar = document.getElementById('upload-progress-bar');
            const stageEl = document.getElementById('upload-stage');
            const total = files.length;
            let successCount = 0;
            const errors = [];

            if (statusEl) statusEl.textContent = '';
            setHidden(progressContainer, false);

            for (let i = 0; i < total; i++) {
                const file = files[i];
                setText(filenameEl, `${i + 1}/${total}: ${file.name}`);
                setText(percentEl, '0%');
                setProgressValue(progressBar, 0);
                setText(stageEl, 'Uploading...');

                const result = await uploadSingleFile(file);
                if (result.success) {
                    successCount++;
                } else {
                    errors.push({ file: file.name, error: result.error });
                }
            }

            setHidden(progressContainer, true);

            if (successCount === total) {
                setUploadStatus(statusEl, 'success', `All ${total} files ingested`);
                scheduleUploadStatusClear(statusEl, 6000);
            } else if (successCount > 0) {
                const errorList = errors.map(e => `${e.file}: ${e.error}`);
                setUploadStatus(statusEl, 'warning', `${successCount}/${total} files ingested`, ['Failed:', ...errorList]);
                scheduleUploadStatusClear(statusEl, 9000);
            } else {
                const errorList = errors.map(e => `${e.file}: ${e.error}`);
                setUploadStatus(statusEl, 'error', 'Upload failed', errorList);
                scheduleUploadStatusClear(statusEl, 12000);
            }

            const fileInput = document.getElementById('file-input');
            if (fileInput) fileInput.value = '';
        }

        function uploadSingleFile(file) {
            return new Promise(async (resolve) => {
                const sector = sectorSelect.value;
                if (!authState.authenticated) {
                    showAuthModal('Sign in to upload.');
                    resolve({ success: false, error: 'Authentication required' });
                    return;
                }
                const formData = new FormData();
                formData.append('file', file);
                formData.append('dept', sector);

                const xhr = new XMLHttpRequest();

                xhr.upload.addEventListener('progress', (e) => {
                    if (e.lengthComputable) {
                        const percent = Math.round((e.loaded / e.total) * 100);
                        const percentEl = document.getElementById('upload-percent');
                        const progressBar = document.getElementById('upload-progress-bar');
                        setText(percentEl, `${percent}%`);
                        setProgressValue(progressBar, percent);

                        if (percent < 100) {
                            setText(document.getElementById('upload-stage'), 'Uploading...');
                        } else {
                            setText(document.getElementById('upload-stage'), 'Processing & vectorizing...');
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
                        if (xhr.status === 401) {
                            showAuthModal('Sign in to continue.');
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
                const csrfToken = await ensureCsrfToken();
                if (csrfToken) {
                    xhr.setRequestHeader('X-XSRF-TOKEN', csrfToken);
                }
                xhr.timeout = 300000;
                xhr.send(formData);
            });
        }

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
                setHidden(panel, true);
                return;
            }

            setHidden(panel, false);
            countEl.textContent = state.contextDocs.size;

            docsContainer.innerHTML = '';
            state.contextDocs.forEach((data, filename) => {
                const docEl = document.createElement('div');
                docEl.className = `context-doc ${data.active ? 'active' : ''}`;
                docEl.onclick = () => toggleContextDoc(filename);
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

        function getActiveContextFiles() {
            const activeFiles = [];
            state.contextDocs.forEach((data, filename) => {
                if (data.active) {
                    activeFiles.push(filename);
                }
            });
            return activeFiles;
        }

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
            state.messageIndex.clear();
        }

        function resetDashboardState() {
            if (currentSessionId && currentMessages && currentMessages.length > 0) {
                saveCurrentSession();
            }

            clearChat();

            clearInfoPanel();

            closeAllSources();

            state.contextDocs.clear();
            state.openSources = [];
            state.activeSource = null;
            state.currentFeedbackMsgId = null;

            currentSessionId = generateSessionId();
            currentMessages = [];

            const chatTitle = document.getElementById('chat-title-text');
            if (chatTitle) chatTitle.textContent = 'New Conversation';
        }

        function hideWelcome() {
            const welcome = document.getElementById('welcome-state');
            setHidden(welcome, true);
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
            recordMessage('user', text, [], [], null);
        }

        function appendLoadingIndicator(queryText = '') {
            const id = 'loading-' + Date.now();
            const div = document.createElement('div');
            div.className = 'message assistant';
            div.id = id;

            // Estimate complexity based on query characteristics
            const wordCount = queryText.split(/\s+/).length;
            const isComplex = wordCount > 10 ||
                /summary|detailed|comprehensive|all|compare|analyze/i.test(queryText);
            const estimatedTime = isComplex ? '30-120 seconds' : '5-15 seconds';

            // Progress stages for complex queries
            const stages = [
                'Scanning security protocols...',
                'Analyzing query intent...',
                'Searching document vectors...',
                'Retrieving relevant documents...',
                'Reranking by relevance...',
                'Synthesizing response...'
            ];

            div.innerHTML = `
            <div class="loading-indicator">
                <div class="loading-spinner"></div>
                <span class="loading-text" data-stages='${JSON.stringify(stages)}' data-stage-index="0">
                    ${stages[0]}
                </span>
                ${isComplex ? `<span class="loading-estimate">(Complex query: ~${estimatedTime})</span>` : ''}
                <div class="loading-progress-bar">
                    <div class="loading-progress-fill" style="width: 5%"></div>
                </div>
            </div>
        `;
            chatMessages.appendChild(div);

            // Animate through stages for complex queries
            if (isComplex) {
                let stageIndex = 0;
                const progressInterval = setInterval(() => {
                    stageIndex = (stageIndex + 1) % stages.length;
                    const textEl = div.querySelector('.loading-text');
                    const progressEl = div.querySelector('.loading-progress-fill');
                    if (textEl && document.getElementById(id)) {
                        textEl.textContent = stages[stageIndex];
                        if (progressEl) {
                            const progress = Math.min(95, 5 + (stageIndex + 1) * 15);
                            progressEl.style.width = progress + '%';
                        }
                    } else {
                        clearInterval(progressInterval);
                    }
                }, 5000);
                div.dataset.progressInterval = progressInterval;
            }

            if (appSettings.autoScroll) {
                chatMessages.scrollTop = chatMessages.scrollHeight;
            }
            return id;
        }

        function removeElement(id) {
            const el = document.getElementById(id);
            if (el) {
                // Clear any progress animation interval
                if (el.dataset.progressInterval) {
                    clearInterval(parseInt(el.dataset.progressInterval));
                }
                el.remove();
            }
        }

        function appendAssistantResponse(text, reasoningSteps = [], sources = [], traceId = null, metrics = null) {
            hideWelcome();
            const msgId = 'msg-' + Date.now();
            const div = document.createElement('div');
            div.className = 'message assistant';
            div.id = msgId;

            const processedText = processCitations(text);
            const bracketCitations = (text.match(/\[([^\]]+\.(pdf|txt|md))\]/gi) || []);
            const backtickCitations = (text.match(/`([^`]+\.(pdf|txt|md))`/gi) || []);
            const citationCount = bracketCitations.length + backtickCitations.length;
            const confidence = Math.floor(75 + Math.random() * 20);
            const confClass = confidence >= 85 ? 'high' : confidence >= 70 ? 'medium' : 'low';

            const totalDuration = reasoningSteps.reduce((sum, step) => sum + (step.durationMs || 0), 0);
            const hasRealTiming = reasoningSteps.some(s => s.durationMs !== undefined);

            let reasoningHtml = '';
            if (reasoningSteps.length > 0) {
                const safeTraceId = traceId ? escapeHtml(traceId) : '';
                const traceIdDisplay = traceId ? `<span class="trace-id" title="Trace ID for audit: ${safeTraceId}">[${safeTraceId}]</span>` : '';
                const timingDisplay = hasRealTiming ? `<span class="total-duration">${totalDuration}ms</span>` : '';
                reasoningHtml = `
                <div class="reasoning-accordion">
                    <button class="reasoning-toggle" data-action="toggleReasoning">
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

            div.innerHTML = `
            ${reasoningHtml}
            <div class="message-bubble">
                ${processedText}
            </div>
            <div class="message-actions">
                <button class="feedback-btn positive" data-action="handleFeedback" data-msg-id="${msgId}" data-type="positive" title="Helpful">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3zM7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/>
                    </svg>
                </button>
                <button class="feedback-btn negative" data-action="handleFeedback" data-msg-id="${msgId}" data-type="negative" title="Report Issue">
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


            const entities = extractEntities(text, true);

            const sourceFilenames = sources
                .map(s => typeof s === 'string' ? s : (s.filename || s.source || s.name))
                .filter(Boolean);
            lastQueryMeta = buildQueryMeta(lastQuery, metrics, reasoningSteps);
            recordMessage('assistant', text, sourceFilenames, entities, lastQueryMeta);
            state.messageIndex.set(msgId, {
                query: lastQuery || '',
                response: text || '',
                sources: sourceFilenames,
                meta: lastQueryMeta,
                reasoningSteps: reasoningSteps.length,
                metrics: metrics || {}
            });

            updateRightPanel(text, sources, confidence, metrics);
        }

        function normalizeFilename(filename) {
            return filename
                // Handle markdown link syntax: [text](url) -> extract just the filename
                .replace(/^\[([^\]]+)\]\([^)]*\)$/, '$1')
                .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
                .replace(/^filename:\s*/i, '')
                .replace(/^source:\s*/i, '')
                .replace(/^citation:\s*/i, '')
                .replace(/[\[\]()]/g, '')
                .trim();
        }

        function extractSourcesFromText(text) {
            const sources = new Set();

            const bracketMatches = text.match(/\[(?:(?:filename|source|citation):\s*)?([^\]]+\.(pdf|txt|md))\]/gi) || [];
            bracketMatches.forEach(m => {
                const filename = m.replace(/[\[\]]/g, '').replace(/^(filename|source|citation):\s*/i, '').trim();
                if (filename) sources.add(filename);
            });

            const backtickMatches = text.match(/`([^`]+\.(pdf|txt|md))`/gi) || [];
            backtickMatches.forEach(m => {
                const filename = m.replace(/`/g, '').trim();
                if (filename) sources.add(filename);
            });

            const boldMatches = text.match(/\*{1,2}([^*]+\.(pdf|txt|md))\*{1,2}/gi) || [];
            boldMatches.forEach(m => {
                const filename = m.replace(/\*/g, '').trim();
                if (filename) sources.add(filename);
            });

            const quotedMatches = text.match(/"([^"]+\.(pdf|txt|md))"/gi) || [];
            quotedMatches.forEach(m => {
                const filename = m.replace(/"/g, '').trim();
                if (filename) sources.add(filename);
            });

            return Array.from(sources).map(filename => ({ filename }));
        }

        function processCitations(text) {

            let cleanText = text.replace(/\[(?:(?:filename|source|citation):\s*)?([^\]]+\.(pdf|txt|md|docx|doc))\]/gi, '');

            cleanText = cleanText.replace(/\[filename\]\s*\(([^)]+\.(pdf|txt|md|docx|doc))\)/gi, '');

            cleanText = cleanText.replace(/\(([^)]+\.(pdf|txt|md|docx|doc))\)/gi, '');

            cleanText = cleanText.replace(/`([^`]+\.(pdf|txt|md|docx|doc))`/gi, '');

            cleanText = cleanText.replace(/\*{1,2}([^*]+\.(pdf|txt|md|docx|doc))\*{1,2}/gi, '');

            cleanText = cleanText.replace(/"([^"]+\.(pdf|txt|md|docx|doc))"/gi, '');

            cleanText = cleanText.replace(/\[filename\]/gi, '');

            cleanText = cleanText.replace(/\baccording to\s*,/gi, ',');
            cleanText = cleanText.replace(/\bas mentioned in\s*,/gi, ',');
            cleanText = cleanText.replace(/\bas described in\s*,/gi, ',');
            cleanText = cleanText.replace(/\bfrom the document\s*,/gi, ',');
            cleanText = cleanText.replace(/\bin the document\s*,/gi, ',');
            cleanText = cleanText.replace(/\bper\s*,/gi, ',');

            cleanText = cleanText.replace(/,\s*,/g, ',');
            cleanText = cleanText.replace(/\.\s*\./g, '.');
            cleanText = cleanText.replace(/,\s*\./g, '.');

            cleanText = cleanText.replace(/[ \t]{2,}/g, ' ');
            cleanText = cleanText.replace(/ *\n */g, '\n');
            cleanText = cleanText.replace(/\n{3,}/g, '\n\n');
            cleanText = cleanText.trim();

            return markdownToHtml(cleanText);
        }

        function markdownToHtml(text) {
            let html = text
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;')
                .replace(/'/g, '&#039;');

            html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
            html = html.replace(/__([^_]+)__/g, '<strong>$1</strong>');

            html = html.replace(/(?<![*\w])\*([^*]+)\*(?![*\w])/g, '<em>$1</em>');
            html = html.replace(/(?<![_\w])_([^_]+)_(?![_\w])/g, '<em>$1</em>');

            html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

            html = html.replace(/(\s)(\d+)\.\s+(<strong>|<em>)?([^.]+?)(<\/strong>|<\/em>)?:([^0-9]+?)(?=\s*\d+\.\s|$)/g,
                function(match, space, num, openTag, label, closeTag, content) {
                    openTag = openTag || '';
                    closeTag = closeTag || '';
                    return '\n' + num + '. ' + openTag + label + closeTag + ':' + content.trim();
                });

            html = html.replace(/([:;])\s+(\d+)\.\s+/g, '$1\n$2. ');
            html = html.replace(/(\.\s*)(\d+)\.\s+/g, '.\n$2. ');

            const lines = html.split('\n');
            let result = [];
            let inOrderedList = false;
            let inUnorderedList = false;

            const closeLists = () => {
                if (inOrderedList) {
                    result.push('</ol>');
                    inOrderedList = false;
                }
                if (inUnorderedList) {
                    result.push('</ul>');
                    inUnorderedList = false;
                }
            };

            const isTableSeparator = (line) =>
                /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line);

            const parseTableRow = (row) => {
                let trimmed = row.trim();
                if (trimmed.startsWith('|')) {
                    trimmed = trimmed.slice(1);
                }
                if (trimmed.endsWith('|')) {
                    trimmed = trimmed.slice(0, -1);
                }
                return trimmed.split('|').map((cell) => cell.trim());
            };

            for (let i = 0; i < lines.length; i++) {
                const line = lines[i].trim();
                const nextLine = i + 1 < lines.length ? lines[i + 1].trim() : '';

                if (line && line.includes('|') && isTableSeparator(nextLine)) {
                    closeLists();

                    const headers = parseTableRow(line);
                    const rows = [];
                    let rowIndex = i + 2;
                    for (; rowIndex < lines.length; rowIndex++) {
                        const rowLine = lines[rowIndex].trim();
                        if (!rowLine || !rowLine.includes('|')) {
                            break;
                        }
                        rows.push(parseTableRow(rowLine));
                    }

                    const columnCount = headers.length || (rows[0] ? rows[0].length : 0);
                    const headerCells = headers.map((cell) => `<th>${cell || ''}</th>`).join('');
                    let tableHtml = '<div class="response-table-wrap"><table class="response-table"><thead><tr>' +
                        headerCells +
                        '</tr></thead><tbody>';

                    rows.forEach((row) => {
                        const normalized = row.slice(0, columnCount);
                        while (normalized.length < columnCount) {
                            normalized.push('');
                        }
                        tableHtml += '<tr>' + normalized.map((cell) => `<td>${cell || ''}</td>`).join('') + '</tr>';
                    });

                    tableHtml += '</tbody></table></div>';
                    result.push(tableHtml);

                    i = rowIndex - 1;
                    continue;
                }

                const numberedMatch = line.match(/^(\d+)\.\s*(.*)$/);
                const bulletMatch = line.match(/^[\*\-]\s+(.+)$/);

                if (numberedMatch) {
                    const content = numberedMatch[2].trim();
                    if (content) {
                        if (inUnorderedList) {
                            result.push('</ul>');
                            inUnorderedList = false;
                        }
                        if (!inOrderedList) {
                            result.push('<ol class="response-list">');
                            inOrderedList = true;
                        }
                        result.push('<li>' + content + '</li>');
                    }
                } else if (bulletMatch) {
                    if (inOrderedList) {
                        result.push('</ol>');
                        inOrderedList = false;
                    }
                    if (!inUnorderedList) {
                        result.push('<ul class="response-list">');
                        inUnorderedList = true;
                    }
                    result.push('<li>' + bulletMatch[1] + '</li>');
                } else {
                    closeLists();
                    if (line) {
                        result.push('<p>' + line + '</p>');
                    }
                }
            }

            closeLists();

            html = result.join('\n');

            html = html.replace(/<p>\s*<\/p>/g, '');
            html = html.replace(/\n+/g, '\n');

            return html;
        }

        function toggleReasoning(btn) {
            btn.classList.toggle('expanded');
            const content = btn.nextElementSibling;
            content.classList.toggle('visible');
        }

        let lastQuery = '';
        let lastQueryMeta = {};
        let lastResponseText = '';
        const sourceSnippetCache = new Map();
        const sourceSnippetInFlight = new Map();

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
            const activeFiles = getActiveContextFiles();
            const params = new URLSearchParams({ q: query, dept: sector });
            activeFiles.forEach((file) => params.append('file', file));

            appendUserMessage(query);
            queryInput.value = '';

            // Detect if query is complex (use SSE for complex queries)
            const wordCount = query.split(/\s+/).length;
            const isComplex = wordCount > 8 ||
                /summary|detailed|comprehensive|all|compare|analyze|explain/i.test(query);

            if (isComplex && typeof EventSource !== 'undefined') {
                // Use SSE streaming for complex queries
                await executeQueryWithStreaming(query, sector, activeFiles, params);
                return;
            }

            const loadingId = appendLoadingIndicator(query);

            try {
                const startTime = Date.now();
                const response = await guardedFetch(`${API_BASE}/ask/enhanced?${params.toString()}`);
                const data = await response.json();
                const latency = Date.now() - startTime;

                removeElement(loadingId);

                console.log('Enhanced response:', { sources: data.sources, reasoning: data.reasoning?.length, answer: data.answer?.substring(0, 100) });

                const reasoningSteps = (data.reasoning || []).map(step => ({
                    type: mapStepType(step.type),
                    label: step.label,
                    detail: step.detail,
                    durationMs: step.durationMs
                }));

                let sources = (data.sources || []).map(s => {
                    if (typeof s === 'string') return { filename: s };
                    if (s && typeof s === 'object') return s;
                    return { filename: String(s || '') };
                }).filter(s => s.filename || s.source || s.name);
                console.log('Backend sources:', data.sources, '-> Mapped:', sources);

                if (sources.length === 0 && data.answer) {
                    sources = extractSourcesFromText(data.answer);
                    console.log('Fallback extracted sources:', sources);
                }

                const responseMetrics = { ...(data.metrics || {}), activeFileCount: activeFiles.length };
                appendAssistantResponse(data.answer, reasoningSteps, sources, data.traceId, responseMetrics);
                fetchSystemStatus();

            } catch (error) {
                removeElement(loadingId);
                if (error && error.code === 'auth') {
                    return;
                }
                try {
                    const fallbackResponse = await guardedFetch(`${API_BASE}/ask?${params.toString()}`);
                    const answer = await fallbackResponse.text();
                    const reasoningSteps = generateReasoningSteps(query, answer);
                    const sourceMatches = answer.match(/\[([^\]]+\.(pdf|txt|md))\]/gi) || [];
                    const sources = sourceMatches.map(m => ({ filename: m.replace(/[\[\]]/g, '') }));
                    appendAssistantResponse(answer, reasoningSteps, sources, null, { activeFileCount: activeFiles.length });
                } catch (fallbackError) {
                    appendAssistantResponse(`Error: ${error.message}`, [], [], null, null);
                }
            }
        }

        /**
         * Execute query with SSE streaming for real-time token output.
         * Minimal status indicator during pre-generation, then live token stream.
         */
        async function executeQueryWithStreaming(query, sector, activeFiles, params) {
            const loadingId = 'streaming-' + Date.now();
            const div = document.createElement('div');
            div.className = 'message assistant';
            div.id = loadingId;
            div.innerHTML = `
                <div class="streaming-container">
                    <div class="streaming-status-minimal">
                        <span class="status-dot"></span>
                        <span class="status-text">Connecting...</span>
                    </div>
                    <div class="streaming-response" style="display: none;"></div>
                </div>
            `;
            chatMessages.appendChild(div);
            if (appSettings.autoScroll) {
                chatMessages.scrollTop = chatMessages.scrollHeight;
            }

            const statusEl = div.querySelector('.streaming-status-minimal');
            const statusText = div.querySelector('.status-text');
            const responseEl = div.querySelector('.streaming-response');
            const reasoningSteps = [];
            let tokenBuffer = '';
            let isGenerating = false;

            try {
                const eventSource = new EventSource(`${API_BASE}/ask/stream?${params.toString()}`);

                eventSource.addEventListener('connected', (e) => {
                    statusText.textContent = 'Processing...';
                });

                eventSource.addEventListener('step', (e) => {
                    try {
                        const step = JSON.parse(e.data);

                        // Store for reasoning accordion (still collected but not shown step-by-step)
                        reasoningSteps.push({
                            type: mapStepType(step.type),
                            label: step.label,
                            detail: step.detail,
                            durationMs: 0
                        });

                        // Update minimal status based on phase
                        if (!isGenerating) {
                            if (step.type === 'vector_search') {
                                statusText.textContent = 'Searching documents...';
                            } else if (step.type === 'context_assembly') {
                                statusText.textContent = 'Building context...';
                            } else if (step.type === 'llm_generation') {
                                if (step.detail.includes('Generating')) {
                                    isGenerating = true;
                                    // Hide status, show response area
                                    statusEl.style.display = 'none';
                                    responseEl.style.display = 'block';
                                    responseEl.innerHTML = '<span class="streaming-cursor">▊</span>';
                                } else {
                                    statusText.textContent = 'Generating response...';
                                }
                            }
                        }

                        if (appSettings.autoScroll) {
                            chatMessages.scrollTop = chatMessages.scrollHeight;
                        }
                    } catch (err) {
                        console.error('Error parsing step:', err);
                    }
                });

                // Handle real-time token streaming
                eventSource.addEventListener('token', (e) => {
                    try {
                        const data = JSON.parse(e.data);
                        tokenBuffer += data.token;

                        // Ensure status is hidden and response is visible
                        if (statusEl.style.display !== 'none') {
                            statusEl.style.display = 'none';
                            responseEl.style.display = 'block';
                        }

                        // Convert markdown to HTML for display
                        let displayText = escapeHtml(tokenBuffer);
                        displayText = displayText.replace(/\n/g, '<br>');
                        displayText = displayText.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
                        displayText = displayText.replace(/\[([^\]]+)\]/g, '<span class="citation">[$1]</span>');

                        responseEl.innerHTML = displayText + '<span class="streaming-cursor">▊</span>';

                        if (appSettings.autoScroll) {
                            chatMessages.scrollTop = chatMessages.scrollHeight;
                        }
                    } catch (err) {
                        console.error('Error parsing token:', err);
                    }
                });

                eventSource.addEventListener('complete', (e) => {
                    eventSource.close();

                    try {
                        const result = JSON.parse(e.data);

                        // Remove streaming UI
                        removeElement(loadingId);

                        // Show final response with sources
                        const sources = (result.sources || []).map(s => ({ filename: s }));
                        appendAssistantResponse(
                            result.answer,
                            reasoningSteps,
                            sources,
                            null,
                            { activeFileCount: activeFiles.length, streamed: true }
                        );
                        fetchSystemStatus();
                    } catch (err) {
                        console.error('Error parsing complete:', err);
                        removeElement(loadingId);
                        appendAssistantResponse('Error processing response.', reasoningSteps, [], null, null);
                    }
                });

                eventSource.addEventListener('error', (e) => {
                    if (e.data) {
                        try {
                            const err = JSON.parse(e.data);
                            statusText.textContent = 'Error: ' + err.error;
                        } catch {
                            statusText.textContent = 'Connection error';
                        }
                    }
                    eventSource.close();

                    // Fall back to non-streaming after a delay
                    setTimeout(() => {
                        removeElement(loadingId);
                        appendAssistantResponse('Streaming failed. Please try again.', [], [], null, null);
                    }, 2000);
                });

                eventSource.onerror = () => {
                    eventSource.close();
                    removeElement(loadingId);
                    // Fall back to regular endpoint
                    executeQueryFallback(query, sector, activeFiles, params);
                };

            } catch (error) {
                console.error('SSE error:', error);
                removeElement(loadingId);
                executeQueryFallback(query, sector, activeFiles, params);
            }
        }

        async function executeQueryFallback(query, sector, activeFiles, params) {
            const loadingId = appendLoadingIndicator(query);
            try {
                const response = await guardedFetch(`${API_BASE}/ask/enhanced?${params.toString()}`);
                const data = await response.json();
                removeElement(loadingId);
                const reasoningSteps = (data.reasoning || []).map(step => ({
                    type: mapStepType(step.type),
                    label: step.label,
                    detail: step.detail,
                    durationMs: step.durationMs
                }));
                const sources = (data.sources || []).map(s => typeof s === 'string' ? { filename: s } : s);
                appendAssistantResponse(data.answer, reasoningSteps, sources, data.traceId, data.metrics);
            } catch (err) {
                removeElement(loadingId);
                appendAssistantResponse('Error: ' + err.message, [], [], null, null);
            }
        }

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
                    detail: 'Applied ANALYZE → VERIFY → CITE protocol'
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

        async function openSource(filename, switchTab = true) {
            if (switchTab) {
                switchRightTab('source');
            }

            if (!state.openSources.includes(filename)) {
                state.openSources.push(filename);
            }
            state.activeSource = filename;

            renderSourceTabs();

            setHidden(document.getElementById('source-empty'), true);
            const viewerContainer = document.getElementById('source-viewer-container');
            setHidden(viewerContainer, false);

            document.getElementById('source-filename').textContent = filename;
            document.getElementById('source-sector').textContent = sectorSelect.value;
            document.getElementById('source-type').textContent = filename.endsWith('.pdf') ? 'PDF Document' : 'Text Document';

            const viewer = document.getElementById('source-viewer');
            viewer.textContent = 'Loading document content...';

            try {
                const currentQuery = queryInput.value.trim() || document.querySelector('.message.user:last-child .message-bubble')?.innerText || '';
                const dept = sectorSelect?.value || '';
                const deptParam = dept ? `&dept=${encodeURIComponent(dept)}` : '';
                const res = await guardedFetch(`${API_BASE}/inspect?fileName=${encodeURIComponent(filename)}&query=${encodeURIComponent(currentQuery)}${deptParam}`);

                const data = await res.json();

                viewer.innerHTML = formatSourceContent(data.content, data.highlights);

            } catch (error) {
                if (error && error.code === 'auth') {
                    viewer.textContent = 'Authentication required.';
                    return;
                }
                viewer.textContent = 'Error loading document: ' + error.message;
            }

            document.querySelectorAll('.citation').forEach(c => {
                c.classList.toggle('active', c.textContent.trim().includes(filename));
            });
        }

        let currentHighlightIndex = 0;

        function formatSourceContent(content, highlights = []) {
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

            updateHighlightNav(highlightCount);

            return result.join('\n');
        }

        function updateHighlightNav(count) {
            const nav = document.getElementById('highlight-nav');
            const countEl = document.getElementById('highlight-count');
            const posEl = document.getElementById('highlight-position');

            if (count > 0) {
                setHidden(nav, false);
                countEl.textContent = count;
                currentHighlightIndex = 0;
                posEl.textContent = `1 of ${count}`;
                setTimeout(() => navigateHighlight('first'), 100);
            } else {
                setHidden(nav, true);
            }
        }

        function navigateHighlight(direction) {
            const highlights = document.querySelectorAll('.source-viewer .highlight');
            if (highlights.length === 0) return;

            if (direction === 'next') {
                currentHighlightIndex = (currentHighlightIndex + 1) % highlights.length;
            } else if (direction === 'prev') {
                currentHighlightIndex = (currentHighlightIndex - 1 + highlights.length) % highlights.length;
            } else if (direction === 'first') {
                currentHighlightIndex = 0;
            }

            const targetHighlight = highlights[currentHighlightIndex];
            if (targetHighlight) {
                targetHighlight.scrollIntoView({ behavior: 'smooth', block: 'center' });

                highlights.forEach(h => h.classList.remove('highlight-focus'));
                targetHighlight.classList.add('highlight-focus');

                document.getElementById('highlight-position').textContent =
                    `${currentHighlightIndex + 1} of ${highlights.length}`;
            }
        }

        function renderSourceTabs() {
            const tabsContainer = document.getElementById('source-tabs');

            if (state.openSources.length === 0) {
                setHidden(tabsContainer, true);
                return;
            }

            setHidden(tabsContainer, false);
            tabsContainer.innerHTML = '';

            state.openSources.forEach(filename => {
                const tab = document.createElement('div');
                tab.className = `source-tab ${filename === state.activeSource ? 'active' : ''}`;
                tab.innerHTML = `
                <span data-action="openSource" data-filename="${escapeHtml(filename)}">${filename}</span>
                <span class="close" data-action="closeSource" data-filename="${escapeHtml(filename)}">×</span>
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
                    setHidden(document.getElementById('source-empty'), false);
                    const vc = document.getElementById('source-viewer-container');
                    setHidden(vc, true);
                }
            }

            renderSourceTabs();
        }

        function closeAllSources() {
            state.openSources = [];
            state.activeSource = null;
            setHidden(document.getElementById('source-tabs'), true);
            setHidden(document.getElementById('source-empty'), false);
            const vc = document.getElementById('source-viewer-container');
            setHidden(vc, true);
        }

        function buildFeedbackMetadata(record) {
            if (!record) return null;
            const meta = record.meta || {};
            const metrics = record.metrics || {};
            return {
                routingDecision: meta.routingDecision || '',
                sourceDocuments: record.sources || [],
                similarityThreshold: appSettings.similarityThreshold,
                topK: appSettings.topK,
                signals: {
                    useHyde: appSettings.hyde,
                    useGraphRag: appSettings.graphrag,
                    useReranking: appSettings.reranking,
                    debug: appSettings.debug
                },
                responseTimeMs: meta.latencyMs || 0,
                reasoningSteps: record.reasoningSteps || 0,
                hallucinationScore: metrics.hallucinationScore ?? null
            };
        }

        async function sendFeedbackRequest(type, payload) {
            const token = await ensureCsrfToken();
            const response = await guardedFetch(`${API_BASE}/feedback/${type}`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    ...(token ? { 'X-XSRF-TOKEN': token } : {})
                },
                body: JSON.stringify(payload)
            });
            if (!response.ok) {
                throw new Error(`Feedback request failed (${response.status})`);
            }
            return response.json();
        }

        async function submitPositiveFeedback(msgId) {
            const record = state.messageIndex.get(msgId);
            if (!record) return;
            const payload = {
                messageId: msgId,
                query: record.query || '',
                response: record.response || '',
                ragMetadata: buildFeedbackMetadata(record)
            };
            return sendFeedbackRequest('positive', payload);
        }

        async function submitNegativeFeedback(msgId, category, comments) {
            const record = state.messageIndex.get(msgId);
            if (!record) return;
            const payload = {
                messageId: msgId,
                query: record.query || '',
                response: record.response || '',
                category,
                comments: comments || '',
                ragMetadata: buildFeedbackMetadata(record)
            };
            return sendFeedbackRequest('negative', payload);
        }

        async function handleFeedback(msgId, type) {
            const positiveBtn = document.querySelector(`#${msgId} .feedback-btn.positive`);
            const negativeBtn = document.querySelector(`#${msgId} .feedback-btn.negative`);

            if (type === 'positive') {
                try {
                    const result = await submitPositiveFeedback(msgId);
                    if (result && result.removed) {
                        if (positiveBtn) positiveBtn.classList.remove('active');
                    } else {
                        if (positiveBtn) positiveBtn.classList.add('active');
                        if (negativeBtn) negativeBtn.classList.remove('active');
                    }
                } catch (error) {
                    console.error(error);
                    alert('Unable to submit feedback. Please try again.');
                }
            } else {
                state.currentFeedbackMsgId = msgId;
                document.getElementById('feedback-modal').classList.add('open');
            }
        }

        function closeFeedbackModal() {
            document.getElementById('feedback-modal').classList.remove('open');
            state.currentFeedbackMsgId = null;

            document.querySelectorAll('.feedback-option').forEach(opt => {
                opt.classList.remove('selected');
            });
        }

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
            const msgId = state.currentFeedbackMsgId;
            if (!msgId) {
                closeFeedbackModal();
                return;
            }

            submitNegativeFeedback(msgId, feedbackType, '')
                .then(result => {
                    const btn = document.querySelector(`#${msgId} .feedback-btn.negative`);
                    const positiveBtn = document.querySelector(`#${msgId} .feedback-btn.positive`);
                    if (result && result.removed) {
                        if (btn) btn.classList.remove('active');
                    } else {
                        if (btn) btn.classList.add('active');
                        if (positiveBtn) positiveBtn.classList.remove('active');
                    }
                })
                .catch(error => {
                    console.error(error);
                    alert('Unable to submit feedback. Please try again.');
                })
                .finally(() => closeFeedbackModal());
        }

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        function getTimestamp() {
            return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        }
