/* ============================================
 * SENTINEL Admin Console
 * Authentication, navigation, and section logic
 * ============================================ */

(function () {
    'use strict';

    const API_BASE = window.location.origin + '/api';
    const REGULATED_EDITIONS = new Set(['MEDICAL', 'GOVERNMENT']);

    let currentEdition = '';
    let currentUser = null;
    let csrfTokenCache = '';
    let csrfTokenUnavailable = false;
    const sectionLoaded = {};

    // ── Utility Functions ──────────────────────────────────

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
        if (csrfTokenUnavailable) return '';
        try {
            const response = await fetch(`${API_BASE}/auth/csrf`, { credentials: 'same-origin' });
            if (response.ok) {
                const data = await response.json();
                if (data && data.token) {
                    csrfTokenCache = data.token;
                    return csrfTokenCache;
                }
            } else if (response.status === 404) {
                csrfTokenUnavailable = true;
            }
        } catch (_) {
            // CSRF endpoint unavailable
        }
        return '';
    }

    function getWorkspaceId() {
        return localStorage.getItem('workspaceId') || 'workspace_default';
    }

    async function guardedFetch(url, options = {}) {
        const workspaceId = getWorkspaceId();
        const mergedHeaders = { ...(options.headers || {}), 'X-Workspace-Id': workspaceId };
        const mergedOptions = { credentials: 'same-origin', ...options, headers: mergedHeaders };
        const response = await fetch(url, mergedOptions);
        if (response.status === 401) {
            window.location.href = 'index.html';
            throw Object.assign(new Error('auth'), { code: 'auth' });
        }
        return response;
    }

    function setHidden(el, hidden) {
        if (!el) return;
        el.classList.toggle('hidden', hidden);
    }

    function setText(el, text) {
        if (!el) return;
        el.textContent = text;
    }

    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    function isRegulatedEdition() {
        return REGULATED_EDITIONS.has(currentEdition);
    }

    function formatTimestamp(value) {
        if (!value) return '\u2014';
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return String(value);
        return date.toLocaleString();
    }

    // ── Toast Notifications ────────────────────────────────

    function showToast(message, type = 'info') {
        const container = document.getElementById('toast-container');
        if (!container) return;
        const toast = document.createElement('div');
        toast.className = `toast toast-${type}`;
        toast.textContent = message;
        container.appendChild(toast);
        setTimeout(() => {
            toast.style.opacity = '0';
            toast.style.transform = 'translateY(8px)';
            toast.style.transition = 'all 0.2s ease';
            setTimeout(() => toast.remove(), 200);
        }, 4000);
    }

    // ── Navigation ─────────────────────────────────────────

    function switchSection(sectionId) {
        document.querySelectorAll('.admin-section').forEach(el => el.classList.add('hidden'));
        document.querySelectorAll('.admin-nav-item').forEach(el => el.classList.remove('active'));

        const section = document.getElementById(`section-${sectionId}`);
        const navItem = document.querySelector(`.admin-nav-item[data-section="${sectionId}"]`);
        if (section) section.classList.remove('hidden');
        if (navItem) navItem.classList.add('active');

        setText(document.getElementById('admin-breadcrumb-section'), navItem?.textContent?.trim() || sectionId);

        if (!sectionLoaded[sectionId]) {
            sectionLoaded[sectionId] = true;
            loadSectionData(sectionId);
        }
    }

    function loadSectionData(sectionId) {
        switch (sectionId) {
            case 'overview': loadOverview(); break;
            case 'users': loadUsers(); break;
            case 'workspaces': loadWorkspaces(); break;
            case 'sectors': loadSectors(); initPipelineDefaultsUI(); break;
            case 'connectors': loadConnectors(); break;
            case 'reports': loadReports(); break;
            case 'platform': loadPlatform(); break;
        }
    }

    // ── Section: Overview ──────────────────────────────────

    async function loadOverview() {
        try {
            const response = await guardedFetch(`${API_BASE}/admin/dashboard`);
            if (!response.ok) throw new Error('Dashboard load failed');
            const data = await response.json();
            renderOverview(data);
        } catch (error) {
            if (error.code === 'auth') return;
            setText(document.getElementById('health-body'), 'Unable to load dashboard.');
        }
    }

    function renderOverview(data) {
        const health = data.health || {};
        const usage = data.usage || {};
        const docs = data.documents || {};
        const pending = data.pendingApprovals ?? 0;

        const healthBody = document.getElementById('health-body');
        if (healthBody) {
            const statusClass = health.ollamaReachable ? 'admin-badge-success' : 'admin-badge-error';
            const statusText = health.ollamaReachable ? 'HEALTHY' : 'DEGRADED';
            healthBody.innerHTML = `
                <div class="admin-stat">
                    <div class="admin-stat-value"><span class="admin-badge ${statusClass}">${statusText}</span></div>
                </div>
                <div class="admin-stat-row">
                    <span class="admin-stat-label">Ollama</span>
                    <span class="admin-badge ${health.ollamaReachable ? 'admin-badge-success' : 'admin-badge-error'}">${health.ollamaReachable ? 'Connected' : 'Unreachable'}</span>
                </div>
                <div class="admin-stat-row">
                    <span class="admin-stat-label">MongoDB</span>
                    <span class="admin-badge ${health.mongoReachable ? 'admin-badge-success' : 'admin-badge-error'}">${health.mongoReachable ? 'Connected' : 'Unreachable'}</span>
                </div>
                <div class="admin-stat-row">
                    <span class="admin-stat-label">Model</span>
                    <span>${escapeHtml(health.ollamaModel || 'N/A')}</span>
                </div>`;
        }

        const usageBody = document.getElementById('usage-body');
        if (usageBody) {
            usageBody.innerHTML = `
                <div class="admin-stat">
                    <div class="admin-stat-value">${usage.totalQueries ?? 0}</div>
                    <div class="admin-stat-label">Total Queries</div>
                </div>
                <div class="admin-stat-row">
                    <span class="admin-stat-label">Active Users</span>
                    <span>${usage.activeUsers ?? 0}</span>
                </div>
                <div class="admin-stat-row">
                    <span class="admin-stat-label">Ingestions</span>
                    <span>${usage.totalIngestions ?? 0}</span>
                </div>`;
        }

        const docsBody = document.getElementById('docs-body');
        if (docsBody) {
            docsBody.innerHTML = `
                <div class="admin-stat">
                    <div class="admin-stat-value">${docs.totalDocuments ?? 0}</div>
                    <div class="admin-stat-label">Documents</div>
                </div>
                <div class="admin-stat-row">
                    <span class="admin-stat-label">Chunks</span>
                    <span>${docs.totalChunks ?? 0}</span>
                </div>`;
        }

        const pendingBody = document.getElementById('pending-body');
        if (pendingBody) {
            pendingBody.innerHTML = `
                <div class="admin-stat">
                    <div class="admin-stat-value">${pending}</div>
                    <div class="admin-stat-label">Pending Approvals</div>
                </div>
                ${pending > 0 ? `<button class="btn btn-primary btn-sm" data-action="goToUsers" style="margin-top:12px;">Review Users</button>` : ''}`;
        }
    }

    // ── Section: Users ─────────────────────────────────────

    async function loadUsers() {
        try {
            const response = await guardedFetch(`${API_BASE}/admin/users`);
            if (!response.ok) throw new Error('Users load failed');
            const users = await response.json();
            renderUsers(users);
        } catch (error) {
            if (error.code === 'auth') return;
            const tbody = document.getElementById('users-tbody');
            if (tbody) tbody.innerHTML = '<tr><td colspan="7">Unable to load users.</td></tr>';
        }
    }

    function renderUsers(users) {
        const tbody = document.getElementById('users-tbody');
        if (!tbody) return;
        if (!Array.isArray(users) || users.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" class="admin-loading" style="padding:24px;">No users found.</td></tr>';
            return;
        }
        tbody.innerHTML = users.map(user => {
            const roles = (user.roles || []).map(r => {
                const cls = r === 'ADMIN' ? 'role-tag role-tag-admin' : 'role-tag';
                return `<span class="${cls}">${escapeHtml(r)}</span>`;
            }).join(' ');
            const sectors = (user.allowedSectors || []).map(s =>
                `<span class="sector-tag">${escapeHtml(s)}</span>`
            ).join(' ');
            const statusBadge = user.active
                ? '<span class="admin-badge admin-badge-success">Active</span>'
                : (user.approved === false
                    ? '<span class="admin-badge admin-badge-warning">Pending</span>'
                    : '<span class="admin-badge admin-badge-neutral">Inactive</span>');
            const lastLogin = formatTimestamp(user.lastLogin);

            let actions = '';
            if (user.approved === false) {
                actions += `<button class="btn btn-primary btn-xs" data-action="approveUser" data-user-id="${escapeHtml(user.id)}">Approve</button> `;
            }
            if (!user.active) {
                actions += `<button class="btn btn-secondary btn-xs" data-action="activateUser" data-user-id="${escapeHtml(user.id)}">Activate</button>`;
            } else {
                actions += `<button class="btn btn-secondary btn-xs" data-action="deactivateUser" data-user-id="${escapeHtml(user.id)}">Deactivate</button>`;
            }

            return `<tr>
                <td><strong>${escapeHtml(user.displayName || user.id)}</strong></td>
                <td><div class="user-role-tags">${roles || '<span class="admin-badge admin-badge-neutral">None</span>'}</div></td>
                <td>${escapeHtml(user.clearance || 'UNCLASSIFIED')}</td>
                <td><div class="sector-tags">${sectors || '<span class="sector-tag">All</span>'}</div></td>
                <td>${statusBadge}</td>
                <td>${escapeHtml(lastLogin)}</td>
                <td><div class="user-actions">${actions}</div></td>
            </tr>`;
        }).join('');
    }

    async function approveUser(userId) {
        await userAction(`/admin/users/${userId}/approve`, 'POST', 'User approved.');
    }

    async function activateUser(userId) {
        await userAction(`/admin/users/${userId}/activate`, 'POST', 'User activated.');
    }

    async function deactivateUser(userId) {
        await userAction(`/admin/users/${userId}/deactivate`, 'POST', 'User deactivated.');
    }

    async function userAction(path, method, successMsg) {
        const headers = { 'Content-Type': 'application/json' };
        const csrfToken = await ensureCsrfToken();
        if (csrfToken) headers['X-XSRF-TOKEN'] = csrfToken;
        try {
            const response = await guardedFetch(`${API_BASE}${path}`, { method, headers });
            if (!response.ok) {
                const err = await response.json().catch(() => ({}));
                throw new Error(err.message || 'Action failed');
            }
            showToast(successMsg, 'success');
            sectionLoaded['users'] = false;
            loadUsers();
        } catch (error) {
            if (error.code === 'auth') return;
            showToast(`Action failed: ${error.message}`, 'error');
        }
    }

    // ── Section: Workspaces ────────────────────────────────

    async function loadWorkspaces() {
        const listEl = document.getElementById('workspace-list');
        if (!listEl) return;
        listEl.innerHTML = '<div class="admin-loading">Loading...</div>';
        try {
            const response = await guardedFetch(`${API_BASE}/workspaces`);
            if (!response.ok) throw new Error('Workspaces load failed');
            const workspaces = await response.json();
            renderWorkspaces(workspaces);
        } catch (error) {
            if (error.code === 'auth') return;
            listEl.innerHTML = '<div class="admin-text-muted">Unable to load workspaces.</div>';
        }
    }

    function renderWorkspaces(workspaces) {
        const listEl = document.getElementById('workspace-list');
        if (!listEl) return;
        if (!Array.isArray(workspaces) || workspaces.length === 0) {
            listEl.innerHTML = '<div class="admin-empty"><div class="admin-empty-text">No workspaces found.</div></div>';
            return;
        }
        listEl.innerHTML = workspaces.map(ws => {
            const quota = ws.quota || {};
            return `
                <div class="workspace-card">
                    <div class="workspace-card-header">
                        <div class="workspace-card-name">${escapeHtml(ws.name || ws.id)}</div>
                        <span class="admin-badge admin-badge-info">${escapeHtml(ws.id)}</span>
                    </div>
                    <div class="workspace-card-meta">
                        ${ws.description ? `<div>${escapeHtml(ws.description)}</div>` : ''}
                        <div>Members: ${ws.memberCount ?? 'N/A'}</div>
                        <div>Doc Limit: ${quota.maxDocuments || 'Unlimited'} | Query Limit: ${quota.maxQueriesPerDay || 'Unlimited'}/day</div>
                        <div>Storage Limit: ${quota.maxStorageMb ? quota.maxStorageMb + ' MB' : 'Unlimited'}</div>
                    </div>
                </div>`;
        }).join('');
    }

    function showCreateWorkspace() {
        setHidden(document.getElementById('create-workspace-modal'), false);
    }

    function closeCreateWorkspace() {
        setHidden(document.getElementById('create-workspace-modal'), true);
    }

    async function createWorkspace() {
        const name = document.getElementById('ws-name')?.value?.trim();
        const description = document.getElementById('ws-description')?.value?.trim();
        if (!name) {
            showToast('Workspace name is required.', 'warning');
            return;
        }

        const headers = { 'Content-Type': 'application/json' };
        const csrfToken = await ensureCsrfToken();
        if (csrfToken) headers['X-XSRF-TOKEN'] = csrfToken;

        try {
            const response = await guardedFetch(`${API_BASE}/workspaces`, {
                method: 'POST',
                headers,
                body: JSON.stringify({ name, description })
            });
            if (!response.ok) {
                const err = await response.json().catch(() => ({}));
                throw new Error(err.error || err.message || 'Workspace creation failed');
            }
            showToast('Workspace created.', 'success');
            closeCreateWorkspace();
            document.getElementById('ws-name').value = '';
            document.getElementById('ws-description').value = '';
            sectionLoaded['workspaces'] = false;
            loadWorkspaces();
        } catch (error) {
            if (error.code === 'auth') return;
            showToast(`Workspace creation failed: ${error.message}`, 'error');
        }
    }

    // ── Section: Sectors & Pipelines ───────────────────────

    async function loadSectors() {
        try {
            const response = await guardedFetch(`${API_BASE}/config/sectors`);
            if (!response.ok) throw new Error('Sectors load failed');
            const sectors = await response.json();
            renderSectors(sectors);
        } catch (error) {
            if (error.code === 'auth') return;
            setText(document.getElementById('sectors-body'), 'Unable to load sectors.');
        }
    }

    function initPipelineDefaultsUI() {
        const container = document.getElementById('pipeline-config-list');
        if (!container) return;

        // Avoid overwriting any existing content that may be rendered elsewhere
        if (container.children.length > 0) return;

        const emptyState = document.createElement('div');
        emptyState.className = 'empty-state';
        emptyState.textContent = 'No pipeline defaults are configured.';
        container.appendChild(emptyState);
    }

    function renderSectors(sectors) {
        const body = document.getElementById('sectors-body');
        if (!body) return;
        if (!Array.isArray(sectors) || sectors.length === 0) {
            body.innerHTML = '<div class="admin-text-muted">No sectors configured.</div>';
            return;
        }
        body.innerHTML = sectors.map(s => `
            <div class="sector-item">
                <div>
                    <div class="sector-item-name">${escapeHtml(s.label || s.id)}</div>
                    <div class="admin-text-muted" style="margin:0;">${escapeHtml(s.description || '')}</div>
                </div>
                <div class="sector-item-clearance">${escapeHtml(s.id)}</div>
            </div>`
        ).join('');
    }

    // ── Section: Connectors ────────────────────────────────

    async function loadConnectors() {
        await refreshConnectorStatus();
        await refreshConnectorCatalog();
    }

    function resolveConnectorId(name) {
        if (!name) return '';
        const normalized = String(name).toLowerCase().trim();
        if (normalized.includes('sharepoint')) return 'sharepoint';
        if (normalized.includes('confluence')) return 'confluence';
        if (normalized === 's3') return 's3';
        return normalized.replace(/[^a-z0-9_-]/g, '');
    }

    function formatConnectorStatus(status) {
        if (!status) return { label: 'Unknown', stateClass: 'disabled', title: '' };
        let label = status.enabled ? 'Enabled' : 'Disabled';
        let stateClass = status.enabled ? 'enabled' : 'disabled';
        let title = '';
        if (status.lastResult) {
            const message = status.lastResult.message || '';
            if (status.lastResult.success === false) {
                label = 'Error';
                stateClass = 'error';
                title = message || 'Sync failed';
            } else if (status.lastResult.success === true) {
                label = status.lastResult.loaded > 0 ? 'Synced' : 'Ready';
                stateClass = status.enabled ? 'enabled' : 'disabled';
                title = message || 'Sync complete';
            }
        }
        return { label, stateClass, title };
    }

    async function refreshConnectorStatus() {
        const listEl = document.getElementById('admin-connector-list');
        if (!listEl) return;
        listEl.innerHTML = '<div class="admin-loading">Loading...</div>';
        try {
            const response = await guardedFetch(`${API_BASE}/admin/connectors/status`);
            if (!response.ok) throw new Error('Connector status failed');
            const data = await response.json();
            if (!Array.isArray(data) || data.length === 0) {
                listEl.innerHTML = '<div class="admin-text-muted">No connectors configured.</div>';
                return;
            }
            listEl.innerHTML = data.map(status => {
                const formatted = formatConnectorStatus(status);
                const badgeClass = formatted.stateClass === 'enabled' ? 'admin-badge-success'
                    : formatted.stateClass === 'error' ? 'admin-badge-error'
                    : 'admin-badge-neutral';
                return `
                    <div class="connector-item">
                        <span class="connector-item-name">${escapeHtml(status.name || 'Unknown')}</span>
                        <span class="admin-badge ${badgeClass}" title="${escapeHtml(formatted.title)}">${escapeHtml(formatted.label)}</span>
                    </div>`;
            }).join('');
        } catch (error) {
            if (error.code === 'auth') return;
            listEl.innerHTML = '<div class="admin-text-muted">Unable to load connector status.</div>';
        }
    }

    async function syncConnectors() {
        if (isRegulatedEdition()) {
            showToast('Connector sync disabled for regulated editions.', 'warning');
            return;
        }
        const confirmed = confirm('Run connector sync now? This will ingest external content.');
        if (!confirmed) return;

        const headers = { 'Content-Type': 'application/json' };
        const csrfToken = await ensureCsrfToken();
        if (csrfToken) headers['X-XSRF-TOKEN'] = csrfToken;

        try {
            const response = await guardedFetch(`${API_BASE}/admin/connectors/sync`, {
                method: 'POST',
                headers
            });
            if (!response.ok) {
                const err = await response.json().catch(() => ({}));
                throw new Error(err.message || 'Connector sync failed');
            }
            showToast('Connector sync complete.', 'success');
            refreshConnectorStatus();
        } catch (error) {
            if (error.code === 'auth') return;
            showToast(`Connector sync failed: ${error.message}`, 'error');
        }
    }

    async function refreshConnectorCatalog() {
        const catalogEl = document.getElementById('admin-connector-catalog');
        const hintEl = document.getElementById('admin-connector-hint');
        if (!catalogEl) return;
        catalogEl.innerHTML = '<div class="admin-loading">Loading...</div>';
        try {
            const response = await guardedFetch(`${API_BASE}/admin/connectors/catalog`);
            if (!response.ok) throw new Error('Catalog load failed');
            const catalog = await response.json();
            renderConnectorCatalog(catalog);
        } catch (error) {
            if (error.code === 'auth') return;
            catalogEl.innerHTML = '<div class="admin-text-muted">Connector catalog unavailable.</div>';
        }
        if (hintEl) {
            hintEl.textContent = isRegulatedEdition()
                ? 'Connectors are disabled for regulated editions by policy.'
                : 'Use the catalog to plan integrations before enabling connectors.';
        }
    }

    function renderConnectorCatalog(entries) {
        const catalogEl = document.getElementById('admin-connector-catalog');
        if (!catalogEl) return;
        if (!Array.isArray(entries) || entries.length === 0) {
            catalogEl.innerHTML = '<div class="admin-text-muted">No connectors available.</div>';
            return;
        }
        catalogEl.innerHTML = entries.map(entry => {
            const formatted = formatConnectorStatus({
                enabled: entry.enabled,
                lastSync: entry.lastSync,
                lastResult: entry.lastResult
            });
            const badgeClass = formatted.stateClass === 'enabled' ? 'admin-badge-success'
                : formatted.stateClass === 'error' ? 'admin-badge-error'
                : 'admin-badge-neutral';
            const connectorKey = resolveConnectorId(entry.id);
            const detailsId = `connector-details-${connectorKey}`;
            const configKeys = Array.isArray(entry.configKeys) ? entry.configKeys.join(', ') : 'No config keys';
            const lastSync = entry.lastSync ? new Date(entry.lastSync).toLocaleString() : 'Never';
            const regulatedTag = entry.supportsRegulated ? '<span class="admin-badge admin-badge-info" style="margin-left:4px;">Regulated</span>' : '';

            return `
                <div class="connector-item" style="flex-direction:column;align-items:stretch;">
                    <div style="display:flex;justify-content:space-between;align-items:center;">
                        <div>
                            <span class="connector-item-name">${escapeHtml(entry.name || entry.id)}</span>
                            <span class="admin-badge ${badgeClass}" style="margin-left:8px;">${escapeHtml(formatted.label)}</span>
                            ${regulatedTag}
                        </div>
                        <button class="btn btn-secondary btn-xs" data-action="toggleConnectorDetails" data-connector-id="${escapeHtml(connectorKey)}">Details</button>
                    </div>
                    ${entry.description ? `<div class="admin-text-muted" style="margin:6px 0 0;">${escapeHtml(entry.description)}</div>` : ''}
                    <div class="hidden" id="${escapeHtml(detailsId)}" style="margin-top:8px;padding-top:8px;border-top:1px solid var(--border-subtle);font-size:12px;color:var(--text-tertiary);">
                        <div>Category: ${escapeHtml(entry.category || 'N/A')}</div>
                        <div>Last Sync: ${escapeHtml(lastSync)}</div>
                        <div>Config Keys: ${escapeHtml(configKeys)}</div>
                    </div>
                </div>`;
        }).join('');
    }

    function toggleConnectorDetails(connectorId) {
        if (!connectorId) return;
        const details = document.getElementById(`connector-details-${connectorId}`);
        if (details) details.classList.toggle('hidden');
    }

    // ── Section: Reports & Audit ───────────────────────────

    function loadReports() {
        refreshReportSchedules();
        refreshReportExports();
    }

    async function runExecutiveReport() {
        const days = parseInt(document.getElementById('report-exec-days')?.value, 10) || 30;
        try {
            const response = await guardedFetch(`${API_BASE}/admin/reports/executive?days=${days}`);
            if (!response.ok) {
                const err = await response.json().catch(() => ({}));
                throw new Error(err.error || err.message || 'Report failed');
            }
            const report = await response.json();
            const outputEl = document.getElementById('report-exec-output');
            if (outputEl) {
                outputEl.textContent = JSON.stringify(report, null, 2);
                outputEl.classList.add('visible');
            }
            showToast('Executive report generated.', 'success');
        } catch (error) {
            if (error.code === 'auth') return;
            showToast(`Executive report failed: ${error.message}`, 'error');
        }
    }

    async function runSlaReport() {
        const days = parseInt(document.getElementById('report-sla-days')?.value, 10) || 7;
        try {
            const response = await guardedFetch(`${API_BASE}/admin/reports/sla?days=${days}`);
            if (!response.ok) {
                const err = await response.json().catch(() => ({}));
                throw new Error(err.error || err.message || 'Report failed');
            }
            const report = await response.json();
            const outputEl = document.getElementById('report-sla-output');
            if (outputEl) {
                outputEl.textContent = JSON.stringify(report, null, 2);
                outputEl.classList.add('visible');
            }
            showToast('SLA report generated.', 'success');
        } catch (error) {
            if (error.code === 'auth') return;
            showToast(`SLA report failed: ${error.message}`, 'error');
        }
    }

    async function runAuditExport() {
        const days = parseInt(document.getElementById('report-audit-days')?.value, 10) || 30;
        const limit = parseInt(document.getElementById('report-audit-limit')?.value, 10) || 1000;
        const format = document.getElementById('report-audit-format')?.value || 'json';
        const type = document.getElementById('report-audit-type')?.value || 'standard';
        const endpoint = type === 'hipaa'
            ? `${API_BASE}/admin/reports/hipaa/export`
            : `${API_BASE}/admin/reports/audit/export`;

        const params = new URLSearchParams();
        if (days > 0) params.set('days', String(days));
        if (limit > 0) params.set('limit', String(limit));
        params.set('format', format);

        try {
            const response = await guardedFetch(`${endpoint}?${params.toString()}`);
            if (!response.ok) {
                const err = await response.json().catch(() => ({}));
                throw new Error(err.error || err.message || 'Export failed');
            }
            const content = await response.text();
            const disposition = response.headers.get('content-disposition') || '';
            const filename = parseContentDispositionFilename(disposition) || `${type}_audit_export.${format}`;
            const mimeType = format === 'csv' ? 'text/csv' : 'application/json';

            const outputEl = document.getElementById('report-audit-output');
            if (outputEl) {
                outputEl.textContent = content;
                outputEl.classList.add('visible');
            }
            downloadContent(content, filename, mimeType);
            showToast('Audit export ready.', 'success');
        } catch (error) {
            if (error.code === 'auth') return;
            showToast(`Audit export failed: ${error.message}`, 'error');
        }
    }

    async function refreshReportSchedules() {
        const listEl = document.getElementById('schedule-list');
        if (!listEl) return;
        listEl.innerHTML = '<div class="admin-loading">Loading...</div>';
        try {
            const response = await guardedFetch(`${API_BASE}/admin/reports/schedules`);
            if (response.status === 403) {
                listEl.innerHTML = '<div class="admin-text-muted">Schedules disabled for this edition.</div>';
                return;
            }
            if (!response.ok) throw new Error('Failed to load schedules');
            const schedules = await response.json();
            renderSchedules(schedules);
        } catch (error) {
            if (error.code === 'auth') return;
            listEl.innerHTML = '<div class="admin-text-muted">Unable to load schedules.</div>';
        }
    }

    function renderSchedules(schedules) {
        const listEl = document.getElementById('schedule-list');
        if (!listEl) return;
        if (!Array.isArray(schedules) || schedules.length === 0) {
            listEl.innerHTML = '<div class="admin-text-muted">No schedules configured.</div>';
            return;
        }
        listEl.innerHTML = schedules.map(s => `
            <div class="schedule-item">
                <div>
                    <strong>${escapeHtml(s.type)}</strong> \u2022 ${escapeHtml(s.cadence)} \u2022 ${escapeHtml(s.format)}
                    <br><span style="font-size:11px;color:var(--text-tertiary);">Next: ${escapeHtml(formatTimestamp(s.nextRunAt))}</span>
                </div>
                <div style="display:flex;gap:4px;">
                    <button class="btn btn-secondary btn-xs" data-action="toggleReportSchedule" data-schedule-id="${escapeHtml(s.id)}" data-enabled="${s.enabled}">${s.enabled ? 'Disable' : 'Enable'}</button>
                    <button class="btn btn-secondary btn-xs" data-action="runReportSchedule" data-schedule-id="${escapeHtml(s.id)}">Run</button>
                </div>
            </div>`
        ).join('');
    }

    async function createReportSchedule() {
        const type = document.getElementById('schedule-type')?.value || 'executive';
        const cadence = document.getElementById('schedule-cadence')?.value || 'weekly';
        const payload = { type: type.toUpperCase(), format: 'JSON', cadence: cadence.toUpperCase(), windowDays: 7, limit: 0 };

        const headers = { 'Content-Type': 'application/json' };
        const csrfToken = await ensureCsrfToken();
        if (csrfToken) headers['X-XSRF-TOKEN'] = csrfToken;

        try {
            const response = await guardedFetch(`${API_BASE}/admin/reports/schedules`, {
                method: 'POST',
                headers,
                body: JSON.stringify(payload)
            });
            if (!response.ok) {
                const err = await response.json().catch(() => ({}));
                throw new Error(err.error || err.message || 'Schedule creation failed');
            }
            showToast('Schedule created.', 'success');
            refreshReportSchedules();
        } catch (error) {
            if (error.code === 'auth') return;
            showToast(`Schedule create failed: ${error.message}`, 'error');
        }
    }

    async function toggleReportSchedule(scheduleId, enabled) {
        if (!scheduleId) return;
        const nextEnabled = String(enabled) !== 'true';
        const headers = { 'Content-Type': 'application/json' };
        const csrfToken = await ensureCsrfToken();
        if (csrfToken) headers['X-XSRF-TOKEN'] = csrfToken;
        try {
            const response = await guardedFetch(`${API_BASE}/admin/reports/schedules/${scheduleId}`, {
                method: 'PATCH',
                headers,
                body: JSON.stringify({ enabled: nextEnabled })
            });
            if (!response.ok) throw new Error('Schedule update failed');
            showToast(`Schedule ${nextEnabled ? 'enabled' : 'disabled'}.`, 'success');
            refreshReportSchedules();
        } catch (error) {
            if (error.code === 'auth') return;
            showToast(`Schedule update failed: ${error.message}`, 'error');
        }
    }

    async function runReportSchedule(scheduleId) {
        if (!scheduleId) return;
        const headers = {};
        const csrfToken = await ensureCsrfToken();
        if (csrfToken) headers['X-XSRF-TOKEN'] = csrfToken;
        try {
            const response = await guardedFetch(`${API_BASE}/admin/reports/schedules/${scheduleId}/run`, {
                method: 'POST',
                headers
            });
            if (!response.ok) throw new Error('Schedule run failed');
            showToast('Schedule export generated.', 'success');
            refreshReportExports();
        } catch (error) {
            if (error.code === 'auth') return;
            showToast(`Schedule run failed: ${error.message}`, 'error');
        }
    }

    async function refreshReportExports() {
        const listEl = document.getElementById('export-list');
        if (!listEl) return;
        listEl.innerHTML = '<div class="admin-loading">Loading...</div>';
        try {
            const response = await guardedFetch(`${API_BASE}/admin/reports/exports?limit=25`);
            if (!response.ok) throw new Error('Failed to load exports');
            const exports = await response.json();
            renderExports(exports);
        } catch (error) {
            if (error.code === 'auth') return;
            listEl.innerHTML = '<div class="admin-text-muted">Unable to load exports.</div>';
        }
    }

    function renderExports(exports) {
        const listEl = document.getElementById('export-list');
        if (!listEl) return;
        if (!Array.isArray(exports) || exports.length === 0) {
            listEl.innerHTML = '<div class="admin-text-muted">No exports yet.</div>';
            return;
        }
        listEl.innerHTML = exports.map(exp => `
            <div class="export-item">
                <div>
                    <strong>${escapeHtml(exp.type || 'REPORT')}</strong> \u2022 ${escapeHtml(exp.format || '')}
                    <br><span style="font-size:11px;color:var(--text-tertiary);">${escapeHtml(formatTimestamp(exp.createdAt))}</span>
                </div>
                <div class="export-item-actions">
                    <button class="btn btn-secondary btn-xs" data-action="viewReportExport" data-export-id="${escapeHtml(exp.id)}">View</button>
                    <button class="btn btn-secondary btn-xs" data-action="downloadReportExport" data-export-id="${escapeHtml(exp.id)}">Download</button>
                </div>
            </div>`
        ).join('');
    }

    function getOrCreateExportViewer() {
        let viewer = document.getElementById('report-export-viewer');
        if (viewer) {
            return viewer;
        }
        const listEl = document.getElementById('export-list');
        if (!listEl || !listEl.parentNode) {
            return null;
        }
        viewer = document.createElement('pre');
        viewer.id = 'report-export-viewer';
        viewer.className = 'report-export-viewer';
        viewer.textContent = '';
        listEl.parentNode.insertBefore(viewer, listEl.nextSibling);
        return viewer;
    }

    async function viewReportExport(exportId) {
        if (!exportId) return;
        const exportData = await fetchExport(exportId);
        if (!exportData) return;
        const outputEl = getOrCreateExportViewer();
        if (outputEl) {
            outputEl.textContent = exportData.content || '';
            outputEl.classList.add('visible');
        }
        showToast('Export loaded.', 'info');
    }

    async function downloadReportExport(exportId) {
        if (!exportId) return;
        const exportData = await fetchExport(exportId);
        if (!exportData) return;
        const format = String(exportData.format || 'JSON').toLowerCase();
        const filename = `${exportData.type || 'report'}_${exportId}.${format}`;
        const mimeType = format === 'csv' ? 'text/csv' : 'application/json';
        downloadContent(exportData.content || '', filename, mimeType);
        showToast('Download started.', 'info');
    }

    async function fetchExport(exportId) {
        try {
            const response = await guardedFetch(`${API_BASE}/admin/reports/exports/${exportId}`);
            if (!response.ok) throw new Error('Export fetch failed');
            return await response.json();
        } catch (error) {
            if (error.code === 'auth') return null;
            showToast(`Export fetch failed: ${error.message}`, 'error');
            return null;
        }
    }

    function downloadContent(content, filename, mimeType) {
        try {
            const blob = new Blob([content], { type: mimeType || 'text/plain' });
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = filename || 'export.txt';
            document.body.appendChild(link);
            link.click();
            link.remove();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
        } catch (_) {
            // Download failed silently
        }
    }

    function parseContentDispositionFilename(header) {
        if (!header) return null;
        const match = header.match(/filename=([^;]+)/i);
        if (!match) return null;
        return match[1].replace(/"/g, '').trim();
    }

    // ── Section: Platform ──────────────────────────────────

    function loadPlatform() {
        if (!currentUser) return;
        setText(document.getElementById('platform-edition'), currentEdition || 'Unknown');
        setText(document.getElementById('platform-profile'), currentUser.profile || 'dev');
        setText(document.getElementById('platform-auth-mode'), currentUser.authMode || 'DEV');
        setText(document.getElementById('platform-fail-closed'), currentUser.failClosed ? 'Enabled' : 'Disabled');
    }

    // ── Action Dispatch ────────────────────────────────────

    document.addEventListener('click', function (e) {
        const el = e.target.closest('[data-action]');
        if (!el) return;
        e.preventDefault();
        e.stopPropagation();

        const action = el.dataset.action;
        switch (action) {
            // Overview
            case 'refreshOverview': sectionLoaded['overview'] = false; loadOverview(); break;
            case 'goToUsers': switchSection('users'); break;

            // Users
            case 'refreshUsers': sectionLoaded['users'] = false; loadUsers(); break;
            case 'approveUser': approveUser(el.dataset.userId); break;
            case 'activateUser': activateUser(el.dataset.userId); break;
            case 'deactivateUser': deactivateUser(el.dataset.userId); break;

            // Workspaces
            case 'refreshWorkspaces': sectionLoaded['workspaces'] = false; loadWorkspaces(); break;
            case 'showCreateWorkspace': showCreateWorkspace(); break;
            case 'closeCreateWorkspace': closeCreateWorkspace(); break;
            case 'createWorkspace': createWorkspace(); break;

            // Connectors
            case 'refreshConnectors': refreshConnectorStatus(); break;
            case 'syncConnectors': syncConnectors(); break;
            case 'refreshConnectorCatalog': refreshConnectorCatalog(); break;
            case 'toggleConnectorDetails': toggleConnectorDetails(el.dataset.connectorId); break;

            // Reports
            case 'runExecutiveReport': runExecutiveReport(); break;
            case 'runSlaReport': runSlaReport(); break;
            case 'runAuditExport': runAuditExport(); break;
            case 'refreshReportSchedules': refreshReportSchedules(); break;
            case 'createReportSchedule': createReportSchedule(); break;
            case 'toggleReportSchedule': toggleReportSchedule(el.dataset.scheduleId, el.dataset.enabled); break;
            case 'runReportSchedule': runReportSchedule(el.dataset.scheduleId); break;
            case 'refreshReportExports': refreshReportExports(); break;
            case 'viewReportExport': viewReportExport(el.dataset.exportId); break;
            case 'downloadReportExport': downloadReportExport(el.dataset.exportId); break;
        }
    });

    // ── Sidebar Navigation ─────────────────────────────────

    document.getElementById('admin-nav')?.addEventListener('click', function (e) {
        const navItem = e.target.closest('.admin-nav-item');
        if (!navItem) return;
        e.preventDefault();
        const sectionId = navItem.dataset.section;
        if (sectionId) switchSection(sectionId);
    });

    // ── Initialization ─────────────────────────────────────

    async function init() {
        try {
            const response = await guardedFetch(`${API_BASE}/user/context`);
            if (!response.ok) {
                window.location.href = 'index.html';
                return;
            }
            const ctx = await response.json();

            if (!ctx.isAdmin) {
                window.location.href = 'index.html';
                return;
            }

            currentUser = ctx;
            currentEdition = String(ctx.edition || '').toUpperCase();
            setText(document.getElementById('admin-user'), ctx.displayName || 'Admin');

            if (currentEdition) {
                document.body.classList.add(`edition-${currentEdition.toLowerCase()}`);
            }

            sectionLoaded['overview'] = true;
            loadOverview();
        } catch (error) {
            if (error.code === 'auth') return;
            window.location.href = 'index.html';
        }
    }

    init();
})();
