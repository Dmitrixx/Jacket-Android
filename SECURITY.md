# Security Policy

## Supported versions

Security fixes target the latest commit on `main`. There is no stable release yet.

## Reporting a vulnerability

Do not publish access tokens, private indexer credentials, personal data, or a working exploit in a public issue.

Use GitHub's **Report a vulnerability** flow in the repository Security tab when available. Otherwise contact the maintainer privately through a verified GitHub contact channel. Include the affected commit, Android version/device ABI, reproduction steps and impact. Remove secrets from logs.

## Security design

- The optional TMDB Read Access Token is encrypted with AES/GCM using a key held in Android Keystore.
- User-defined RSS/Atom indexer URLs must use HTTPS and cannot contain URL user-info credentials.
- Network requests have bounded response sizes and timeouts.
- XML document type declarations are disabled in feed parsing.
- Remote poster and media URLs are restricted to HTTPS.
- Android backup is disabled to avoid copying private app settings to cloud backup.
- Direct torrent links are treated as untrusted source data. Verify the publisher and license before opening or downloading content.

## User guidance

Do not share private indexer credentials or add feeds from sources you do not trust. Jacket no longer needs a Jackett server or Jackett API key. Use only catalogs and media streams you have permission to access.
