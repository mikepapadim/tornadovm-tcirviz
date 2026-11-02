/*
 * tcirviz - an interactive visualiser for the TornadoVM TC compiler.
 * Copyright (c) 2026, Michalis Papadimitriou.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.mikepapadim.tcirviz.compile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.mikepapadim.tcirviz.backend.Backend;
import io.github.mikepapadim.tcirviz.device.Nvrtc;
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
        // False when the kernel never reads an array length: one compilation serves every array size
        // (TornadoExecutionPlan.withDynamicShapes()).
        out.put("readsLengths", c.readsLengths());
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
        if (c.succeeded() && backend.id().equals("cuda")) {
            out.put("device", device(recorder, c, caps.computeCapability()));
        }
        out.put("stages", c.stages().stream().map(s -> Map.of("name", s.name(), "nanos", s.nanos(), "size", s.size())).toList());
        out.put("tcNanos", tcNanos);
        out.put("totalNanos", System.nanoTime() - start);
        out.putAll(recorder.json());
        return out;
    }

    /** NVRTC's file name for the kernel source: device-code line markers name it. */
    private static final String KERNEL_FILE = "tornado_kernel.cu";
    /** PTX: {@code .file 1 "tornado_kernel.cu"} once, then {@code .loc 1 23 5} before the code of CUDA line 23. */
    private static final Pattern PTX_LOC = Pattern.compile("^\\s*\\.loc\\s+(\\d+)\\s+(\\d+)");
    /** SASS from nvdisasm -g: {@code //## File "tornado_kernel.cu", line 23}. */
    private static final Pattern SASS_LOC = Pattern.compile("//## File \"([^\"]+)\", line (\\d+)");
    /** cuobjdump -res-usage: {@code REG:24 STACK:0 SHARED:0 LOCAL:0 ...} after each function's name. */
    private static final Pattern RESOURCE = Pattern.compile("(REG|STACK|SHARED|LOCAL):(\\d+)");

    /**
     * PTX and SASS of the kernel, as two more steps after the CUDA source, and its resource usage.
     * They need NVRTC (and for SASS the CUDA toolkit); without them the step says why.
     */
    private static Map<String, Object> device(Recorder recorder, TcCompiler.Compilation c, int computeCapability) {
        var result = Nvrtc.compile(c.source(), computeCapability);
        var out = new LinkedHashMap<String, Object>();
        out.put("options", result.options());
        out.put("note", result.note());
        if (!result.ptx().isEmpty()) {
            // The PTX names the kernel source by number: .file 1 "tornado_kernel.cu".
            var fileNumber = Pattern.compile("\\.file\\s+(\\d+)\\s+\"" + Pattern.quote(KERNEL_FILE) + "\"").matcher(result.ptx());
            var number = fileNumber.find() ? fileNumber.group(1) : "1";
            recorder.device(c.function(), "ptx", "ptx", result.ptx(), PTX_LOC, number);
        } else {
            recorder.device(c.function(), "ptx", "ptx", "// " + result.note().replace("\n", "\n// "), PTX_LOC, "");
        }
        if (!result.sass().isEmpty()) {
            var header = result.resources().isBlank() ? "" : "// cuobjdump -res-usage:\n// " + result.resources().strip().replace("\n", "\n// ") + "\n";
            recorder.device(c.function(), "sass", "sass", header + result.sass(), SASS_LOC, KERNEL_FILE);
        }
        var usage = new LinkedHashMap<String, Integer>();
        var m = RESOURCE.matcher(result.resources());
        while (m.find()) {
            usage.merge(m.group(1).toLowerCase(java.util.Locale.ROOT), Integer.parseInt(m.group(2)), Math::max);
        }
        out.put("resources", usage);
        return out;
    }

    private static List<Object> problems(JavaSources.Compiled compiled) {
        return compiled.problems().stream().map(p -> (Object) Map.of("kind", p.kind(), "line", p.line(), "column", p.column(), "message", p.message())).toList();
    }
}
