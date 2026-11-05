# Follow-ups

Known gaps, open questions and ideas that were weighed and set aside, with the reason each one is
still here. Items come from the round-3 and round-4 code reviews and the CPU work; none of them
blocks a release. Open an issue before picking one up so the discussion has a home.

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

- **Phone drag on the playback bar does not accelerate.** Round 4 asked for the accelerating hold
  "or the phone's drag". A drag is an absolute scrubber (the finger's position *is* the time) and
  the debounce applies to it, but it does not step 10 s → 15 min like a held key, because a finger
  already covers the whole day in one gesture. If a relative, accelerating drag is wanted anyway
  (e.g. a slow drag near the thumb for fine seeking), `TimeBarView` has the press/drag/release hooks
  for it.

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

- **A 4-minute TV-wide freeze seen once.** During round 4 (12:28, first install of the live-dot build)
  the MiTV stopped logging from every process for four minutes right after launch; `lowmemorykiller`
  had just killed nine cached processes and the load average was 67 afterwards. The app survived and
  its streams reconnected; the freeze was not reproduced in the following runs. Memory headroom with
  the grid up is ~90–120 MB free. If it recurs, capture `dmesg`/`lowmemorykiller` lines around it.
- **Cold start on the MiTV demotes a tile or two for a couple of minutes.** In the round-4 final run
  (release build, 12 cameras in 4 × 3, 8 live) the first three minutes after a fresh process start
  showed back-to-back ART concurrent GCs of 1–3 s each (heap 17 → 20 MB, ~2.5 MB/s allocated by the
  demux/decrypt pipeline: a `ByteArray` per NAL plus a copy per encrypted NAL) on a saturated 4×A55;
  three software decoders fell 15–19 pictures behind at once, the budget demoted to 4 live and
  promoted back to 8 within two minutes, after which GCs were 80–440 ms and nothing stalled for the
  rest of the run. Working as designed, but a pooled NAL buffer (or decrypting in place also for
  TYPE_HINT, which currently copies) would cut the allocation rate and likely the warm-up dip.
- **App CPU with many software tiles.** With 7 software-decoded tiles the app process itself (not
  `media.codec`) ran at 60–100 % of one core on the 4×A55 TV: the decrypt/demux pipeline scales with
  tiles. The round-3 CPU notes list what was tried; the next candidates are the AES calls per NAL
  (one `Cipher.doFinal` per 16-byte probe block plus one per body) and the RTP reassembly copies.

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
- **`H264SpsRewriter` assumes no B-frames.** It declares `max_num_reorder_frames = 0` for every
  H.264 stream, which is true for this recorder (P-frames only, order count growing within a GOP;
  ffmpeg frame md5s are identical before and after) and what stops libavc holding ten pictures. A
  recorder that emits B-frames would display them out of order and nothing would say why. The SPS
  alone cannot tell; a guard would have to watch the order count of the first slices of a GOP and
  skip the rewrite when it ever goes backwards.
- **Two clocks in the player stack.** `PlaybackViewModel` ticks with `System.nanoTime()` (so the
  render timestamp handed to `MediaCodec` is on the same clock SurfaceFlinger uses) while the rest of
  the view model runs on `uptimeMillis()`. Giving `TilePlayer.tick` the injected clock would make the
  tests and the code read from one time source.
- **Small leftovers from the round-4 review.** `SeekController.isHolding`/`reset()` are only used by
  tests; three log tags (`HikViewer`, `TilePlayer`, `LiveBudget`) cover one subsystem, one prefix
  would make a TV logcat easier to grep; a tile dropped from the layout keeps its `onSurface` hook
  until it is re-bound (harmless, the player is stopped).
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
