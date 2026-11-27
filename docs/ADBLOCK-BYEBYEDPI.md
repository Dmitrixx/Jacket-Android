# Lampa ad filtering and ByeByeDPI companion menu

## Lampa settings

The Lampa settings menu exposes two native sections:

- **Блокировка рекламы** — enables a conservative host-based filter for known third-party advertising domains; optional analytics/tracker filtering is separate and off by default.
- **ByeByeDPI** — opens the original ByeByeDPI Android application when it is installed. Otherwise, it opens the official GitHub release page.

The ad filter operates on network requests made by the embedded Android WebView. It does not rewrite HLS or DASH manifests, inspect video frames, or modify a media stream. It therefore cannot reliably remove mid-roll ads inserted by the cinema into the same video stream or delivered from the same host as the film. Requests made by external players such as VLC and MX Player do not pass through this WebView filter.

Some websites use ad domains for functions beyond advertising. When such a host is blocked, a site or provider may stop working. Disable the ad-domain filter or add a future allow-list when diagnosing a broken provider.

## ByeByeDPI is kept as a separate app

The linked upstream ByeByeDPI project is licensed under GNU GPL-3.0-only. The bundled Lampa web client carries GPL-2.0. To avoid combining those separately licensed codebases into one APK, this repository does not vendor or link ByeByeDPI's source into the Lampa application.

Instead, the new Lampa settings item launches package `io.github.romanvht.byedpi` if it is installed. If it is missing, the item opens the official releases page:

https://github.com/romanvht/ByeByeDPI/releases/latest

ByeByeDPI retains its own settings screens, strategy editor, service modes, VPN permission flow, application filters and import/export features.

The upstream README explains that ByeByeDPI runs ByeDPI locally on the device. Its Android VPN mode is a local traffic-redirection mechanism; it is not a remote VPN, does not encrypt traffic and does not hide the user's public IP address. Android allows only one active VPN service at a time, so starting ByeByeDPI's VPN mode may conflict with another VPN already in use.

## Validation boundaries

The ad-domain filter is a best-effort browser-request filter, not a universal video-ad remover. A provider can still show video ads when the ad is stitched into the media stream, shares the movie's host, or is fetched by an external player. Provider-specific breakage must be tested on the actual TV/device before enabling stricter rules by default.
