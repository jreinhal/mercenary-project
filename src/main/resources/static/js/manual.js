function setHidden(el, hidden) {
    if (!el) return;
    el.classList.toggle('hidden', hidden);
}

function toggleTheme() {
    const currentTheme = document.documentElement.getAttribute('data-theme') || 'dark';
    const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', newTheme);
    localStorage.setItem('sentinel-theme', newTheme);
    updateThemeIcon(newTheme);
}

function updateThemeIcon(theme) {
    const darkIcon = document.getElementById('theme-icon-dark');
    const lightIcon = document.getElementById('theme-icon-light');
    setHidden(darkIcon, theme !== 'dark');
    setHidden(lightIcon, theme === 'dark');
}

function initTheme() {
    const savedTheme = localStorage.getItem('sentinel-theme') || 'dark';
    document.documentElement.setAttribute('data-theme', savedTheme);
    updateThemeIcon(savedTheme);
}

function updateActiveNav() {
    const sections = document.querySelectorAll('.section-anchor');
    const navItems = document.querySelectorAll('.nav-item');
    const tocItems = document.querySelectorAll('.toc-list a');

    let currentSection = '';
    sections.forEach(section => {
        const rect = section.getBoundingClientRect();
        if (rect.top <= 100) {
            currentSection = section.id;
        }
    });

    navItems.forEach(item => {
        const href = item.getAttribute('href');
        if (href === '#' + currentSection) {
            item.classList.add('active');
        } else {
            item.classList.remove('active');
        }
    });

    tocItems.forEach(item => {
        const href = item.getAttribute('href');
        if (href === '#' + currentSection) {
            item.classList.add('active');
        } else {
            item.classList.remove('active');
        }
    });
}

function toggleSidebar() {
    const sidebar = document.getElementById('docs-sidebar');
    if (sidebar) sidebar.classList.toggle('open');
}

function getActiveSector() {
    const urlParams = new URLSearchParams(window.location.search);
    const urlSector = urlParams.get('sector');
    if (urlSector) return urlSector.toUpperCase();

    const savedSector = localStorage.getItem('sentinel-sector');
    if (savedSector) return savedSector.toUpperCase();

    return 'ENTERPRISE';
}

function applySectorFilter() {
    const activeSector = getActiveSector();

    const sectorNames = {
        'GOVERNMENT': 'Government & Defense',
        'MEDICAL': 'Healthcare & Medical',
        'ENTERPRISE': 'Enterprise'
    };

    document.querySelectorAll('[data-sector]').forEach(el => {
        const allowedSectors = el.getAttribute('data-sector').split(',').map(s => s.trim().toUpperCase());
        el.classList.toggle('sector-hidden', !allowedSectors.includes(activeSector));
    });

    const versionEl = document.querySelector('.sidebar-version');
    if (versionEl) {
        versionEl.textContent = `${sectorNames[activeSector] || activeSector} Edition`;
    }
}

document.addEventListener('DOMContentLoaded', () => {
    initTheme();
    applySectorFilter();
    updateActiveNav();
    window.addEventListener('scroll', updateActiveNav);

    const themeToggle = document.querySelector('.theme-toggle');
    if (themeToggle) themeToggle.addEventListener('click', toggleTheme);

    const sidebarToggle = document.querySelector('[data-action=\"toggleSidebar\"]');
    if (sidebarToggle) sidebarToggle.addEventListener('click', toggleSidebar);
});
