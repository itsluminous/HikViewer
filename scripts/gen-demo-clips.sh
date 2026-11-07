#!/usr/bin/env bash
# Regenerates the debug demo clips (app/src/debug/assets/demo/<slug>.ts) from still pictures.
#
# Usage: scripts/gen-demo-clips.sh <dir with back_garden.jpg driveway.jpg ... side_gate.jpg>
#
# One clip per demo camera, named after the camera (DemoCloudServices.NAMES lower-cased, spaces to
# underscores): front_door, driveway, garage, back_garden, porch, living_room, kitchen, hallway,
# side_gate, patio, office, nursery. The still may be <slug>.jpg or <slug>.jpeg. Each becomes a 3 s,
# 12 fps, 640x360 Constrained Baseline H.264 clip in MPEG-TS with a slow zoom, so the demo grid looks
# like a wall of quiet cameras. The encoder flags are identical for every clip, which gives them
# byte-identical SPS/PPS: the keyframe pool then keeps one decoder for all tiles, exactly as it does
# with one recorder (the script checks that at the end). Identical means identical: the PPS carries
# x264's initial QP, which it derives from the CRF, so one CRF serves every clip and steps up for
# all of them together until the largest is under MAX_BYTES (a detailed still costs more bits than
# a plain wall, and the APK should stay small); and `setsar=1` keeps a still's own pixel aspect
# ratio out of the SPS. Only stock pictures may be used here, never footage from anyone's cameras.
set -euo pipefail
src=${1:?usage: $0 <dir with <slug>.jpg or <slug>.jpeg for every demo camera>}
out=$(cd "$(dirname "$0")/.." && pwd)/app/src/debug/assets/demo
MAX_BYTES=${MAX_BYTES:-300000}
SLUGS="back_garden driveway front_door garage hallway kitchen living_room nursery office patio porch side_gate"
mkdir -p "$out"

encode() { # <still> <crf> <out.ts>
  ffmpeg -v error -y -loop 1 -framerate 12 -i "$1" -t 3 \
    -vf "scale=1280:720:force_original_aspect_ratio=increase,crop=1280:720,zoompan=z='1+0.0015*on':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=640x360:fps=12,setsar=1,format=yuv420p,setrange=tv,setparams=colorspace=bt709:color_primaries=bt709:color_trc=bt709" \
    -c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p -g 12 -keyint_min 12 -bf 0 -refs 1 \
    -x264-params "repeat-headers=1:scenecut=0:ref=1:fullrange=off:colorprim=bt709:transfer=bt709:colormatrix=bt709" \
    -crf "$2" -f mpegts "$3"
}

stills=()
for slug in $SLUGS; do
  still=""
  for ext in jpg jpeg; do
    [ -f "$src/$slug.$ext" ] && still="$src/$slug.$ext" && break
  done
  [ -n "$still" ] || { echo "missing $src/$slug.jpg (or .jpeg)" >&2; exit 1; }
  stills+=("$still")
done

for crf in 26 28 30 32 34 36; do
  largest=0
  for still in "${stills[@]}"; do
    slug=$(basename "${still%.*}")
    encode "$still" "$crf" "$out/$slug.ts"
    size=$(stat -f %z "$out/$slug.ts")
    [ "$size" -gt "$largest" ] && largest=$size
  done
  [ "$largest" -le "$MAX_BYTES" ] && break
done
[ "$largest" -le "$MAX_BYTES" ] || { echo "largest clip is $largest bytes at crf $crf, over $MAX_BYTES" >&2; exit 1; }
echo "crf $crf"
ls -l "$out"/*.ts | awk '{printf "%-16s %7s bytes\n", $NF, $5}' | sed "s#$out/##"

# Every clip must carry the same SPS and PPS (NAL types 7 and 8), or the keyframe pool would need
# a decoder per clip. Extract the elementary stream and compare the parameter sets' digests.
python3 - "$out" <<'PY'
import hashlib, pathlib, subprocess, sys
out = pathlib.Path(sys.argv[1])
seen = {}
for ts in sorted(out.glob("*.ts")):
    h264 = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(ts), "-c", "copy", "-bsf:v", "h264_mp4toannexb", "-f", "h264", "-"],
        check=True, capture_output=True).stdout
    nals, i = [], 0
    while True:
        j = h264.find(b"\x00\x00\x01", i)
        if j < 0:
            break
        k = h264.find(b"\x00\x00\x01", j + 3)
        body = h264[j + 3:k if k >= 0 else len(h264)].rstrip(b"\x00")
        nals.append(body)
        if k < 0:
            break
        i = k
    ps = {t: hashlib.md5(next(n for n in nals if n and n[0] & 0x1F == t)).hexdigest() for t in (7, 8)}
    seen[ts.name] = ps
    print(f"{ts.name:16} SPS {ps[7]}  PPS {ps[8]}")
if len({(p[7], p[8]) for p in seen.values()}) != 1:
    sys.exit("SPS/PPS differ between clips")
print(f"{len(seen)} clips, identical SPS/PPS")
PY
