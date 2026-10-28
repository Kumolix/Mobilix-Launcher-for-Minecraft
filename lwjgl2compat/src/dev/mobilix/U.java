package dev.mobilix;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Доступ к sun.misc.Unsafe через рефлексию (без зависимости при компиляции). */
final class U {
    static final Object unsafe;
    private static final Method sfb, sfo, ofo, putObj, putInt, putLong, putBool, getObj;

    static {
        try {
            Class<?> c = Class.forName("sun.misc.Unsafe");
            Field f = c.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = f.get(null);
            sfb = c.getMethod("staticFieldBase", Field.class);
            sfo = c.getMethod("staticFieldOffset", Field.class);
            ofo = c.getMethod("objectFieldOffset", Field.class);
            putObj = c.getMethod("putObject", Object.class, long.class, Object.class);
            putInt = c.getMethod("putInt", Object.class, long.class, int.class);
            putLong = c.getMethod("putLong", Object.class, long.class, long.class);
            putBool = c.getMethod("putBoolean", Object.class, long.class, boolean.class);
            getObj = c.getMethod("getObject", Object.class, long.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static Object staticBase(Field f) throws Exception { return sfb.invoke(unsafe, f); }
    static long staticOff(Field f) throws Exception { return (Long) sfo.invoke(unsafe, f); }
    static long objOff(Field f) throws Exception { return (Long) ofo.invoke(unsafe, f); }
    static void putObject(Object o, long off, Object v) throws Exception { putObj.invoke(unsafe, o, off, v); }
    static void putInt(Object o, long off, int v) throws Exception { putInt.invoke(unsafe, o, off, v); }
    static void putLong(Object o, long off, long v) throws Exception { putLong.invoke(unsafe, o, off, v); }
    static void putBoolean(Object o, long off, boolean v) throws Exception { putBool.invoke(unsafe, o, off, v); }

    /** «Доверенный» Lookup: позволяет вызывать любые конструкторы (в том числе enum). */
    static MethodHandles.Lookup implLookup() throws Exception {
        Field lf = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        return (MethodHandles.Lookup) getObj.invoke(unsafe, staticBase(lf), staticOff(lf));
    }

    /** Снимает фильтр рефлексии JDK: возвращает доступ к полям вроде Field.modifiers (Forge и моды их используют). */
    static void unfilterReflection() throws Throwable {
        MethodHandles.Lookup l = implLookup();
        Class<?> rc = Class.forName("jdk.internal.reflect.Reflection");
        java.lang.invoke.MethodHandle h = l.findStaticSetter(rc, "fieldFilterMap", java.util.Map.class);
        h.invoke(new java.util.HashMap<Object, Object>());
        // кэш списка полей мог уже запомнить отфильтрованный вариант: сбрасываем его
        Field rd = Class.class.getDeclaredField("reflectionData");
        long off = objOff(rd);
        for (Class<?> c : new Class<?>[] { Field.class, java.lang.reflect.Method.class, java.lang.reflect.Constructor.class }) {
            putObject(c, off, null);
        }
    }
}
