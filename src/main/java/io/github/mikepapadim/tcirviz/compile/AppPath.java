/*
 * tcirviz - an interactive visualiser for the TornadoVM TC compiler.
 * Copyright (c) 2026, Michalis Papadimitriou.
 * SPDX-License-Identifier: GPL-2.0-only
 */
package io.github.mikepapadim.tcirviz.compile;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * An application's classes (and optionally its sources), so kernels of a real program can be opened:
 * {@code tornado --tcirviz --params "--classpath app.jar --sourcepath src/main/java"}. Sources are also
 * looked up in {@code *-sources.jar} files next to the classpath jars.
 */
public final class AppPath {

    public static final AppPath NONE = new AppPath(List.of(), List.of());

    private final List<Path> classpath;
    private final List<Path> sourcepath;
    private final ClassLoader loader;
    private List<String> classes;

    public AppPath(List<Path> classpath, List<Path> sourcepath) {
        this.classpath = List.copyOf(classpath);
        var sources = new ArrayList<>(sourcepath);
        for (var entry : classpath) {
            var name = entry.getFileName() == null ? "" : entry.getFileName().toString();
            if (name.endsWith(".jar")) {
                var sibling = entry.resolveSibling(name.substring(0, name.length() - 4) + "-sources.jar");
                if (Files.isRegularFile(sibling)) {
                    sources.add(sibling);
                }
            }
        }
        this.sourcepath = List.copyOf(sources);
        var urls = classpath.stream().map(AppPath::url).toArray(URL[]::new);
        this.loader = urls.length == 0 ? AppPath.class.getClassLoader() : new URLClassLoader(urls, AppPath.class.getClassLoader());
    }

    public static AppPath of(String classpath, String sourcepath) {
        return new AppPath(split(classpath), split(sourcepath));
    }

    private static List<Path> split(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        return Stream.of(path.split(File.pathSeparator)).filter(s -> !s.isBlank()).map(Path::of).toList();
    }

    private static URL url(Path p) {
        try {
            return p.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public boolean isEmpty() {
        return classpath.isEmpty();
    }

    public ClassLoader loader() {
        return loader;
    }

    /** For javac: the application classpath after the tool's own. */
    public String javacClasspath() {
        var sb = new StringBuilder(System.getProperty("java.class.path"));
        classpath.forEach(p -> sb.append(File.pathSeparator).append(p));
        return sb.toString();
    }

    /** Class names on the application classpath containing {@code query} (case-insensitive), at most {@code limit}. */
    public synchronized List<String> classes(String query, int limit) {
        if (classes == null) {
            var all = new TreeSet<String>();
            for (var entry : classpath) {
                try {
                    if (Files.isDirectory(entry)) {
                        try (var files = Files.walk(entry)) {
                            files.filter(f -> f.toString().endsWith(".class")).forEach(f -> all.add(className(entry.relativize(f).toString())));
                        }
                    } else if (entry.toString().endsWith(".jar") && Files.isRegularFile(entry)) {
                        try (var jar = new JarFile(entry.toFile())) {
                            jar.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class") && !n.startsWith("META-INF/")).forEach(n -> all.add(className(n)));
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            all.removeIf(n -> n.endsWith("module-info") || n.endsWith("package-info") || n.contains("$"));
            classes = List.copyOf(all);
        }
        var q = query.toLowerCase();
        return classes.stream().filter(c -> c.toLowerCase().contains(q)).limit(limit).toList();
    }

    private static String className(String path) {
        return path.substring(0, path.length() - ".class".length()).replace('/', '.').replace('\\', '.');
    }

    /** The source of a top-level class, from the source path or a sources jar. */
    public Optional<String> source(String className) {
        var relative = className.replace('.', '/') + ".java";
        for (var entry : sourcepath) {
            try {
                if (Files.isDirectory(entry)) {
                    var file = entry.resolve(relative);
                    if (Files.isRegularFile(file)) {
                        return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
                    }
                } else if (Files.isRegularFile(entry)) {
                    try (var jar = new JarFile(entry.toFile())) {
                        var e = jar.getEntry(relative);
                        if (e != null) {
                            try (var in = jar.getInputStream(e)) {
                                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return Optional.empty();
    }
}
