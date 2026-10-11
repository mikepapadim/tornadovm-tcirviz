/*
 * tcirviz - an interactive visualiser for the TornadoVM TC compiler.
 * Copyright (c) 2026, Michalis Papadimitriou.
 * SPDX-License-Identifier: GPL-2.0-only
 */
package io.github.mikepapadim.tcirviz.compile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.mikepapadim.tcirviz.backend.Backend;
import io.github.mikepapadim.tcirviz.model.Recorder;
import uk.ac.manchester.tornado.tc.frontend.Frontend;
import uk.ac.manchester.tornado.tc.runtime.TcCompiler;
import uk.ac.manchester.tornado.tc.runtime.TcOptions;
import uk.ac.manchester.tornado.tc.runtime.TcTrace;

/**
 * Java source to everything the UI shows: javac, then TC with a {@link Recorder} observing every
 * stage and pass. Headless: no GPU and no TornadoVM runtime are needed.
 */
public final class Pipeline {

    /** What to compile and for which target. */
    public record Request(String source, String method, String descriptor, String backend, Map<String, String> targetOptions, boolean mathOptimizations, Map<String, String> arguments,
            String className) {
    }

    private Pipeline() {
    }

    /** Compiles the source and lists the methods that can be kernels (the UI's kernel picker). */
    public static Map<String, Object> kernels(String source, AppPath app) {
        var compiled = JavaSources.compile(source, app);
        var out = new LinkedHashMap<String, Object>();
        out.put("className", compiled.className());
        out.put("problems", problems(compiled));
        out.put("kernels", compiled.kernels().stream().map(k -> Map.of("name", k.name(), "descriptor", k.descriptor(), "signature", k.signature(), "kernelContext", k.kernelContext())).toList());
        return out;
    }

    public static Map<String, Object> compile(Request request) {
        return compile(request, AppPath.NONE);
    }

    /** Compiles {@code request.source()}, or, when it is blank, the class {@code request.className()} from the application classpath. */
    public static Map<String, Object> compile(Request request, AppPath app) {
        long start = System.nanoTime();
        var out = new LinkedHashMap<String, Object>();
        boolean fromClass = (request.source() == null || request.source().isBlank()) && request.className() != null;
        var compiled = fromClass ? JavaSources.fromClass(request.className(), app) : JavaSources.compile(request.source(), app);
        out.put("fromClass", fromClass);
        out.put("className", compiled.className());
        out.put("fileName", compiled.fileName());
        out.put("problems", problems(compiled));
        out.put("kernels", compiled.kernels().stream().map(k -> Map.of("name", k.name(), "descriptor", k.descriptor(), "signature", k.signature(), "kernelContext", k.kernelContext())).toList());
        if (!compiled.ok()) {
            out.put("ok", false);
            return out;
        }
        var kernel = compiled.kernels().stream().filter(k -> k.name().equals(request.method()) && (request.descriptor() == null || k.descriptor().equals(request.descriptor()))).findFirst()
                .or(() -> compiled.kernels().stream().findFirst());
        if (kernel.isEmpty()) {
            out.put("ok", false);
            out.put("diagnostics", List.of(Map.of("code", "tcirviz", "message", "no kernel method: a static void method whose parameters are primitives or TornadoVM types")));
            return out;
        }
        var backend = Optional.ofNullable(Backend.all().get(request.backend())).orElse(Backend.all().get("cuda"));
        var caps = backend.caps(request.targetOptions());
        var options = new TcOptions(Set.of(), Path.of("tc-dumps"), true, Optional.empty(), true, request.mathOptimizations(), "tcirviz");
        var recorder = new Recorder();
        var source = Frontend.BytecodeSource.classLoader(compiled.loader());
        long tc = System.nanoTime();
        TcCompiler.Compilation c;
        try {
            var facts = Arguments.facts(kernel.get().method(), request.arguments());
            c = TcTrace.with(recorder, () -> TcCompiler.compileJava(source, compiled.className(), kernel.get().name(), kernel.get().descriptor(), caps, options, facts));
        } catch (RuntimeException e) {
            out.put("ok", false);
            out.put("diagnostics", List.of(Map.of("code", "TC-E9999", "message", "internal error: " + e)));
            return out;
        }
        long tcNanos = System.nanoTime() - tc;
        for (var stage : c.stages()) {
            if (stage.kind() == TcCompiler.Kind.BYTECODE && !stage.text().isEmpty()) {
                recorder.bytecode(c.function(), compiled.fileName(), stage.text());
            }
        }
        out.put("ok", c.succeeded());
        out.put("method", kernel.get().name());
        out.put("params", Arguments.describe(kernel.get().method(), request.arguments()));
        out.put("descriptor", kernel.get().descriptor());
        out.put("function", c.function());
        out.put("backend", Map.of("id", backend.id(), "label", backend.label(), "language", backend.language()));
        out.put("target", caps.toString());
        out.put("entry", c.entryName());
        var diagnostics = new ArrayList<Object>();
        for (var d : c.diagnostics()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("code", d.code());
            m.put("message", d.message());
            m.put("where", d.where());
            if (d.loc().isKnown()) {
                m.put("f", d.loc().file());
                m.put("l", d.loc().line());
                m.put("b", d.loc().bci());
            }
            diagnostics.add(m);
        }
        out.put("diagnostics", diagnostics);
        out.put("stages", c.stages().stream().map(s -> Map.of("name", s.name(), "nanos", s.nanos(), "size", s.size())).toList());
        out.put("tcNanos", tcNanos);
        out.put("totalNanos", System.nanoTime() - start);
        out.putAll(recorder.json());
        return out;
    }

    private static List<Object> problems(JavaSources.Compiled compiled) {
        return compiled.problems().stream().map(p -> (Object) Map.of("kind", p.kind(), "line", p.line(), "column", p.column(), "message", p.message())).toList();
    }
}
