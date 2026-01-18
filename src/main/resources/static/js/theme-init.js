// Early theme initialization to avoid flash before CSS loads.
(function() {
    var saved = localStorage.getItem('sentinel-display-mode');
    var theme = (saved === 'dark') ? 'dark' : 'light';
    document.documentElement.setAttribute('data-theme', theme);
})();
