package org.lwjgl;

/** Системные функции LWJGL 2 (Mobilix): минимальный набор, нужный игре. */
public final class Sys {
	private static final String VERSION = "2.9.4";
	private static final long START = System.nanoTime();

	private Sys() {
	}

	public static void initialize() {
	}

	public static String getVersion() { return VERSION; }

	public static boolean is64Bit() { return true; }

	/** Тики в секунду: миллисекунды. */
	public static long getTimerResolution() { return 1000L; }

	public static long getTime() { return System.nanoTime() / 1000000L; }

	public static void alert(String title, String message) {
		System.err.println("[LWJGL alert] " + title + ": " + message);
	}

	public static boolean openURL(String url) { return false; }

	public static String getClipboard() { return ""; }

	
}
