# Module app

The Android application: screens, decoder layer and encrypted storage. Protocol code is in `:core`.

# Package com.itsluminous.hikviewer.ui

Screens of HikViewer.

Architecture: **one Activity per screen** (`login.LoginActivity`, `grid.GridActivity`, `fullscreen.FullscreenActivity`, `settings.SettingsActivity`), each with a ViewModel exposing `StateFlow`, wired by hand through `di.AppGraph`. Fragments were rejected on purpose: on a TV every screen owns the whole display and its own focus rules (the grid routes UP into a hidden bar, fullscreen swallows LEFT/RIGHT, the settings list reorders rows on OK), and keeping those rules in separate windows means a focus bug in one screen cannot leak into another. The Activity stack also gives Back-to-grid and Back-to-launcher for free. Playback is tied to the grid and fullscreen Activities' started state, so `onStop` releases every decoder and socket.

# Package com.itsluminous.hikviewer.player

`MediaCodec` layer: `TilePlayer` per camera, `TileDecoder` (asynchronous codec API) for live tiles, `KeyframeDecoderPool` (two shared decoders) for one-frame-per-second tiles, `DecoderPolicy` and `LiveBudget` deciding who gets which, orchestrated by `PlaybackCoordinator`.

# Package com.itsluminous.hikviewer.data

`CloudServices` (the slice of `:core` the app uses, swappable for a fake), `AccountRepository` (login state + camera inventory), `SessionStore` and `SettingsStore` on encrypted preferences.
