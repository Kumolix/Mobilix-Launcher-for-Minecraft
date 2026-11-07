package dev.mobilix;

import java.awt.Canvas;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Запуск Minecraft 1.0-1.5.2 и Classic (c0.x) без окна AWT.
 * Эти версии стартовали через Minecraft.main -> Frame + Canvas, а у нас нет экрана для AWT (java.awt.headless).
 * Игра умеет работать и без холста (так включается полноэкранный режим), поэтому создаём клиент сами:
 * передаём null вместо окна, холста и апплета, подставляем сессию и запускаем главный поток игры.
 * Классы игры обфусцированы, так что ищем нужное по сигнатурам: конструктор с Canvas и поле с типом (String, String).
 *
 * Аргументы: <главный класс> <ник> <сессия> [--width W] [--height H]
 */
public final class OldMain {
	private OldMain() {}

	/** Запасной вариант: перебираем классы из jar игры в пакетах клиента и ищем конкретный класс с конструктором (..Canvas..). */
	private static Constructor<?> scanForClient(ClassLoader cl) {
		String[] prefixes = { "net/minecraft/client/", "com/mojang/minecraft/", "com/mojang/rubydung/" };
		for (String path : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
			if (!path.endsWith(".jar")) continue;
			try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(path)) {
				for (java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
					String n = en.nextElement().getName();
					if (!n.endsWith(".class") || n.indexOf('$') >= 0) continue;
					boolean ok = false;
					for (String pf : prefixes) if (n.startsWith(pf) && n.indexOf('/', pf.length()) < 0) ok = true;
					if (!ok) continue;
					try {
						Class<?> c = Class.forName(n.substring(0, n.length() - 6).replace('/', '.'), false, cl);
						if (Modifier.isAbstract(c.getModifiers()) || !Runnable.class.isAssignableFrom(c)) continue;
						for (Constructor<?> k : c.getDeclaredConstructors())
							for (Class<?> p : k.getParameterTypes()) if (p == Canvas.class) return k;
					} catch (Throwable ignored) {}
				}
			} catch (Throwable ignored) {}
		}
		return null;
	}

	public static void main(String[] a) throws Throwable {
		String mainName = a[0];
		// аргументы бывают позиционными («ник сессия») или с флагами («--username ник --session сессия»)
		String user = a.length > 1 ? a[1] : "Player";
		String session = a.length > 2 ? a[2] : "-";
		int w = 854, h = 480;
		for (int i = 1; i + 1 < a.length; i++) {
			try {
				if (a[i].equals("--username")) user = a[i + 1];
				else if (a[i].equals("--session")) session = a[i + 1];
				else if (a[i].equals("--width")) w = Integer.parseInt(a[i + 1]);
				else if (a[i].equals("--height")) h = Integer.parseInt(a[i + 1]);
			} catch (NumberFormatException ignored) {}
		}
		ClassLoader cl = OldMain.class.getClassLoader();
		String pkg = mainName.contains(".") ? mainName.substring(0, mainName.lastIndexOf('.') + 1) : "";
		String[] names = { mainName, pkg + "MinecraftImpl", pkg + "Minecraft", "net.minecraft.client.MinecraftImpl", "net.minecraft.client.Minecraft", "com.mojang.minecraft.Minecraft" };

		Constructor<?> ctor = null;
		for (String n : names) {
			Class<?> c;
			try { c = Class.forName(n, true, cl); } catch (Throwable t) { continue; }
			if (Modifier.isAbstract(c.getModifiers())) continue;
			for (Constructor<?> k : c.getDeclaredConstructors()) {
				for (Class<?> p : k.getParameterTypes()) if (p == Canvas.class) { ctor = k; break; }
				if (ctor != null) break;
			}
			if (ctor != null) break;
		}
		if (ctor == null) ctor = scanForClient(cl);
		if (ctor == null) throw new RuntimeException("Не найден конструктор клиента (с Canvas) для " + mainName);
		System.out.println("[Mobilix] Старая версия без AWT: " + ctor.getDeclaringClass().getName());

		Class<?>[] pt = ctor.getParameterTypes();
		Object[] args = new Object[pt.length];
		int ints = 0;
		for (int i = 0; i < pt.length; i++) {
			if (pt[i] == int.class) args[i] = ints++ == 0 ? Integer.valueOf(w) : Integer.valueOf(h);
			else if (pt[i] == boolean.class) args[i] = Boolean.FALSE;   // не полноэкранный режим: окно создаёт слой LWJGL 2
			else args[i] = null;
		}
		ctor.setAccessible(true);
		Object mc = ctor.newInstance(args);

		// сессия: поле, чей тип создаётся из двух строк (ник, идентификатор сессии)
		boolean set = false;
		for (Class<?> c = mc.getClass(); c != null && !set; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				Class<?> t = f.getType();
				if (Modifier.isStatic(f.getModifiers()) || t.isPrimitive() || t == String.class || t.isArray()) continue;
				Constructor<?> sc;
				try { sc = t.getDeclaredConstructor(String.class, String.class); } catch (Throwable e) { continue; }
				try {
					sc.setAccessible(true); f.setAccessible(true);
					f.set(mc, sc.newInstance(user, session));
					set = true;
					break;
				} catch (Throwable e) { /* пробуем следующее поле */ }
			}
		}
		if (!set) System.out.println("[Mobilix] Не нашёл поле сессии: игра запустится с ником по умолчанию");

		if (!(mc instanceof Runnable)) throw new RuntimeException("Клиент не Runnable: " + mc.getClass().getName());
		Thread t = new Thread((Runnable) mc, "Minecraft main thread");
		t.setPriority(Thread.MAX_PRIORITY);
		t.start();
	}
}
