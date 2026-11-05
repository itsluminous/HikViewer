# References

HikViewer implements a private protocol that others reverse-engineered first. This file credits the
projects and documents we learned from, and notes what each one taught us. Nothing below is vendored;
the protocol was re-implemented in Kotlin in the `:core` module.

## Protocol implementations

- **[Bahrombekk/cloud-cam-viewer](https://github.com/Bahrombekk/cloud-cam-viewer)** (Python, MIT +
  Apache-2.0). Our primary reference: logs in the way the official app does (`clientType` 55,
  `/v3/users/login/v2`, region redirect on meta code 1100), fetches the device page list with the
  filter the stream path needs, signs the VTDU token, speaks the binary VTM relay framing
  (`ysproto://`), demuxes RTP / MPEG-PS into NAL units, decrypts them with AES-ECB using the
  verification code (three key variants auto-detected from SPS/VPS/PPS), and paces the GOP bursts the
  cloud sends through a jitter buffer with a reconnect watchdog. Its
  [internals notes](https://github.com/Bahrombekk/cloud-cam-viewer/blob/main/docs/internals.md)
  documented the first-frame latency and the concurrent-stream ceiling we designed around.
- **[RenierM26/pyEzvizApi](https://github.com/RenierM26/pyEzvizApi)** (Python, Apache-2.0). The library
  cloud-cam-viewer's transport is adapted from; its
  [`docs/streaming.md`](https://github.com/RenierM26/pyEzvizApi/blob/master/docs/streaming.md) explains
  the EZVIZ/Hik-Connect cloud VTM/VTDU relay, the LAN "local SDK" path and the HCNetSDK port-8000
  protocol, and showed that the EZVIZ and Hik-Connect clouds share one backend. Cross-checked for the
  recording search (`/v3/streaming/records`) and the playback relay parameters.
- **[tomasbedrich/hikconnect](https://github.com/tomasbedrich/hikconnect)** (Python, MIT). The cleanest
  description of the login flow: MD5-hashed password, `featureCode`, the `loginArea.apiDomain` redirect
  (also present on a code-200 response), session refresh and the device/channel list endpoints.
- **[pedropaulovc/go2rtc fork](https://github.com/pedropaulovc/go2rtc/pull/2)** (Go, MIT; upstream
  discussion in [AlexxIT/go2rtc#2289](https://github.com/AlexxIT/go2rtc/issues/2289)). Shows the true
  P2P alternative (login → `P2P_SETUP` → UDP hole punch → SRT, HEVC + G.711) and, in its
  `feat/ezviz-playback` branch, **recordings playback over MPEG-PS**: the `/playback?…&begin=&end=`
  relay URL, the recorder-local time format, the end-of-window control packet, and the finding that
  variable speed and reverse are not reliable over the relay path, which is why HikViewer implements
  speeds other than 1× as keyframe stepping. We chose the TCP relay over P2P because it is simpler,
  works through NAT without hole punching and the ~0.6 Mbit/s sub-streams fit comfortably.
- **[ijtan/hikconnect-cloud-relay](https://github.com/ijtan/hikconnect-cloud-relay)** and
  **[Frens98/hikconnect-nvr](https://github.com/Frens98/hikconnect-nvr)** (Python, MIT). Confirmed the
  cloud video route and documented the official "Hik-Connect for Teams" OpenAPI (HLS, H.264 only),
  which we did not use because it needs per-team API keys and cannot deliver H.265 or low latency.
- **[phuthuycoding/hik-connect-proxy-viewer](https://github.com/phuthuycoding/hik-connect-proxy-viewer)**
  and **[yannik2480/hikvision_bridge](https://github.com/yannik2480/hikvision_bridge)**. LAN-side
  alternatives (HCNetSDK port 8000, ISAPI + RTSP) that we evaluated and rejected: this app must work
  away from home, over the cloud only.

## Android TV UI

- **[aryswisnu/hermes-tv](https://github.com/aryswisnu/hermes-tv)** (Java, MIT). An Android TV launcher
  with an RTSP camera grid; its README lists the TV-specific pitfalls (focus handling, decoder limits on
  cheap TVs) that shaped our plain-View, explicit-focus grid and the decoder pool.
- **[VibeCodingAntagonist/opah-frigate-tv-app](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app)**
  (Kotlin, Apache-2.0). Remote-first multi-camera UI; a reference for D-pad navigation patterns.

## Vendor documentation

- Hikvision / EZVIZ help on video encryption and the verification code: the Hik-Connect help page
  ["Enabling or Disabling Video and Image Encryption"](https://www.hik-connect.com/views/terms/helpAndroid/Hik%20Enabling%20or%20Disabling%20Video%20and%20Image%20Encryption.html)
  and Hikvision USA knowledge base articles
  [17000128922](https://supportusa.hikvision.com/support/solutions/articles/17000128922-what-is-a-verification-code-encryption-key)
  and
  [17000129204](https://supportusa.hikvision.com/support/solutions/articles/17000129204-i-can-t-see-the-live-view-of-the-cameras-in-the-web-browser-i-keep-getting-a-message-the-stream-is-).
  These establish that encryption applies to the platform stream (live view, playback and alarm
  pictures) and that a device's `encryptPwd` is `MD5(MD5(verificationCode))`, which lets the app
  verify a typed code locally before opening a stream.
- **[Ezviz-Open/EzvizSDK-Android](https://github.com/Ezviz-Open/EzvizSDK-Android)** (Apache-2.0). The
  official EZVIZ SDK; evaluated and rejected because it needs a paid EZVIZ Open Platform AppKey and does
  not serve Hik-Connect consumer accounts.
- The official Hik-Connect Android app itself, studied for its REST endpoints, region table
  (`area.xml`, where the telephone codes behind the region picker come from), the `featureCode`
  semantics and the playback URL shape. No code or asset of it is included here.

## Standards

- RFC 1321 (MD5), RFC 3550 (RTP), ISO/IEC 13818-1 (MPEG-PS), ITU-T H.264 / H.265 (NAL unit syntax,
  Annex B byte streams), FIPS 197 (AES), RFC 7519 (JWT, the session and VTDU tokens).

## Test footage

- Every binary fixture under `core/src/test/resources/fixtures/` is synthetic and reproducible with
  `scripts/gen-fixtures.sh`: ffmpeg's `testsrc2` / `testsrc` generators encoded by x264 and x265 to
  the shape the cloud sends (an H.264 High 960x480 @ 12 fps MPEG-TS sub-stream with an IDR every
  12 pictures; an HEVC Main 1280x720 @ 25 fps playback window with closed GOPs of 25), and the first
  GOP of the H.264 clip encrypted with cloud-cam-viewer's algorithm under a dummy verification code
  in each of the encryption variants. The JSON fixtures are hand-written with fake serials. Nothing
  in the repository or its history comes from a real recorder; the known-answer tests pin the
  numbers ffprobe reports for the generated clips.
- The demo clips under `app/src/debug/assets/demo/` are three stock pictures of empty rooms, given a
  slow zoom and encoded with x264 by `scripts/gen-demo-clips.sh`. The README screenshots show those
  clips, never a real camera.
