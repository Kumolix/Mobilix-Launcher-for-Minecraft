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
