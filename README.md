# Backy

An Android app that backs up your phone gallery to Google Photos at original
quality: any folder, to any of your Google accounts, into matching albums.
Backups are manual by default; automatic backup is opt-in.

> [!WARNING]
> Backy talks to Google Photos' private, undocumented mobile API. It is not
> affiliated with or endorsed by Google and may stop working at any time.
> Uploads may later be counted against your storage, and an account could be
> restricted for using an unofficial client. Keep your own copies; use it at
> your own risk.

## Features

- **Folders, your way**: pick which phone folders back up. Camera is on by
  default; Screenshots, WhatsApp and the rest are separate switches.
- **Albums**: a phone folder becomes the same album in Google Photos, or send
  everything to one album.
- **Several accounts**: route each folder to a different Google account.
- **Back up now**, or opt in to **automatic backup** of new photos (Wi-Fi only
  by default, optionally only while charging).
- **Live progress** in a notification (Android 16 Live Update), a Quick Settings
  tile and a home-screen widget. Long uploads keep going in the background.
- **Share to Backy** from any app, with account and album choice; anything that
  fails is kept for a retry.
- **Free up space**: review, then delete phone copies already safe in Google
  Photos, filtered by age, type and folder.
- **Gallery**: status badge on every item, search, pinch to resize,
  drag to select, fast scroller, full-screen viewer.
- **Every upload option** exposed: quality, storage quota, parallel uploads,
  retries, file filters, Live/motion photos, proxy. Settings are searchable.
- **Private**: credentials encrypted with the Android Keystore, optional
  fingerprint/face app lock, no analytics.
- Material 3 Expressive, dynamic colour, light/dark/pure black, phones,
  landscape and tablets, high refresh rate.

## Install and build

Backy isn't on the Play Store. Build the APK yourself and sideload it; see
[android/README.md](android/README.md). In short:

```sh
android/gobind.sh                              # Go upload core → AAR
cd android && ./gradlew :app:assembleRelease   # → app/build/outputs/apk/release/
```

Requires Android 12 or newer (arm64).

## How it works

A Kotlin/Jetpack Compose app on top of a Go upload core, bound with gomobile.

| Path | What |
| --- | --- |
| `android/` | The app |
| `mobile/` | Go ↔ Kotlin bridge |
| `internal/gotohp/core` | Google Photos upload protocol, vendored from [xob0t/gotohp](https://github.com/xob0t/gotohp) |
| `cmd/ptg` | `ptg`, the same core as a desktop CLI: [docs/ptg-cli.md](docs/ptg-cli.md) |

## Credits and licence

Built on [photos-to-google](https://github.com/saileshbro/photos-to-google) by
Sailesh Dahal and [gotohp](https://github.com/xob0t/gotohp) by xob0t. MIT; see
[LICENSE](LICENSE) and [NOTICE](NOTICE).
