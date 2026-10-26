package org.lwjgl.opengl;

/** Параметры контекста (LWJGL 2). Android всегда даёт один контекст, поэтому значения только хранятся. */
public final class ContextAttribs {
	private int major = 1, minor = 0;
	private boolean core, compat, forward, debug;

	public ContextAttribs() {
	}

	public ContextAttribs(int majorVersion, int minorVersion) {
		this.major = majorVersion; this.minor = minorVersion;
	}

	public int getMajorVersion() { return major; }
	public int getMinorVersion() { return minor; }
	public boolean isProfileCore() { return core; }
	public boolean isProfileCompatibility() { return compat; }
	public boolean isForwardCompatible() { return forward; }
	public boolean isDebug() { return debug; }

	public ContextAttribs withForwardCompatible(boolean f) { forward = f; return this; }
	public ContextAttribs withProfileCore(boolean p) { core = p; return this; }
	public ContextAttribs withProfileCompatibility(boolean p) { compat = p; return this; }
	public ContextAttribs withDebug(boolean d) { debug = d; return this; }
	public ContextAttribs withLayer(int layer) { return this; }
	public ContextAttribs withContextReleaseBehavior(int b) { return this; }
	public ContextAttribs withRobustAccess(boolean r) { return this; }
	public ContextAttribs withResetNotificationStrategy(boolean r) { return this; }
}
