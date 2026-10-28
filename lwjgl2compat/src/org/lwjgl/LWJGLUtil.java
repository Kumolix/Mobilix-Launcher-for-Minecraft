package org.lwjgl;

/** Часть LWJGLUtil из LWJGL 2: Forge и моды спрашивают платформу. Мы всегда «linux». */
public class LWJGLUtil {
    public static final int PLATFORM_LINUX = 1;
    public static final int PLATFORM_MACOSX = 2;
    public static final int PLATFORM_WINDOWS = 3;

    public static final String PLATFORM_LINUX_NAME = "linux";
    public static final String PLATFORM_MACOSX_NAME = "macosx";
    public static final String PLATFORM_WINDOWS_NAME = "windows";

    public static final boolean DEBUG = false;
    public static final boolean CHECKS = true;

    public static int getPlatform() { return PLATFORM_LINUX; }

    public static String getPlatformName() { return PLATFORM_LINUX_NAME; }

    public static void log(CharSequence msg) { if (DEBUG) System.err.println("[LWJGL] " + msg); }

    public static boolean isMacOSXEqualsOrBetterThan(int major, int minor) { return false; }

    public static String getPrivilegedProperty(String property) { return System.getProperty(property); }

    public static String getPrivilegedProperty(String property, String def) { return System.getProperty(property, def); }
}
