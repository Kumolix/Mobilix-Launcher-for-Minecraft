import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Генерирует ContextCapabilities (поля LWJGL 2) по GLCapabilities из LWJGL 3. Запуск: java -cp ... GenCaps <out.java> */
public class GenCaps {
    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName("org.lwjgl.opengl.GLCapabilities");
        List<String> names = new ArrayList<>();
        for (Field f : c.getFields()) {
            if (f.getType() == boolean.class && !Modifier.isStatic(f.getModifiers())) names.add(f.getName());
        }
        if (a.length > 1) {   // поля LWJGL 2, которых нет в LWJGL 3: всегда false
            Set<String> have = new HashSet<>(names);
            for (String l : Files.readAllLines(Paths.get(a[1]))) {
                l = l.trim();
                if (!l.isEmpty() && have.add(l)) names.add(l);
            }
        }
        Collections.sort(names);
        StringBuilder sb = new StringBuilder();
        sb.append("package org.lwjgl.opengl;\n\n/** Сгенерировано GenCaps: возможности контекста в стиле LWJGL 2. */\npublic final class ContextCapabilities {\n");
        for (String n : names) sb.append("\tpublic final boolean ").append(n).append(";\n");
        sb.append("\n\tContextCapabilities(GLCapabilities c) {\n");
        Set<String> real = new HashSet<>();
        for (Field f : c.getFields()) real.add(f.getName());
        for (String n : names) {
            if (real.contains(n)) sb.append("\t\tthis.").append(n).append(" = c.").append(n).append(";\n");
            else sb.append("\t\tthis.").append(n).append(" = false;\n");
        }
        sb.append("\t}\n}\n");
        Files.write(Paths.get(a[0]), sb.toString().getBytes("UTF-8"));
        System.out.println("GenCaps: " + names.size() + " fields");
    }
}
