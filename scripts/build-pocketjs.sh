#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CACHE="$ROOT/.pocket-build"
POCKET_SHA=d3f0be0c7739ea704ca25d8b5158581bb176abc6
QUICKJS_SHA=ba5bdd0dc013518768e76cd9e05cd30ed53dd35b
POCKET_SOURCE=${POCKET_SOURCE:-$CACHE/sources/pocketjs}
QUICKJS_SOURCE=${QUICKJS_SOURCE:-$CACHE/sources/quickjs-rs}
export POCKET_SOURCE
fetch_source() {
  local source_dir=$1 remote=$2 revision=$3
  if [ ! -d "$source_dir/.git" ]; then
    mkdir -p "$source_dir"
    git -C "$source_dir" init -q
    git -C "$source_dir" remote add origin "$remote"
    git -C "$source_dir" fetch --depth 1 origin "$revision"
    git -C "$source_dir" checkout --detach FETCH_HEAD
  fi
  [ "$(git -C "$source_dir" rev-parse HEAD)" = "$revision" ] || { echo "Unexpected source revision: $source_dir" >&2; exit 1; }
  [ -z "$(git -C "$source_dir" status --porcelain --untracked-files=no)" ] || { echo "Modified dependency: $source_dir" >&2; exit 1; }
}
fetch_source "$POCKET_SOURCE" https://github.com/pocket-nexus/pocketjs.git "$POCKET_SHA"
fetch_source "$QUICKJS_SOURCE" https://github.com/pocket-stack/quickjs-rs.git "$QUICKJS_SHA"
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")}}
NDK="$SDK/ndk/28.2.13676358"
case "$(uname -s)" in Darwin) HOST_TAG=darwin-x86_64;; Linux) HOST_TAG=linux-x86_64;; *) echo 'Unsupported build host' >&2; exit 1;; esac
LLVM="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CC="$LLVM/aarch64-linux-android26-clang"
[ -x "$CC" ] || { echo "Required NDK 28.2.13676358 missing" >&2; exit 1; }
if ! command -v rustup >/dev/null; then
  if [ -x /opt/homebrew/opt/rustup/bin/rustup ]; then export PATH="/opt/homebrew/opt/rustup/bin:$PATH"; else export PATH="$HOME/.cargo/bin:$PATH"; fi
fi
# Setup is explicit; this script never upgrades global toolchains.
rustup run nightly-2026-07-02 rustc --version
export RUSTC="$(rustup which --toolchain nightly-2026-07-02 rustc)"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"
export CARGO_PROFILE_RELEASE_LTO=false
RUST_OUT=${POCKET_RUST_OUT:-$CACHE/rust}
rustup run nightly-2026-07-02 cargo build --manifest-path "$POCKET_SOURCE/engine/ui-cabi/Cargo.toml" --locked --release \
  --target aarch64-linux-android --features bare-platform --target-dir "$RUST_OUT"
OBJ="$CACHE/objects"
mkdir -p "$OBJ" "$CACHE/jniLibs/arm64-v8a"
QJS="$QUICKJS_SOURCE/libquickjs-sys/embed/quickjs"
FLAGS=(-std=gnu11 -O2 -fPIC -funsigned-char -fno-strict-aliasing -ffunction-sections -fdata-sections -D_GNU_SOURCE '-DCONFIG_VERSION="2026-06-04"' "-I$QJS")
for source in cutils dtoa libregexp libunicode quickjs; do
  "$CC" "${FLAGS[@]}" -c "$QJS/$source.c" -o "$OBJ/$source.o"
done
"$CC" "${FLAGS[@]}" -c "$QUICKJS_SOURCE/libquickjs-sys/embed/static-functions.c" -o "$OBJ/static-functions.o"
"$CC" "${FLAGS[@]}" -fvisibility=hidden -Wall -Wextra -Werror -Wno-unused-parameter \
  '-DPOCKETJS_TARGET_ID="duophone-poc"' -DPOCKETJS_HOST_ABI=1 -DPOCKET_RASTER_DENSITY=2 \
  "-I$POCKET_SOURCE/engine/quickjs-c" "-I$POCKET_SOURCE/engine/ui-cabi/include" \
  "-I$POCKET_SOURCE/contracts/generated" "-I$POCKET_SOURCE/hosts/shared" \
  -c "$ROOT/app/src/main/cpp/pocket/android_jni.c" -o "$OBJ/android_jni.o"
"$CC" -fPIC -c "$POCKET_SOURCE/engine/quickjs-c/rust_eh_personality.c" -o "$OBJ/personality.o"
"$CC" -shared -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--no-undefined -Wl,-z,max-page-size=16384 \
  "$OBJ/android_jni.o" "$OBJ/cutils.o" "$OBJ/dtoa.o" "$OBJ/libregexp.o" "$OBJ/libunicode.o" \
  "$OBJ/quickjs.o" "$OBJ/static-functions.o" "$OBJ/personality.o" \
  "$RUST_OUT/aarch64-linux-android/release/libpocketjs_symbian_core.a" \
  -o "$CACHE/jniLibs/arm64-v8a/libduophone_pocket.so" -lEGL -lGLESv2 -llog -ldl -lm
"${POCKET_PYTHON:-python3}" "$ROOT/pocket-ui/build.py"
mkdir -p "$CACHE/assets/pocket/licenses"
cp "$POCKET_SOURCE/LICENSE" "$CACHE/assets/pocket/licenses/PocketJS-MIT.txt"
cp "$QJS/LICENSE" "$CACHE/assets/pocket/licenses/QuickJS-MIT.txt"
find "$POCKET_SOURCE/assets/fonts" -maxdepth 1 -iname '*license*' -exec cp {} "$CACHE/assets/pocket/licenses/" \;
"$LLVM/llvm-readelf" -l "$CACHE/jniLibs/arm64-v8a/libduophone_pocket.so" > "$CACHE/elf-segments.txt"
shasum -a 256 "$CACHE/jniLibs/arm64-v8a/libduophone_pocket.so" "$CACHE/assets/pocket/top.js" > "$CACHE/build-sha256.txt"
echo 'PocketJS ARM64 GLES2 library + bundle built (device validation still required)'
