/*
 * Jacket-local Lampa integration.
 * This file is maintained separately from the upstream Lampa source.
 * It loads the embedded Lampac Lampa plugin from the loopback-only server.
 */
(function () {
    var base = window.__JACKET_LAMPAC_URL__ || 'http://127.0.0.1:9118';
    var attempts = 0;
    var maxAttempts = 90;
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

    function observeLampaPlayer() {
        var player = window.Lampa && window.Lampa.Player;
        if (!player || !player.listener || typeof AndroidJS === 'undefined') {
            window.setTimeout(observeLampaPlayer, 250);
            return;
        }
        if (player.__jacketBackBridgeInstalled) return;
        player.__jacketBackBridgeInstalled = true;
        player.listener.follow('start', function () {
            AndroidJS.setPlayerOpen(true);
        });
        player.listener.follow('close', function () {
            AndroidJS.setPlayerOpen(false);
        });
        AndroidJS.setPlayerOpen(Boolean(player.opened && player.opened()));
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', loadLampacPlugin, { once: true });
    } else {
        loadLampacPlugin();
    }

    observeLampaPlayer();

    window.addEventListener('beforeunload', function () {
        if (timer) window.clearTimeout(timer);
    });
})();
