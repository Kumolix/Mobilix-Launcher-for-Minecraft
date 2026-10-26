package org.lwjgl.input;

import java.nio.IntBuffer;
import org.lwjgl.LWJGLException;

/** Заглушка курсора LWJGL 2: на Android нативных курсоров нет. */
public class Cursor {
	public static final int CURSOR_ONE_BIT_TRANSPARENCY = 1;
	public static final int CURSOR_8_BIT_ALPHA = 2;
	public static final int CURSOR_ANIMATION = 4;

	public Cursor(int width, int height, int xHotspot, int yHotspot, int numImages, IntBuffer images, IntBuffer delays) throws LWJGLException {
	}

	public static int getMinCursorSize() { return 1; }
	public static int getMaxCursorSize() { return 32; }
	public static int getCapabilities() { return CURSOR_8_BIT_ALPHA; }
	public void setTimeout(int index, long timeout) {}
	public long getTimeout(int index) { return 0; }
	public void destroy() {}
}
