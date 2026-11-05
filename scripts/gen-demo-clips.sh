#!/usr/bin/env bash
# Regenerates the debug demo clips (app/src/debug/assets/demo/*.ts) from still pictures.
#
# Usage: scripts/gen-demo-clips.sh <dir with hall.jpg lobby.jpg room.jpg>
#
# Each still becomes a 3 s, 12 fps, 640x360 Constrained Baseline H.264 clip in MPEG-TS with a slow
# zoom, so the demo grid looks like a wall of quiet cameras. The encoder flags are identical for
# every clip, which gives them byte-identical SPS/PPS: the keyframe pool then keeps one decoder for
# all tiles, exactly as it does with one recorder. Only stock pictures may be used here, never
# footage from anyone's cameras.
set -euo pipefail
src=${1:?usage: $0 <dir with hall.jpg lobby.jpg room.jpg>}
out=$(cd "$(dirname "$0")/.." && pwd)/app/src/debug/assets/demo
mkdir -p "$out"
for name in hall lobby room; do
  ffmpeg -v error -y -loop 1 -framerate 12 -i "$src/$name.jpg" -t 3 \
    -vf "scale=1280:720:force_original_aspect_ratio=increase,crop=1280:720,zoompan=z='1+0.0015*on':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=640x360:fps=12,format=yuv420p,setrange=tv,setparams=colorspace=bt709:color_primaries=bt709:color_trc=bt709" \
    -c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p -g 12 -keyint_min 12 -bf 0 -refs 1 \
    -x264-params "repeat-headers=1:scenecut=0:ref=1:fullrange=off:colorprim=bt709:transfer=bt709:colormatrix=bt709" \
    -crf 26 -f mpegts "$out/$name.ts"
done
ls -l "$out"
