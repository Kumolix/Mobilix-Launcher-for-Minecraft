package org.lwjgl.input;

import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;

/**
 * Мышь в стиле LWJGL 2 поверх GLFW (Mobilix). Начало координат внизу слева, как в LWJGL 2.
 */
public class Mouse {
	private static final int BUTTON_COUNT = 8;

	private static boolean created;
	private static boolean grabbed;
	private static final boolean[] buttons = new boolean[BUTTON_COUNT];

	/** Текущая позиция (низ-лево) и позиция предыдущего события. */
	private static int x, y;
	private static double lastCx, lastCy;
	private static boolean haveLast;

	/** Накопленные смещения: sinceEvent* пишутся в события, poll* отдаются через getDX/getDY/getDWheel. */
	private static int accumDX, accumDY, accumWheel;
	private static int readDX, readDY, readWheel;

	private static final class MouseEvent {
		int button; boolean state; int dx, dy, dwheel, x, y; long nanos;
	}
	private static final java.util.ArrayDeque<MouseEvent> queue = new java.util.ArrayDeque<MouseEvent>();
	private static MouseEvent current = new MouseEvent();
	private static boolean clipToWindow = true;

	private Mouse() {
	}

	public static void create() throws LWJGLException {
		synchronized (InputBridge.LOCK) {
			if (!Display.isCreated()) throw new IllegalStateException("Display must be created.");
			if (created) return;
			created = true;
			reset();
		}
	}

	private static void reset() {
		queue.clear();
		current = new MouseEvent();
		java.util.Arrays.fill(buttons, false);
		accumDX = accumDY = accumWheel = readDX = readDY = readWheel = 0;
		haveLast = false;
	}

	public static boolean isCreated() {
		synchronized (InputBridge.LOCK) { return created; }
	}

	public static void destroy() {
		synchronized (InputBridge.LOCK) {
			if (!created) return;
			created = false;
			reset();
		}
	}

	public static void poll() {
		synchronized (InputBridge.LOCK) {
			if (!created) throw new IllegalStateException("Mouse must be created before you can poll it");
			readDX += accumDX; readDY += accumDY; readWheel += accumWheel;
			accumDX = accumDY = accumWheel = 0;
		}
	}

	public static boolean isButtonDown(int button) {
		synchronized (InputBridge.LOCK) {
			if (!created) throw new IllegalStateException("Mouse must be created before you can poll the button state");
			return button >= 0 && button < BUTTON_COUNT && buttons[button];
		}
	}

	public static String getButtonName(int button) {
		switch (button) {
			case 0: return "BUTTON0";
			case 1: return "BUTTON1";
			case 2: return "BUTTON2";
			default: return button >= 0 && button < BUTTON_COUNT ? "BUTTON" + button : null;
		}
	}

	public static int getButtonIndex(String buttonName) {
		for (int i = 0; i < BUTTON_COUNT; i++) if (("BUTTON" + i).equals(buttonName)) return i;
		return -1;
	}

	public static boolean next() {
		synchronized (InputBridge.LOCK) {
			if (!created) throw new IllegalStateException("Mouse must be created before you can read events");
			MouseEvent e = queue.poll();
			if (e == null) return false;
			current = e;
			return true;
		}
	}

	public static int getEventButton() { synchronized (InputBridge.LOCK) { return current.button; } }
	public static boolean getEventButtonState() { synchronized (InputBridge.LOCK) { return current.state; } }
	public static int getEventDX() { synchronized (InputBridge.LOCK) { return current.dx; } }
	public static int getEventDY() { synchronized (InputBridge.LOCK) { return current.dy; } }
	public static int getEventX() { synchronized (InputBridge.LOCK) { return current.x; } }
	public static int getEventY() { synchronized (InputBridge.LOCK) { return current.y; } }
	public static int getEventDWheel() { synchronized (InputBridge.LOCK) { return current.dwheel; } }
	public static long getEventNanoseconds() { synchronized (InputBridge.LOCK) { return current.nanos; } }

	public static int getX() { synchronized (InputBridge.LOCK) { return x; } }
	public static int getY() { synchronized (InputBridge.LOCK) { return y; } }

	public static int getDX() {
		synchronized (InputBridge.LOCK) { int r = readDX; readDX = 0; return r; }
	}

	public static int getDY() {
		synchronized (InputBridge.LOCK) { int r = readDY; readDY = 0; return r; }
	}

	public static int getDWheel() {
		synchronized (InputBridge.LOCK) { int r = readWheel; readWheel = 0; return r; }
	}

	public static boolean isInsideWindow() { return true; }

	public static int getButtonCount() { return 3; }

	public static boolean hasWheel() { return true; }

	public static boolean isGrabbed() {
		synchronized (InputBridge.LOCK) { return grabbed; }
	}

	public static void setGrabbed(boolean grab) {
		synchronized (InputBridge.LOCK) {
			boolean was = grabbed;
			grabbed = grab;
			if (was != grab) Display.applyCursorMode(grab);
			if (grab) haveLast = false;
		}
	}

	public static void setCursorPosition(int newX, int newY) {
		synchronized (InputBridge.LOCK) {
			if (!created) throw new IllegalStateException("Mouse is not created");
			x = newX; y = newY;
			haveLast = false;
			Display.moveCursor(newX, Display.getHeight() - newY);
		}
	}

	public static void setClipMouseCoordinatesToWindow(boolean clip) { clipToWindow = clip; }

	public static void setEventQueueSize(int queue_size) {
	}

	public static Cursor setNativeCursor(Cursor cursor) throws LWJGLException {
		return null;
	}

	public static Cursor getNativeCursor() { return null; }

	public static void updateCursor() {
	}

	// ---- вызывается из Display (обработчики GLFW) ----

	public static void onMove(double cx, double cy, int height) {
		synchronized (InputBridge.LOCK) {
			if (!created) return;
			if (!haveLast) { lastCx = cx; lastCy = cy; haveLast = true; }
			int dx = (int) Math.round(cx - lastCx);
			int dy = (int) Math.round(lastCy - cy);   // вверх = плюс
			lastCx = cx; lastCy = cy;
			x = (int) Math.round(cx);
			y = height - 1 - (int) Math.round(cy);
			if (clipToWindow && !grabbed) {
				if (x < 0) x = 0;
				if (y < 0) y = 0;
			}
			if (dx == 0 && dy == 0) return;
			accumDX += dx; accumDY += dy;
			MouseEvent e = new MouseEvent();
			e.button = -1; e.dx = dx; e.dy = dy; e.x = x; e.y = y; e.nanos = System.nanoTime();
			add(e);
		}
	}

	public static void onButton(int button, boolean pressed) {
		synchronized (InputBridge.LOCK) {
			if (!created || button < 0 || button >= BUTTON_COUNT) return;
			buttons[button] = pressed;
			MouseEvent e = new MouseEvent();
			e.button = button; e.state = pressed; e.x = x; e.y = y; e.nanos = System.nanoTime();
			add(e);
		}
	}

	public static void onScroll(double dy) {
		synchronized (InputBridge.LOCK) {
			if (!created) return;
			int w = (int) Math.round(dy * 120.0);
			if (w == 0) return;
			accumWheel += w;
			MouseEvent e = new MouseEvent();
			e.button = -1; e.dwheel = w; e.x = x; e.y = y; e.nanos = System.nanoTime();
			add(e);
		}
	}

	private static void add(MouseEvent e) {
		if (queue.size() > 500) queue.poll();
		queue.add(e);
	}
}
