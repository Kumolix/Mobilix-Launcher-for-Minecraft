package dev.mobilix;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** Замена sun.reflect.ReflectionFactory для старого Forge: в Java 12+ нужных методов уже нет. */
public final class ReflectionFactory {
    private static final ReflectionFactory INSTANCE = new ReflectionFactory();

    public static ReflectionFactory getReflectionFactory() { return INSTANCE; }

    public FieldAccessor newFieldAccessor(Field f, boolean override) {
        f.setAccessible(true);
        return new FieldAccessor(f);
    }

    public ConstructorAccessor newConstructorAccessor(Constructor<?> c) {
        return new ConstructorAccessor(c);
    }
}
