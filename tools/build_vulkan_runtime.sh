#!/usr/bin/env bash
# Builds an SoC-agnostic llama.cpp Vulkan runtime pack (arm64-v8a) for the
# Android app. Targets GPUs the Snapdragon pack cannot use, e.g. Exynos Xclipse
# (AMD RDNA) and Mali. Install the resulting ZIP through the app's
# "Runtime 추가" button; nothing is copied into the APK.
#
# Requirements (Linux x86_64): Android NDK r26+, cmake >= 3.22, ninja, git, zip,
# and a host C++ compiler (llama.cpp builds vulkan-shaders-gen for the host).
#
#   ANDROID_NDK_HOME=/path/to/ndk ./tools/build_vulkan_runtime.sh
#
# Optional env:
#   LLAMA_ROOT     existing llama.cpp checkout (default: clone LLAMA_REF into OUT)
#   LLAMA_REF      llama.cpp tag to clone (default: b11490, same as iOS)
#   VULKAN_HEADERS_REF  Khronos Vulkan-Headers tag (default: v1.4.304)
#   SPIRV_HEADERS_REF   Khronos SPIRV-Headers tag (default: vulkan-sdk-1.4.304.0)
#   CPU_ARCH       GGML_CPU_ARM_ARCH for the CPU fallback (default: armv8.2-a+dotprod+fp16)
#   OUT            output directory (default: build/vulkan-runtime)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$ROOT/build/vulkan-runtime}"
LLAMA_REF="${LLAMA_REF:-b11490}"
VULKAN_HEADERS_REF="${VULKAN_HEADERS_REF:-v1.4.304}"
SPIRV_HEADERS_REF="${SPIRV_HEADERS_REF:-vulkan-sdk-1.4.304.0}"
CPU_ARCH="${CPU_ARCH:-armv8.2-a+dotprod+fp16}"
BRIDGE_REV="${BRIDGE_REV:-4}"
API_LEVEL=28

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_LATEST_HOME:-}}}"
[[ -n "$NDK" && -f "$NDK/build/cmake/android.toolchain.cmake" ]] || {
  echo "Android NDK not found. Set ANDROID_NDK_HOME." >&2; exit 1; }
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
GLSLC="$NDK/shader-tools/linux-x86_64/glslc"
VULKAN_LIB="$SYSROOT/usr/lib/aarch64-linux-android/$API_LEVEL/libvulkan.so"
[[ -x "$GLSLC" ]] || { echo "glslc not found in NDK: $GLSLC" >&2; exit 1; }
[[ -f "$VULKAN_LIB" ]] || { echo "libvulkan.so stub not found: $VULKAN_LIB" >&2; exit 1; }

rm -rf "$OUT/build-llama" "$OUT/build-bridge" "$OUT/runtime"
mkdir -p "$OUT/runtime"

if [[ -z "${LLAMA_ROOT:-}" ]]; then
  LLAMA_ROOT="$OUT/llama.cpp"
  if [[ ! -d "$LLAMA_ROOT/.git" ]]; then
    git clone --depth 1 --branch "$LLAMA_REF" https://github.com/ggml-org/llama.cpp.git "$LLAMA_ROOT"
  fi
fi

# The NDK ships vulkan.h but not the C++ vulkan.hpp that ggml-vulkan needs.
# Both headers must come from the same Vulkan-Headers release.
VK_HEADERS="$OUT/Vulkan-Headers"
if [[ ! -d "$VK_HEADERS/include/vulkan" ]]; then
  git clone --depth 1 --branch "$VULKAN_HEADERS_REF" https://github.com/KhronosGroup/Vulkan-Headers.git "$VK_HEADERS"
fi

# ggml-vulkan does find_package(SPIRV-Headers CONFIG); the NDK has no CMake
# package for it. It is header-only, so install it once with the host CMake.
SPIRV_SRC="$OUT/SPIRV-Headers"
SPIRV_PREFIX="$OUT/spirv-headers-install"
if [[ ! -f "$SPIRV_PREFIX/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake" ]]; then
  [[ -d "$SPIRV_SRC/.git" ]] || git clone --depth 1 --branch "$SPIRV_HEADERS_REF" https://github.com/KhronosGroup/SPIRV-Headers.git "$SPIRV_SRC"
  cmake -S "$SPIRV_SRC" -B "$OUT/build-spirv" -DCMAKE_INSTALL_PREFIX="$SPIRV_PREFIX" -DSPIRV_HEADERS_ENABLE_TESTS=OFF
  cmake --install "$OUT/build-spirv"
fi

