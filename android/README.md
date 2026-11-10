# Backy (Android)

Backs up the phone gallery to Google Photos using the same upload core as the
`ptg` CLI, bound to Kotlin with gomobile. Unofficial: read the warning in the
[top-level README](../README.md) first.

## Build

Needs Go 1.27, JDK 25, and the Android SDK with NDK (`~/Android/Sdk`).

```sh
go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
gomobile init

android/gobind.sh                       # Go core → android/app/libs/ptg.aar (not committed)
cd android
./gradlew :app:testDebugUnitTest        # unit tests
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

Re-run `gobind.sh` whenever anything under `mobile/` or `internal/` changes.
The Go tests run from the repo root: `go test ./...`.

Use release builds to judge speed: debug Compose is several times slower.

## Signing

Without `android/keystore.properties`, release builds are signed with the debug
key. That's fine for your own phone, but a build signed with one key can't
update an install signed with another. Before giving the app to anyone:

```sh
keytool -genkeypair -v -keystore ~/keys/backy.jks -alias backy \
  -keyalg RSA -keysize 4096 -validity 10000
```

```properties
# android/keystore.properties — ignored by git; keep it and the .jks backed up
storeFile=/home/you/keys/backy.jks
storePassword=…
keyAlias=backy
keyPassword=…
```

Lose the key and installed copies can never be updated, only uninstalled
(which also loses the app's upload history).

## Layout

- `app/src/main/java/global/ptg/data` — Go bridge wrapper, MediaStore, Room, settings, Keystore sealer
- `.../backup` — the backup job, album sync, notifications, widget, tile
- `.../ui` — Compose screens
- `app/src/test` — unit tests (backoff, statuses, settings, album sync)
