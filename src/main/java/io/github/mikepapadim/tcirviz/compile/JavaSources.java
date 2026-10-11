/*
 * tcirviz - an interactive visualiser for the TornadoVM TC compiler.
 * Copyright (c) 2026, Michalis Papadimitriou.
 * SPDX-License-Identifier: GPL-2.0-only
 */
package io.github.mikepapadim.tcirviz.compile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Compiles the Java the user typed with the JDK's own compiler (in process, with debug information,
 * so TC sees local names and line numbers), loads it, and lists the methods that can be kernels.
 */
public final class JavaSources {

    /** A compile problem, positioned in the user's source. */
    public record Problem(String kind, long line, long column, String message) {
    }

    /** A static method that could be a TornadoVM task (its parameters are primitives or TornadoVM API types). */
    public record KernelMethod(String name, String descriptor, String signature, boolean kernelContext, Method method) {
    }

    /** The result of compiling one source file. */
    public record Compiled(String fileName, String className, Path classes, ClassLoader loader, List<Problem> problems, List<KernelMethod> kernels) {
        public boolean ok() {
            return problems.stream().noneMatch(p -> p.kind().equals("ERROR"));
        }
    }

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern PUBLIC_CLASS = Pattern.compile("public\\s+(?:final\\s+|abstract\\s+)*class\\s+(\\w+)");
    private static final Pattern ANY_CLASS = Pattern.compile("\\bclass\\s+(\\w+)");
    private static final String API = "uk.ac.manchester.tornado.api";

    private JavaSources() {
    }

    public static Compiled compile(String source) {
        return compile(source, AppPath.NONE);
    }

    /** A class already on the application classpath (no source compiled): its kernels, loaded from there. */
    public static Compiled fromClass(String className, AppPath app) {
        var problems = new ArrayList<Problem>();
        var simple = className.substring(className.lastIndexOf('.') + 1);
        try {
            var type = Class.forName(className, false, app.loader());
            return new Compiled(simple + ".java", className, null, app.loader(), problems, kernelsOf(type));
        } catch (ClassNotFoundException | LinkageError e) {
            problems.add(new Problem("ERROR", 0, 0, "cannot load " + className + ": " + e));
            return new Compiled(simple + ".java", className, null, null, problems, List.of());
        }
    }

    /** The last few compilations, by source text and classpath: switching kernels or targets does not run javac again. */
    private static final java.util.Map<String, Compiled> CACHE = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Compiled> eldest) {
            return size() > 8;
        }
    });

    public static Compiled compile(String source, AppPath app) {
        var key = app.javacClasspath() + '\0' + source;
        var cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        var compiled = compileUncached(source, app);
        if (compiled.ok()) {
            CACHE.put(key, compiled);
        }
        return compiled;
    }

    private static Compiled compileUncached(String source, AppPath app) {
        var pkg = PACKAGE.matcher(source);
        var cls = PUBLIC_CLASS.matcher(source);
        String simple = cls.find() ? cls.group(1) : null;
        if (simple == null) {
            var any = ANY_CLASS.matcher(source);
            simple = any.find() ? any.group(1) : "Kernels";
        }
        var className = (pkg.find() ? pkg.group(1) + "." : "") + simple;
        var fileName = simple + ".java";
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new Compiled(fileName, className, null, null, List.of(new Problem("ERROR", 0, 0, "no Java compiler: run tcirviz on a JDK, not a JRE")), List.of());
        }
        Path out;
        try {
            out = Files.createTempDirectory("tcirviz-");
            out.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(java.net.URI.create("string:///" + className.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var options = List.of("-g", "-parameters", "-proc:none", "--release", Integer.toString(Runtime.version().feature()), "--enable-preview", "-Xlint:-preview", "-classpath", app.javacClasspath(), "-d", out.toString());
        boolean ok = compiler.getTask(null, null, diagnostics, options, null, List.of(file)).call();
        var problems = new ArrayList<Problem>();
        for (var d : diagnostics.getDiagnostics()) {
            problems.add(new Problem(d.getKind() == Diagnostic.Kind.ERROR ? "ERROR" : "WARNING", d.getLineNumber(), d.getColumnNumber(), d.getMessage(Locale.ROOT)));
        }
        if (!ok) {
            return new Compiled(fileName, className, out, null, problems, List.of());
        }
        ClassLoader loader;
        try {
            loader = new URLClassLoader(new java.net.URL[] { out.toUri().toURL() }, app.loader());
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
        List<KernelMethod> kernels = List.of();
        try {
            kernels = kernelsOf(Class.forName(className, false, loader));
        } catch (ClassNotFoundException | LinkageError e) {
            problems.add(new Problem("ERROR", 0, 0, "cannot load " + className + ": " + e));
        }
        return new Compiled(fileName, className, out, loader, problems, kernels);
    }

    private static List<KernelMethod> kernelsOf(Class<?> type) {
        var kernels = new ArrayList<KernelMethod>();
        var methods = new ArrayList<>(List.of(type.getDeclaredMethods()));
        methods.sort(Comparator.comparing(Method::getName));
        for (var m : methods) {
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class && !m.isSynthetic() && !m.getName().equals("main") && kernelParameters(m)) {
                boolean kc = List.of(m.getParameterTypes()).stream().anyMatch(p -> p.getName().equals(API + ".KernelContext"));
                kernels.add(new KernelMethod(m.getName(), descriptor(m), signature(m), kc, m));
            }
        }
        return kernels;
    }

    private static boolean kernelParameters(Method m) {
        if (m.getParameterCount() == 0) {
            return false;
        }
        for (var p : m.getParameterTypes()) {
            if (!(p.isPrimitive() || p.getName().startsWith(API) || (p.isArray() && p.getComponentType().isPrimitive()))) {
                return false;
            }
        }
        return true;
    }

    static String descriptor(Method m) {
        var sb = new StringBuilder("(");
        for (var p : m.getParameterTypes()) {
            sb.append(p.descriptorString());
        }
        return sb.append(')').append(m.getReturnType().descriptorString()).toString();
    }

    private static String signature(Method m) {
        var sb = new StringBuilder(m.getName()).append('(');
        var params = m.getParameters();
        for (int i = 0; i < params.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(params[i].getType().getSimpleName()).append(' ').append(params[i].getName());
        }
        return sb.append(')').toString();
    }
}
