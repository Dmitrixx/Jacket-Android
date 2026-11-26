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
        // /online.js is only the VOD source plugin. /lampainit.js is the
        // official Lampac bootstrap that registers all configured client
        // plugins (JacRed, TMDB/CUB proxy, Online, SISI, TorrServer, etc.).
        script.src = base + '/lampainit.js?client=lampa-android';
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

    var settingsAttempts = 0;

    function registerLampacSettings() {
        if (window.__LAMPA_ANDROID_LAMPAC_SETTINGS_REGISTERED__) return;

        // Only expose this section when the native APK has the matching bridge.
        // This keeps standalone Lampa web-bundle updates compatible with older APKs.
        if (!window.AndroidJS || typeof AndroidJS.openLampacSettings !== 'function') {
            if (settingsAttempts++ < 120) {
                window.setTimeout(registerLampacSettings, 250);
            }
            return;
        }

        var lampa = window.Lampa;
        if (!lampa || !lampa.SettingsApi || typeof lampa.SettingsApi.addComponent !== 'function') {
            if (settingsAttempts++ < 120) {
                window.setTimeout(registerLampacSettings, 250);
            }
            return;
        }

        lampa.SettingsApi.addComponent({
            component: 'jacket_lampac',
            name: 'Lampac',
            after: 'player',
            icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="white" d="M19.14 12.94a7.5 7.5 0 0 0 .05-.94 7.5 7.5 0 0 0-.05-.94l2.03-1.58a.5.5 0 0 0 .12-.64l-1.92-3.32a.5.5 0 0 0-.61-.22l-2.39.96a7.28 7.28 0 0 0-1.63-.94L14.38 2.8a.49.49 0 0 0-.49-.4h-3.84a.49.49 0 0 0-.49.4L9.2 5.32c-.59.23-1.14.55-1.63.94l-2.39-.96a.5.5 0 0 0-.61.22L2.65 8.84a.5.5 0 0 0 .12.64L4.8 11.06a7.5 7.5 0 0 0-.05.94 7.5 7.5 0 0 0 .05.94l-2.03 1.58a.5.5 0 0 0-.12.64l1.92 3.32a.5.5 0 0 0 .61.22l2.39-.96c.49.39 1.04.71 1.63.94l.36 2.52a.49.49 0 0 0 .49.4h3.84a.49.49 0 0 0 .49-.4l.36-2.52c.59-.23 1.14-.55 1.63-.94l2.39.96a.5.5 0 0 0 .61-.22l1.92-3.32a.5.5 0 0 0-.12-.64l-2.03-1.58ZM12 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7Z"/></svg>'
        });

        lampa.SettingsApi.addParam({
            component: 'jacket_lampac',
            param: {
                name: 'jacket_lampac_open_controls',
                type: 'button'
            },
            field: {
                name: 'Открыть управление Lampac',
                description: 'Запуск и остановка сервера, обновления Lampa и Lampac, управление модулями.'
            },
            onChange: function () {
                AndroidJS.openLampacSettings();
            }
        });

        window.__LAMPA_ANDROID_LAMPAC_SETTINGS_REGISTERED__ = true;
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
    registerLampacSettings();

    window.addEventListener('beforeunload', function () {
        if (retryTimer) window.clearTimeout(retryTimer);
    });
})();
