package org.lwjgl.opengl;

/** Ошибка OpenGL (LWJGL 2). */
public class OpenGLException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public OpenGLException(int gl_error_code) {
		this(Util.translateGLErrorString(gl_error_code));
	}

	public OpenGLException() { super(); }
	public OpenGLException(String message) { super(message); }
	public OpenGLException(String message, Throwable cause) { super(message, cause); }
	public OpenGLException(Throwable cause) { super(cause); }
}
