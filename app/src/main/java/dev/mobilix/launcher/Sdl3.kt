package dev.mobilix.launcher

import java.io.File

/**
 * Minecraft 26.3+ вместо GLFW использует SDL3 (LWJGL org.lwjgl.sdl). Настоящая libSDL3.so (официальный релиз, zlib) собирается в CI
 * (.github/workflows/build-sdl3.yml) и лежит в runtime-libs. Здесь она только скачивается (процесс лаунчера); грузится в процессе игры, см. loadSdl3.
 */
/** Номер сборки libSDL3.so в runtime-libs: увеличить при пересборке (патчи SDL), чтобы телефоны скачали новую копию. */
private const val SDL3_REV = "3"
private var sdl3Loaded: String? = null

/** Каталог с libSDL3.so или null, если библиотеку получить не удалось (тогда игра, вероятно, упадёт на «Loading library SDL»). */
internal fun MainActivity.ensureSdl3(): String? {
    if (!isArm64) return null
    sdl3Loaded?.let { return it }
    val dir = File(filesDir, "sdl3").apply { mkdirs() }
    val so = File(dir, "libSDL3.so")
    return try {
        // файл из репозитория может обновиться: в именах версий не нужно, проверяем размер
        val rev = File(dir, "rev")
        if (!so.exists() || so.length() < 500_000 || (try { rev.readText() } catch (_: Exception) { "" }) != SDL3_REV) {
            log("SDL3: скачивание libSDL3.so"); downloadBig(MIRROR + "sdl3/arm64-v8a/libSDL3.so", so); rev.writeText(SDL3_REV)
        }
        so.setReadable(true, false); so.setExecutable(true, false)
        log("SDL3: libSDL3.so готова (${so.length() / 1024} КБ)")
        sdl3Loaded = dir.path
        dir.path
    } catch (e: Throwable) {
        so.delete()
        log("SDL3: не удалось подготовить libSDL3.so ($e). Игре 26.3+ она нужна, без неё запуск не удастся.")
        null
    }
}

/**
 * Вызывается в процессе игры (:game) до запуска JVM: JNI_OnLoad библиотеки регистрирует методы классов org.libsdl.app.* (они в приложении),
 * поэтому грузим её через System.load в ART, а LWJGL потом получает уже загруженную копию по тому же пути (org.lwjgl.librarypath).
 */
internal fun loadSdl3(dir: String): String? = try {
    System.load(File(dir, "libSDL3.so").path)
    org.libsdl.app.SDL.setupJNI()
    null
} catch (e: Throwable) { e.toString() }
