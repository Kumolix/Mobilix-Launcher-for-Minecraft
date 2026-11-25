package org.libsdl.app;

import android.app.Activity;
import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Surface;

/**
 * Mobilix: SDL3 without SDLActivity. The game screen (GameActivity) owns the SurfaceView; this class gives its Surface to SDL
 * and sends SDL the same calls that SDLSurface sends in the stock Android project. Must be used on the UI thread.
 */
public final class SdlBridge {
    private static Surface sSurface;
    private static boolean sStarted, sPaused;

    private static final class Surf extends SDLSurface {
        Surf(Context c) { super(c); }
        @Override protected Surface getNativeSurface() { return sSurface; }
    }

    /** Before the game starts: SDL gets a context (Activity) and a stand-in for the surface view. */
    public static void attach(Activity a) {
        SDL.initialize();
        SDL.setContext(a);
        SDLActivity.mSurface = new Surf(a);
    }

    /** SurfaceHolder.surfaceChanged: width/height are the real size of the render surface (render resolution is already applied). */
    public static void surfaceChanged(Activity a, Surface s, int w, int h) {
        sSurface = s;
        float rate = 60f;
        try { rate = a.getWindowManager().getDefaultDisplay().getRefreshRate(); } catch (Throwable ignored) {}
        // the display size equals the render size, so the game sees one consistent screen with the resolution the player chose
        SDLActivity.nativeSetScreenResolution(w, h, w, h, 1.0f, rate);
        SDLActivity.onNativeResize();
        if (!sStarted) {
            sStarted = true;
            SDLActivity.onNativeSurfaceCreated();
            SDLActivity.onNativeSurfaceChanged();
        } else {
            SDLActivity.onNativeSurfaceChanged();
            if (sPaused) { sPaused = false; SDLActivity.nativeResume(); }
        }
        SDLActivity.nativeFocusChanged(true);
    }

    public static void surfaceDestroyed() {
        if (!sStarted) return;
        sPaused = true;
        SDLActivity.nativePause();
        SDLActivity.onNativeSurfaceDestroyed();
    }
}
