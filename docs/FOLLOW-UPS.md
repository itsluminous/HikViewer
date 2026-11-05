# Follow-ups

Known gaps, open questions and ideas that were weighed and set aside, with the reason each one is
still here. Items come from the round-3 code review and the CPU work; none of them blocks a release.
Open an issue before picking one up so the discussion has a home.

## Needs a product decision

- **What "1 + N" means for N > 5.** The layouts 1 + 7, 1 + 9, 1 + 11 and 1 + 15 put the hero on a
  square (k + 1) × (k + 1) cell grid with the hero spanning k × k cells, so the hero is 3×, 4×, 5× and 7×
  a small tile respectively. A hero exactly twice the size of a small tile on a uniform grid can only
  leave 5, 8, 11, 12, 14 or 16 companions, so a strict "2× hero" cannot yield 7 or 9. On a 1080p TV
  "1 + 15" gives 240 × 135 px companions for a 960 × 480 stream. The maths is tested
  (`GridGeometryTest`); the question is whether this family is the one wanted, or whether 2×-hero
  layouts with 8 / 11 / 14 companions should replace it.
- **Cached-process footprint.** After Back the process stays cached at roughly 45 MB PSS, above the
  30 MB the brief asked for. 35 MB of that is shared framework pages (`.so`, `.jar`, `.art`) attributed
  proportionally; the app's private footprint in that state is about 0.6 MB of data plus 10 MB of
  code, and it holds no sockets, codecs, views or activities. Nothing in the app can shrink the shared
  part. Either accept the measured outcome or lower the code size with a tighter R8 configuration.

## Needs a real TV

- **Confirm the API-29 native-heap fix on the MiTV.** Round 3 found the grid growing the native heap by
  ~3.9 MB/min on the Android 10 TV, caused by the keyframe pool switching `MediaCodec` output surfaces
  ~24 times a second. The pool now decodes to buffers and paints a bitmap, so there are zero
  `setOutputSurface` calls; the emulator shows a flat native heap and one pool codec. The TV itself has
  not been re-measured since. Recipe: install the release APK, stream the grid for 3 min,
  `dumpsys meminfo com.itsluminous.hikviewer | grep 'Native Heap'` every 60 s (expect flat), `top`
  (expect `logd` far below the 61 % seen before, `media.codec` lower), `logcat -s KeyframePool TilePlayer`
  (expect one `codec #1` line, no `recreating`, hardware refusals spaced 5 / 10 / 20 … s).
- **Thumbnail upload cost.** On the emulator (swiftshader) the keyframe stills cost RenderThread ~16 %
  of a core for 13 tiles because each bitmap is uploaded through software GL. On a TV with a GPU the
  13 texture uploads per second (about 3.4 MB/s) should be negligible. If the TV disagrees, lower
  `CameraTileView.STILL_MAX_WIDTH` from 480 to 320, which halves both the YUV → RGB conversion and the
  upload.
- **CPU budget on the 1.7 GB TV.** Before the round-3 fixes the TV sat at `media.codec` 137–157 % and app
  43 % of one core with 8 live software tiles (budget ≤ 150 % / ≤ 40 %). The fixes removed two churn
  sources (surface switching, hardware retries every 5 s) but the TV has not been re-measured.

## Code and tests

- **`GridActivity.updateLiveCount` recounts every player on each tile state change** (O(n²) over 16
  tiles). Harmless today; a counter maintained incrementally would be cleaner.
- **`PlaybackCoordinator.stop()` does not emit a new player list**, so the grid's tile collectors keep
  observing released players until the next bind. They are `StateFlow` collectors and cost nothing,
  but a terminal emission would make the lifecycle easier to reason about.
- **Large files.** `NalDecryptor.kt` (~490 lines) and `PlaybackViewModel.kt` (~450 lines) are past the
  "small files" guidance. The decryptor's three key variants could become three small strategies; the
  playback view model could split the transport (window reopen, hop timer) from the UI state.
- **Phone Espresso suite is thin.** `PhoneNavigationTest` has two tests: the persistent bar, and the
  portrait camera screen with a swipe and a bar scrub. The speed toggle, the date chip and landscape
  on a phone are only covered by Robolectric.
- **Verification dialog over the grid.** While the "video encryption password" dialog is open the
  tiles underneath keep decoding. The dialog is up for seconds, so the saving was judged not worth a
  covered-tile signal from the UI into the player; revisit if the dialog ever stays open longer.

## CPU ideas set aside, worth revisiting if the numbers change

These were measured or reasoned about in round 3 and not adopted; the reasons are recorded so the
next person does not repeat the work blind.

- **`Surface.setFrameRate`, `KEY_OPERATING_RATE`, `KEY_PRIORITY`.** Not measurable on the emulator
  (the goldfish / `c2.android` decoders ignore them) and the target TV runs API 29 where
  `setFrameRate` does not exist. An unmeasured hint to a vendor OMX decoder risks lower frame rates
  for no proven gain. Worth a trial on an API 31+ TV with a hardware decoder.
- **Smaller or opaque SurfaceViews.** Already opaque; the buffer size is set by the decoder, not the
  view, and SurfaceFlinger scales the 960 × 480 buffer onto the tile. A smaller view saves nothing in
  decode. Only a smaller sub-stream on the recorder changes this.
- **One NIO selector thread for all relay sockets.** Threads blocked in `recv` cost no CPU; the cost
  is per byte and per syscall, which the 64 KB buffered reads already address. A selector rewrite of
  `VtmRelayClient` would remove ~12 idle threads at considerable risk in the one component the
  real-account tests exercise.
- **Collapsing the `flowOn(Dispatchers.IO)` hop per picture.** About 144 channel hand-offs per second
  across 12 tiles, below the measurement noise floor.
- **Dropping P-frames.** Impossible: they are reference frames. Keyframe-only mode is the only valid
  reduction and already exists.
- **Lowering `STILL_MAX_WIDTH`** (see "Thumbnail upload cost" above).
