package dev.mobilix.loader;

import net.minecraft.launchwrapper.IClassTransformer;

/** Заменяет ссылки на java/util/jar/Pack200 на dev/mobilix/Pack200ab (строки той же длины, структура класса не меняется). */
public class Pack200Transformer implements IClassTransformer {
    // собираем из частей, чтобы в самом этом классе подстроки не было
    private static final byte[] FROM = ("java/util/jar/" + "Pack" + "200").getBytes();
    private static final byte[] TO = ("dev/mobilix/" + "Pack200" + "ab").getBytes();

    public byte[] transform(String name, String transformedName, byte[] b) {
        if (b == null || b.length < FROM.length) return b;
        byte[] out = null;
        int n = b.length - FROM.length;
        for (int i = 0; i <= n; i++) {
            if (b[i] != FROM[0]) continue;
            int j = 1;
            while (j < FROM.length && b[i + j] == FROM[j]) j++;
            if (j == FROM.length) {
                if (out == null) out = b.clone();
                System.arraycopy(TO, 0, out, i, TO.length);
                i += FROM.length - 1;
            }
        }
        return out != null ? out : b;
    }
}
