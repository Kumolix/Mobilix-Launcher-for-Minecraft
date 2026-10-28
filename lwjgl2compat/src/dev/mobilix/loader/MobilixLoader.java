package dev.mobilix.loader;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Системный загрузчик классов для старого Forge (LaunchWrapper): на Java 9+ системный загрузчик
 * не является URLClassLoader, а LaunchWrapper приводит его к этому типу. Этот загрузчик им является.
 * Подключается ключом -Djava.system.class.loader=dev.mobilix.loader.MobilixLoader.
 */
public class MobilixLoader extends URLClassLoader {
    public MobilixLoader(ClassLoader parent) {
        super(urls(), parent);
    }

    private static URL[] urls() {
        List<URL> out = new ArrayList<URL>();
        String cp = System.getProperty("java.class.path", "");
        for (String s : cp.split(File.pathSeparator)) {
            if (s.isEmpty()) continue;
            try { out.add(new File(s).toURI().toURL()); } catch (Exception ignored) {}
        }
        return out.toArray(new URL[0]);
    }

    private static boolean parentFirst(String n) {
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("sun.") || n.startsWith("jdk.")
                || n.startsWith("com.sun.") || n.equals("dev.mobilix.loader.MobilixLoader");
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (parentFirst(name)) return super.loadClass(name, resolve);
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException e) {
                    return super.loadClass(name, resolve);
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    /** Вызывается JVM при подключении java-агентов (-javaagent). */
    @SuppressWarnings("unused")
    void appendToClassPathForInstrumentation(String path) {
        try { addURL(new File(path).toURI().toURL()); } catch (Exception ignored) {}
    }
}
