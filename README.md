# HikViewer

[![CI](https://github.com/itsluminous/HikViewer/actions/workflows/ci.yml/badge.svg)](https://github.com/itsluminous/HikViewer/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/itsluminous/HikViewer?include_prereleases&sort=semver)](https://github.com/itsluminous/HikViewer/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%20TV%20%7C%20Android-3DDC84?logo=android&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-26-informational)

A lightweight, open-source Android TV and phone client for **Hik-Connect** accounts. Log in, pick
the channels of your Hikvision DVR/NVR, and watch them live in a D-pad, mouse and touch friendly
grid. Cloud streams are decrypted on the device with your own video-encryption (verification)
code. No alarms, no recordings, no 270 MB app.

> **Status:** under construction. The sections below are filled in as features land.

## Screenshots

<!-- docs/screenshots/*.png — grid, fullscreen, settings, phone layout (added by the docs stage). -->

## Features

<!-- Filled by the docs stage. Planned:
- Hik-Connect login (account with country code, password, region) with the official app's protocol
- Channel list with online/signal state; choose which cameras are shown and in which order
- 3x3 / 4x3 / 4x4 / hero (1+N) grid layouts; OK on a tile for fullscreen, Back to return
- Hidden top bar that appears on any D-pad key and hides after 5 s
- On-device AES decryption of encrypted streams with your verification code (stored encrypted)
- Adaptive decoding: hardware decoders for the focused/hero tile, software for the rest, keyframe-only
  tiles when the TV runs out of CPU/RAM
- Phone and tablet support (touch); a single universal APK (no ABI splits)
-->

## How it works

<!-- Five lines, filled by the docs stage: login → device/channel list → VTM relay token → relay TCP
stream (RTP/MPEG-PS) → AES-ECB NAL decryption → MediaCodec. -->

## Install

<!-- Filled by the docs stage: download the universal APK from Releases, sideload with adb or a file
manager on the TV; phone install; which APK to pick. -->

## Building

See [BUILDING.md](BUILDING.md) for prerequisites, build, test, lint, emulator and release-signing
instructions.

## References

HikViewer stands on the shoulders of several open-source projects that documented the Hik-Connect /
EZVIZ protocols. They are credited in [REFERENCES.md](REFERENCES.md).

## Disclaimer

HikViewer is an unofficial client. It is not affiliated with, endorsed by or supported by Hikvision,
EZVIZ or Hik-Connect. It talks to a private, undocumented protocol that may change or be restricted
at any time, and it only works with cameras you own and an account you control. Use at your own
risk; see [LICENSE](LICENSE).
