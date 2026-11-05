# Building HikViewer

Every command below was run on macOS (Apple Silicon) with the versions listed; CI runs the same
Gradle tasks on Ubuntu, so the commands are the same there.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 (Temurin or Corretto) | The project pins a Java 17 toolchain. Set `JAVA_HOME` if your default `java` is older. |
| Android SDK | platform 35, build-tools 35.0.0 | Install via Android Studio or `sdkmanager "platforms;android-35" "build-tools;35.0.0"`. |
| Gradle | wrapper, 8.14.6 | Nothing to install: `./gradlew` downloads it. |
| Android Gradle Plugin | 8.7.3 | From `gradle/libs.versions.toml`. |
| Kotlin | 2.1.21 | From `gradle/libs.versions.toml`. |
| ffmpeg | any recent | Only to regenerate the demo clips (see below); not needed to build. |

Point Gradle at the SDK once:

```sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS; use your path on Linux
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home  # example
```

`local.properties` is gitignored.

## Modules

| Module | What it is | Tests |
|---|---|---|
| `:core` | Pure Kotlin/JVM: Hik-Connect REST client, VTDU token, VTM relay framing, RTP/MPEG-PS demux, NAL decryption, recording search and playback. No Android imports. | JUnit 5 + Truth + MockWebServer, run on the JVM. |
| `:app` | Android application `com.itsluminous.hikviewer`: UI, MediaCodec decoder pool, encrypted settings. | JUnit 4 + Robolectric (unit), Espresso (instrumented). |

## Everyday commands

```sh
./gradlew assembleDebug            # debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease          # release APK (debug-signed unless a keystore is configured, see below)
./gradlew :core:test               # protocol unit tests (JUnit 5)
./gradlew :app:testDebugUnitTest   # UI unit tests (Robolectric)
./gradlew check                    # every quality gate, see next section
./gradlew ci                       # alias for what CI runs (= check on every module)
./gradlew ktlintFormat             # auto-fix formatting findings
```

Builds produce one universal APK per build type. There are no ABI splits: the app has no native
code, so a per-ABI APK would only be a smaller-looking copy of the same thing. The debug build has
the application id `com.itsluminous.hikviewer.debug`, so it installs next to a release build
instead of replacing it. Sizes at `v0.1.0`: debug 9.4 MB, release (R8, resources shrunk) 2.0 MB.

## Quality gates

`./gradlew check` runs all of these and fails on the first problem:

| Gate | Tool and configuration | Report |
|---|---|---|
| Unit tests | JUnit 5 (`:core`, 165 tests), JUnit 4 + Robolectric (`:app`, 232 tests in the debug variant) | `*/build/reports/tests/` |
| Formatting | ktlint (`org.jlleitschuh.gradle.ktlint`), `ktlint_official` style from `.editorconfig` | `*/build/reports/ktlint/` |
| Static analysis | detekt, `config/detekt/detekt.yml` on top of the defaults, `maxIssues: 0`, no baseline | `*/build/reports/detekt/` |
| Android Lint | `abortOnError`, `warningsAsErrors`, `checkAllWarnings`, `checkDependencies`; tuning in `config/lint/lint.xml`, no baseline | `app/build/reports/lint-results-debug.*` |
| Coverage | Kover, XML + HTML; line-coverage floor 85 % in `:core`, 75 % in `:app` (debug variant); measured 97 % and 88 % at `v0.1.0` | `core/build/reports/kover/`, `app/build/reports/kover/` |

Fix the finding rather than suppressing it. If a rule is wrong for this project, tune it in the config
file with a comment that says why.

## Emulators

The app is developed against headless emulators: an Android TV AVD for the D-pad screens and a
phone AVD for the touch screens.

