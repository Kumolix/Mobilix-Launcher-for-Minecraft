package org.lwjgl.opengl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.input.InputBridge;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Окно LWJGL 2 поверх GLFW (Mobilix). Размер окна всегда равен поверхности Android,
 * запрошенный игрой размер используется только как начальное значение.
 */
public final class Display {
	private static long window;
	private static boolean created;
	private static boolean glfwReady;
	private static boolean closeRequested;
	private static boolean active = true;
	private static boolean fullscreen;
	private static boolean resizable;
	private static boolean resizedPending, resized;
	private static volatile long lastFrame = System.currentTimeMillis();
	private static boolean watchdog;
	private static boolean vsync;
	private static boolean grabWanted;
	private static String title = "Minecraft";
	private static int width = 854, height = 480;
	private static DisplayMode mode = new DisplayMode(854, 480);
	private static DisplayMode desktop;
	private static long lastSync;

	private Display() {
	}

	private static void ensureGlfw() throws LWJGLException {
		if (glfwReady) return;
		if (!GLFW.glfwInit()) throw new LWJGLException("glfwInit failed");
		glfwReady = true;
		long mon = GLFW.glfwGetPrimaryMonitor();
		GLFWVidMode vm = mon != 0 ? GLFW.glfwGetVideoMode(mon) : null;
		int w = vm != null ? vm.width() : 1920, h = vm != null ? vm.height() : 1080;
		desktop = new DisplayMode(w, h, 32, vm != null ? vm.refreshRate() : 60);
	}

	// ---------- создание ----------

	public static void create() throws LWJGLException {
		create(new PixelFormat());
	}

	public static void create(PixelFormat pixel_format) throws LWJGLException {
		create(pixel_format, null, null);
	}

	public static void create(PixelFormat pixel_format, Drawable shared_drawable) throws LWJGLException {
		create(pixel_format, shared_drawable, null);
	}

	public static void create(PixelFormat pixel_format, ContextAttribs attribs) throws LWJGLException {
		create(pixel_format, null, attribs);
	}

	public static synchronized void create(PixelFormat pixel_format, Drawable shared_drawable, ContextAttribs attribs) throws LWJGLException {
		if (created) throw new IllegalStateException("Only one LWJGL context may be instantiated at any one time.");
		ensureGlfw();
		GLFW.glfwDefaultWindowHints();
		window = GLFW.glfwCreateWindow(Math.max(1, mode.getWidth()), Math.max(1, mode.getHeight()), title, 0L, 0L);
		if (window == 0L) throw new LWJGLException("Failed to create the window (EGL context)");
		GLFW.glfwMakeContextCurrent(window);
		GL.createCapabilities();
		GLContext.reset();
		readSize();
		installCallbacks();
		GLFW.glfwSwapInterval(vsync ? 1 : 0);
		created = true;
		mode = new DisplayMode(width, height, 32, 60);
		startWatchdog();
		resizedPending = true; // окно имеет реальный размер экрана, игра должна подстроиться
		try {
			if (!Mouse.isCreated()) Mouse.create();
			if (!Keyboard.isCreated()) Keyboard.create();
		} catch (LWJGLException e) {
			throw e;
		}
		if (grabWanted) applyCursorMode(true);
	}

	private static void readSize() {
		IntBuffer w = BufferUtils.createIntBuffer(1), h = BufferUtils.createIntBuffer(1);
		GLFW.glfwGetFramebufferSize(window, w, h);
		if (w.get(0) > 0 && h.get(0) > 0) { width = w.get(0); height = h.get(0); }
	}

