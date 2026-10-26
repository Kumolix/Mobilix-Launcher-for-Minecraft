#!/bin/bash
# Собирает lwjgl2compat.jar: слой LWJGL 2 поверх LWJGL 3.
# Использование: build.sh <каталог с jar LWJGL3 (lwjgl, glfw, opengl, openal)> <asm.jar> <выходной jar>
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
LIBS="$1"; ASM="$2"; OUT="$3"
W="$(mktemp -d)"
CP="$LIBS/lwjgl.jar:$LIBS/lwjgl-glfw.jar:$LIBS/lwjgl-opengl.jar:$LIBS/lwjgl-openal.jar"
mkdir -p "$W/tools" "$W/gen" "$W/classes" "$W/patched"
javac -nowarn -cp "$ASM" -d "$W/tools" "$HERE"/tools/*.java
java -cp "$W/tools:$CP" GenCaps "$W/gen/ContextCapabilities.java"
javac -nowarn --release 8 -cp "$CP" -d "$W/classes" $(find "$HERE/src" -name '*.java') "$W/gen/ContextCapabilities.java"
java -cp "$W/tools:$ASM" Patcher "$W/patched" "$W/classes" $(echo "$CP" | tr ':' ' ')
# итог: наши классы + пропатченные классы LWJGL3 (идут первыми в classpath)
cp -r "$W/classes/." "$W/patched/"
(cd "$W/patched" && jar cf "$OUT" .)
echo "compat jar: $(ls -la "$OUT")"
