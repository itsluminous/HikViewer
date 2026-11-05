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

Point Gradle at the SDK once:

```sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS; use your path on Linux
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home  # example
```

`local.properties` is gitignored.

## Modules

| Module | What it is | Tests |
|---|---|---|
| `:core` | Pure Kotlin/JVM: Hik-Connect REST client, VTDU token, VTM relay framing, RTP/MPEG-PS demux, NAL decryption. No Android imports. | JUnit 5 + Truth + MockWebServer, run on the JVM. |
| `:app` | Android application `com.itsluminous.hikviewer`: UI, MediaCodec decoder pool, encrypted settings. | JUnit 4 + Robolectric (unit), Espresso (instrumented). |

## Everyday commands

```sh
./gradlew assembleDebug            # debug APKs -> app/build/outputs/apk/debug/
./gradlew assembleRelease          # release APKs (debug-signed unless a keystore is configured, see below)
./gradlew :core:test               # protocol unit tests (JUnit 5)
./gradlew :app:testDebugUnitTest   # UI unit tests (Robolectric)
./gradlew check                    # every quality gate, see next section
./gradlew ci                       # alias for what CI runs (= check on every module)
./gradlew ktlintFormat             # auto-fix formatting findings
```

Builds produce one universal APK per build type (`app/build/outputs/apk/debug/app-debug.apk`,
`app/build/outputs/apk/release/app-release.apk`). There are no ABI splits: the app has no native code,
so a per-ABI APK would only be a smaller-looking copy of the same thing.

## Quality gates

`./gradlew check` runs all of these and fails on the first problem:

| Gate | Tool and configuration | Report |
|---|---|---|
| Unit tests | JUnit 5 (`:core`), JUnit 4 + Robolectric (`:app`) | `*/build/reports/tests/` |
| Formatting | ktlint (`org.jlleitschuh.gradle.ktlint`), `ktlint_official` style from `.editorconfig` | `*/build/reports/ktlint/` |
| Static analysis | detekt, `config/detekt/detekt.yml` on top of the defaults, `maxIssues: 0`, no baseline | `*/build/reports/detekt/` |
| Android Lint | `abortOnError`, `warningsAsErrors`, `checkAllWarnings`, `checkDependencies`; tuning in `config/lint/lint.xml`, no baseline | `app/build/reports/lint-results-debug.*` |
| Coverage | Kover, XML + HTML; line-coverage floor 85 % in `:core`, 75 % in `:app` (debug variant) | `core/build/reports/kover/`, `app/build/reports/kover/` |

Fix the finding rather than suppressing it. If a rule is wrong for this project, tune it in the config
file with a comment that says why.

## Emulator (Android TV)

The app is developed against a headless Android TV emulator; see
[`.research/EMULATOR.md`](.research/EMULATOR.md) if you have the research folder, otherwise:

```sh
SDK=$HOME/Library/Android/sdk
# API 34 Android TV image. Google ships it for arm64-v8a (Apple Silicon) and x86 (Intel/CI).
$SDK/cmdline-tools/latest/bin/sdkmanager --install "system-images;android-34;android-tv;arm64-v8a"
$SDK/cmdline-tools/latest/bin/avdmanager create avd -n HikViewer_TV_1080p -d tv_1080p \
    -k "system-images;android-34;android-tv;arm64-v8a"
$SDK/emulator/emulator -avd HikViewer_TV_1080p -no-window -no-audio -no-snapshot -gpu swiftshader_indirect &
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
```

Then install and run:

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.itsluminous.hikviewer.debug/com.itsluminous.hikviewer.ui.login.LoginActivity
```

To exercise the real cloud, put your Hik-Connect credentials in a gitignored `local.env`
(`{"mobile": "91…", "password": "…", "encryption": "…"}`) and let the scripts type them in, so the
values never reach a shell history or a screenshot:

```sh
scripts/emu-login.sh emulator-5554      # installs the debug APK, clears data, logs in
scripts/emu-code.sh  emulator-5554      # answers the "video encryption password" dialog
adb -s emulator-5554 logcat -s LiveBudget:I TilePlayer:I   # live-tile budget and codec choices
```

Instrumented (Espresso) tests run against every connected device, so make sure only the emulator is
attached, or pin it. They use a fake cloud (`app/src/sharedTest`) and real `MediaCodec` decoders fed
from the committed H.264 capture, so no account is needed:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

The touch suite (`PhoneNavigationTest`) needs a phone emulator (for example `Android_16_AOSP_Medium`
on `-port 5558`); it skips itself on a television and the D-pad suites skip themselves on a phone, so
the same command is safe on either. It locks its task to portrait, so the emulator's rotation setting
does not matter.

A real TV runs the **release** APK (`app/build/outputs/apk/release/app-release.apk`, debug-signed
without a keystore): the debug build carries the `.debug` application-id suffix and would install as a
second app next to it. Install with `adb install -r`; settings, login and the verification code survive
an upgrade in place.

Memory check (the brief's budget is 150 MB PSS): with 12 tiles on the 1080p AVD the app process sits
around 100 MB (`adb shell dumpsys meminfo com.itsluminous.hikviewer.debug | grep "TOTAL PSS"`). On a
1 GB AVD (`hw.ramSize=1024M`) `LiveBudget` logs `ceiling=4` and demotes tiles to keyframe mode as the
decoders fall behind.

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

Pushing a tag `v*` runs `.github/workflows/release.yml`, which builds, signs and attaches the universal
APK plus a `SHA256SUMS` file to a GitHub release. Signing uses these repository secrets;
when they are missing the APKs are debug-signed:

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
- `build` assembles debug and release APKs and uploads them.
- `instrumented` runs the Espresso suite on an API 34 Android TV emulator and on an API 34 phone
  emulator (the D-pad suites skip themselves on the phone, the touch suite on the TV); it only runs on
  pushes to `main` and manual runs because the emulators take several minutes to boot.

Dependabot (`.github/dependabot.yml`) opens weekly PRs for the version catalog and the actions.
