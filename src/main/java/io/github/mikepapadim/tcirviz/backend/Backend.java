/*
 * tcirviz - an interactive visualiser for the TornadoVM TC compiler.
 * Copyright (c) 2026, Michalis Papadimitriou.
 * SPDX-License-Identifier: GPL-2.0-only
 */
package io.github.mikepapadim.tcirviz.backend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import uk.ac.manchester.tornado.tc.ir.target.TargetCaps;

/**
 * A code-generation target the visualiser can show. The built-in ones are CUDA, OpenCL and Metal;
 * more are added without changing the tool by implementing this interface and listing the class in
 * {@code META-INF/services/io.github.mikepapadim.tcirviz.backend.Backend} on the classpath.
 */
public interface Backend {

    /** Stable id used in URLs and requests, e.g. {@code cuda}. */
    String id();

    /** Name shown in the UI. */
    String label();

    /** Highlighting language of the emitted source: {@code cuda}, {@code opencl}, {@code metal} or {@code c}. */
    String language();

    /** Target options the UI offers, as name to choices (the first choice is the default). */
    default Map<String, List<String>> options() {
        return Map.of();
    }

    /** The target capabilities for the chosen options (missing options take their default). */
    TargetCaps caps(Map<String, String> options);

    /** A short note shown next to the target (what is assumed, what is experimental). */
    default String note() {
        return "";
    }

    /** The built-in targets followed by any found through {@link ServiceLoader}, by id. */
    static Map<String, Backend> all() {
        var list = new ArrayList<Backend>(List.of(new Cuda(), new OpenCl(), new Metal()));
        ServiceLoader.load(Backend.class).forEach(list::add);
        var map = new LinkedHashMap<String, Backend>();
        list.forEach(b -> map.put(b.id(), b));
        return map;
    }

    /** Options in the order given (name, choices, name, choices, ...). */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> ordered(Object... pairs) {
        var map = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (List<String>) pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    private static String option(Map<String, String> chosen, Map<String, List<String>> offered, String name) {
        return chosen.getOrDefault(name, offered.get(name).get(0));
    }

    /** NVIDIA CUDA C++ (NVRTC), as the TornadoVM CUDA backend compiles it. */
    final class Cuda implements Backend {
        private static final Map<String, List<String>> OPTIONS = ordered("computeCapability", List.of("89", "90", "86", "80", "75", "70"), "fp64", List.of("yes", "no"), "fp16",
                List.of("yes", "no"));

        @Override
        public String id() {
            return "cuda";
        }

        @Override
        public String label() {
            return "CUDA";
        }

        @Override
        public String language() {
            return "cuda";
        }

        @Override
        public Map<String, List<String>> options() {
            return OPTIONS;
        }

        @Override
        public TargetCaps caps(Map<String, String> o) {
            var base = TargetCaps.CUDA_SM89;
            int cc = Integer.parseInt(option(o, OPTIONS, "computeCapability"));
            return new TargetCaps(TargetCaps.Dialect.CUDA, cc, option(o, OPTIONS, "fp64").equals("yes"), option(o, OPTIONS, "fp16").equals("yes"), base.arrayHeaderBytes(),
                    base.maxWorkGroupSize(), base.localMemoryBytes(), base.warpSize(), cc >= 80);
        }

        @Override
        public String note() {
            return "NVRTC C++; tensor-core and tile paths depend on the compute capability";
        }
    }

    /** OpenCL C 1.2, as the TornadoVM OpenCL backend compiles it. */
    final class OpenCl implements Backend {
        private static final Map<String, List<String>> OPTIONS = ordered("fp64", List.of("yes", "no"), "fp16", List.of("yes", "no"));

        @Override
        public String id() {
            return "opencl";
        }

        @Override
        public String label() {
            return "OpenCL";
        }

        @Override
        public String language() {
            return "opencl";
        }

        @Override
        public Map<String, List<String>> options() {
            return OPTIONS;
        }

        @Override
        public TargetCaps caps(Map<String, String> o) {
            var base = TargetCaps.OPENCL_GENERIC;
            return new TargetCaps(TargetCaps.Dialect.OPENCL, 0, option(o, OPTIONS, "fp64").equals("yes"), option(o, OPTIONS, "fp16").equals("yes"), base.arrayHeaderBytes(),
                    base.maxWorkGroupSize(), base.localMemoryBytes(), base.warpSize(), false);
        }

        @Override
        public String note() {
            return "OpenCL C 1.2 with the TornadoVM kernel ABI";
        }
    }

    /** Apple Metal Shading Language with the TornadoVM kernel ABI. */
    final class Metal implements Backend {
        @Override
        public String id() {
            return "metal";
        }

        @Override
        public String label() {
            return "Metal";
        }

        @Override
        public String language() {
            return "metal";
        }

        @Override
        public TargetCaps caps(Map<String, String> o) {
            var base = TargetCaps.OPENCL_GENERIC;
            return new TargetCaps(TargetCaps.Dialect.METAL, 0, false, true, base.arrayHeaderBytes(), base.maxWorkGroupSize(), base.localMemoryBytes(), 32, false);
        }

        @Override
        public String note() {
            return "Metal Shading Language with the TornadoVM kernel ABI (fast math, no fp64)";
        }
    }
}
