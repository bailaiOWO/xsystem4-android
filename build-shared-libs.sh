#!/bin/sh

set -e # make any subsequent failing command exit the script

if [ -z $ANDROID_NDK_HOME ]; then
    echo 'You need to set the ANDROID_NDK_HOME environment variable to point to your Android NDK.'
    exit 1
fi

ABI_NAMES=${ABI_NAMES:-armeabi-v7a arm64-v8a x86_64}
ANDROID_API_LEVEL=${ANDROID_API_LEVEL:-21}

# Movie playback: cross-compile a minimal ffmpeg per ABI so that the ASF/WMV
# movies of several games (Rance Quest, Rance IX, ...) play out of the box.
# The engine's CMake picks it up automatically from <repo>/ffmpeg/<abi>.
# FFmpeg build/verification failures stop this script. SKIP_FFMPEG=1 explicitly
# skips that build; without installed FFmpeg libraries CMake selects pl_mpeg.
FFMPEG_PREFIX=${FFMPEG_PREFIX:-${PWD}/ffmpeg}
if [ "${SKIP_FFMPEG:-0}" != "1" ]; then
    echo "=== Building ffmpeg (movie playback) ==="
    # The engine's CMake looks for <repo>/ffmpeg/<abi>, so keep the two in sync.
    ABIS_ENV="${ABI_NAMES}" OUT_DIR="${FFMPEG_PREFIX}" ANDROID_API="${ANDROID_API_LEVEL}" \
        bash ./tools/build_ffmpeg_android.sh
fi

for abi in ${ABI_NAMES}; do
    cmake -B build/${abi} \
	  -S . \
	  -GNinja \
	  -DCMAKE_BUILD_TYPE=Release \
	  -DCMAKE_ANDROID_ARCH_ABI=${abi} \
	  -DANDROID_PLATFORM=${ANDROID_API_LEVEL} \
	  -DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF \
	  -DCMAKE_TOOLCHAIN_FILE=${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake
    ninja -C build/${abi} install
    ./collect-licenses.sh build/${abi} project/app/src/main/assets/licenses
done
