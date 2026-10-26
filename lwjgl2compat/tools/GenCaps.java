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
        Collections.sort(names);
        StringBuilder sb = new StringBuilder();
        sb.append("package org.lwjgl.opengl;\n\n/** Сгенерировано GenCaps: возможности контекста в стиле LWJGL 2. */\npublic final class ContextCapabilities {\n");
        for (String n : names) sb.append("\tpublic final boolean ").append(n).append(";\n");
        sb.append("\n\tContextCapabilities(GLCapabilities c) {\n");
        for (String n : names) sb.append("\t\tthis.").append(n).append(" = c.").append(n).append(";\n");
        sb.append("\t}\n}\n");
        Files.write(Paths.get(a[0]), sb.toString().getBytes("UTF-8"));
        System.out.println("GenCaps: " + names.size() + " fields");
    }
}
