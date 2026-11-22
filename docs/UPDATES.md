# Installing Jacket updates without uninstalling

Jacket keeps the same Android package name (`app.jacket.mobile`) and increments `versionCode` for each app update. Android can update an installed copy in place and preserve its settings, torrent queue, private Debian rootfs, .NET runtime and Lampac files. Do not uninstall Jacket and do not clear its storage when applying a normal update.

> Main-branch CI publishes `Jacket-main-apk` only after validating the permanent keystore, alias and private-key password by signing and verifying a temporary probe. If signing secrets are missing or invalid, lint, tests and APK assembly still run without signing, but no update APK is uploaded. Read the workflow summary and check the four signing secrets below.

## One-time persistent signing setup

## Validate signing credentials

Before Gradle receives the signing configuration, CI decodes the keystore, creates a disposable probe JAR, signs it with the configured alias and private-key password, and verifies the signature. This catches invalid keystore data, aliases and passwords without printing secret values. If validation fails, the workflow continues with an unsigned release build for CI checks and skips publishing the update APK. Correct the four Actions secrets and rerun the workflow.

Android accepts an in-place APK update only when the new APK is signed with the same certificate as the installed APK. A CI debug key generated on a temporary runner is not persistent, so debug builds from separate CI runs may not update each other. Configure a permanent private keystore once; never commit it to this public repository.

Generate a keystore on a trusted computer:

```bash
keytool -genkeypair -v \
  -keystore jacket-release.jks \
  -storetype JKS \
  -alias jacket \
  -keyalg RSA -keysize 4096 \
  -validity 10000
```

Keep the `jacket-release.jks` file and both passwords in a safe offline backup. Losing this key means future APKs cannot update the already signed install without removing the old app first.

Add these four repository secrets in GitHub → Settings → Secrets and variables → Actions:

| Secret | Value |
| --- | --- |
| `JACKET_KEYSTORE_BASE64` | Base64 of the entire `jacket-release.jks` file |
| `JACKET_KEYSTORE_PASSWORD` | Keystore password |
| `JACKET_KEY_ALIAS` | `jacket` (or the alias selected at generation) |
| `JACKET_KEY_PASSWORD` | Password for that alias |

Base64 examples:

- Linux: `base64 -w 0 jacket-release.jks`
- macOS: `base64 < jacket-release.jks | tr -d '\n'`
- PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes('jacket-release.jks'))`

GitHub Actions restores the key into the temporary runner directory and signs the Release APK. The keystore and passwords are never written to the repository or included in the APK artifact.

## Applying an update

1. Wait for the latest `main` Android CI run to finish successfully.
2. Download `Jacket-main-apk` from that run's Artifacts section and extract the APK.
3. Open the APK on the Android device and accept **Update** / **Install**. Do not uninstall the existing Jacket first.
4. Keep `applicationId` unchanged and keep using the same keystore for every later build.

If the currently installed app was signed with a temporary CI debug key, switching to the permanent release key may require one final uninstall/reinstall because Android rejects APKs signed by a different certificate. This is not required for every update: after the installed copy and CI use the same permanent key, later builds install over the existing app and preserve app-private data.

**Do not uninstall Jacket merely to test an update.** First try the signed APK. If Android reports a signature/package conflict, cancel the installation. In particular, do not remove an installation containing the embedded Lampac rootfs until its data has been safely backed up; a normal APK update preserves that private data, but uninstalling the app deletes it.

The APK is a personal development build. Do not share the keystore, passwords, or signed APK with others.
