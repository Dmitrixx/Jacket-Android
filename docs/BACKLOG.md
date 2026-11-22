# Backlog

Priority order for the automated improvement cycle (`docs/AUTOMATION.md`). Pick the first unblocked item, do one
slice, tick it off or split it, and keep this file current. Before starting an item, check open pull requests and
remote branches (`gh pr list`, `git branch -r`): another agent or the owner may already be on it.

**Goal:** one APK — phone, tablet, Android TV — a full media center with its own torrent engine and its own
tracker parser, with no separately installed Jackett or TorrServer.

## P0 — must work first

- [ ] **B-001 Installable, upgradeable APK.** `assembleRelease` currently has no signing config and yields an
  unsigned APK. Read the keystore path/passwords from environment or Gradle properties (CI secrets
  `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), fall back to the debug key when they are
  absent so the APK is always installable. Add a `nightly` pre-release workflow step that attaches the APK to a
  GitHub release on changes to `main`. Add an in-app "Check for updates" (HTTPS GitHub Releases API, version
  compare, opens the APK download). Document key setup in `docs/AUTOMATION.md`.
- [ ] **B-002 LAN HTTP policy.** `network_security_config.xml` blocks cleartext for everything except `127.0.0.1`,
  while `LocalNetwork` accepts plain HTTP to private addresses, so a LAN Jackett/Torznab server cannot connect.
  Make the two consistent (allow cleartext at the OS level and keep `LocalNetwork` as the in-code policy, or drop
  HTTP from the policy), add tests that public HTTP stays rejected, document it in `SECURITY.md`.
- [ ] **B-003 Built-in indexer engine (the project's own parser).** Replace heuristic HTML parsing with a
  definition-driven engine: JSON definitions (encoding, mirrors, request template, row/field selectors, filters),
  HTML and JSON responses, per-definition charset for queries and responses (windows-1251), cookie session per
  source, mirror failover, bounded parallel detail fetches, correct challenge/login detection (only when zero rows
  matched), result normalisation and de-duplication by info-hash. Unit tests on saved HTML/JSON fixtures; a
  per-source diagnostics screen showing which step failed. Ship the engine, the format and generic definitions
  (RSS, Torznab, JSON API); per-tracker definitions only on the owner's explicit request.
  *Phase 2:* read Cardigann/Jackett-style YAML definitions that the **user** imports from a file or a URL (never
  bundled: the public definition sets are GPL-licensed). Support the common subset: `links` (mirrors), `encoding`,
  `caps` category mappings, `login`, `search.paths`, `rows` / `fields` selectors and the usual filters (`regexp`,
  `replace`, `trim`, `dateparse`, `querystring`). A `details` block parses the release page: description, poster,
  ratings, file list, voice-over / translation information.
- [ ] **B-004 Indexer menu in the style of Jackett's dashboard.** Screen "Add indexer": searchable list with columns
  name, categories, type (public / private badge), language; filters by type, language and category; buttons
  "configure" and "add". Configure dialog: site link / mirror, username, password, cookie, "search freeleech only",
  sort (created, seeders, size…), order (asc / desc), tags, a capabilities table (category id and name, Torznab
  numbering), "Test" button with a readable result. List of configured indexers with enable / disable / edit /
  delete. Reuse `IndexerConfig`. Must be usable with a D-pad on TV and adapt to tablets.
  Also: quick filters for language / region (e.g. Russian-language only) and public vs private; categories filled
  from `t=caps` instead of free text; a "manual search" results view with a keyword filter box (e.g. "1080") and
  quality chips, like Jackett's manual search.

## P1 — search and discovery (ideas from the Habr article on Jackett and its alternatives)

- [ ] **B-005 Voice-over and translation tags.** Search results rarely say who dubbed a release. Extend
  `ReleaseHeuristics` to extract translation type (dubbing, multi-voice, professional, original + subtitles),
  studio names (LostFilm and similar), audio languages and subtitles from titles and, later, from the details page.
  Show them as chips, add a filter and a "preferred voice-over" setting that raises matching releases. Unit tests
  on real-looking Russian and English release titles.
- [ ] **B-006 Release contents before download.** Like qBittorrent's search view: show the file list and sizes of a
  release before downloading. Pure-Kotlin file listing from `.torrent` bencode (extend `TorrentFileInfo`) and a
  bounded, cancellable metadata fetch for magnet links. Reuse it for episode pickers in season packs.
- [ ] **B-007 Series subscriptions (a small Sonarr).** Watch list with quality and voice-over preferences;
  WorkManager checks the built-in indexers every few hours for new episodes, notifies ("episode X is out") with
  one-tap Watch / Download, optional auto-download. Respect metered networks, battery saver and a daily request budget.
- [ ] **B-008 New releases feed.** Jackett-style RSS mode: a "New releases" screen per category (movies, series,
  anime…) from the configured indexers' feeds, with the category picker from B-004.
- [ ] **B-009 Optional Jackett import.** When a Jackett server is configured, list its configured indexers
  (`/api/v2.0/indexers?configured=true`) with status, language and categories, and let the user pick which to query.

## P1 — the media center

- [ ] **B-010 Streaming robustness without TorrServer.** Persist libtorrent resume data, bound storage with a
  cleanup policy ("delete after watching", quota), measure and improve seek latency, handle process death and
  network loss, warn on metered networks, review foreground-service types and battery/Doze behaviour. Tests with
  fake torrent storage.
- [ ] **B-011 Player.** Remember audio/subtitle choice per title, playback position and history, auto-play next
  episode, Picture-in-Picture on phones, faster D-pad seek, TV safe areas.
- [ ] **B-012 Android TV.** Audit every screen for D-pad reachability and visible focus, leanback banner and
  launcher filters, Watch Next channel, voice search, media-key handling, 10-foot typography.
- [ ] **B-013 Tablets.** `WindowSizeClass`-based adaptive layouts: navigation rail, two-pane details, larger grids.
- [ ] **B-014 Catalog.** "Continue watching", collections, filters, title search fallback to the original title,
  better offline behaviour.

## P2 — health

- [ ] **B-020 Test debt.** ViewModel and settings-store tests with fakes, engine tests, coverage report in CI.
- [ ] **B-021 CI.** Weekly emulator smoke test, dependency review, lint baseline clean-up.
- [ ] **B-022 Refactor.** Split very large files (check the `feat/stage2-*` branches first to avoid duplicate work).
- [ ] **B-023 Localisation and accessibility.** ru / en / uk resources, content descriptions, large-text support.
- [ ] **B-024 Performance.** Baseline profile, start-up time, memory and image-cache tuning.
- [ ] **B-025 Docs.** README quick start (ru / en), troubleshooting for LAN and TV, architecture refresh.
- [ ] **B-026 Diagnostics.** Local log export with secret redaction (no telemetry).

## Frozen / out of scope for the automated cycle

- Site-specific connectors and scrapers for unlicensed streaming portals and CDN balancers (including the
  Filmix / HDRezka account code already in the repository): do not extend, repair or add to them.
- CAPTCHA, anti-bot, paywall and DRM circumvention.
- New per-tracker scraping definitions unless the owner asks for them through the `focus` input.
