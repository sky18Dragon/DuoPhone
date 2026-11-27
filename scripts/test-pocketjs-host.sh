#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CACHE="$ROOT/.pocket-build"
POCKET_SOURCE=${POCKET_SOURCE:-$CACHE/sources/pocketjs}
QUICKJS_SOURCE=${QUICKJS_SOURCE:-$CACHE/sources/quickjs-rs}
[ "$(git -C "$POCKET_SOURCE" rev-parse HEAD)" = d3f0be0c7739ea704ca25d8b5158581bb176abc6 ]
[ "$(git -C "$QUICKJS_SOURCE" rev-parse HEAD)" = ba5bdd0dc013518768e76cd9e05cd30ed53dd35b ]
export PATH="/opt/homebrew/opt/rustup/bin:$HOME/.cargo/bin:$PATH"
export RUSTC="$(rustup which --toolchain nightly-2026-07-02 rustc)"
CARGO_PROFILE_RELEASE_LTO=false rustup run nightly-2026-07-02 cargo build \
 --manifest-path "$POCKET_SOURCE/engine/ui-cabi/Cargo.toml" --locked --release \
 --features bare-platform,software-only --target-dir "$CACHE/host-rust"
OBJ="$CACHE/host-objects"
mkdir -p "$OBJ"
QJS="$QUICKJS_SOURCE/libquickjs-sys/embed/quickjs"
FLAGS=(-std=gnu11 -O2 -funsigned-char -fno-strict-aliasing -D_GNU_SOURCE '-DCONFIG_VERSION="2026-06-04"' "-I$QJS")
for source in cutils dtoa libregexp libunicode quickjs; do
  cc "${FLAGS[@]}" -c "$QJS/$source.c" -o "$OBJ/$source.o"
done
cc "${FLAGS[@]}" -c "$QUICKJS_SOURCE/libquickjs-sys/embed/static-functions.c" -o "$OBJ/static-functions.o"
cc "${FLAGS[@]}" '-DPOCKETJS_TARGET_ID="duophone-poc"' -DPOCKETJS_HOST_ABI=1 -DPOCKET_RASTER_DENSITY=2 \
 "-I$POCKET_SOURCE/engine/quickjs-c" "-I$POCKET_SOURCE/engine/ui-cabi/include" "-I$POCKET_SOURCE/contracts/generated" \
 "$ROOT/app/src/main/cpp/pocket/host_smoke.c" "$POCKET_SOURCE/engine/quickjs-c/rust_eh_personality.c" "$OBJ/"*.o \
 "$CACHE/host-rust/release/libpocketjs_symbian_core.a" -lm -o "$CACHE/host-smoke"
"$CACHE/host-smoke" "$CACHE/assets/pocket/top.js" "$CACHE/host-pocket-frame.ppm"
"${POCKET_PYTHON:-python3}" - "$CACHE" <<'PY'
from PIL import Image
import sys
from pathlib import Path
p=Path(sys.argv[1]); Image.open(p/'host-pocket-frame.ppm').save(p/'host-pocket-frame.png')
PY
