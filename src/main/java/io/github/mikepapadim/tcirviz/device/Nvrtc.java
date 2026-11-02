/*
 * tcirviz - an interactive visualiser for the TornadoVM TC compiler.
 * Copyright (c) 2026, Michalis Papadimitriou.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.mikepapadim.tcirviz.device;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The CUDA source TC generates, compiled as the TornadoVM CUDA backend compiles it: NVRTC through
 * the SDK's own binding ({@code NVRTCAPI}, which finds libnvrtc the way the runtime does), with the
 * runtime's default options. PTX comes from a {@code compute_XX} compile; the cubin from an
 * {@code sm_XX} one, which the CUDA toolkit's {@code nvdisasm} turns into SASS and
 * {@code cuobjdump} into register, spill and shared-memory counts. No GPU is needed.
 *
 * <p>
 * Reflection only: tcirviz builds against any SDK, with or without the CUDA backend, and the FFM API
 * the binding uses is a preview on JDK 21.
 */
public final class Nvrtc {

    private static final String API = "uk.ac.manchester.tornado.drivers.cuda.ffm.NVRTCAPI";
    private static final String FFM = "uk.ac.manchester.tornado.runtime.ffm.FFMSupport";

    /** What the device compiler made of one CUDA source. Empty strings for what could not be produced; {@code note} says why. */
    public record Result(String ptx, String sass, String resources, String options, String note) {
    }

    private Nvrtc() {
    }

    public static Result compile(String source, int computeCapability) {
        List<String> options = new ArrayList<>();
        try {
            Class<?> api = Class.forName(API);
            if (!(boolean) api.getMethod("isAvailable").invoke(null)) {
                return new Result("", "", "", "", "NVRTC is not available: no CUDA toolkit was found on this host.");
            }
            options.addAll(defaultOptions());
            if (source.contains("<<<")) {
                // As the runtime does for a kernel that launches kernels (CUDA Dynamic Parallelism).
                options.add(0, "-rdc=true");
            }
            // Maps each PTX and SASS instruction to the CUDA line it came from; the code is unchanged.
            options.add("-lineinfo");
            for (String root : toolkitRoots(api)) {
                var include = Path.of(root, "include");
                if (Files.isDirectory(include)) {
                    options.add("--include-path=" + include);
                }
            }
            var ptx = run(api, source, "--gpu-architecture=compute_" + computeCapability, options, false);
            if (!ptx.ok()) {
                return new Result("", "", "", String.join(" ", options), "NVRTC refused the CUDA source:\n" + ptx.log());
            }
            var cubin = run(api, source, "--gpu-architecture=sm_" + computeCapability, options, true);
            String sass = "";
            String resources = "";
            String note = "";
            if (cubin.ok()) {
                var tools = Tools.find(toolkitRoots(api));
                if (tools.isPresent()) {
                    var file = Files.createTempFile("tcirviz", ".cubin");
                    try {
                        Files.write(file, cubin.image());
                        sass = tools.get().run("nvdisasm", "-g", "-c", file.toString());
                        resources = tools.get().run("cuobjdump", "-res-usage", file.toString());
                    } finally {
                        Files.deleteIfExists(file);
                    }
                } else {
                    note = "SASS needs nvdisasm and cuobjdump from the CUDA toolkit, which was not found.";
                }
            } else {
                note = "NVRTC could not build a cubin for sm_" + computeCapability + ":\n" + cubin.log();
            }
            return new Result(new String(ptx.image(), StandardCharsets.UTF_8).replace("\0", ""), sass, resources, String.join(" ", options), note);
        } catch (ClassNotFoundException e) {
            return new Result("", "", "", "", "This TornadoVM SDK has no CUDA backend: build it with BACKEND=cuda to see PTX and SASS.");
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError e) {
            var cause = e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
            return new Result("", "", "", String.join(" ", options), "NVRTC could not be used: " + cause);
        }
    }

