#!/usr/bin/env bash
# Regenerates every binary fixture under core/src/test/resources/fixtures/.
#
# All footage is synthetic: ffmpeg's lavfi test-pattern generators encoded with x264 / x265 to the
# shape the Hik-Connect cloud sends (H.264 High 960x480 @ 12 fps sub-stream in MPEG-TS with an IDR
# every 12 pictures; HEVC Main 1280x720 @ 25 fps playback with a closed GOP every 25 pictures), then
# encrypted with cloud-cam-viewer's algorithm (src/cloudcam/codec/nal.py: AES-128-ECB over the whole
# 16-byte blocks of the first 4096 bytes, key = code left-justified with NUL to 16 bytes) under the
# dummy verification code below. No real recorder, account or camera is involved.
#
# Needs ffmpeg/ffprobe (any recent build with libx264 and libx265), python3 and openssl (or the
# python `cryptography` package). The known-answer tests pin the numbers printed at the end; rerun
# them (`./gradlew :core:test`) after changing anything here.
set -euo pipefail

cd "$(dirname "$0")/.."
out=core/src/test/resources/fixtures
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Dummy verification code shared with NalDecryptorTest.KEY. Not anybody's real code.
KEY=${KEY:-ABCDEF1234}

# 1. Live sub-stream: 31 pictures (2.5 s at 12 fps), 3 GOPs. `filter_units` drops x264's SEI so each
#    keyframe access unit is exactly AUD + SPS + PPS + IDR, as the recorder sends it.
ffmpeg -y -loglevel error -f lavfi -i "testsrc2=size=960x480:rate=12" -frames:v 31 \
  -c:v libx264 -profile:v high -pix_fmt yuv420p -g 12 -keyint_min 12 -bf 0 -refs 1 \
  -x264-params "repeat-headers=1:scenecut=0:aud=1" -crf 23 \
  -bsf:v filter_units=remove_types=6 -f mpegts "$out/synthetic_sub_2s5.ts"

# 2. Playback window: 274 pictures (10 full GOPs of 25 plus a truncated 24), HEVC Annex-B, a
#    VPS/SPS/PPS before every IDR, no AUD and no SEI (what the decrypted relay stream looks like).
ffmpeg -y -loglevel error -f lavfi -i "testsrc=size=1280x720:rate=25" -frames:v 274 \
  -c:v libx265 -pix_fmt yuv420p -crf 35 \
  -x265-params "keyint=25:min-keyint=25:bframes=0:scenecut=0:open-gop=0:repeat-headers=1:aud=0:info=0:log-level=error" \
  -f hevc "$out/synthetic_pb_winend.h265"

# 3. Decryption known answers: the first GOP of the live clip (AUD, SPS, PPS, IDR, then 9 x (AUD, P))
#    as u32-BE length-prefixed NAL units, clear and in the four encryption variants.
ffmpeg -y -loglevel error -i "$out/synthetic_sub_2s5.ts" -c copy -f h264 "$tmp/live.h264"
KEY="$KEY" python3 - "$tmp/live.h264" "$out" <<'EOF'
import os, struct, subprocess, sys

src, out = sys.argv[1], sys.argv[2]
key = os.environ["KEY"].encode().ljust(16, b"\0")[:16]

try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

    def aes(block):
        enc = Cipher(algorithms.AES(key), modes.ECB()).encryptor()
        return enc.update(block) + enc.finalize()
except ImportError:
    def aes(block):
        return subprocess.run(
            ["openssl", "enc", "-aes-128-ecb", "-nosalt", "-nopad", "-K", key.hex()],
            input=block, capture_output=True, check=True,
        ).stdout

def enc(data, start):
    """cloud-cam-viewer's _decrypt_body in reverse: whole blocks of the first 4096 bytes after start."""
    body = data[start:]
    n = (min(len(body), 4096) // 16) * 16
    return data[:start] + aes(body[:n]) + body[n:]

data = open(src, "rb").read()
starts, i = [], 0
while i < len(data) - 3:
    if data[i:i+3] == b"\x00\x00\x01":
        starts.append(i + 3)
        i += 3
    else:
        i += 1
nals = []
for n, s in enumerate(starts):
    end = (starts[n + 1] - 3) if n + 1 < len(starts) else len(data)
    while end > s and data[end - 1] == 0:  # zero_byte of a 4-byte start code; a NAL never ends in 0x00
        end -= 1
    nals.append(data[s:end])

first_gop = nals[:4 + 2 * 9]  # AUD SPS PPS IDR + 9 x (AUD P)
types = [n[0] & 0x1F for n in first_gop]
assert types == [9, 7, 8, 5] + [9, 1] * 9, types

KEYFRAME_TYPES = {5, 7, 8}  # what a selectively encrypting recorder encrypts: IDR, SPS, PPS
variants = {
    "clear": lambda n, t: n,
    "header_clear_selective": lambda n, t: enc(n, 1) if t in KEYFRAME_TYPES else n,
    "header_clear_all": lambda n, t: enc(n, 1) if t in KEYFRAME_TYPES | {1} else n,
    "whole": lambda n, t: enc(n, 0),
    "type_hint": lambda n, t: n[:1] + enc(n, 0) if t in KEYFRAME_TYPES else n,
}
for name, f in variants.items():
    with open(os.path.join(out, f"h264_nals_{name}.bin"), "wb") as fh:
        for nal, t in zip(first_gop, types):
            u = f(nal, t)
            fh.write(struct.pack(">I", len(u)) + u)
print(f"h264_nals_*.bin: {len(first_gop)} NAL units, IDR {len(first_gop[3])} bytes")
EOF

# 4. The numbers the tests pin.
echo "synthetic_sub_2s5.ts:"
ffprobe -v error -show_packets -show_entries packet=pts,flags "$out/synthetic_sub_2s5.ts" \
  | awk -F= '/^pts=/{n++; if (n == 1) first = $2} /^flags=K/{k++} END{printf "  %d packets, %d keyframes, first pts %s\n", n, k, first}'
ffprobe -v error -show_frames -show_entries frame=pict_type "$out/synthetic_sub_2s5.ts" | sort | uniq -c | grep pict_type
echo "synthetic_pb_winend.h265:"
ffprobe -v error -count_frames -show_entries stream=codec_name,profile,width,height,nb_read_frames -of default=nw=1 \
  "$out/synthetic_pb_winend.h265" | sed 's/^/  /'
ls -l "$out"/*.ts "$out"/*.h265 "$out"/*.bin
