# Changelog

All notable changes to HikViewer are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). Each release on GitHub attaches the universal APK and a
`SHA256SUMS` file.

## [Unreleased]

### Added

- Hik-Connect sign-in with region selector (the region supplies the country code; `+` or an e-mail
  address is sent as typed), session refresh and logout. CAPTCHA and SMS challenges produce a clear
  error.
- Camera grid of the account's channels with layouts Auto, 3 × 3, 4 × 3, 4 × 4 and 1 + 5 / 1 + 7 /
  1 + 9 / 1 + 11 / 1 + 15 (hero layouts); the menu lists only layouts every tile of which has a
  camera. Tile positions are kept per layout, the last layout is restored on launch.
- Long-press menu on a tile: *Move* (D-pad, mouse drag or touch drag; OK drops, Back cancels) and
  *Playback*.
- Fullscreen view with LEFT / RIGHT (TV) or swipe (phone) to switch camera and a self-hiding hint.
- Recorded playback from the cloud: 24 h time bar with recorded spans, LEFT / RIGHT seek with
  hold-to-accelerate, speed toggle −10× … +10× (speeds other than 1× step between keyframes), date
  chip, pause. Times are the recorder's clock.
- Hidden top bar on TV (any key shows it, hides after 5 s, never takes focus); persistent bar on
  phones (top in portrait, left edge in landscape).
- Phone portrait camera screen: live video on top, the playback timeline, speed and date below.
- Settings: layout, visible cameras and order, hero camera, fullscreen stream quality, *Show camera
  names*, *Show frame rate*, *Change encryption password*, Logout.
- On-device decryption of encrypted streams (AES-ECB per NAL, three key variants detected
  automatically). The verification code is checked locally against the recorder's `MD5(MD5(code))`,
  stored in encrypted preferences and never asked again unless the recorder's code changes.
- Adaptive decoding for weak TVs: at most two hardware decoders (focused and hero tiles), software
  decoders for the rest, a shared keyframe-only pool for tiles beyond the live budget, which adapts
  to RAM, cores and measured decode lag. Hardware refusals back off exponentially; a failed tile
  dims and offers *Retry*.
- Clean lifecycle: every stream, socket and decoder is released in `onStop`; no services, jobs or
  alarms.
- Universal APK (no ABI splits), Android TV and phone launchers in one build, `minSdk` 26.
- Debug-only demo mode (`DemoActivity`) with bundled synthetic test patterns and placeholder camera
  names, used for the README screenshots.
- Quality gates: ktlint, detekt, Android Lint (warnings as errors, no baseline), JUnit 5 protocol
  tests with known-answer fixtures from real captures, Robolectric UI tests, Espresso suites for TV
  (D-pad) and phone (touch), Kover coverage floors (85 % `:core`, 75 % `:app`).
- GitHub Actions: CI (tests, lint, APKs, instrumented tests on TV and phone emulators) and a
  tag-triggered release workflow with optional keystore signing.

[Unreleased]: https://github.com/itsluminous/HikViewer/commits/main