    /** The NVRTC options the runtime compiles CUDA kernels with: its defaults, wherever this SDK keeps them. */
    static List<String> defaultOptions() {
        for (var candidate : List.of("uk.ac.manchester.tornado.runtime.common.CompilerFlags#cudaDefaults", "uk.ac.manchester.tornado.runtime.common.TornadoOptions#DEFAULT_CUDA_COMPILER_FLAGS")) {
            var parts = candidate.split("#");
            try {
                var type = Class.forName(parts[0]);
                Object value = Arrays.stream(type.getMethods()).anyMatch(m -> m.getName().equals(parts[1]) && m.getParameterCount() == 0) ? type.getMethod(parts[1]).invoke(null)
                        : type.getField(parts[1]).get(null);
                var text = String.valueOf(value).trim();
                return text.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(text.split("\\s+")));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                // Not in this SDK: try the next place.
            }
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static List<String> toolkitRoots(Class<?> api) throws ReflectiveOperationException {
        return (List<String>) api.getMethod("toolkitRoots").invoke(null);
    }

    private record Compiled(boolean ok, byte[] image, String log) {
    }

    /** One NVRTC compile through the binding: {@code cubin} selects the image to return. */
    private static Compiled run(Class<?> api, String source, String arch, List<String> options, boolean cubin) throws ReflectiveOperationException {
        var ffm = Class.forName(FFM);
        var arenaType = Class.forName("java.lang.foreign.Arena");
        var segmentType = Class.forName("java.lang.foreign.MemorySegment");
        var allocatorType = Class.forName("java.lang.foreign.SegmentAllocator");
        var layoutType = Class.forName("java.lang.foreign.MemoryLayout");
        Object pointer = ffm.getField("C_POINTER").get(null);
        Object cLong = ffm.getField("C_LONG").get(null);
        Object cChar = ffm.getField("C_CHAR").get(null);
        Object nul = segmentType.getField("NULL").get(null);
        Method cString = ffm.getMethod("allocateCString", allocatorType, String.class);
        Method array = ffm.getMethod("allocateArray", allocatorType, layoutType, long.class);
        Object arena = arenaType.getMethod("ofConfined").invoke(null);
        try {
            Object programOut = array.invoke(null, arena, pointer, 1L);
            int status = (int) method(api, "nvrtcCreateProgram").invoke(null, programOut, cString.invoke(null, arena, source), cString.invoke(null, arena, "tornado_kernel.cu"), 0, nul, nul);
            if (status != 0) {
                return new Compiled(false, new byte[0], "nvrtcCreateProgram failed: " + status);
            }
            Object programSegment = get(segmentType, programOut, pointer);
            long program = (long) segmentType.getMethod("address").invoke(programSegment);
            try {
                var all = new ArrayList<String>();
                all.add(arch);
                all.addAll(options);
                Object optionArray = array.invoke(null, arena, pointer, (long) all.size());
                for (int i = 0; i < all.size(); i++) {
                    set(segmentType, optionArray, pointer, i, cString.invoke(null, arena, all.get(i)));
                }
                status = (int) method(api, "nvrtcCompileProgram").invoke(null, program, all.size(), optionArray);
                String log = bytes(api, segmentType, array, arena, cLong, cChar, program, "nvrtcGetProgramLogSize", "nvrtcGetProgramLog").map(b -> new String(b, StandardCharsets.UTF_8).replace("\0", "").strip())
                        .orElse("");
                if (status != 0) {
                    return new Compiled(false, new byte[0], log);
                }
                var image = cubin ? bytes(api, segmentType, array, arena, cLong, cChar, program, "nvrtcGetCUBINSize", "nvrtcGetCUBIN")
                        : bytes(api, segmentType, array, arena, cLong, cChar, program, "nvrtcGetPTXSize", "nvrtcGetPTX");
                return image.map(b -> new Compiled(b.length > 0, b, log)).orElse(new Compiled(false, new byte[0], "no " + (cubin ? "cubin" : "PTX") + " produced"));
            } finally {
                method(api, "nvrtcDestroyProgram").invoke(null, programOut);
            }
        } finally {
            arenaType.getMethod("close").invoke(arena);
        }
    }

    private static Optional<byte[]> bytes(Class<?> api, Class<?> segmentType, Method array, Object arena, Object cLong, Object cChar, long program, String sizeCall, String dataCall)
            throws ReflectiveOperationException {
        Object size = array.invoke(null, arena, cLong, 1L);
        if ((int) method(api, sizeCall).invoke(null, program, size) != 0) {
            return Optional.empty();
        }
        long n = (long) segmentType.getMethod("get", Class.forName("java.lang.foreign.ValueLayout$OfLong"), long.class).invoke(size, cLong, 0L);
        if (n <= 0) {
            return Optional.of(new byte[0]);
        }
        Object data = array.invoke(null, arena, cChar, n);
        if ((int) method(api, dataCall).invoke(null, program, data) != 0) {
            return Optional.empty();
        }
        return Optional.of((byte[]) segmentType.getMethod("toArray", Class.forName("java.lang.foreign.ValueLayout$OfByte")).invoke(data, cChar));
    }

    private static Object get(Class<?> segmentType, Object segment, Object pointer) throws ReflectiveOperationException {
        return segmentType.getMethod("get", Class.forName("java.lang.foreign.AddressLayout"), long.class).invoke(segment, pointer, 0L);
    }

    private static void set(Class<?> segmentType, Object segment, Object pointer, int index, Object value) throws ReflectiveOperationException {
        long bytes = (long) Class.forName("java.lang.foreign.MemoryLayout").getMethod("byteSize").invoke(pointer);
        segmentType.getMethod("set", Class.forName("java.lang.foreign.AddressLayout"), long.class, segmentType).invoke(segment, pointer, index * bytes, value);
    }

    private static Method method(Class<?> api, String name) {
        return Arrays.stream(api.getMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow(() -> new IllegalStateException("NVRTCAPI has no " + name));
    }

    /** The CUDA toolkit's binaries, found under the roots the binding knows, then on PATH. */
    record Tools(Path bin) {

        static Optional<Tools> find(List<String> roots) {
            for (var root : roots) {
                var bin = Path.of(root, "bin");
                if (Files.isExecutable(bin.resolve("nvdisasm")) && Files.isExecutable(bin.resolve("cuobjdump"))) {
                    return Optional.of(new Tools(bin));
                }
            }
            for (var dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
                var bin = Path.of(dir);
                if (Files.isExecutable(bin.resolve("nvdisasm")) && Files.isExecutable(bin.resolve("cuobjdump"))) {
                    return Optional.of(new Tools(bin));
                }
            }
            return Optional.empty();
        }

        String run(String tool, String... args) throws IOException {
            var command = new ArrayList<String>();
            command.add(bin.resolve(tool).toString());
            command.addAll(List.of(args));
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return tool + " timed out";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return tool + " was interrupted";
            }
            return out;
        }
    }
}
