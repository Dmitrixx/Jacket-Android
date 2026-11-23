/*
 * Native WebView bridge for the unmodified Lampa client.
 * Loads Lampac's official Lampa plugin from the app-private loopback server.
 */
(function () {
    var base = window.__LAMPA_LAMPAC_URL__ || 'http://127.0.0.1:9118';
    var attempts = 0;
    var retryTimer = null;

    function reportLampacState(state) {
        try {
            if (window.AndroidJS && AndroidJS.setLampacState) {
                AndroidJS.setLampacState(state);
            }
        } catch (error) {
            console.warn('Lampa: could not update local Lampac status', error);
        }
    }

    function loadLampacPlugin() {
        if (window.__LAMPA_LAMPAC_PLUGIN_LOADED__) {
            reportLampacState('connected');
            return;
        }
        reportLampacState('connecting');

        var script = document.createElement('script');
        script.async = true;
        script.src = base + '/online.js?client=lampa-android';
        script.onload = function () {
            window.__LAMPA_LAMPAC_PLUGIN_LOADED__ = true;
            reportLampacState('connected');
        };
        script.onerror = function () {
            script.remove();
            attempts += 1;
            if (attempts < 120) {
                retryTimer = window.setTimeout(loadLampacPlugin, 5000);
            } else {
                reportLampacState('unavailable');
            }
        };
        document.head.appendChild(script);
    }

    function observePlayer() {
        var player = window.Lampa && window.Lampa.Player;
        if (!player || !player.listener || !window.AndroidJS) {
            window.setTimeout(observePlayer, 250);
            return;
        }
        if (player.__lampaAndroidBridgeInstalled) return;
        player.__lampaAndroidBridgeInstalled = true;
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
    observePlayer();

    window.addEventListener('beforeunload', function () {
        if (retryTimer) window.clearTimeout(retryTimer);
    });
})();