	private static void installCallbacks() {
		GLFW.glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
			int k = InputBridge.toLwjgl(key);
			if (k != 0) Keyboard.pushKey(k, action != GLFW.GLFW_RELEASE, action == GLFW.GLFW_REPEAT);
		});
		GLFW.glfwSetCharCallback(window, (win, cp) -> Keyboard.pushChar(cp));
		GLFW.glfwSetMouseButtonCallback(window, (win, button, action, mods) -> Mouse.onButton(button, action == GLFW.GLFW_PRESS));
		GLFW.glfwSetCursorPosCallback(window, (win, x, y) -> Mouse.onMove(x, y, height));
		GLFW.glfwSetScrollCallback(window, (win, dx, dy) -> Mouse.onScroll(dy));
		GLFW.glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
			if (w > 0 && h > 0 && (w != width || h != height)) { width = w; height = h; resizedPending = true; }
		});
		GLFW.glfwSetWindowCloseCallback(window, win -> closeRequested = true);
		GLFW.glfwSetWindowFocusCallback(window, (win, focused) -> active = focused);
	}

	public static synchronized void destroy() {
		if (!created) return;
		created = false;
		Mouse.destroy();
		Keyboard.destroy();
		try { GLFW.glfwDestroyWindow(window); } catch (Throwable t) {}
		window = 0L;
	}

	public static boolean isCreated() { return created; }

	// ---------- кадр ----------

	public static void update() {
		update(true);
	}

	public static void update(boolean processMessages) {
		if (!created) throw new IllegalStateException("Display not created");
		lastFrame = System.currentTimeMillis();
		GLFW.glfwSwapBuffers(window);
		if (processMessages) processMessages();
	}

	public static void processMessages() {
		if (!created) throw new IllegalStateException("Display not created");
		GLFW.glfwPollEvents();
		resized = resizedPending;
		resizedPending = false;
		if (Mouse.isCreated()) Mouse.poll();
		if (Keyboard.isCreated()) Keyboard.poll();
	}

	private static void startWatchdog() {
		if (watchdog) return;
		watchdog = true;
		Thread t = new Thread(() -> {
			long lastDump = 0;
			while (true) {
				try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
				long now = System.currentTimeMillis();
				if (now - lastFrame > 20000 && now - lastDump > 40000) {
					lastDump = now;
					System.err.println("[Mobilix] Кадры не выводятся уже " + (now - lastFrame) / 1000 + " с. Потоки игры:");
					for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
						String n = e.getKey().getName();
						if (!(n.contains("Client") || n.contains("main") || n.contains("Thread-") || n.contains("Server"))) continue;
						System.err.println("  поток " + n + " (" + e.getKey().getState() + ")");
						int k = 0;
						for (StackTraceElement el : e.getValue()) { System.err.println("    at " + el); if (++k >= 18) break; }
					}
				}
			}
		}, "Mobilix-watchdog");
		t.setDaemon(true);
		t.start();
	}

	public static void swapBuffers() {
		lastFrame = System.currentTimeMillis();
		if (!created) throw new IllegalStateException("Display not created");
		GLFW.glfwSwapBuffers(window);
	}

	public static void sync(int fps) {
		if (fps <= 0) return;
		long frame = 1000000000L / fps;
		long target = (lastSync == 0 ? System.nanoTime() : lastSync) + frame;
		long now;
		while ((now = System.nanoTime()) < target) {
			long rem = target - now;
			try {
				if (rem > 2000000L) Thread.sleep((rem - 1000000L) / 1000000L);
				else Thread.yield();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		lastSync = Math.max(target, System.nanoTime() - frame * 3);
	}

	// ---------- состояние ----------

	public static boolean isCloseRequested() { return closeRequested; }
	public static boolean isActive() { return active; }
	public static boolean isVisible() { return true; }
	public static boolean isDirty() { return true; }
	public static boolean wasResized() { return resized; }

	public static int getWidth() { return created ? width : mode.getWidth(); }
	public static int getHeight() { return created ? height : mode.getHeight(); }
	public static int getX() { return 0; }
	public static int getY() { return 0; }
	public static void setLocation(int x, int y) {}

	public static boolean isFullscreen() { return fullscreen; }
	public static void setFullscreen(boolean f) throws LWJGLException { fullscreen = f; }
	public static void setDisplayModeAndFullscreen(DisplayMode m) throws LWJGLException { setDisplayMode(m); }

	public static void setDisplayMode(DisplayMode newMode) throws LWJGLException {
		if (newMode == null) throw new NullPointerException("mode must be non-null");
		mode = newMode;
	}

	public static DisplayMode getDisplayMode() { return created ? new DisplayMode(width, height, 32, 60) : mode; }

	public static DisplayMode getDesktopDisplayMode() {
		try { ensureGlfw(); } catch (LWJGLException e) { return new DisplayMode(1920, 1080, 32, 60); }
		return desktop;
	}

	public static DisplayMode[] getAvailableDisplayModes() throws LWJGLException {
		ensureGlfw();
		return new DisplayMode[] { desktop };
	}

	public static void setTitle(String newTitle) {
		title = newTitle == null ? "" : newTitle;
		if (created) GLFW.glfwSetWindowTitle(window, title);
	}

	public static String getTitle() { return title; }

	public static void setResizable(boolean r) { resizable = r; }
	public static boolean isResizable() { return resizable; }
	public static void setParent(Object parent) throws LWJGLException {}
	public static Object getParent() { return null; }
	public static int setIcon(ByteBuffer[] icons) { return 0; }
	public static void setInitialBackground(float red, float green, float blue) {}
	public static float getPixelScaleFactor() { return 1.0f; }
	public static String getAdapter() { return "Android"; }
	public static String getVersion() { return "Mobilix GLFW"; }

	public static void setVSyncEnabled(boolean sync) {
		vsync = sync;
		if (created) GLFW.glfwSwapInterval(sync ? 1 : 0);
	}

	public static void setSwapInterval(int value) {
		vsync = value != 0;
		if (created) GLFW.glfwSwapInterval(value);
	}

	public static boolean isCurrent() throws LWJGLException { return created && GLFW.glfwGetCurrentContext() == window; }
	public static void makeCurrent() throws LWJGLException { if (created) GLFW.glfwMakeContextCurrent(window); }
	public static void releaseContext() throws LWJGLException { GLFW.glfwMakeContextCurrent(0L); }

	// ---------- для Mouse ----------

	public static void applyCursorMode(boolean grab) {
		grabWanted = grab;
		if (created) GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, grab ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
	}

	public static void moveCursor(int x, int yFromTop) {
		if (created) GLFW.glfwSetCursorPos(window, x, yFromTop);
	}
}
