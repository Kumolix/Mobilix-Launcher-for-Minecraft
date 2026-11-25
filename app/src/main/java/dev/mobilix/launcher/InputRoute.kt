package dev.mobilix.launcher

/** Куда идут события управления: в наш GLFW (до 26.2) или в SDL3 (26.3+). Типы событий как у Native.pushEvent. */
object Input {
    @JvmStatic fun push(type: Int, a: Double, b: Double, c: Int) {
        if (org.libsdl.app.SdlBridge.active) org.libsdl.app.SdlBridge.push(type, a, b, c) else Native.pushEvent(type, a, b, c)
    }
    /** Мышь «захвачена» игрой (камера по движению пальца). */
    @JvmStatic fun cursorGrabbed(): Boolean =
        if (org.libsdl.app.SdlBridge.active) org.libsdl.app.SdlBridge.relative else Native.cursorMode() == 0x34003
}
