# References

HikViewer implements a private protocol that others reverse-engineered first. This file credits the
projects and documents we learned from, and notes what each one taught us. Nothing below is vendored;
the protocol was re-implemented in Kotlin.

## Protocol implementations

- **[Bahrombekk/cloud-cam-viewer](https://github.com/Bahrombekk/cloud-cam-viewer)** (Python, MIT +
  Apache-2.0). Our primary reference: logs in the way the official app does (`clientType` 55,
  `/v3/users/login/v2`, region redirect on meta code 1100), fetches the device page list with the
  filter the stream path needs, signs the VTDU token, speaks the binary VTM relay framing
  (`ysproto://`), demuxes RTP / MPEG-PS into NAL units, decrypts them with AES-ECB using the
  verification code (three key variants auto-detected from SPS/VPS/PPS), and paces the GOP bursts the
  cloud sends through a jitter buffer with a reconnect watchdog.
- **[RenierM26/pyEzvizApi](https://github.com/RenierM26/pyEzvizApi)** (Python, Apache-2.0). The library
  cloud-cam-viewer's transport is adapted from; its `docs/streaming.md` explains the EZVIZ/Hik-Connect
  cloud VTM/VTDU relay, the LAN "local SDK" path and the HCNetSDK port-8000 protocol, and showed that the
  EZVIZ and Hik-Connect clouds share one backend.
- **[tomasbedrich/hikconnect](https://github.com/tomasbedrich/hikconnect)** (Python, MIT). The cleanest
  description of the login flow: MD5-hashed password, `featureCode`, the `loginArea.apiDomain` redirect
  (also present on a code-200 response), session refresh and the device/channel list endpoints.
- **[pedropaulovc/go2rtc fork](https://github.com/pedropaulovc/go2rtc/pull/2)** (Go, MIT). Shows the true
  P2P alternative (login → `P2P_SETUP` → UDP hole punch → SRT, HEVC + G.711). We chose the TCP relay
  instead because it is simpler, works through NAT without hole punching and the ~0.6 Mbit/s sub-streams
  fit comfortably; the fork remains the reference if P2P is ever needed.
- **[ijtan/hikconnect-cloud-relay](https://github.com/ijtan/hikconnect-cloud-relay)** and
  **[Frens98/hikconnect-nvr](https://github.com/Frens98/hikconnect-nvr)** (Python, MIT). Confirmed the
  cloud video route and documented the official "Hik-Connect for Teams" OpenAPI (HLS, H.264 only),
  which we did not use because it needs per-team API keys and cannot deliver H.265 or low latency.

## Android TV UI

- **[aryswisnu/hermes-tv](https://github.com/aryswisnu/hermes-tv)** (Java, MIT). An Android TV launcher
  with an RTSP camera grid; its README lists the TV-specific pitfalls (focus handling, decoder limits on
  cheap TVs) that shaped our plain-View, explicit-focus grid and the decoder pool.
- **[VibeCodingAntagonist/opah-frigate-tv-app](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app)**
  (Kotlin, Apache-2.0). Remote-first multi-camera UI; a reference for D-pad navigation patterns.

## Vendor documentation

- Hikvision / EZVIZ help on video encryption and the verification code: the "Enabling or Disabling Video
  and Image Encryption" Hik-Connect help page and Hikvision USA knowledge base articles 17000128922 and
  17000129204. These establish that encryption applies to the platform stream and that a device's
  `encryptPwd` is `MD5(MD5(verificationCode))`, which lets the app verify a typed code locally.
- **[Ezviz-Open/EzvizSDK-Android](https://github.com/Ezviz-Open/EzvizSDK-Android)** (Apache-2.0). The
  official EZVIZ SDK; evaluated and rejected because it needs a paid EZVIZ Open Platform AppKey and does
  not serve Hik-Connect consumer accounts.

## Standards

- RFC 1321 (MD5), RFC 3550 (RTP), ISO/IEC 13818-1 (MPEG-PS), ITU-T H.264 / H.265 (NAL unit syntax,
  Annex B byte streams), FIPS 197 (AES).
