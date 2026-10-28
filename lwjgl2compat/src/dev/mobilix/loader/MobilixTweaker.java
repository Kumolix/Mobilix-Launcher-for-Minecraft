package dev.mobilix.loader;

import java.io.File;
import java.util.List;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

/** Подключается как дополнительный --tweakClass: ставит трансформер, чтобы старый Forge работал на Java 21. */
public class MobilixTweaker implements ITweaker {
    public MobilixTweaker() {
        try {
            Class.forName("dev.mobilix.U"); // проверка, что Unsafe доступен
        } catch (Throwable ignored) {}
        try {
            Class<?> u = Class.forName("dev.mobilix.U");
            java.lang.reflect.Method m = u.getDeclaredMethod("unfilterReflection");
            m.setAccessible(true);
            m.invoke(null);
        } catch (Throwable t) {
            System.err.println("[Mobilix] не удалось снять фильтр рефлексии: " + t);
        }
        try {
            LaunchClassLoader l = Launch.classLoader;
            l.addClassLoaderExclusion("dev.mobilix.");
            l.addClassLoaderExclusion("org.apache.commons.compress.");
            l.registerTransformer("dev.mobilix.loader.Pack200Transformer");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {}

    public void injectIntoClassLoader(LaunchClassLoader classLoader) {}

    public String getLaunchTarget() { return "net.minecraft.client.main.Main"; }

    public String[] getLaunchArguments() { return new String[0]; }
}
