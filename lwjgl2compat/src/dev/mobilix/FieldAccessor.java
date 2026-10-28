package dev.mobilix;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Замена sun.reflect.FieldAccessor: запись в поля (в том числе static final) через Unsafe. */
public final class FieldAccessor {
    private final Field f;

    FieldAccessor(Field f) { this.f = f; }

    public void set(Object obj, Object value) throws Exception {
        boolean st = Modifier.isStatic(f.getModifiers());
        Object base = st ? U.staticBase(f) : obj;
        long off = st ? U.staticOff(f) : U.objOff(f);
        Class<?> t = f.getType();
        if (t == boolean.class) U.putBoolean(base, off, (Boolean) value);
        else if (t == int.class) U.putInt(base, off, (Integer) value);
        else if (t == long.class) U.putLong(base, off, (Long) value);
        else if (!t.isPrimitive()) U.putObject(base, off, value);
        else f.set(obj, value);
    }
}
