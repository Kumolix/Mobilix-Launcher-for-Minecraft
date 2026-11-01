#!/usr/bin/env bash
# Скачивает копии всех внешних библиотек Mobilix в папку libraries/.
# Небольшие файлы (jar, исходники) остаются в папке, большие архивы (JRE, нативы LWJGL, APK MobileGlues)
# складываются в libraries/big/ и затем загружаются в релиз "libraries" (см. .github/workflows/libraries.yml).
# Нужны: curl, git, tar, gh (с GH_TOKEN).
set -u
cd "$(dirname "$0")"
M=https://repo1.maven.org/maven2
mkdir -p maven sources big
: > MANIFEST.txt
add() { printf '%s  %s  %s\n' "$(sha256sum "$1" | cut -d' ' -f1)" "$1" "$2" >> MANIFEST.txt; }

get_maven() { # group-path artifact version
  local f="maven/$2-$3.jar"
  curl -fsSL "$M/$1/$2/$3/$2-$3.jar" -o "$f" && add "$f" "$M/$1/$2/$3/" || echo "FAIL maven $2"
}
for a in lwjgl lwjgl-glfw lwjgl-opengl lwjgl-openal; do get_maven org/lwjgl $a 3.3.3; done
get_maven org/ow2/asm asm 9.7
get_maven org/apache/commons commons-compress 1.26.2
get_maven org/tukaani xz 1.9

snap() { # name url [ref]
  local d; d=$(mktemp -d)
  if git clone --depth 1 ${3:+--branch "$3"} "$2" "$d/src" >/dev/null 2>&1; then
    local sha; sha=$(git -C "$d/src" rev-parse HEAD)
    rm -rf "$d/src/.git"
    tar czf "sources/$1.tar.gz" -C "$d" src && add "sources/$1.tar.gz" "$2 @ $sha"
  else echo "FAIL clone $1"; fi
  rm -rf "$d"
}
snap openal-soft-1.23.1 https://github.com/kcat/openal-soft.git 1.23.1
snap gl4es https://github.com/ptitSeb/gl4es.git

# Готовые сборки из сторонних репозиториев (JRE, нативы LWJGL, плагин MobileGlues).
pull() { # group repo count
  local g=$1 r=$2 n=${3:-5}
  mkdir -p "big/$g"
  gh api "repos/$r/releases?per_page=$n" --jq '.[].assets[] | select(.size < 1900000000) | select(.name|test("\\.(zip|tar\\.xz|tar\\.gz|apk)$";"i")) | select(.name|test("src|source|sha|sums";"i")|not) | [.name,.browser_download_url,.size]|@tsv' 2>/dev/null |
  while IFS=$'\t' read -r name url size; do
    out="big/$g/${r//\//_}__$name"
    [ -f "$out" ] || curl -fsSL "$url" -o "$out" || { echo "FAIL $url"; continue; }
    add "$out" "$url"
  done
}
for r in metinjakupi/openjdk-android-arm64 feeldev12/nerion-android-jdk-build AstralNext/aml-android-jre vhynlain/nlauncher-jre-android; do pull jre "$r" 3; done
for r in Unxkki/nlauncher-android-natives MDTCopper/android-lwjgl3 yuyi2439/AndroidLWJGL; do pull lwjgl-natives "$r" 10; done
pull mobileglues MobileGL-Dev/MobileGlues-release 3
echo "done: $(wc -l < MANIFEST.txt) files"
