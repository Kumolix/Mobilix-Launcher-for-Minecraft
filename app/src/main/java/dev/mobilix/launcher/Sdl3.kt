package dev.mobilix.launcher

import java.io.File

/**
 * Minecraft 26.3+ вместо GLFW использует SDL3 (LWJGL org.lwjgl.sdl). Настоящая libSDL3.so (официальный релиз, zlib) собирается в CI
 * (.github/workflows/build-sdl3.yml) и лежит в runtime-libs. Её JNI_OnLoad обязательно срабатывает в ART: он регистрирует методы
 * классов org.libsdl.app.* (они в приложении), поэтому библиотеку грузим в процесс приложения через System.load, а LWJGL потом
 * находит её по тому же пути (org.lwjgl.librarypath) и получает уже загруженную копию.
 */
private var sdl3Loaded: String? = null

/** Каталог с libSDL3.so или null, если библиотеку получить не удалось (тогда игра, вероятно, упадёт на «Loading library SDL»). */
internal fun MainActivity.ensureSdl3(): String? {
    if (!isArm64) return null
    sdl3Loaded?.let { return it }
    val dir = File(filesDir, "sdl3").apply { mkdirs() }
    val so = File(dir, "libSDL3.so")
    return try {
        // файл из репозитория может обновиться: в именах версий не нужно, проверяем размер
        if (!so.exists() || so.length() < 500_000) { log("SDL3: скачивание libSDL3.so"); downloadBig(MIRROR + "sdl3/arm64-v8a/libSDL3.so", so) }
        so.setReadable(true, false); so.setExecutable(true, false)
        System.load(so.path)
        org.libsdl.app.SDL.setupJNI()
        log("SDL3: libSDL3.so загружена (${so.length() / 1024} КБ)")
        sdl3Loaded = dir.path
        dir.path
    } catch (e: Throwable) {
        if (e !is LinkageError) so.delete()
        log("SDL3: не удалось подготовить libSDL3.so ($e). Игре 26.3+ она нужна, без неё запуск не удастся.")
        null
    }
}
