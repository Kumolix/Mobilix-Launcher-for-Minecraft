package org.lwjgl.input;

/** Геймпад LWJGL 2. На Android подключённых контроллеров для старых версий нет, поэтому это только интерфейс. */
public interface Controller {
	String getName();
	int getIndex();
	int getButtonCount();
	String getButtonName(int index);
	boolean isButtonPressed(int index);
	void poll();
	float getPovX();
	float getPovY();
	float getDeadZone(int index);
	void setDeadZone(int index, float zone);
	int getAxisCount();
	String getAxisName(int index);
	float getAxisValue(int index);
	float getXAxisValue();
	float getYAxisValue();
	float getZAxisValue();
	float getRXAxisValue();
	float getRYAxisValue();
	float getRZAxisValue();
}
