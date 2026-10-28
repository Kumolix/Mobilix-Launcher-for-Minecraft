package net.minecraft.launchwrapper;
public interface ITweaker {
    void acceptOptions(java.util.List<String> args, java.io.File gameDir, java.io.File assetsDir, String profile);
    void injectIntoClassLoader(LaunchClassLoader classLoader);
    String getLaunchTarget();
    String[] getLaunchArguments();
}
