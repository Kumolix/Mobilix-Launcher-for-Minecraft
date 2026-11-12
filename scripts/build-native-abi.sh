#!/usr/bin/env bash
# Нативные библиотеки лаунчера под один ABI: armeabi-v7a | x86_64 | x86.
# Используется только job'ом extra-abi в CI; основная сборка arm64 остаётся как была.
# Каждая библиотека собирается отдельно, провал одной не останавливает остальные: итог пишется в native-<abi>.log.
set -u
ABI="$1"
NDK="${ANDROID_NDK_LATEST_HOME:-$ANDROID_NDK_HOME}"
API=21
case "$ABI" in
  armeabi-v7a) TRIPLE="armv7a-linux-androideabi$API" ;;
  x86_64)      TRIPLE="x86_64-linux-android$API" ;;
  x86)         TRIPLE="i686-linux-android$API" ;;
  *) echo "unknown ABI $ABI"; exit 2 ;;
esac
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/$TRIPLE-clang"
OUT="$PWD/app/src/main/jniLibs/$ABI"
mkdir -p "$OUT"
LOG="$PWD/native-$ABI.log"; : > "$LOG"
ok() { echo "OK   $1" | tee -a "$LOG"; }
bad() { echo "FAIL $1" | tee -a "$LOG"; }

# 1. наши библиотеки (окно GLFW, мост, заглушка)
if "$CC" -shared -fPIC -O2 -o "$OUT/libnotag.so" native/notag.c >>"$LOG" 2>&1; then ok notag; else bad notag; fi
if "$CC" -shared -fPIC -O2 -Wl,-soname,libglfw.so -o "$OUT/libglfw.so" native/glfw.c -llog -landroid -lEGL -ldl >>"$LOG" 2>&1; then ok glfw; else bad glfw; fi
if "$CC" -shared -fPIC -O2 -o "$OUT/libmobilix.so" native/mobilix.c -L"$OUT" -lglfw -llog -landroid -lEGL -lGLESv2 -ldl >>"$LOG" 2>&1; then ok mobilix; else bad mobilix; fi

cm() { # src build extra-args...
  local src="$1" b="$2"; shift 2
  cmake -S "$src" -B "$b" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=$API -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 "$@" >>"$LOG" 2>&1 && cmake --build "$b" -j4 >>"$LOG" 2>&1
}
T=/tmp/abi-$ABI; mkdir -p "$T"

# 2. OpenAL
if git clone -q --depth 1 --branch 1.23.1 https://github.com/kcat/openal-soft.git "$T/oal" >>"$LOG" 2>&1 \
   && cm "$T/oal" "$T/oal/build" -DANDROID_STL=c++_static -DLIBTYPE=SHARED -DALSOFT_UTILS=OFF -DALSOFT_EXAMPLES=OFF -DALSOFT_TESTS=OFF -DALSOFT_INSTALL=OFF \
   && F=$(ls "$T"/oal/build/libopenal.so* | head -1) && cp -L "$F" "$OUT/libopenal.so"; then ok openal; else bad openal; fi

# 3. FreeType
if git clone -q --depth 1 --branch VER-2-13-2 https://github.com/freetype/freetype.git "$T/ft" >>"$LOG" 2>&1 \
   && cm "$T/ft" "$T/ft/build" -DBUILD_SHARED_LIBS=ON -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON \
   && F=$(ls "$T"/ft/build/libfreetype.so* | head -1) && cp -L "$F" "$OUT/libfreetype.so"; then ok freetype; else bad freetype; fi

# 4. gl4es (рендерер для версий до 1.16.5; на 32-бит единственный вариант, MobileGlues есть только под arm64)
if git clone -q --depth 1 https://github.com/ptitSeb/gl4es.git "$T/gl4es" >>"$LOG" 2>&1 \
   && cm "$T/gl4es" "$T/gl4es/build" -DANDROID=ON -DNOX11=ON -DNO_GBM=ON -DNOEGL=ON -DNO_INIT_CONSTRUCTOR=ON -DDEFAULT_ES=2 -DUSE_ANDROID_LOG=ON \
   && F=$(ls "$T"/gl4es/lib/libGL.so* | head -1) && cp -L "$F" "$OUT/libgl4es.so"; then ok gl4es; else bad gl4es; fi

echo "::group::native $ABI summary"; grep -E "^(OK|FAIL)" "$LOG"; echo "::endgroup::"
for l in libglfw libmobilix libgl4es libopenal; do [ -s "$OUT/$l.so" ] || echo "::warning::$ABI: $l.so missing"; done
ls -la "$OUT"
