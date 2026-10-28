package net.minecraft.launchwrapper;
public class LaunchClassLoader extends java.net.URLClassLoader {
    public LaunchClassLoader(java.net.URL[] u) { super(u); }
    public void registerTransformer(String name) {}
    public void addClassLoaderExclusion(String prefix) {}
}
