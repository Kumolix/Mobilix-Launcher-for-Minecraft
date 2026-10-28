package dev.mobilix;

import java.io.IOException;
import java.io.InputStream;
import java.util.jar.JarOutputStream;

/**
 * Замена java.util.jar.Pack200 (удалён в Java 14): старый Forge распаковывает им патчи классов.
 * Имя этого класса той же длины, что и java/util/jar/Pack200, чтобы Pack200Transformer подменял ссылки в байткоде.
 * Распаковка идёт через Apache Commons Compress (harmony), он подключается к classpath отдельно.
 */
public final class Pack200ab {
    public interface Unpacker {
        void unpack(InputStream in, JarOutputStream out) throws IOException;
    }

    public static Unpacker newUnpacker() {
        return new Unpacker() {
            public void unpack(InputStream in, JarOutputStream out) throws IOException {
                // поток LZMA отдаёт данные кусками, а распаковщик ждёт всё сразу: читаем всё в память
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] tmp = new byte[65536];
                int r;
                while ((r = in.read(tmp)) > 0) buf.write(tmp, 0, r);
                in = new java.io.ByteArrayInputStream(buf.toByteArray());
                try {
                    Class<?> c = Class.forName("org.apache.commons.compress.harmony.unpack200.Archive");
                    Object a = c.getConstructor(InputStream.class, JarOutputStream.class).newInstance(in, out);
                    c.getMethod("unpack").invoke(a);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable t = e.getCause();
                    if (t instanceof IOException) throw (IOException) t;
                    throw new IOException(String.valueOf(t), t);
                } catch (Exception e) {
                    throw new IOException(String.valueOf(e), e);
                }
                try { out.finish(); } catch (Exception ignored) {}
            }
        };
    }
}
