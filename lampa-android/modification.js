/*
 * Jacket-local Lampa integration.
 * This file is maintained separately from the upstream Lampa source.
 * It loads the embedded Lampac Lampa plugin from the loopback-only server.
 */
(function () {
    var base = window.__JACKET_LAMPAC_URL__ || 'http://127.0.0.1:9118';
    var attempts = 0;
    var maxAttempts = 60;
    var timer = null;

    function loadLampacPlugin() {
        if (window.__JACKET_LAMPAC_PLUGIN_LOADED__) return;

        var script = document.createElement('script');
        script.async = true;
        script.src = base + '/online.js?client=jacket';

        script.onload = function () {
            window.__JACKET_LAMPAC_PLUGIN_LOADED__ = true;
            console.info('Jacket: embedded Lampac Lampa plugin loaded');
        };

        script.onerror = function () {
            script.remove();
            attempts += 1;
            if (attempts < maxAttempts) {
                timer = window.setTimeout(loadLampacPlugin, 5000);
            } else {
                console.warn('Jacket: embedded Lampac is not available yet. Reopen Lampa after starting the server.');
            }
        };

        document.head.appendChild(script);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', loadLampacPlugin, { once: true });
    } else {
        loadLampacPlugin();
    }

    window.addEventListener('beforeunload', function () {
        if (timer) window.clearTimeout(timer);
    });
})();
