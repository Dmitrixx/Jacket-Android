# Updating the Android app

The application ID and persistent signing key remain unchanged so newer APKs update the existing installation.

- Install the signed APK over the current app.
- Do not uninstall the previous app and do not clear its data.
- The private Lampac server, runtime and init.conf remain in app-private storage.
- Use the in-app Lampac control to start or stop the local server.
- If the application ID or signing certificate changes, Android will reject the APK as an in-place update. Do not delete the old installation to work around a signing mismatch.
## Updating Lampa and Lampac inside the app

1. Open the `Lampac` control in the upper-right corner.
2. Choose `Обновления Lampa и Lampac` and press `Проверить обновления`.
3. Update Lampa web assets or Lampac independently. Each package is verified before it is activated.
4. Lampac stops for the update and starts again afterwards. Its `init.conf`, user databases, data directories and supported local overrides are carried forward. The old server directory is retained until the new one passes the startup check; otherwise the service restores the previous version.
5. The APK is still required for changes to the Android shell, native bridge or player integration. Install signed APK updates over the existing app; do not uninstall or clear app data.

Lampa web bundles are published by this repository's main-branch CI and use a SHA-256 manifest. A weekly workflow checks the upstream Lampa source and opens a pull request rather than silently replacing the embedded source. Lampac updates are offered only for newer stable upstream releases whose `lampac-nextgen.zip` asset publishes a SHA-256 digest; releases without that digest are deliberately not installed.
