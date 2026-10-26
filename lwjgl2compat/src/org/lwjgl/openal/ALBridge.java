package org.lwjgl.openal;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** Методы LWJGL 2 для класса AL (добавляются в AL из LWJGL 3 патчером). */
public final class ALBridge {
	private static long device, context;

	private ALBridge() {}

	public static synchronized void create(String deviceArguments, int contextFrequency, int contextRefresh, boolean contextSynchronized) {
		create();
	}

	public static synchronized void create() {
		if (context != 0) return;
		ALC.create();
		device = ALC10.alcOpenDevice((ByteBuffer) null);
		if (device == 0) throw new IllegalStateException("Не удалось открыть звуковое устройство");
		context = ALC10.alcCreateContext(device, (IntBuffer) null);
		ALC10.alcMakeContextCurrent(context);
		AL.createCapabilities(ALC.createCapabilities(device));
	}

	public static synchronized boolean isCreated() {
		return context != 0;
	}

	public static synchronized long getDevice() {
		return device;
	}

	public static synchronized void destroy() {
		if (context == 0) return;
		ALC10.alcMakeContextCurrent(0);
		ALC10.alcDestroyContext(context);
		ALC10.alcCloseDevice(device);
		context = 0;
		device = 0;
	}
}
