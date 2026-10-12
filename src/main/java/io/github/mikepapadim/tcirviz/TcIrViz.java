/*
 * tcirviz - an interactive visualiser for the TornadoVM TC compiler.
 * Copyright (c) 2026, Michalis Papadimitriou.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.mikepapadim.tcirviz;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.mikepapadim.tcirviz.compile.AppPath;
import io.github.mikepapadim.tcirviz.compile.Pipeline;
import io.github.mikepapadim.tcirviz.server.Json;
import io.github.mikepapadim.tcirviz.server.Server;

/**
 * {@code tornado --tcirviz [File.java]}: starts the visualiser on a local port and opens it in the
 * browser. {@code --json File.java [method]} prints the trace instead (no UI), for scripts and tests.
 */
public final class TcIrViz {

    private TcIrViz() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = 7878;
        boolean open = true;
        String file = null;
        String json = null;
        String method = null;
        String backend = "cuda";
        String classpath = null;
        String sourcepath = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--no-open" -> open = false;
                case "--json" -> json = args[++i];
                case "--method" -> method = args[++i];
                case "--backend" -> backend = args[++i];
                case "--classpath", "-cp" -> classpath = args[++i];
                case "--sourcepath" -> sourcepath = args[++i];
                case "-h", "--help" -> {
                    usage();
                    return;
                }
                default -> file = args[i];
            }
        }
        if (json != null) {
            var source = Files.readString(Path.of(json));
            Map<String, String> none = new LinkedHashMap<>();
            System.out.println(Json.write(Pipeline.compile(new Pipeline.Request(source, method, null, backend, none, true, new LinkedHashMap<>(), null), AppPath.of(classpath, sourcepath))));
            return;
        }
        var initial = file == null ? null : Files.readString(Path.of(file));
        Server server;
        try {
            server = new Server(port, initial, AppPath.of(classpath, sourcepath));
        } catch (java.net.BindException e) {
            server = new Server(0, initial, AppPath.of(classpath, sourcepath)); // the default port is taken: any free one
        }
        server.start();
        var url = "http://127.0.0.1:" + server.port() + "/";
        System.out.println("tcirviz: TC IR visualiser at " + url + "  (Ctrl+C to stop)");
        if (open) {
            browse(url);
        }
        Thread.currentThread().join();
    }

    private static void browse(String url) {
        var os = System.getProperty("os.name").toLowerCase();
        var command = os.contains("mac") ? new String[] { "open", url } : os.contains("win") ? new String[] { "rundll32", "url.dll,FileProtocolHandler", url } : new String[] { "xdg-open", url };
        try {
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            System.out.println("tcirviz: open " + url + " in a browser");
        }
    }

    private static void usage() {
        System.out.println("""
                tcirviz - the TC compiler, stage by stage

                  tornado --tcirviz [File.java]           start the UI (http://127.0.0.1:7878)
                    --port N                              listen on port N (0: any free port)
                    --no-open                             do not open a browser
                    --classpath CP                        an application's classes: open its kernels
                    --sourcepath SP                       its sources (also found in *-sources.jar)
                  tcirviz --json File.java [--method m] [--backend cuda|opencl|metal]
                                                          print the compilation trace as JSON
                """);
    }
}
