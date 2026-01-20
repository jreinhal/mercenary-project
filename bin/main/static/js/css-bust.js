// Dev-only CSS cache busting to avoid stale styles under strict CSP.
(function() {
    try {
        var host = window.location.hostname;
        var isLocal = host === 'localhost' || host === '127.0.0.1' || host === '::1';
        if (!isLocal) return;

        var link = document.getElementById('sentinel-css');
        if (!link) return;

        var href = link.getAttribute('href') || '';
        var base = href.split('?')[0] || 'css/sentinel.css';
        link.setAttribute('href', base + '?v=dev-' + Date.now());
    } catch (e) {
        // Swallow errors to avoid breaking the page if CSP or DOM is restricted.
    }
})();
