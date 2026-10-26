package org.lwjgl.opengl;

import java.nio.ByteBuffer;

/** Методы LWJGL 2 для ARBShaderObjects (добавляются патчером). */
public final class ARBShaderObjectsBridge {
	private ARBShaderObjectsBridge() {}

	public static void glShaderSourceARB(int shader, ByteBuffer string) {
		ARBShaderObjects.glShaderSourceARB(shader, (CharSequence) GL20Bridge.text(string));
	}
}
