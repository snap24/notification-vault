<div align="center">
  <h1>Notification Vault</h1>
  <p>A private, secure, open-source notification logger and history manager.</p>
  <img src=".github/assets/icon.png" width="128" height="128" />
  <br><br>

  [![Latest Version](https://img.shields.io/badge/Version-v1.0.0-9575CD?style=flat&logo=github&logoColor=white)](https://github.com/snap24/notification-vault/releases)
  ![Java](https://img.shields.io/badge/Java-17-ED8B00?style=flat&logo=openjdk&logoColor=white)
  ![Android](https://img.shields.io/badge/API-29%2B-3DDC84?style=flat&logo=android&logoColor=white)
  [![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat&logo=gnu&logoColor=white)](LICENSE)
</div>

---
<h3>Notification Vault securely captures and stores all your notifications locally.</h3>

## Versions

- v1.0.0: Initial stable release.

## Core Features

- **Absolute Privacy**: Operates entirely offline with zero internet permissions or telemetry. Your data stays on your device.
- **Enterprise-Grade Security**: Biometric lock (Fingerprint/Face Unlock) required to access the vault, deeply integrated with the app lifecycle.
- **Advanced Search & Filter**: Instantly locate past notifications with full-text search and per-app filtering.
- **Dynamic Calendar Log**: Jump to a specific date in your notification history instantly, featuring smart bounds locking to prevent future or pre-install date selections.
- **Bespoke UI/UX**: Built with Material 3, featuring 5 color themes and a custom AMOLED Pitch Black mode with elevation overrides.
- **Reliable Capture**: Safely stores Notification payloads, titles, and text continuously using Room Database.

<details>
<summary><h3><b>Interface Gallery</b></h3></summary>
<br>
<div align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpeg" width="200" hspace="10" vspace="10" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpeg" width="200" hspace="10" vspace="10" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpeg" width="200" hspace="10" vspace="10" />
  <br><br>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpeg" width="200" hspace="10" vspace="10" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpeg" width="200" hspace="10" vspace="10" />
</div>
</details>

## Build Requirements

1. Clone: `git clone https://github.com/snap24/notification-vault.git`
2. Environment: Android Studio Koala+, JDK 17.
3. Target: Minimum SDK 29 (Android 10), Target SDK 34 (Android 14).
4. Execution: Run `./gradlew assembleRelease` for optimized production binaries.

## Available On

<a href="https://f-droid.org/packages/com.zygisk_enc.notivault"><img src=".github/assets/badge_fdroid.png" height="60" alt="Get it on F-Droid" /></a>
<a href="https://github.com/snap24/notification-vault/releases"><img src=".github/assets/badge_github.png" height="60" alt="Get it on GitHub" /></a>

## License

<a href="LICENSE"><img src=".github/assets/gplv3.svg" height="90" alt="GPLv3"></a>

This project is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for details.

---
<div align="center">
  Maintained by Chinmai H B
</div>
