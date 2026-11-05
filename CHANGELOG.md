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
- Long-press menu on a tile: *Move* (D-pad, mouse drag or touch drag; OK drops, Back cancels),
  *Change* (a focusable list of your cameras; the pick replaces this tile's camera or swaps the two
  if it was already on the grid, remembered per layout like Move) and *Playback*.
- Live dot in the top-right corner of every tile: green while pictures reach the screen (a decoded frame within
  2 s, or a freshly rendered still on a keyframe-only tile), red while connecting, stalled or failed.
  The top bar's status comes from the same rule: "14 live" when every tile decodes at stream rate,
  otherwise "14 cameras · 8 live · 6 at 1 fps".
- Grid tiles are paced by the stream's own timestamps through a 500 ms jitter buffer, so the
  picture moves evenly instead of with the relay's arrival jitter (on the test TV the gap between
  shown pictures went from 83 ± 41 ms to 83 ± 15–27 ms). The pacing follows the tile's role, not
  its codec: the hero, fullscreen, the phone camera screen and recorded playback are never delayed,
  even while a refused hardware decoder leaves them on software.
- The live budget may try up to four tiles beyond its static ceiling, one at a time, when the device
  reports at least 35 % idle CPU and 96 MB to spare, and gives them back when either goes; a
  recorder set to 6–8 fps on a 1.8 GB TV then gets twelve live tiles instead of eight. The gate is
  35 % rather than 25 % because one more software tile took 12–17 points off the idle reading on the
  test TV, and promotions at 25 % were shed within a minute. The readings only exist while the grid
  has more tiles than the ceiling; below it the budget never leaves the ceiling.
- The failed-tile *Retry* button is an icon: the refresh glyph (two arrows forming a circle) drawn
  white with a thin black outline so it reads on any picture, no text, no background, 48 dp. It is
  the tile's own click (OK on the tile reopens the stream) and grows a tenth while its tile has focus.
- The focused tile's border hides 5 s after the last key or pointer movement, on the top bar's
  timer, and returns at the next one. Focus itself never moves; a tile lifted for *Move* always
  draws its border.
- A per-tile diagnostics line every 10 s in logcat (`HikViewer` tag) with the inter-picture gaps as
  they arrive and as they are shown (mean ± stddev, max), the jitter buffer's fill and the budget's
  idle and memory readings.
- Fullscreen view with LEFT / RIGHT (TV) or swipe (phone) to switch camera and a self-hiding hint.
- Recorded playback from the cloud: 24 h time bar with recorded spans, speed toggle −10× … +10×
  (speeds other than 1× step between keyframes), date chip, pause. LEFT / RIGHT seek 10 s and a held
  key accelerates 10 s → 30 s → 1 min → 5 min → 15 min (reset on release); the time label moves at
  once and the stream reopens 600 ms after the last seek input, so a burst of presses, a hold or a
  drag of the bar on a phone costs one reconnect. Times are the recorder's clock.
- Hidden top bar on TV (any key shows it, hides after 5 s, never takes focus); persistent bar on
  phones (top in portrait, left edge in landscape).
- Phone portrait camera screen: live video on top, the playback timeline, speed and date below.
- Settings: layout, visible cameras and order, hero camera, fullscreen stream quality, *Show camera
  names*, *Show frame rate*, *Change encryption password*, Logout.
- On-device decryption of encrypted streams (AES-ECB per NAL, three key variants detected
  automatically). The verification code is checked locally against the recorder's `MD5(MD5(code))`,
  stored in encrypted preferences and never asked again unless the recorder's code changes.

### Fixed

- On Android 10/11 TVs only one tile showed its live dot and the focus border vanished on some
  UP/DOWN moves. Each tile's `SurfaceView` punches its hole in the window with `drawColor(CLEAR)`
  over the whole current clip, and the round-4 tile had `clipChildren = false` so its focus frame
  could grow past the tile, which let every surface wipe the overlays of all tiles drawn before it
  (Android 12+ clears only the view's bounds, so emulators never showed it). Tiles now clip their
  children, the surfaces are media overlays, the focus border is a 4 dp stroke drawn inset inside
  the tile by an overlay view that is the tile's last child (no scale, no elevation; a lifted tile
  gets a thicker stroke and an accent wash), and the grid draws the focused tile last.
- The remote lagged by seconds while a grid streamed on the MiTV. The main thread was not the
  bottleneck (a D-pad press reaches the grid in 4-16 ms and no main-thread message runs over
  100 ms while streaming; a debug-only watchdog and `StrictMode` now report both); the display was.
  The vendor composer gives the grid one hardware overlay and SurfaceFlinger composites the other 16
  layers on the GPU, and eight tiles at 12 fps with uncorrelated phases made it recompose the whole
  screen ~60 times a second, so the app's own frames (focus border, top bar) queued behind it.
  Software tiles now present their pictures on a shared 15 Hz cadence (`RenderPacer`), which lands
  them in the same vsync: janky frames 51 % → 20 %, frame p90 32 → 21 ms, SurfaceFlinger 32 → 21 %
  CPU, no composer-missed frames, tile frame rate unchanged. Hero and fullscreen render at once.
