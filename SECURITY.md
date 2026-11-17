# Security Policy

## Supported versions

Security fixes are currently targeted at the latest commit on the `main` branch. There is no stable release yet.

## Reporting a vulnerability

Do not publish API keys, private tracker credentials, personal data, or a working exploit in a public issue.

Use GitHub's **Report a vulnerability** flow in the repository Security tab when it is available. Otherwise, contact the maintainer privately through a verified GitHub contact channel. Include:
- affected version or commit;
- Android version and device ABI;
- concise reproduction steps;
- impact and any relevant logs with secrets removed.

## Security design

- Jackett API keys are encrypted with AES/GCM using a key held in Android Keystore.
- Redirect following is disabled on authenticated Jackett requests, preventing the API key from being forwarded to another host.
- Public/non-local HTTP endpoints are rejected; use HTTPS or a VPN for remote access.
- Torznab response size, request timeouts, and maximum result count are bounded.
- XML document type declarations are disabled in the pull parser.
- Android backup is disabled to avoid copying application secrets to cloud backup.

## User guidance

Do not share your Jackett API key or expose the Jackett web/API port directly to the public internet. If the phone needs remote access, use a properly configured VPN or HTTPS reverse proxy with authentication.