cmake -S "$LLAMA_ROOT" -B "$OUT/build-llama" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM="android-$API_LEVEL" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_CXX_FLAGS="-I$VK_HEADERS/include -I$SPIRV_PREFIX/include" \
  -DCMAKE_C_FLAGS="-I$VK_HEADERS/include -I$SPIRV_PREFIX/include" \
  -DBUILD_SHARED_LIBS=ON \
  -DGGML_VULKAN=ON \
  -DGGML_OPENCL=OFF \
  -DGGML_HEXAGON=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_NATIVE=OFF \
  -DGGML_BACKEND_DL=OFF \
  -DGGML_CPU_ARM_ARCH="$CPU_ARCH" \
  -DVulkan_INCLUDE_DIR="$VK_HEADERS/include" \
  -DVulkan_LIBRARY="$VULKAN_LIB" \
  -DVulkan_GLSLC_EXECUTABLE="$GLSLC" \
  -DSPIRV-Headers_DIR="$SPIRV_PREFIX/share/cmake/SPIRV-Headers" \
  -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_TOOLS=ON
cmake --build "$OUT/build-llama" --target llama llama-bench -j"$(nproc)"

cmake -S "$ROOT/tools/native-wrapper" -B "$OUT/build-bridge" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM="android-$API_LEVEL" \
  -DCMAKE_BUILD_TYPE=Release \
  -DLLAMA_ROOT="$LLAMA_ROOT"
cmake --build "$OUT/build-bridge" --target lilac_local_ai_jni -j"$(nproc)"

BRIDGE_SO="$OUT/build-bridge/liblilac_local_ai_jni.so"
[[ -f "$BRIDGE_SO" ]] || { echo "native bridge build failed: $BRIDGE_SO" >&2; exit 4; }
cp "$BRIDGE_SO" "$OUT/runtime/"
printf '%s\n' "$BRIDGE_REV" > "$OUT/runtime/lilac_local_ai_jni.bridge-revision"

# Load order matters: dependencies before libggml.so / libllama.so.
LIBS=(libggml-base.so libggml-cpu.so libggml-vulkan.so libggml.so libllama.so)
for f in "${LIBS[@]}"; do
  src="$(find "$OUT/build-llama" -name "$f" -type f -print -quit)"
  [[ -n "$src" ]] || { echo "missing $f" >&2; exit 3; }
  cp "$src" "$OUT/runtime/$f"
done
# Newer llama.cpp splits common code into its own shared library.
common="$(find "$OUT/build-llama" -name libllama-common.so -type f -print -quit)"
if [[ -n "$common" ]]; then
  cp "$common" "$OUT/runtime/"
  LIBS=(libggml-base.so libggml-cpu.so libggml-vulkan.so libggml.so libllama-common.so libllama.so)
fi
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$OUT/runtime/"*.so

# llama-bench is not part of the pack; push bench/ with adb to compare CPU vs Vulkan.
# It needs libllama-bench-impl.so in addition to the runtime libraries.
rm -rf "$OUT/bench" && mkdir -p "$OUT/bench"
bench="$(find "$OUT/build-llama" -name llama-bench -type f -print -quit)"
if [[ -n "$bench" ]]; then
  cp "$bench" "$OUT/bench/llama-bench"
  impl="$(find "$OUT/build-llama" -name libllama-bench-impl.so -type f -print -quit)"
  [[ -n "$impl" ]] && cp "$impl" "$OUT/bench/"
  cp "$OUT/runtime/"lib*.so "$OUT/bench/"
  rm -f "$OUT/bench/liblilac_local_ai_jni.so"
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$OUT/bench/"*
fi

VERSION="$(cd "$LLAMA_ROOT" && git describe --tags --always 2>/dev/null || echo "$LLAMA_REF")"
LIB_JSON="$(printf '    "%s",\n' "${LIBS[@]}" | sed '$ s/,$//')"
cat > "$OUT/runtime/runtime.json" <<JSON
{
  "id": "llama-vulkan-gpu",
  "name": "llama.cpp Vulkan GPU",
  "version": "${VERSION}-vulkan-bridge-${BRIDGE_REV}",
  "abi": "arm64-v8a",
  "jniContract": "lilac-local-ai-v3",
  "libraryFile": "liblilac_local_ai_jni.so",
  "nativeLibraries": [
${LIB_JSON}
  ],
  "backendOrder": ["gpu", "cpu"],
  "socVendors": [],
  "supportedArchitectures": [],
  "supportedQuantizations": []
}
JSON

rm -f "$OUT/lilac-llama-vulkan-runtime.zip"
(cd "$OUT/runtime" && zip -q -9 -r "$OUT/lilac-llama-vulkan-runtime.zip" .)
echo "Runtime pack: $OUT/lilac-llama-vulkan-runtime.zip"
echo "Benchmark:    $OUT/bench (adb push the whole folder)"
echo "Bridge revision: $BRIDGE_REV"
