package org.lwjgl.opengl;

import org.lwjgl.LWJGLException;

/** Что угодно, на чём можно рисовать (LWJGL 2). */
public interface Drawable {
	boolean isCurrent() throws LWJGLException;
	void makeCurrent() throws LWJGLException;
	void releaseContext() throws LWJGLException;
	void destroy();
	void setCLSharingProperties(java.nio.IntBuffer properties) throws LWJGLException;
}
