# Lampa stream-ad filtering and integrated DPI bypass

## One application

ByeDPI is integrated into the Lampa APK. The Lampa settings item **ByeByeDPI** opens an in-app control panel; there is no external-app hand-off and no second APK requirement.

The integrated engine uses the MIT-licensed [hufrea/byedpi](https://github.com/hufrea/byedpi) native proxy and [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) for Android VPN traffic redirection. The Lampa-side settings and Android services are maintained in this repository. The separate GPL-3.0-only ByeByeDPI Android frontend is not copied into the GPL-2.0 Lampa application.

The in-app control panel currently provides the local VPN mode, a local SOCKS5 mode, a ByeDPI argument/strategy field, DNS and IPv6 controls. VPN mode requires Android's system permission and only one Android VPN can be active at a time.

ByeDPI is a local DPI circumvention engine, not a remote VPN. It does not send traffic to a remote VPN server, encrypt traffic, or conceal the public IP address.

## Removing advertisements from media streams

The Lampa WebView and the local media proxy used by external players now inspect HLS and DASH manifests before returning them to the player.

For HLS, the filter removes segments in explicitly signalled ad breaks marked by `#EXT-X-CUE-OUT` / `#EXT-X-CUE-IN` and SCTE-35 `EXT-X-DATERANGE` markers. It also removes Apple HLS interstitial insertion markers.

For DASH, the filter removes Periods with explicit advertising metadata (for example an advertisement Role or an ad-named Period) and Periods containing recognised SCTE-35 cue-out events in an EventStream.

The media segments that remain are requested from their original media origin. The filter does not transcode or re-encode the film.

### Important limitation

This removes ad breaks that the stream exposes as separable segments/Periods or signals with metadata. It cannot reliably recognise an advertisement that has been stitched into the same video/audio segments as the film without any cue, SCTE-35, interstitial, or advertising-period metadata. Such a stream is technically indistinguishable from ordinary programme content from its manifest alone. Removing it would require content-level audio/video recognition or a known ad fingerprint catalogue, and automatic detection can cut out real film scenes.

The current filter therefore uses explicit stream signalling rather than guessing. Some providers may still show unmarked server-side ads. Provider-specific testing is needed to confirm the actual behaviour on the user's source.

## Browser ad-domain filter

The separate WebView domain filter blocks a conservative list of known third-party ad-network hosts, with analytics/tracker blocking as an independent option. It is not a substitute for manifest-level stream filtering and does not remove ads embedded in the same media segments.

