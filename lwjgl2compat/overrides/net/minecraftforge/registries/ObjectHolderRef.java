package net.minecraftforge.registries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Заглушка внешнего класса: в jar попадает только вложенный FinalFieldHelper, он подменяет класс из Forge 1.12.2.
 * Оригинал лезет в sun.reflect.ReflectionFactory.newFieldAccessor, которого нет в Java 12+.
 * Здесь static final поля записываются через sun.misc.Unsafe.
 */
class ObjectHolderRef {
    static class FinalFieldHelper {
        private static Object unsafe;
        private static Method staticFieldBase, staticFieldOffset, objectFieldOffset, putObject;

        private static synchronized void init() throws ReflectiveOperationException {
            if (unsafe != null) return;
            Class<?> c = Class.forName("sun.misc.Unsafe");
            Field f = c.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object u = f.get(null);
            staticFieldBase = c.getMethod("staticFieldBase", Field.class);
            staticFieldOffset = c.getMethod("staticFieldOffset", Field.class);
            objectFieldOffset = c.getMethod("objectFieldOffset", Field.class);
            putObject = c.getMethod("putObject", Object.class, long.class, Object.class);
            unsafe = u;
        }

        static Field makeWritable(Field f) throws ReflectiveOperationException {
            f.setAccessible(true);
            init();
            return f;
        }

        static void setField(Field field, Object instance, Object thing) throws ReflectiveOperationException {
            init();
            if (Modifier.isStatic(field.getModifiers())) {
                Object base = staticFieldBase.invoke(unsafe, field);
                long off = (Long) staticFieldOffset.invoke(unsafe, field);
                putObject.invoke(unsafe, base, off, thing);
            } else {
                long off = (Long) objectFieldOffset.invoke(unsafe, field);
                putObject.invoke(unsafe, instance, off, thing);
            }
        }
    }
}
