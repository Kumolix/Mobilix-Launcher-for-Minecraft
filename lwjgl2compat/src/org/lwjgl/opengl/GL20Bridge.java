package org.lwjgl.opengl;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Методы LWJGL 2 для GL20 (добавляются патчером). */
public final class GL20Bridge {
	private GL20Bridge() {}

	static String text(ByteBuffer b) {
		byte[] a = new byte[b.remaining()];
		b.duplicate().get(a);
		return new String(a, StandardCharsets.UTF_8);
	}

	public static void glShaderSource(int shader, ByteBuffer string) {
		GL20.glShaderSource(shader, (CharSequence) text(string));
	}
}
