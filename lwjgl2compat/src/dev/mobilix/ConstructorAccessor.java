package dev.mobilix;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/** Замена sun.reflect.ConstructorAccessor: создаёт объекты, в том числе элементы enum. */
public final class ConstructorAccessor {
    private final Constructor<?> c;

    ConstructorAccessor(Constructor<?> c) { this.c = c; }

    public Object newInstance(Object[] args) throws Exception {
        try {
            c.setAccessible(true);
            MethodHandle h = U.implLookup().unreflectConstructor(c);
            return h.invokeWithArguments(args == null ? new Object[0] : args);
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }
}