```sh
SDK=$HOME/Library/Android/sdk
# API 34 Android TV image. Google ships it for arm64-v8a (Apple Silicon) and x86 (Intel/CI).
$SDK/cmdline-tools/latest/bin/sdkmanager --install "system-images;android-34;android-tv;arm64-v8a"
$SDK/cmdline-tools/latest/bin/avdmanager create avd -n HikViewer_TV_1080p -d tv_1080p \
    -k "system-images;android-34;android-tv;arm64-v8a"
$SDK/emulator/emulator -avd HikViewer_TV_1080p -no-window -no-audio -no-snapshot -gpu swiftshader_indirect &
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
```

A second AVD with `hw.ramSize = 1024M` in its `config.ini` (`HikViewer_TV_1GB`) reproduces the 1 GB TV
case: `LiveBudget` logs `ceiling=4` and demotes tiles to keyframe stills as the software decoders
fall behind. Any phone AVD works for the touch screens; start it on another port
(`-port 5558`) so both can be attached at once.

Then install and run:

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.itsluminous.hikviewer.debug/com.itsluminous.hikviewer.ui.login.LoginActivity
```

### Demo mode and screenshots

Debug builds contain a demo entry point (`app/src/debug/`) that swaps the Hik-Connect cloud for an
in-memory "Demo DVR": placeholder camera names and three bundled ffmpeg test patterns
(`app/src/debug/assets/demo/*.ts`, 320 × 180 at 12 fps, 76 KB in total) that run through the real
demuxer and `MediaCodec` path. It exists so the README screenshots never show anyone's cameras, and
it is handy for trying the UI without an account. The release build has none of it.

```sh
PKG=com.itsluminous.hikviewer.debug
adb -s emulator-5554 shell am start -n $PKG/com.itsluminous.hikviewer.demo.DemoActivity \
    --es screen grid --es layout GRID_4X3 --ei cameras 12
```

Extras: `screen` = `login` | `grid` | `fullscreen` | `camera` (phone portrait screen) |
`playback` | `settings`; `layout` = a `GridLayoutMode` name (`AUTO`, `GRID_3X3`, `HERO_1_7`, …);
`cameras` = channel count; `index` = tile to open; `names` = overlay the camera names (default on).

`scripts/demo-screenshots.sh <tv-serial> [<phone-serial>]` drives this to produce the eight files
under `docs/screenshots/` (1080p from the TV AVD, portrait from the phone AVD, each shrunk until it
is under 400 KB). To regenerate the clips:

```sh
for src in testsrc2 smptebars testsrc; do
  ffmpeg -y -f lavfi -i "$src=size=320x180:rate=12" -t 2 \
    -vf "format=yuv420p,setrange=tv,setparams=colorspace=bt709:color_primaries=bt709:color_trc=bt709" \
    -c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p -g 12 -keyint_min 12 -bf 0 -refs 1 \
    -x264-params "repeat-headers=1:scenecut=0:ref=1:fullrange=off:colorprim=bt709:transfer=bt709:colormatrix=bt709" \
    -crf 28 -f mpegts "$src.ts"
done
```

The colour-space flags make every clip share one SPS/PPS, so the keyframe pool keeps a single
decoder for all tiles exactly as it does with one recorder.

### The real cloud

To exercise the real cloud on an emulator, put your Hik-Connect credentials in a gitignored
`local.env` (`{"mobile": "91…", "password": "…", "encryption": "…"}`, the mobile number with its
country code) and let the scripts type them in, so the values never reach a shell history or a
screenshot:

```sh
scripts/emu-login.sh emulator-5554      # installs the debug APK, clears data, logs in
scripts/emu-code.sh  emulator-5554      # answers the "video encryption password" dialog
adb -s emulator-5554 logcat -s LiveBudget:I TilePlayer:I KeyframePool:I   # budget and codec choices
```

Both scripts refuse any serial that is not an `emulator-NNNN`. Keep real captures in the gitignored
`.screenshots/` directory, never under `docs/`.

The `:core` module has an end-to-end test against the real account, `RealAccountIntegrationTest`
(log in, list cameras, decrypt ten keyframes of a live sub-stream; list yesterday's recordings and
decrypt five keyframes of a 60 s playback window). It runs only when the environment variable
points at the file, so CI never executes it:

```sh
HIKVIEWER_LOCAL_ENV=$PWD/local.env ./gradlew :core:test --tests '*RealAccountIntegrationTest*' --rerun
```

### Instrumented tests

Instrumented (Espresso) tests run against every connected device, so pin the device. They use the
fake cloud from `app/src/sharedTest` and real `MediaCodec` decoders fed from the committed H.264
capture, so no account is needed:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest   # TV: D-pad suites, 13 tests
ANDROID_SERIAL=emulator-5558 ./gradlew connectedDebugAndroidTest   # phone: touch suite
```

The touch suite (`PhoneNavigationTest`) skips itself on a television and the D-pad suites skip
themselves on a phone, so the same command is safe on either. It locks its task to portrait, so the
emulator's rotation setting does not matter.

### Real TV

A real TV runs the **release** APK (`app/build/outputs/apk/release/app-release.apk`, debug-signed
without a keystore): the debug build carries the `.debug` application-id suffix and would install as a
second app next to it. Install with `adb install -r`; settings, login and the verification code survive
an upgrade in place.

Memory and CPU reference points (release build, 16 channels, real account): on a 1080p AVD with
1.5 GB the app sits around 100 MB PSS while streaming; on a 1 GB AVD `media.swcodec` + the app +
SurfaceFlinger total about one core (≈104 % of one core) with four live tiles and twelve keyframe
stills. Twenty seconds after HOME the process uses 0 % CPU, holds no sockets and no codecs; after
Back it is a plain cached process of roughly 45 MB PSS, 35 MB of which are shared framework pages.
Check with `adb shell dumpsys meminfo com.itsluminous.hikviewer | grep "TOTAL PSS"`.

## Release signing

`assembleRelease` looks for signing material in this order and falls back to the debug keystore when
nothing is found, so a release build always succeeds:

1. Environment variables `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
2. A gitignored `keystore.properties` at the repository root:
   ```properties
   storeFile=release.jks        # relative to the repository root
   storePassword=...
   keyAlias=...
   keyPassword=...
   ```

Create a keystore once:

```sh
keytool -genkeypair -v -keystore release.jks -alias hikviewer -keyalg RSA -keysize 4096 -validity 10000
```

### GitHub releases

Pushing a tag `v*` runs `.github/workflows/release.yml`, which runs the quality gates, builds, signs
and attaches `HikViewer-<version>-universal.apk` plus a `SHA256SUMS` file to a GitHub release
(marked pre-release when the tag contains a `-`). Signing uses these repository secrets; when they
are missing the APK is debug-signed:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 < release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Verify a downloaded APK with `apksigner verify --print-certs HikViewer-<version>-universal.apk`.

## Continuous integration

`.github/workflows/ci.yml`:

- `verify` runs `./gradlew ci` and uploads test, static-analysis and coverage reports.
- `build` assembles debug and release APKs and uploads them (`apk-debug`, `apk-release-debug-signed`).
- `instrumented` runs the Espresso suite on an API 34 Android TV emulator and on an API 34 phone
  emulator (the D-pad suites skip themselves on the phone, the touch suite on the TV); it only runs on
  pushes to `main` and manual runs because the emulators take several minutes to boot.

Dependabot (`.github/dependabot.yml`) opens weekly PRs for the version catalog and the actions.

## Repository layout

```
app/                 Android application (:app)
  src/main/          UI, player, data stores, resources
  src/debug/         demo mode (DemoActivity, synthetic clips) — debug builds only
  src/test/          Robolectric unit tests; src/testDebug/ for the demo mode
  src/sharedTest/    fakes shared by Robolectric and Espresso (FakeCloudServices, SyntheticStream)
  src/androidTest/   Espresso suites (TV D-pad, phone touch)
core/                pure-JVM protocol module (:core) with fixtures from real captures
config/              detekt and Lint configuration
docs/                screenshots, FOLLOW-UPS.md
scripts/             emulator helpers (login, verification code, demo screenshots)
.github/workflows/   ci.yml, release.yml
```
