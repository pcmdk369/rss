# Android release signing

The repository does not contain a release keystore. Create and protect your own upload key; never commit it or send it in chat, issues, screenshots or source files.

## 1. Generate a private upload key

On a trusted computer with JDK 17:

```sh
keytool -genkeypair -v \
  -keystore superioa-upload.jks \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -alias superioa-upload
```

Store the keystore and passwords in a secure password manager and keep an offline backup. Do not put the keystore in the repository. The `.gitignore` excludes common keystore formats.

## 2. Add GitHub Actions secrets

In the private repository settings, open **Secrets and variables → Actions** and add:

- `ANDROID_KEYSTORE_BASE64`: Base64 encoding of the complete `superioa-upload.jks` file
- `ANDROID_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_KEY_ALIAS`: `superioa-upload` (or the alias you chose)
- `ANDROID_KEY_PASSWORD`: key password

Linux / Termux base64 example:

```sh
base64 -w 0 superioa-upload.jks
```

PowerShell example:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("superioa-upload.jks"))
```

Paste the resulting Base64 string into the GitHub secret field. Never put the Base64 value in a tracked file. Prefer a protected GitHub Environment for release-only secrets and restrict which branches can deploy.

## 3. Build and verify

When all four secrets are present, the CI workflow:

1. Reconstructs the keystore in the runner's temporary directory.
2. Signs the release APK.
3. Builds the signed Android App Bundle.
4. Verifies the APK signature with `apksigner`.
5. Uploads the signed APK, AAB and SHA-256 checksums as a short-lived CI artifact.

Without all four secrets, CI builds an **unsigned release validation APK** only. That file is not installable as a production release and must not be distributed as one.

## 4. Versioning and Play Console

- Current release identity: `com.pcmdk369.superioa`, version code `2`, version name `0.2.0`.
- Increase `versionCode` for every Play Store update; never reuse a previously published code.
- Keep the same application ID and upload key for future updates.
- Configure Play App Signing in Play Console and retain the upload key securely.
- Review Play Console declarations, privacy policy, Data safety, accessibility-service disclosures, screenshots, store listing and policy requirements before submission.
- Do not publish automatically from CI. Review the signed bundle and complete device acceptance testing first.
