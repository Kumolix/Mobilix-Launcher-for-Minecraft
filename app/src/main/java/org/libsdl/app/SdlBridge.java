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
    /** True while the game has SDL relative mouse mode on (mouse grabbed: the screen drags turn the camera). */
    public static volatile boolean relative;
    public static volatile boolean active;
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
        active = true;
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

    // ---------- input: the launcher produces GLFW-style events, here they become SDL events ----------
    private static double lastX, lastY;
    private static int buttons;

    /** types as in Native.pushEvent: 1 key, 2 char, 3 cursor position, 4 mouse button, 5 scroll, 6 focus. */
    public static void push(int type, double a, double b, int c) {
        try {
            switch (type) {
                case 1: {
                    int act = c & 15;
                    if (act == 2) return;   // repeat: SDL makes its own
                    int k = androidKey((int) a);
                    if (k == 0) return;
                    if (act == 0) SDLActivity.onNativeKeyUp(k); else SDLActivity.onNativeKeyDown(k);
                    break;
                }
                case 2:
                    SDLInputConnection.nativeCommitText(new String(Character.toChars((int) a)), 1);
                    break;
                case 3: {
                    // SDL gets deltas: in absolute mode the cursor is moved by them, in relative mode they are the mouse motion
                    float dx = (float) (a - lastX), dy = (float) (b - lastY);
                    lastX = a; lastY = b;
                    if (dx != 0 || dy != 0) SDLActivity.onNativeMouse(0, 7 /*ACTION_HOVER_MOVE*/, dx, dy, true);
                    break;
                }
                case 4: {
                    int bit = a == 0 ? 1 : a == 1 ? 2 : a == 2 ? 4 : 0;   // BUTTON_PRIMARY / SECONDARY / TERTIARY
                    if (bit == 0) return;
                    if (c == 0) buttons &= ~bit; else buttons |= bit;
                    SDLActivity.onNativeMouse(buttons, c == 0 ? 1 : 0 /*ACTION_UP / ACTION_DOWN*/, 0f, 0f, true);
                    break;
                }
                case 5:
                    SDLActivity.onNativeMouse(0, 8 /*ACTION_SCROLL*/, (float) a, (float) b, false);
                    break;
                case 6:
                    SDLActivity.nativeFocusChanged(c != 0);
                    break;
                default:
            }
        } catch (Throwable ignored) {}
    }

    /** GLFW key code -> Android KeyEvent key code (0 = unknown). */
    private static int androidKey(int g) {
        if (g >= 65 && g <= 90) return 29 + (g - 65);        // A-Z
        if (g >= 48 && g <= 57) return 7 + (g - 48);         // 0-9
        if (g >= 290 && g <= 301) return 131 + (g - 290);    // F1-F12
        if (g >= 320 && g <= 329) return 144 + (g - 320);    // keypad 0-9
        switch (g) {
            case 32: return 62;   case 39: return 75;  case 44: return 55;  case 45: return 69;  case 46: return 56;
            case 47: return 76;   case 59: return 74;  case 61: return 70;  case 91: return 71;  case 92: return 73;
            case 93: return 72;   case 96: return 68;
            case 256: return 111; case 257: return 66; case 258: return 61; case 259: return 67; case 260: return 124;
            case 261: return 112; case 262: return 22; case 263: return 21; case 264: return 20; case 265: return 19;
            case 266: return 92;  case 267: return 93; case 268: return 122; case 269: return 123; case 280: return 115;
            case 335: return 160; case 340: return 59; case 341: return 113; case 342: return 57;  case 343: return 117;
            case 344: return 60;  case 345: return 114; case 346: return 58;
            default: return 0;
        }
    }
}
