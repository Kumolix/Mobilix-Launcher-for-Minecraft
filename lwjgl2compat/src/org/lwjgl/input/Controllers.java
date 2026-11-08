package org.lwjgl.input;

import org.lwjgl.LWJGLException;

/** Заглушка LWJGL 2 Controllers: старые версии Minecraft (1.0-1.5) её вызывают при старте. Контроллеров нет. */
public final class Controllers {
	private static boolean created;
	private Controllers() {}

	public static void create() throws LWJGLException { created = true; }
	public static boolean isCreated() { return created; }
	public static void destroy() { created = false; }
	public static void poll() {}
	public static int getControllerCount() { return 0; }
	public static Controller getController(int index) { return null; }
	public static void clearEvents() {}
	public static boolean next() { return false; }
	public static Controller getEventSource() { return null; }
	public static int getEventControlIndex() { return 0; }
	public static boolean isEventButton() { return false; }
	public static boolean isEventAxis() { return false; }
	public static boolean isEventXAxis() { return false; }
	public static boolean isEventYAxis() { return false; }
	public static boolean isEventPovX() { return false; }
	public static boolean isEventPovY() { return false; }
	public static long getEventNanoseconds() { return 0L; }
	public static boolean getEventButtonState() { return false; }
	public static float getEventXAxisValue() { return 0f; }
	public static float getEventYAxisValue() { return 0f; }
}
