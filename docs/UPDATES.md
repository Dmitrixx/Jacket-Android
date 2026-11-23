# Updating the Android app

The application ID and persistent signing key remain unchanged so newer APKs update the existing installation.

- Install the signed APK over the current app.
- Do not uninstall the previous app and do not clear its data.
- The private Lampac server, runtime and init.conf remain in app-private storage.
- Use the in-app Lampac control to start or stop the local server.
- If the application ID or signing certificate changes, Android will reject the APK as an in-place update. Do not delete the old installation to work around a signing mismatch.