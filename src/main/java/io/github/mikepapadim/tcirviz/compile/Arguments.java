/*
 * tcirviz - an interactive visualiser for the TornadoVM TC compiler.
 * Copyright (c) 2026, Michalis Papadimitriou.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.mikepapadim.tcirviz.compile;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import uk.ac.manchester.tornado.runtime.common.FieldLayout;
import uk.ac.manchester.tornado.tc.tornado.ObjectLayout;
import uk.ac.manchester.tornado.tc.tornado.ValueSpecialisation.Fact;

/**
 * The facts TornadoVM's runtime gives TC about a task's actual arguments (array lengths, scalar
 * values, the layouts of objects passed by field copy), made from values the user picks. TC
 * specialises every kernel on them, so they change what the pipeline shows (constant loop bounds,
 * folded sizes).
 */
public final class Arguments {

    /** One kernel parameter as the UI edits it: {@code kind} is array, scalar, object or context. */
    public record Param(String name, String type, String kind, String value, String hint) {
        Map<String, Object> json() {
            return Map.of("name", name, "type", type, "kind", kind, "value", value, "hint", hint);
        }
    }

    private static final String NATIVE_ARRAY = "uk.ac.manchester.tornado.api.types.arrays.TornadoNativeArray";
    private static final String KERNEL_CONTEXT = "uk.ac.manchester.tornado.api.KernelContext";
    private static final String TILE_CONTEXT = "uk.ac.manchester.tornado.api.tile.TileContext";

    private Arguments() {
    }

    public static List<Map<String, Object>> describe(Method method, Map<String, String> chosen) {
        var out = new ArrayList<Map<String, Object>>();
        for (var p : params(method, chosen)) {
            out.add(p.json());
        }
        return out;
    }

    static List<Param> params(Method method, Map<String, String> chosen) {
        var list = new ArrayList<Param>();
        // A tile kernel's ints are extents and block counts that must agree with the arrays' lengths;
        // unknown by default, so the views stay symbolic instead of baking in sizes that contradict them.
        boolean tiles = Arrays.stream(method.getParameterTypes()).anyMatch(t -> t.getName().equals(TILE_CONTEXT));
        for (var p : method.getParameters()) {
            var type = p.getType();
            var name = p.getName();
            String kind;
            String fallback;
            String hint;
            if (type.getName().equals(KERNEL_CONTEXT)) {
                kind = "context";
                fallback = "";
                hint = "thread ids and local memory: nothing to specialise";
            } else if (type.getName().equals(TILE_CONTEXT)) {
                // Host-only, like KernelContext: the runtime passes nothing for it to the device.
                kind = "context";
                fallback = "";
                hint = "block ids; tile shapes come from the kernel's constants: nothing to specialise";
            } else if (isNativeArray(type) || type.isArray()) {
                kind = "array";
                fallback = "1024";
                hint = "length";
            } else if (type.isPrimitive()) {
                kind = "scalar";
                fallback = !tiles && (type == int.class || type == long.class || type == short.class || type == byte.class) ? "256" : "";
                hint = "value (empty: not known when compiling)";
            } else {
                kind = "object";
                fallback = "256";
                hint = "size passed to its constructor";
            }
            list.add(new Param(name, type.getSimpleName(), kind, chosen.getOrDefault(name, fallback), hint));
        }
        return list;
    }

    /** The facts for {@code method}'s parameters, from the chosen values. */
    public static List<Fact> facts(Method method, Map<String, String> chosen) {
        var facts = new ArrayList<Fact>();
        var params = params(method, chosen);
        var types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            var p = params.get(i);
            var v = p.value().trim();
            facts.add(switch (p.kind()) {
                case "array" -> v.isEmpty() ? new Fact.Unknown() : new Fact.Length(Long.parseLong(v));
                case "scalar" -> v.isEmpty() ? new Fact.Unknown() : new Fact.Scalar(scalar(types[i], v));
                case "object" -> v.isEmpty() ? new Fact.Unknown() : instance(types[i], Integer.parseInt(v));
                default -> new Fact.Unknown();
            });
        }
        return facts;
    }

    private static Number scalar(Class<?> type, String v) {
        if (type == float.class) {
            return Float.parseFloat(v);
        }
        if (type == double.class) {
            return Double.parseDouble(v);
        }
        if (type == long.class) {
            return Long.parseLong(v);
        }
        if (type == boolean.class) {
            return Boolean.parseBoolean(v) || v.equals("1") ? 1 : 0;
        }
        return Integer.parseInt(v);
    }

    private static boolean isNativeArray(Class<?> type) {
        for (var c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(NATIVE_ARRAY)) {
                return true;
            }
        }
        return false;
    }

    /** Builds an instance through a constructor taking only ints (each gets {@code size}) and describes it as the runtime does. */
    private static Fact instance(Class<?> type, int size) {
        try {
            var ctor = Arrays.stream(type.getConstructors()).filter(c -> c.getParameterCount() > 0 && Arrays.stream(c.getParameterTypes()).allMatch(t -> t == int.class))
                    .min(Comparator.comparingInt(Constructor::getParameterCount)).orElse(null);
            Object object;
            if (ctor != null) {
                var args = new Object[ctor.getParameterCount()];
                Arrays.fill(args, size);
                object = ctor.newInstance(args);
            } else {
                object = type.getConstructor().newInstance();
            }
            var layouts = new ArrayList<ObjectLayout>();
            describe(object, !isNativeArray(type), layouts, new HashSet<>());
            var lengths = new HashMap<String, Long>();
            var finals = new HashMap<String, Number>();
            for (var c = object.getClass(); c != null; c = c.getSuperclass()) {
                for (var field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (isNativeArray(field.getType())) {
                        field.setAccessible(true);
                        var array = field.get(object);
                        if (array != null) {
                            lengths.putIfAbsent(field.getName(), (Long) array.getClass().getMethod("getSizeLong").invoke(array));
                        }
                    } else if (Modifier.isFinal(field.getModifiers()) && field.getType().isPrimitive() && field.getType() != boolean.class && field.getType() != char.class) {
                        field.setAccessible(true);
                        finals.putIfAbsent(field.getName(), (Number) field.get(object));
                    }
                }
            }
            return new Fact.Instance(finals, layouts, lengths);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new Fact.Unknown();
        }
    }

    /** The device layout of {@code object} and the objects it references: the runtime's own {@link FieldLayout}. */
    private static void describe(Object object, boolean includeSuper, List<ObjectLayout> out, Set<Class<?>> seen) {
        var cls = object.getClass();
        if (!seen.add(cls)) {
            return;
        }
        var layout = FieldLayout.of(cls);
        var offsets = new LinkedHashMap<String, Integer>();
        var references = new LinkedHashMap<String, String>();
        for (var field : layout.fields(includeSuper)) {
            offsets.putIfAbsent(field.getName(), layout.offset(field));
            var t = field.getType();
            if (!t.isPrimitive() && !t.isArray() && !isNativeArray(t)) {
                var value = FieldLayout.get(field, object);
                if (value != null) {
                    references.put(field.getName(), value.getClass().getName());
                    describe(value, includeSuper, out, seen);
                }
            }
        }
        out.add(new ObjectLayout(cls.getName(), Map.copyOf(offsets), Map.copyOf(references)));
    }
}
