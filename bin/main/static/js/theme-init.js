// Early theme initialization to avoid flash before CSS loads.
(function() {
    var saved = localStorage.getItem('sentinel-theme') || localStorage.getItem('sentinel-display-mode');
    var theme = (saved === 'light') ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', theme);
})();
