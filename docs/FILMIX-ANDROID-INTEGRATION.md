# Filmix Android integration and proxy privacy

Jacket uses Filmix device-code pairing and the API routes expected by the Lampa Android client. The selected API host is pinned after pairing so catalog requests and profile checks stay on the same origin.

## CORS relay

The optional legacy API relay uses the Lampa-compatible HTTPS `enc2t` route. The proxy field is empty by default, so Jacket attempts the validated HTTPS API directly. A relay is used only after the user explicitly enters and saves an HTTPS endpoint they operate or otherwise trust.

A relay is not an end-to-end encrypted tunnel. It terminates TLS and receives the decoded upstream request. The Lampa-compatible route places the request payload in the URL path using Base64. Base64 is reversible encoding, not encryption. As a result, the relay operator can read the device token, profile data, search terms and other request parameters, and URL paths may be present in relay access logs. HTTPS protects the connection between Jacket and the relay, but does not hide request contents from the relay operator.

To avoid sending these requests to a third-party relay, clear the CORS proxy field to use direct HTTPS with the selected API host, or configure a private HTTPS relay you control. Direct mode may not work with the legacy primary API host; choose a compatible HTTPS API endpoint when using it.

## Validation

- Proxy URLs must be HTTPS, use a public DNS hostname and standard port 443.
- URL credentials, query strings, fragments and non-root paths are rejected.
- No TLS certificate checks are disabled.
- The app does not log the raw Filmix request URL or device token.
- The Filmix relay does not bypass CAPTCHA, Cloudflare/Anubis, DRM, paywalls or regional restrictions.
