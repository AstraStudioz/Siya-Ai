# Siya AI (Android)

Siya AI is an advanced on-device and cloud-connected AI assistant for Android powered by Astra Studios and developed by ShivuOfc.

---

## 🚀 Publishing Releases & In-App Auto-Updates

The project includes a complete in-app auto-update system powered by GitHub Releases and GitHub Actions (`.github/workflows/build-apk.yml`).

### 1. Required GitHub Secrets
To sign your APK with a fixed keystore (allowing seamless updates over previous installations) and broadcast releases to your Telegram channel, add the following secrets in **GitHub Repository → Settings → Secrets and variables → Actions**:

| Secret Name | Description | Example / Format |
|---|---|---|
| `KEYSTORE_BASE64` | Base64-encoded upload/release keystore file (`.jks` / `.keystore`) | Run `base64 -w 0 my-upload-key.jks` and paste string |
| `KEY_ALIAS` | Key alias in the keystore | e.g. `upload` |
| `KEY_PASSWORD` | Password for the key entry | e.g. `YourKeyPass123` |
| `STORE_PASSWORD` | Password for the keystore | e.g. `YourStorePass123` |
| `TG_BOT_TOKEN` | Telegram Bot API token (optional) | e.g. `123456789:ABCdefGhIJKlmNoPQRstuVWxYZ` |
| `TG_CHAT_ID` | Telegram Channel / Chat ID (optional) | e.g. `@AstraStudiosPvt` or `-1001234567890` |

*Note*: If `KEYSTORE_BASE64` is not configured, the workflow generates a temporary keystore so development builds still succeed.

---

### 2. How to Publish a Release

#### Method A: Automatic on Push to `main`
1. Make your code changes or version improvements.
2. Commit and push directly to the `main` branch:
   ```bash
   git commit -m "feat: New neural features and latency improvements"
   git push origin main
   ```
3. GitHub Actions automatically:
   - Increments the `versionCode` and `versionName` based on `github.run_number`.
   - Compiles and signs the release APK (`Siya-AI-build-<versionCode>.apk`).
   - Computes the SHA-256 integrity checksum.
   - Uploads the APK to your Telegram channel via the Bot API (or posts a direct download link if APK >50 MB).
   - Generates and attaches `update.json` alongside the APK asset on GitHub Releases.

#### Method B: Manual Trigger via GitHub Actions (Workflow Dispatch)
1. Navigate to **Actions** in your GitHub repository.
2. Select the **Build and Publish Release APK** workflow from the left sidebar.
3. Click **Run workflow** on the `main` branch.

---

### 3. How the In-App Update Engine Works
- **Check Intervals**: Runs automatically on app launch and via WorkManager periodic background worker every 6 hours (requiring network connectivity).
- **Offline Resilient**: Caches release metadata locally so the Updates screen and badge status function offline.
- **Integrity Guarantee**: In-app download verifies the SHA-256 hash before triggering package installation.
- **Multiple Channels**: Users can update directly in-app via GitHub or jump to the Telegram post announcement.
