import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Добавляет в классы LWJGL 3 методы в стиле LWJGL 2.
 *
 * 1) Алиасы: glLightfv(int,int,FloatBuffer) -> glLight(int,int,FloatBuffer) и т.п.
 *    (LWJGL 2 различал варианты перегрузкой по типу буфера, LWJGL 3 суффиксом fv/iv/...).
 * 2) Мосты: в классе X добавляются все public static методы вспомогательного класса XBridge.
 *
 * Запуск: java Patcher <outDir> <bridgeClassesDir> <lwjgl3.jar>...
 */
public class Patcher {
    static final Pattern HOLDER = Pattern.compile(
        "org/lwjgl/opengl/(GL\\d+|ARB\\w+|EXT\\w+|NV\\w+|AMD\\w+|KHR\\w+|APPLE\\w+|IBM\\w+|SGI\\w+|SUN\\w+|INTEL\\w+|OES\\w+|GREMEDY\\w+|HP\\w+|INGR\\w+|MESA\\w+|OVR\\w+|PGI\\w+|REND\\w+|S3\\w+|3DFX\\w+)"
        + "|org/lwjgl/openal/(AL\\d+|ALC\\d+|EXT\\w+|EFX\\d+|SOFT\\w+)");
    static final Pattern SUFFIX = Pattern.compile("^(.*?)(?:[fdis]|ub|us|ui|i64|ui64)?v$");

    static final class M { final String name, desc; M(String n, String d) { name = n; desc = d; } 
        @Override public boolean equals(Object o) { return o instanceof M && ((M) o).name.equals(name) && ((M) o).desc.equals(desc); }
        @Override public int hashCode() { return name.hashCode() * 31 + desc.hashCode(); } }

    public static void main(String[] a) throws Exception {
        Path out = Paths.get(a[0]);
        Path bridgeDir = Paths.get(a[1]);
        // 1) мосты: имя целевого класса -> список методов
        Map<String, List<M>> bridges = new HashMap<>();
        if (Files.isDirectory(bridgeDir)) {
            try (Stream<Path> s = Files.walk(bridgeDir)) {
                for (Path p : s.filter(x -> x.toString().endsWith("Bridge.class")).collect(Collectors.toList())) {
                    String rel = bridgeDir.relativize(p).toString().replace(File.separatorChar, '/');
                    String bridgeOwner = rel.substring(0, rel.length() - 6);
                    String target = bridgeOwner.substring(0, bridgeOwner.length() - "Bridge".length());
                    final List<M> list = new ArrayList<>();
                    new ClassReader(Files.readAllBytes(p)).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public MethodVisitor visitMethod(int acc, String n, String d, String sig, String[] ex) {
                            if ((acc & Opcodes.ACC_PUBLIC) != 0 && (acc & Opcodes.ACC_STATIC) != 0 && !n.equals("<clinit>")) list.add(new M(n, d));
                            return null;
                        }
                    }, ClassReader.SKIP_CODE);
                    if (!list.isEmpty()) bridges.put(target, list);
                }
            }
        }
        int patched = 0, aliases = 0, bridged = 0;
        for (int i = 2; i < a.length; i++) {
            try (JarFile jf = new JarFile(a[i])) {
                for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                    JarEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.endsWith(".class")) continue;
                    String cn = n.substring(0, n.length() - 6);
                    boolean holder = HOLDER.matcher(cn).matches();
                    boolean hasBridge = bridges.containsKey(cn);
                    if (!holder && !hasBridge) continue;
                    byte[] in;
                    try (InputStream is = jf.getInputStream(e)) { in = is.readAllBytes(); }
                    ClassReader cr = new ClassReader(in);
                    final Set<M> have = new HashSet<>();
                    final List<M> cand = new ArrayList<>();
                    final Set<M> weak = new HashSet<>();
                    final Set<M> promote = new HashSet<>();
                    final boolean[] iface = new boolean[1];
                    cr.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public void visit(int v, int acc, String name, String sig, String sup, String[] itf) {
                            iface[0] = (acc & Opcodes.ACC_INTERFACE) != 0;
                        }
                        @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                            if ((acc & Opcodes.ACC_PUBLIC) != 0) have.add(new M(name, desc)); else weak.add(new M(name, desc));
                            if ((acc & Opcodes.ACC_PUBLIC) != 0 && (acc & Opcodes.ACC_STATIC) != 0) cand.add(new M(name, desc));
                            return null;
                        }
                    }, ClassReader.SKIP_CODE);
                    if (iface[0]) continue;
                    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                    int added = 0;
                    if (holder) {
                        for (M m : cand) {
                            if (!(m.name.startsWith("gl") || m.name.startsWith("al"))) continue;
                            if (!hasBuffer(m.desc)) continue;
                            Matcher mt = SUFFIX.matcher(m.name);
                            if (!mt.matches()) continue;
                            String alias = mt.group(1);
                            if (alias.length() < 3) continue;
                            M am = new M(alias, m.desc);
                            if (have.contains(am)) continue;
                            emit(cw, cn, m, alias);
                            have.add(am); added++; aliases++;
                        }
                    }
                    List<M> br = bridges.get(cn);
                    if (br != null) {
                        for (M m : br) {
                            if (have.contains(m)) continue;
                            if (weak.contains(m)) { promote.add(m); added++; bridged++; continue; }
                            emitBridge(cw, cn + "Bridge", m);
                            have.add(m); added++; bridged++;
                        }
                    }
                    if (added == 0) continue;
                    cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                        @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                            if (promote.contains(new M(name, desc))) acc = (acc & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
                            return super.visitMethod(acc, name, desc, sig, ex);
                        }
                    }, 0);
                    Path dest = out.resolve(n);
                    Files.createDirectories(dest.getParent());
                    Files.write(dest, cw.toByteArray());
                    patched++;
                }
            }
        }
        System.out.println("Patcher: classes=" + patched + " aliases=" + aliases + " bridges=" + bridged);
    }

    static boolean hasBuffer(String desc) {
        return Pattern.compile("Ljava/nio/\\w*Buffer;").matcher(desc).find();
    }

    /** Новый метод alias с тем же дескриптором: вызывает оригинал. */
    static void emit(ClassWriter cw, String owner, M target, String alias) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, alias, target.desc, null, null);
        mv.visitCode();
        forward(mv, owner, target.name, target.desc);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    static void emitBridge(ClassWriter cw, String bridgeOwner, M m) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, m.name, m.desc, null, null);
        mv.visitCode();
        forward(mv, bridgeOwner, m.name, m.desc);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    static void forward(MethodVisitor mv, String owner, String name, String desc) {
        int slot = 0;
        for (Type t : Type.getArgumentTypes(desc)) {
            mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot);
            slot += t.getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, desc, false);
        mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
    }
}
