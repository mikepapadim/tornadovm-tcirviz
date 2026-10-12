/*
 * tcirviz - an interactive visualiser for the TornadoVM TC compiler.
 * Copyright (c) 2026, Michalis Papadimitriou.
 * SPDX-License-Identifier: GPL-2.0-only
 */
package io.github.mikepapadim.tcirviz.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.mikepapadim.tcirviz.backend.Backend;
import io.github.mikepapadim.tcirviz.compile.AppPath;
import io.github.mikepapadim.tcirviz.compile.JavaSources;
import io.github.mikepapadim.tcirviz.compile.Pipeline;

/**
 * The local web server behind the UI: static files from the jar and a small JSON API. It binds to
 * the loopback interface only.
 */
public final class Server {

    private static final String WEB = "/tcirviz/web/";
    private static final String EXAMPLES = "/tcirviz/examples/";
    private static final List<String> EXAMPLE_NAMES = List.of("VectorAdd", "Saxpy", "MatrixMultiply", "Reduction", "BlurFilter", "Mandelbrot", "Branches", "HalfVectors", "TileMatmul");

    private final HttpServer http;
    private final String initialSource;
    private final AppPath app;

    public Server(int port, String initialSource, AppPath app) throws IOException {
        this.initialSource = initialSource;
        this.app = app;
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/api/", this::api);
        http.createContext("/", this::files);
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public void start() {
        http.start();
    }

    public void stop() {
        http.stop(0);
    }

    private void api(HttpExchange ex) throws IOException {
        try {
            var path = ex.getRequestURI().getPath();
            Object result = switch (path) {
                case "/api/info" -> info();
                case "/api/example" -> example(query(ex, "name"));
                case "/api/kernels" -> Pipeline.kernels(string(body(ex), "source", ""), app);
                case "/api/classes" -> Map.of("classes", app.classes(query(ex, "q"), 200));
                case "/api/open" -> open(query(ex, "name"));
                case "/api/compile" -> compile(body(ex));
                default -> null;
            };
            if (result == null) {
                send(ex, 404, "application/json", "{\"error\":\"unknown endpoint\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(ex, 200, "application/json; charset=utf-8", Json.write(result).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            send(ex, 500, "application/json", Json.write(Map.of("error", String.valueOf(e))).getBytes(StandardCharsets.UTF_8));
        }
    }

    private Map<String, Object> info() {
        var backends = new ArrayList<Object>();
        for (var b : Backend.all().values()) {
            backends.add(Map.of("id", b.id(), "label", b.label(), "language", b.language(), "options", b.options(), "note", b.note()));
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("version", Server.class.getPackage().getImplementationVersion() == null ? "dev" : Server.class.getPackage().getImplementationVersion());
        out.put("java", Runtime.version().toString());
        out.put("backends", backends);
        out.put("examples", EXAMPLE_NAMES);
        out.put("initialSource", initialSource);
        out.put("app", !app.isEmpty());
        return out;
    }

    private Map<String, Object> example(String name) throws IOException {
        if (!EXAMPLE_NAMES.contains(name)) {
            return Map.of("error", "no example " + name);
        }
        try (InputStream in = Server.class.getResourceAsStream(EXAMPLES + name + ".java")) {
            return Map.of("name", name, "source", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** A class of the application: its source when one is found, and its kernel methods. */
    private Map<String, Object> open(String className) {
        var out = new LinkedHashMap<String, Object>();
        out.put("className", className);
        out.put("source", app.source(className).orElse(null));
        var compiled = JavaSources.fromClass(className, app);
        out.put("kernels", compiled.kernels().stream().map(k -> Map.of("name", k.name(), "descriptor", k.descriptor(), "signature", k.signature())).toList());
        out.put("problems", compiled.problems().stream().map(p -> Map.of("kind", p.kind(), "message", p.message())).toList());
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compile(Map<String, Object> body) {
        var options = new LinkedHashMap<String, String>();
        if (body.get("targetOptions") instanceof Map<?, ?> m) {
            ((Map<String, Object>) m).forEach((k, v) -> options.put(k, String.valueOf(v)));
        }
        var arguments = new LinkedHashMap<String, String>();
        if (body.get("arguments") instanceof Map<?, ?> m) {
            ((Map<String, Object>) m).forEach((k, v) -> arguments.put(k, v instanceof Double d && d == Math.rint(d) ? Long.toString(d.longValue()) : String.valueOf(v)));
        }
        var request = new Pipeline.Request(string(body, "source", ""), string(body, "method", null), string(body, "descriptor", null), string(body, "backend", "cuda"), options,
                !Boolean.FALSE.equals(body.get("mathOptimizations")), arguments, string(body, "className", null));
        return Pipeline.compile(request, app);
    }

    private void files(HttpExchange ex) throws IOException {
        var path = ex.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            send(ex, 400, "text/plain", new byte[0]);
            return;
        }
        try (InputStream in = Server.class.getResourceAsStream(WEB + path.substring(1))) {
            if (in == null) {
                send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(ex, 200, contentType(path), in.readAllBytes());
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        var text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return text.isBlank() ? Map.of() : (Map<String, Object>) Json.parse(text);
    }

    private static String string(Map<String, Object> m, String key, String fallback) {
        var v = m.get(key);
        return v == null ? fallback : v.toString();
    }

    private static String query(HttpExchange ex, String key) {
        var q = ex.getRequestURI().getQuery();
        if (q == null) {
            return "";
        }
        for (var part : q.split("&")) {
            var kv = part.split("=", 2);
            if (kv[0].equals(key)) {
                return java.net.URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (var out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }
}
