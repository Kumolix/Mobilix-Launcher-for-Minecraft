package org.lwjgl.opengl;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import org.lwjgl.system.MemoryUtil;

/**
 * Методы LWJGL 2 для GL11 (добавляются патчером): клиентские массивы вершин.
 * В LWJGL 2 тип данных задаётся классом буфера: glTexCoordPointer(size, stride, FloatBuffer),
 * а в LWJGL 3 тип передаётся явно. Нужны Minecraft 1.6-1.7 (Tessellator рисует через клиентские массивы).
 * Если такой метод в GL11 уже есть, патчер его пропускает, поэтому остальные версии не затрагиваются.
 */
public final class GL11Bridge {
	private GL11Bridge() {}

	// ---- вершины ----
	public static void glVertexPointer(int size, int stride, DoubleBuffer p) { GL11.glVertexPointer(size, GL11.GL_DOUBLE, stride, MemoryUtil.memAddress(p)); }
	public static void glVertexPointer(int size, int stride, FloatBuffer p) { GL11.glVertexPointer(size, GL11.GL_FLOAT, stride, MemoryUtil.memAddress(p)); }
	public static void glVertexPointer(int size, int stride, IntBuffer p) { GL11.glVertexPointer(size, GL11.GL_INT, stride, MemoryUtil.memAddress(p)); }
	public static void glVertexPointer(int size, int stride, ShortBuffer p) { GL11.glVertexPointer(size, GL11.GL_SHORT, stride, MemoryUtil.memAddress(p)); }

	// ---- текстурные координаты ----
	public static void glTexCoordPointer(int size, int stride, DoubleBuffer p) { GL11.glTexCoordPointer(size, GL11.GL_DOUBLE, stride, MemoryUtil.memAddress(p)); }
	public static void glTexCoordPointer(int size, int stride, FloatBuffer p) { GL11.glTexCoordPointer(size, GL11.GL_FLOAT, stride, MemoryUtil.memAddress(p)); }
	public static void glTexCoordPointer(int size, int stride, IntBuffer p) { GL11.glTexCoordPointer(size, GL11.GL_INT, stride, MemoryUtil.memAddress(p)); }
	public static void glTexCoordPointer(int size, int stride, ShortBuffer p) { GL11.glTexCoordPointer(size, GL11.GL_SHORT, stride, MemoryUtil.memAddress(p)); }

	// ---- цвет ----
	public static void glColorPointer(int size, int stride, DoubleBuffer p) { GL11.glColorPointer(size, GL11.GL_DOUBLE, stride, MemoryUtil.memAddress(p)); }
	public static void glColorPointer(int size, int stride, FloatBuffer p) { GL11.glColorPointer(size, GL11.GL_FLOAT, stride, MemoryUtil.memAddress(p)); }
	public static void glColorPointer(int size, boolean unsigned, int stride, ByteBuffer p) {
		GL11.glColorPointer(size, unsigned ? GL11.GL_UNSIGNED_BYTE : GL11.GL_BYTE, stride, MemoryUtil.memAddress(p));
	}

	// ---- нормали ----
	public static void glNormalPointer(int stride, ByteBuffer p) { GL11.glNormalPointer(GL11.GL_BYTE, stride, MemoryUtil.memAddress(p)); }
	public static void glNormalPointer(int stride, DoubleBuffer p) { GL11.glNormalPointer(GL11.GL_DOUBLE, stride, MemoryUtil.memAddress(p)); }
	public static void glNormalPointer(int stride, FloatBuffer p) { GL11.glNormalPointer(GL11.GL_FLOAT, stride, MemoryUtil.memAddress(p)); }
	public static void glNormalPointer(int stride, IntBuffer p) { GL11.glNormalPointer(GL11.GL_INT, stride, MemoryUtil.memAddress(p)); }

	// ---- индексы цвета ----
	public static void glIndexPointer(int stride, IntBuffer p) { GL11.glIndexPointer(GL11.GL_INT, stride, MemoryUtil.memAddress(p)); }
	public static void glIndexPointer(int stride, ShortBuffer p) { GL11.glIndexPointer(GL11.GL_SHORT, stride, MemoryUtil.memAddress(p)); }
}
