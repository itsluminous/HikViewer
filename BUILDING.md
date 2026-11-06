# Building HikViewer

Every command below was run on macOS (Apple Silicon) with the versions listed; CI runs the same Gradle tasks on Ubuntu, so the commands are the same there.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 (Temurin or Corretto) | The project pins a Java 17 toolchain; AGP 9.5 needs at least 17. Set `JAVA_HOME` if your default `java` is older. |
| Android SDK | platform 37 (`compileSdk`), build-tools 36.0.0 | Install via Android Studio or `sdkmanager "platforms;android-37.0" "build-tools;36.0.0"` (the SDK names platform 37 with its minor version). `targetSdk` is 36, `minSdk` 26. |
| Gradle | wrapper, 9.8.2 | Nothing to install: `./gradlew` downloads it. |
| Android Gradle Plugin | 9.5.1 | From `gradle/libs.versions.toml`. AGP 9 compiles Kotlin itself (built-in Kotlin), so there is no `kotlin-android` plugin in the build. |
| Kotlin | 2.4.21 | From `gradle/libs.versions.toml`; the `kotlin-jvm` plugin on the root classpath is the Kotlin Gradle plugin AGP picks up. |
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
| `:core` | Pure Kotlin/JVM: Hik-Connect REST client, VTDU token, VTM relay framing, RTP/MPEG-PS demux, NAL decryption, recording search and playback. No Android imports. | JUnit 6 (Jupiter) + Truth + MockWebServer, run on the JVM. |
| `:app` | Android application `com.itsluminous.hikviewer`: UI, MediaCodec decoder pool, encrypted settings. | JUnit 4 + Robolectric (unit), Espresso (instrumented). |

## Everyday commands

```sh
./gradlew assembleDebug            # debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease          # release APK (debug-signed unless a keystore is configured, see below)
./gradlew :core:test               # protocol unit tests (JUnit 6)
./gradlew :app:testDebugUnitTest   # UI unit tests (Robolectric)
./gradlew check                    # every quality gate, see next section
./gradlew ci                       # alias for what CI runs (= check on every module + noEmDash)
./gradlew fixEmDash                # rewrite every em dash in tracked text files to ' - '
./gradlew ktlintFormat             # auto-fix formatting findings
```

Builds produce one universal APK per build type. There are no ABI splits: the app has no native code, so a per-ABI APK would only be a smaller-looking copy of the same thing. The debug build has the application id `com.itsluminous.hikviewer.debug`, so it installs next to a release build instead of replacing it. Sizes at `v0.1.0`: debug 9.4 MB, release (R8, resources shrunk) 2.0 MB; with AGP 9 (R8 optimized resource shrinking on by default) the release APK is 1.7 MB.

## Quality gates

`./gradlew check` runs all of these and fails on the first problem:

| Gate | Tool and configuration | Report |
|---|---|---|
| Unit tests | JUnit 6 (`:core`, 171 tests), JUnit 4 + Robolectric (`:app`, 379 tests in the debug variant) | `*/build/reports/tests/` |
| Formatting | ktlint (`org.jlleitschuh.gradle.ktlint`), `ktlint_official` style from `.editorconfig` | `*/build/reports/ktlint/` |
| Static analysis | detekt, `config/detekt/detekt.yml` on top of the defaults, `maxIssues: 0`, no baseline | `*/build/reports/detekt/` |
| Android Lint | `abortOnError`, `warningsAsErrors`, `checkAllWarnings`, `checkDependencies`; tuning in `config/lint/lint.xml`, no baseline | `app/build/reports/lint-results-debug.*` |
| Coverage | Kover, XML + HTML; line-coverage floor 85 % in `:core`, 75 % in `:app` (debug variant); measured 97 % and 88 % at `v0.1.0` | `core/build/reports/kover/`, `app/build/reports/kover/` |
| Em dash | `noEmDash` (root project): fails listing `file:line` for every U+2014 in a `git ls-files` text file; `./gradlew fixEmDash` rewrites them to ` - ` | console |

Fix the finding rather than suppressing it. If a rule is wrong for this project, tune it in the config file with a comment that says why.

## Emulators

