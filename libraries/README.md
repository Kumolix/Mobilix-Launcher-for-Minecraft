# Библиотеки Mobilix

Копии внешних библиотек, которые использует лаунчер. Нужны на случай, если исходный репозиторий исчезнет или поменяется.
Заполняется скриптом `fetch.sh` (запускается вручную: Actions → «Libraries backup» → Run workflow).
Контрольные суммы и адреса источников записываются в `MANIFEST.txt`.

| Что | Версия | Для чего | Лицензия | Где копия |
|---|---|---|---|---|
| LWJGL (core, glfw, opengl, openal) | 3.3.3 | графика, окно и звук игры | BSD-3-Clause | `maven/` |
| ASM | 9.7 | слой совместимости LWJGL 2 | BSD-3-Clause | `maven/` |
| Apache Commons Compress | 1.26.2 | распаковка Java-рантайма | Apache-2.0 | `maven/` |
| XZ for Java | 1.9 | распаковка `.tar.xz` | Public Domain | `maven/` |
| OpenAL Soft | 1.23.1 | звук (собирается в CI) | LGPL-2.1 | `sources/` |
| gl4es | последний коммит | OpenGL для версий 1.8–1.16.5 | MIT | `sources/` |
| MobileGlues (плагин) | последний релиз | OpenGL для 1.17 и новее | LGPL-2.1 | релиз `libraries` |
| OpenJDK 21 для Android arm64 | из 4 сторонних репозиториев | Java-рантайм | GPLv2 + Classpath Exception | релиз `libraries` |
| Нативы LWJGL для Android | из 3 сторонних репозиториев | библиотеки LWJGL под arm64 | BSD-3-Clause | релиз `libraries` |

Источники больших файлов: `metinjakupi/openjdk-android-arm64`, `feeldev12/nerion-android-jdk-build`, `AstralNext/aml-android-jre`, `vhynlain/nlauncher-jre-android`, `Unxkki/nlauncher-android-natives`, `MDTCopper/android-lwjgl3`, `yuyi2439/AndroidLWJGL`, `MobileGL-Dev/MobileGlues-release`.

## Что в папке, а что в релизе
Jar-файлы и исходники небольшие и лежат прямо здесь. JRE, нативы и APK MobileGlues весят сотни мегабайт, поэтому скрипт кладёт их в `libraries/big/`, а workflow загружает их в релиз `libraries` и не коммитит в git: иначе репозиторий раздуется навсегда.

## Не копируется
Gradle подтягивает ещё зависимости Commons Compress (codec, io, lang3). Их версии выбирает Gradle, и в копии они не входят. Файлы игры (Minecraft, библиотеки Mojang, Fabric, Forge) лаунчер скачивает у владельцев при установке версии; их копии здесь не хранятся.

Лицензии: тексты лежат в `licenses/` после первого запуска; при распространении копий соблюдайте условия лицензий (для LGPL и GPL нужно сохранять исходники и тексты лицензий).
