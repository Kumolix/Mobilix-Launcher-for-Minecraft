package org.lwjgl.opengl;

/** Доступ к возможностям текущего контекста (LWJGL 2) поверх LWJGL 3. */
public final class GLContext {
	private static ContextCapabilities caps;

	private GLContext() {
	}

	public static synchronized ContextCapabilities getCapabilities() {
		if (caps == null) {
			GLCapabilities c;
			try { c = GL.getCapabilities(); } catch (IllegalStateException e) { c = null; }
			if (c == null) throw new RuntimeException("No OpenGL context found in the current thread.");
			caps = new ContextCapabilities(c);
		}
		return caps;
	}

	static synchronized void reset() { caps = null; }

	public static void useContext(Object context) {
		reset();
	}

	public static void useContext(Object context, boolean forwardCompatible) {
		reset();
	}

	public static synchronized long getFunctionAddress(String name) {
		return GL.getFunctionProvider() == null ? 0L : GL.getFunctionProvider().getFunctionAddress(name);
	}
}
