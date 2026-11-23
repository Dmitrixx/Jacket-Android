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

    function reportLampacState(state) {
        try {
            if (typeof AndroidJS !== 'undefined' && AndroidJS.setLampacState) {
                AndroidJS.setLampacState(state);
            }
        } catch (error) {
            console.warn('Jacket: could not update Lampac connection status', error);
        }
    }

    function loadLampacPlugin() {
        if (window.__JACKET_LAMPAC_PLUGIN_LOADED__) {
            reportLampacState('connected');
            return;
        }
        reportLampacState('connecting');

        var script = document.createElement('script');
        script.async = true;
        script.src = base + '/online.js?client=jacket';

        script.onload = function () {
            window.__JACKET_LAMPAC_PLUGIN_LOADED__ = true;
            reportLampacState('connected');
            console.info('Jacket: embedded Lampac Lampa plugin loaded');
        };

        script.onerror = function () {
            script.remove();
            attempts += 1;
            if (attempts < maxAttempts) {
                reportLampacState('connecting');
                timer = window.setTimeout(loadLampacPlugin, 5000);
            } else {
                reportLampacState('unavailable');
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
            var pendingId = window.__JACKET_PENDING_PLAYBACK_ID__;
            if (pendingId) {
                window.__JACKET_ACTIVE_PLAYBACK_ID__ = pendingId;
                window.__JACKET_PENDING_PLAYBACK_ID__ = null;
            }
            AndroidJS.setPlayerOpen(true);
        });
        player.listener.follow('close', function () {
            var playbackId = window.__JACKET_ACTIVE_PLAYBACK_ID__ ||
                window.__JACKET_PENDING_PLAYBACK_ID__;
            if (playbackId) {
                AndroidJS.onJacketPlaybackClosed(String(playbackId));
            }
            window.__JACKET_ACTIVE_PLAYBACK_ID__ = null;
            window.__JACKET_PENDING_PLAYBACK_ID__ = null;
            AndroidJS.setPlayerOpen(false);
        });
        AndroidJS.setPlayerOpen(Boolean(player.opened && player.opened()));
    }

    function observeVideoErrors() {
        if (window.__JACKET_VIDEO_ERROR_BRIDGE__) return;
        window.__JACKET_VIDEO_ERROR_BRIDGE__ = true;
        document.addEventListener('error', function (event) {
            var video = event.target;
            if (!video || String(video.tagName).toUpperCase() !== 'VIDEO') return;

            var playbackId = window.__JACKET_ACTIVE_PLAYBACK_ID__ ||
                window.__JACKET_PENDING_PLAYBACK_ID__;
            if (!playbackId || window.__JACKET_PLAYBACK_ERROR_SENT__ === playbackId) return;

            window.__JACKET_PLAYBACK_ERROR_SENT__ = playbackId;
            var code = video.error && video.error.code ? video.error.code : 0;
            var message = 'HTML5 video error ' + code;

            try {
                AndroidJS.onJacketPlaybackError(String(playbackId), message);
            } catch (error) {
                console.warn('Jacket: could not report Lampa playback error', error);
            }

            window.setTimeout(function () {
                if (window.Lampa && Lampa.Player && Lampa.Player.opened()) {
                    Lampa.Player.close();
                }
            }, 0);
        }, true);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', loadLampacPlugin, { once: true });
    } else {
        loadLampacPlugin();
    }

    observeLampaPlayer();
    observeVideoErrors();
    window.__JACKET_ANDROID_BRIDGE_READY__ = true;

    window.addEventListener('beforeunload', function () {
        if (timer) window.clearTimeout(timer);
    });
})();