The app is developed against headless emulators: an Android TV AVD for the D-pad screens and a phone AVD for the touch screens.

```sh
SDK=$HOME/Library/Android/sdk
# API 34 Android TV image. Google ships it for arm64-v8a (Apple Silicon) and x86 (Intel/CI).
$SDK/cmdline-tools/latest/bin/sdkmanager --install "system-images;android-34;android-tv;arm64-v8a"
$SDK/cmdline-tools/latest/bin/avdmanager create avd -n HikViewer_TV_1080p -d tv_1080p \
    -k "system-images;android-34;android-tv;arm64-v8a"
$SDK/emulator/emulator -avd HikViewer_TV_1080p -no-window -no-audio -no-snapshot -gpu swiftshader_indirect &
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
```

A second AVD with `hw.ramSize = 1024M` in its `config.ini` (`HikViewer_TV_1GB`) reproduces the 1 GB TV case: `LiveBudget` logs `ceiling=4` and demotes tiles to keyframe stills as the software decoders fall behind. Any phone AVD works for the touch screens; start it on another port (`-port 5558`) so both can be attached at once.

Then install and run:

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.itsluminous.hikviewer.debug/com.itsluminous.hikviewer.ui.login.LoginActivity
```

### Demo mode and screenshots

Debug builds contain a demo entry point (`app/src/debug/`) that swaps the Hik-Connect cloud for an in-memory "Demo DVR": placeholder camera names and three bundled ffmpeg test patterns (`app/src/debug/assets/demo/*.ts`, 320 × 180 at 12 fps, 76 KB in total) that run through the real demuxer and `MediaCodec` path. It exists so the README screenshots never show anyone's cameras, and it is handy for trying the UI without an account. The release build has none of it.

```sh
PKG=com.itsluminous.hikviewer.debug
adb -s emulator-5554 shell am start -n $PKG/com.itsluminous.hikviewer.demo.DemoActivity \
    --es screen grid --es layout GRID_4X3 --ei cameras 12
```

Extras: `screen` = `login` | `grid` | `fullscreen` | `camera` (phone portrait screen) | `playback` | `settings`; `layout` = a `GridLayoutMode` name (`AUTO`, `GRID_3X3`, `HERO_1_7`, …); `cameras` = channel count; `index` = tile to open; `names` = overlay the camera names (default on).

`scripts/demo-screenshots.sh <tv-serial> [<phone-serial>]` drives this to produce the eight files under `docs/screenshots/` (1080p from the TV AVD, portrait from the phone AVD, each shrunk until it is under 400 KB). The clips are three stock pictures of empty rooms with a slow zoom; to regenerate them from a folder holding `hall.jpg`, `lobby.jpg` and `room.jpg`:

```sh
scripts/gen-demo-clips.sh /path/to/stills
```

The script encodes every clip with the same x264 flags (640x360, 12 fps, Constrained Baseline, one-second GOP), which gives them byte-identical SPS/PPS, so the keyframe pool keeps a single decoder for all tiles exactly as it does with one recorder. Use stock pictures only, never footage from anyone's cameras.

### The real cloud

To exercise the real cloud on an emulator, put your Hik-Connect credentials in a gitignored `local.env` (`{"mobile": "91…", "password": "…", "encryption": "…"}`, the mobile number with its country code) and let the scripts type them in, so the values never reach a shell history or a screenshot:

```sh
scripts/emu-login.sh emulator-5554      # installs the debug APK, clears data, logs in
scripts/emu-code.sh  emulator-5554      # answers the "video encryption password" dialog
adb -s emulator-5554 logcat -s LiveBudget:I TilePlayer:I KeyframePool:I   # budget and codec choices
```

Both scripts refuse any serial that is not an `emulator-NNNN`. Keep real captures in the gitignored `.screenshots/` directory, never under `docs/`.

The `:core` module has an end-to-end test against the real account, `RealAccountIntegrationTest` (log in, list cameras, decrypt ten keyframes of a live sub-stream; list yesterday's recordings and decrypt five keyframes of a 60 s playback window). It runs only when the environment variable points at the file, so CI never executes it:

```sh
HIKVIEWER_LOCAL_ENV=$PWD/local.env ./gradlew :core:test --tests '*RealAccountIntegrationTest*' --rerun
```

### Instrumented tests

Instrumented (Espresso) tests run against every connected device, so pin the device. They use the fake cloud from `app/src/sharedTest` and real `MediaCodec` decoders fed from the committed synthetic H.264 clip, so no account is needed:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest   # TV: D-pad suites, 18 of the 22 tests run
ANDROID_SERIAL=emulator-5558 ./gradlew connectedDebugAndroidTest   # phone: touch suite
```

The touch suite (`PhoneNavigationTest`) skips itself on a television and the D-pad suites skip themselves on a phone, so the same command is safe on either. It locks its task to portrait, so the emulator's rotation setting does not matter.

### Real TV

A real TV runs the **release** APK (`app/build/outputs/apk/release/app-release.apk`, debug-signed without a keystore): the debug build carries the `.debug` application-id suffix and would install as a second app next to it. Install with `adb install -r`; settings, login and the verification code survive an upgrade in place.

Memory and CPU reference points (release build, 16 channels, real account): on a 1080p AVD with 1.5 GB the app sits around 100 MB PSS while streaming; on a 1 GB AVD `media.swcodec` + the app + SurfaceFlinger total about one core (≈104 % of one core) with four live tiles and twelve keyframe stills. Twenty seconds after HOME the process uses 0 % CPU, holds no sockets and no codecs; after Back it is a plain cached process of roughly 45 MB PSS, 35 MB of which are shared framework pages. Check with `adb shell dumpsys meminfo com.itsluminous.hikviewer | grep "TOTAL PSS"`.

Reading a TV session: the release build logs one INFO line per tile every 10 s under the `HikViewer` tag (`chN mode=… state=… in= out= queued= fps= created=`) plus the live budget's `live limit=`, and the `TilePlayer` / `LiveBudget` / `KeyframePool` tags carry the events (`decoder stalled`, `stream lost`, `live budget: demote|promote`, `codec #N`). Channels are named by number only; no serial or session id is ever logged. A healthy MiTV (Android 10, 4×A55) shows every live tile at the recorder's frame rate with `in − out ≤ 5`, `queued=0`, no `decoder stalled` after the first minutes, `media.codec`/OMX around 150–200 % and the app 60–90 % of one core with 7 software tiles, and about 85 MB PSS. Keyframe tiles add `stills=N stillAge=ms` to their line; a healthy one gains a still a second (`KeyframePool` logs the first twenty jobs and every hundredth with the still's size).

When the remote feels slow, the question is where the time goes, and the answers are in different places. The **main thread**: every key logs `key KEYCODE_… down reached the grid N ms after the press` under `HikViewer` (healthy: under 50 ms); the debug build also installs a main-thread watchdog (`MainWatchdog`: every message over 100 ms with the main thread's stack, taken while it is still running) and a logging `StrictMode` thread policy, both from `DebugInitProvider`, so nothing of it is in the release build. The **display**: `dumpsys gfxinfo <pkg>` (janky %, percentiles; reset it first, then stream for a minute; `framestats` splits each frame into UI and GPU stages) and `dumpsys SurfaceFlinger` (`HWC missed frame count` must not grow; the `Display 0 HWC layers` table says which layers the composer takes as overlays and which it sends to the GPU as `CLIENT`). On the MiTV the composer takes one overlay and sends the rest to the GPU, which is why `RenderPacer` lines the software tiles' pictures up on a 15 Hz cadence. Do not time `input keyevent` itself: starting that tool takes 2–3 s on a loaded TV. `scripts/tv-login.sh <apk> <package>` installs a build on the real TV through `tv.py` (no stock adb) and types the credentials and the verification code from `local.env`, the way `emu-login.sh` does on an emulator.

## TV launcher banner

Android TV shows `app/src/main/res/drawable-xhdpi/banner.webp` on its home screen; it must be exactly 320 × 180 px. `scripts/gen-banner.py` draws it (lens glyph, the name, an accent rule) with Pillow, sizes the text from the width left of the glyph and asserts in pixels that all ink stays inside a 20 px padding before writing the lossless WebP with `cwebp`; `BannerTest` keeps the committed file at 320 × 180 by reading its header. Re-run the script after changing the design; never hand-edit the image.

## Versioning and release signing

The version comes from the Gradle property `versionName`: `./gradlew assembleRelease -PversionName=1.2.3` sets `versionName` 1.2.3 and `versionCode` 10203 (`major * 10000 + minor * 100 + patch`; `0.1` and `0.1.0` are both 100; a pre-release suffix such as `-rc1` is kept in the name and ignored for the code) and writes the APK as `app/build/outputs/apk/release/HikViewer-1.2.3.apk`. The release workflow passes the tag without its leading `v`. A build without the property is `0.0.0-dev` with `versionCode` 1 and keeps the default file name `app-release.apk`; it never outranks a published release, so to update a device that already runs a release from a local build pass a higher `-PversionName`.

`assembleRelease` looks for signing material in this order and falls back to the debug keystore when nothing is found, so a release build always succeeds:

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

Pushing a tag `vX.Y` or `vX.Y.Z` runs `.github/workflows/release.yml`, which runs the quality gates, builds `HikViewer-X.Y[.Z].apk` with `-PversionName=X.Y[.Z]`, and attaches it plus a `SHA256SUMS` file to a GitHub release with GitHub's auto-generated release notes (marked pre-release when the tag contains a `-`). There is no changelog in the repository.

Signing uses these repository secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 < release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

The job cannot test a secret in a step's `if:` (the `secrets` context is not available there), so it copies `secrets.KEYSTORE_BASE64 != ''` into a job-level `HAS_KEYSTORE` env var. With the secrets set, the keystore is decoded to `$RUNNER_TEMP`, Gradle signs with it, and a verify step reads the certificate's SHA-256 fingerprint out of the keystore with `keytool` and fails the job unless `apksigner verify --print-certs` reports exactly that fingerprint on the APK, so a mis-wired secret (wrong alias, empty password) cannot publish a debug-signed release. Without the secrets the APK is debug-signed and the step only prints a warning, so a fork can cut test releases.

Verify a downloaded APK with `apksigner verify --print-certs HikViewer-<version>.apk` and `sha256sum -c SHA256SUMS`.

## Documentation style

Markdown files are not hard-wrapped: one paragraph or one list item is one line, so a diff shows the sentence that changed instead of a reflowed block. `scripts/unwrap-markdown.py <files>` joins wrapped lines (code blocks, tables, headings and hard breaks are left alone) and `--check` reports files that still wrap; it asserts that only line breaks changed before writing. No em dash (U+2014) anywhere in the repository, see `./gradlew noEmDash` under Quality gates.

## Continuous integration

`.github/workflows/ci.yml`:

- `verify` runs `./gradlew ci` and uploads test, static-analysis and coverage reports.
- `build` assembles debug and release APKs and uploads them (`apk-debug`, `apk-release-debug-signed`).
- `instrumented` runs the Espresso suite on an API 34 Android TV emulator and on an API 34 phone emulator (the D-pad suites skip themselves on the phone, the touch suite on the TV); because the emulators take several minutes to boot it runs on pushes to `main`, on manual runs, and on pull requests that change code under `app/` or `core/`, the Gradle files or the workflows (a `changes` job with `dorny/paths-filter` decides); a docs-only pull request skips it.

Dependabot (`.github/dependabot.yml`) opens weekly PRs for the version catalog and the actions.

## Repository layout

```
app/                 Android application (:app)
  src/main/          UI, player, data stores, resources
  src/debug/         demo mode (DemoActivity, synthetic clips), debug builds only
  src/test/          Robolectric unit tests; src/testDebug/ for the demo mode
  src/sharedTest/    fakes shared by Robolectric and Espresso (FakeCloudServices, SyntheticStream)
  src/androidTest/   Espresso suites (TV D-pad, phone touch)
core/                pure-JVM protocol module (:core) with synthetic fixtures (scripts/gen-fixtures.sh)
config/              detekt and Lint configuration
docs/                screenshots, FOLLOW-UPS.md
scripts/             emulator helpers (login, verification code, demo screenshots), gen-fixtures.sh, gen-banner.py, unwrap-markdown.py
.github/workflows/   ci.yml, release.yml
```