- Tiles beyond the live budget showed black on the MiTV. The keyframe pool and the still sink were
  fine (they now log their deliveries); the stills were wiped by the same unclipped hole punch as the
  dots above. A Robolectric test covers the whole path from the first IDR to a non-empty bitmap.
- The tile that took focus went black for about a second: focus handed it a hardware decoder (and
  took one away from the tile losing focus), and each swap released a codec, created another and
  waited for the next keyframe. Focus no longer touches any decoder; the hardware decoder goes to
  the hero tile and to the fullscreen and phone camera screens.
- While a tile is lifted for *Move*, every software tile drops to keyframe stills and comes back
  live on drop, cancel or leaving the screen: on the MiTV the eight software decoder threads run at
  the UI thread's own scheduler priority, so a move is answered before the pictures are.
- The stream pipelines (relay reads, demux, decryption, feeding the decoders), the keyframe pool
  and the codec release thread run at background priority; the UI thread no longer shares its CPU
  with twenty default-priority workers. The connecting spinner is an 8 fps frame animation instead
  of the platform's 60 fps vector drawable, which also cost 1.2 s of main thread to inflate for 16
  tiles at launch. On the MiTV with the user's 4 × 3 grid (8 live + 4 keyframe tiles), 60 s idle:
  janky frames 52 % → 9 %, frame p90 29 → 15 ms, p99 44 → 23 ms, high-input-latency frames 48 → 0;
  a D-pad press reaches the grid in 4-17 ms and the focus border is drawn on the next frame.
- Software-decoded tiles froze for a few seconds about once a minute on the MiTV, and in earlier
  builds were then demoted to keyframe stills one after another. The cause was in the decryptor:
  with the "type hint" encryption variant a clear P-slice is mistaken for an encrypted one once in
  256 and decrypted into garbage, which hardware decoders conceal but the TV's software H.264
  decoder answers by producing no more output. The decryptor now learns which NAL header classes a
  recorder encrypts and leaves the others alone (0 garbled slices in 5759 against the real account,
  9 in 3599 before).
- Per-tile diagnostics in logcat (`HikViewer`, `TilePlayer` tags) name channels by number only; the
  recorder's serial no longer appears in any log line.
- Rotating a phone with the camera picker or the verification-code dialog open no longer leaks the
  dialog window.
- A seek key held while the focus moved to the date chip or the speed button no longer keeps its
  accelerated step for the next press on the bar.
- Adaptive decoding for weak TVs: a hardware decoder for the hero tile (and for fullscreen),
  software decoders for the rest, a shared keyframe-only pool for tiles beyond the live budget,
  which adapts to RAM, cores and measured decode lag. Only the tiles the layout shows
  are streamed. Hardware refusals back off exponentially; a `configure` refused while the previous
  codec still holds the surface is retried at the next keyframes; a failed tile dims and offers the
  retry icon.
- The headroom probe read as 100 % idle before it had measured anything, so a grid at or below the
  ceiling (where the probe never runs) climbed to a limit of twelve without a measurement, and the
  next, bigger grid started twelve software decoders at once and shed them one per ten seconds.
  Unknown now reads as no headroom everywhere, the budget only sees the probe while it runs, and it
  never probes past the tiles there are.
- The jitter buffer's capacity bound dropped the oldest picture even when it was a GOP's keyframe,
  which handed the decoder P-frames without their reference. It now drops to the newest keyframe,
  or the whole GOP, and counts a backwards timestamp (`remaps`) in the diagnostics line.
- Decoder watchdog: a decoder that accepts pictures but produces none is caught after ten swallowed
  pictures and 1.2 s (at any frame rate, 6 to 12 fps) instead of 3 s, and rebuilt without counting
  as decode lag, so a wedged software decoder never demotes its tile to keyframe stills.
- The H.264 SPS is rewritten to declare no frame reordering (`max_num_reorder_frames = 0`), so
  software decoders show each picture as soon as it is decoded instead of holding ten of them.
- Clean lifecycle: every stream, socket and decoder is released in `onStop`; no services, jobs or
  alarms.
- Universal APK (no ABI splits), Android TV and phone launchers in one build, `minSdk` 26.
- Debug-only demo mode (`DemoActivity`) with bundled synthetic test patterns and placeholder camera
  names, used for the README screenshots.
- Quality gates: ktlint, detekt, Android Lint (warnings as errors, no baseline), JUnit 5 protocol
  tests with known-answer fixtures generated by ffmpeg, Robolectric UI tests, Espresso suites for TV
  (D-pad) and phone (touch), Kover coverage floors (85 % `:core`, 75 % `:app`).
- GitHub Actions: CI (tests, lint, APKs, instrumented tests on TV and phone emulators) and a
  tag-triggered release workflow with optional keystore signing.

[Unreleased]: https://github.com/itsluminous/HikViewer/commits/main
