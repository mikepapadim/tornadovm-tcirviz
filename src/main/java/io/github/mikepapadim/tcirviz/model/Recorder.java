/*
 * tcirviz - an interactive visualiser for the TornadoVM TC compiler.
 * Copyright (c) 2026, Michalis Papadimitriou.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.mikepapadim.tcirviz.model;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import uk.ac.manchester.tornado.tc.codegen.CEmitter;
import uk.ac.manchester.tornado.tc.ir.Block;
import uk.ac.manchester.tornado.tc.ir.BlockArg;
import uk.ac.manchester.tornado.tc.ir.Function;
import uk.ac.manchester.tornado.tc.ir.Inst;
import uk.ac.manchester.tornado.tc.ir.IrPrinter;
import uk.ac.manchester.tornado.tc.ir.Loc;
import uk.ac.manchester.tornado.tc.ir.Value;
import uk.ac.manchester.tornado.tc.lower.S;
import uk.ac.manchester.tornado.tc.lower.SPrinter;
import uk.ac.manchester.tornado.tc.runtime.TcTrace;

/**
 * Records a TC compilation as a sequence of snapshots per function. Each IR value gets an id the
 * first time it is seen; passes change the IR in place, so the same instruction keeps its id from
 * stage to stage and can be followed until a pass removes it. Every printed line carries the ids it
 * defines and uses and its Java location, which is what the UI links on.
 */
public final class Recorder implements TcTrace {

    /** One printed line and what it refers to. */
    public record Line(String text, int inst, List<Integer> defs, List<Integer> uses, String file, int line, int bci, String vars) {
        public Line {
            text = brief(text);
        }

        Map<String, Object> json() {
            var m = new LinkedHashMap<String, Object>();
            m.put("t", text);
            if (inst >= 0) {
                m.put("i", inst);
            }
            if (!defs.isEmpty()) {
                m.put("d", defs);
            }
            if (!uses.isEmpty()) {
                m.put("u", uses);
            }
            if (file != null) {
                m.put("f", file);
            }
            if (line >= 0) {
                m.put("l", line);
            }
            if (bci >= 0) {
                m.put("b", bci);
            }
            if (vars != null && !vars.isEmpty()) {
                m.put("v", vars);
            }
            return m;
        }
    }

    /** The state of one function after one stage or pass. */
    public record Snapshot(String stage, String pass, String kind, boolean changed, long nanos, int size, String text, List<Line> lines) {
    }

    /** Facts about one value, independent of any stage. */
    public record ValueInfo(int id, String name, String kind, String type, String op, String file, int line, int bci) {
        public ValueInfo {
            type = brief(type);
        }
    }

    private static final Pattern QUALIFIED_OBJ = Pattern.compile("obj<(?:[\\w$]+\\.)+([\\w$]+)>");

    /** {@code obj<uk.ac.manchester.tornado.api.tile.TensorView>} as {@code obj<TensorView>}: the package only widens the IR. */
    static String brief(String text) {
        return text == null || !text.contains("obj<") ? text : QUALIFIED_OBJ.matcher(text).replaceAll("obj<$1>");
    }

    private final Map<Object, Integer> ids = new IdentityHashMap<>();
    private final Map<Integer, ValueInfo> values = new LinkedHashMap<>();
    private final Map<String, List<Snapshot>> functions = new LinkedHashMap<>();

    public Map<String, List<Snapshot>> functions() {
        return functions;
    }

    public Map<Integer, ValueInfo> values() {
        return values;
    }

    private static final java.util.regex.Pattern BYTECODE_LINE = java.util.regex.Pattern.compile("^\\s*// line (\\d+)");
    private static final java.util.regex.Pattern BYTECODE_INSN = java.util.regex.Pattern.compile("^\\s*(\\d+):");

    /**
     * Adds the bytecode listing TC printed for {@code function} as its first snapshot; each
     * instruction line carries its bci and the Java line the line-number table gives it.
     */
    public void bytecode(String function, String file, String listing) {
        var lines = new ArrayList<Line>();
        int javaLine = -1;
        for (var text : listing.split("\n", -1)) {
            var l = BYTECODE_LINE.matcher(text);
            var b = BYTECODE_INSN.matcher(text);
            if (l.find()) {
                javaLine = Integer.parseInt(l.group(1));
                lines.add(new Line(text, -1, List.of(), List.of(), file, javaLine, -1, null));
            } else if (b.find()) {
                lines.add(new Line(text, -1, List.of(), List.of(), file, javaLine, Integer.parseInt(b.group(1)), null));
            } else {
                lines.add(new Line(text, -1, List.of(), List.of(), null, -1, -1, null));
            }
        }
        functions.computeIfAbsent(function, f -> new ArrayList<>()).add(0, new Snapshot("bytecode", null, "bytecode", true, 0, lines.size(), listing, lines));
    }

    private int id(Object o) {
        return ids.computeIfAbsent(o, k -> ids.size() + 1);
    }

    private int valueId(Value value, IrPrinter printer) {
        int id = id(value);
        values.computeIfAbsent(id, k -> {
            if (value instanceof Inst inst) {
                var loc = inst.loc();
                return new ValueInfo(id, printer.name(value), "inst", inst.type().text(), inst.opcode().mnemonic(), loc.isKnown() ? loc.file() : null, loc.isKnown() ? loc.line() : -1,
                        loc.isKnown() ? loc.bci() : -1);
            }
            return new ValueInfo(id, printer.name(value), "arg", value.type().text(), "", null, -1, -1);
        });
        return id;
    }

    // ---------------------------------------------------------------- TcTrace

    @Override
    public void ir(String stage, String pass, boolean changed, long nanos, Function function) {
        var printer = new IrPrinter(false);
        var text = printer.printFunction(function);
        var list = functions.computeIfAbsent(function.name(), f -> new ArrayList<>());
        var previous = lastIr(list);
        boolean same = previous != null && previous.text().equals(text);
        if (same && pass == null) {
            return; // a stage whose last pass already showed this IR
        }
        var lines = same ? previous.lines() : irLines(function, printer);
        list.add(new Snapshot(stage, pass, "ir", changed && !same, nanos, size(function), text, lines));
    }

    private static Snapshot lastIr(List<Snapshot> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).kind().equals("ir")) {
                return list.get(i);
            }
        }
        return null;
    }

    private static int size(Function f) {
        return f.blocks().stream().mapToInt(b -> b.insts().size()).sum();
    }

    private List<Line> irLines(Function function, IrPrinter printer) {
        var lines = new ArrayList<Line>();
        var params = new ArrayList<Integer>();
        function.params().forEach(p -> params.add(valueId(p, printer)));
        var header = new StringBuilder().append(function.kind().text).append(" @").append(function.name()).append('(');
        for (int i = 0; i < function.params().size(); i++) {
            var p = function.params().get(i);
            header.append(i == 0 ? "" : ", ").append('%').append(printer.name(p)).append(": ").append(p.type().text());
        }
        header.append(") -> ").append(function.returnType().text()).append(" {");
        lines.add(new Line(header.toString(), -1, params, List.of(), null, -1, -1, null));
        boolean first = true;
        for (var block : function.blocks()) {
            lines.add(blockLine(block, printer, first));
            first = false;
            for (var inst : block.insts()) {
                var defs = inst.producesValue() ? List.of(valueId(inst, printer)) : List.<Integer>of();
                var uses = new ArrayList<Integer>();
                for (var operand : inst.operands()) {
                    uses.add(valueId(operand, printer));
                }
                for (var successor : inst.successors()) {
                    for (var arg : successor.args()) {
                        uses.add(valueId(arg, printer));
                    }
                }
                var loc = inst.loc();
                lines.add(new Line("  " + printer.instText(inst), id(inst), defs, uses, loc.isKnown() ? loc.file() : null, loc.isKnown() ? loc.line() : -1, loc.isKnown() ? loc.bci() : -1, null));
                if (!inst.producesValue()) {
                    valueIdOfEffect(inst, printer);
                }
            }
        }
        lines.add(new Line("}", -1, List.of(), List.of(), null, -1, -1, null));
        return lines;
    }

    /** Effects (stores, branches) are instructions too: record their facts under their own id. */
    private void valueIdOfEffect(Inst inst, IrPrinter printer) {
        int id = id(inst);
        values.computeIfAbsent(id, k -> {
            var loc = inst.loc();
            return new ValueInfo(id, "", "effect", "void", inst.opcode().mnemonic(), loc.isKnown() ? loc.file() : null, loc.isKnown() ? loc.line() : -1, loc.isKnown() ? loc.bci() : -1);
        });
    }

    private Line blockLine(Block block, IrPrinter printer, boolean entry) {
        var sb = new StringBuilder("^").append(printer.blockName(block));
        var defs = new ArrayList<Integer>();
        if (!entry && !block.args().isEmpty()) {
            sb.append('(');
            for (int i = 0; i < block.args().size(); i++) {
                BlockArg a = block.args().get(i);
                defs.add(valueId(a, printer));
                sb.append(i == 0 ? "" : ", ").append('%').append(printer.name(a)).append(": ").append(a.type().text());
            }
            sb.append(')');
        }
        if (!block.attributes().isEmpty()) {
            sb.append(" [").append(String.join(", ", block.attributes().stream().map(a -> a.text()).toList())).append(']');
        }
        return new Line(sb.append(':').toString(), -1, defs, List.of(), null, -1, -1, null);
    }

    @Override
    public void structured(Function function, S.Kernel kernel) {
        var lines = new ArrayList<Line>();
        var printer = new IrPrinter(false);
        printer.printFunction(function); // the names the structured kernel's values print with
        var f = kernel.source();
        var header = new StringBuilder(f.kind().text).append(' ').append(f.name()).append('(');
        for (int i = 0; i < kernel.params().size(); i++) {
            var v = kernel.params().get(i);
            header.append(i == 0 ? "" : ", ").append(v.type().text()).append(' ').append(v.name());
        }
        lines.add(plain(header.append(") -> ").append(f.returnType().text()).append(" {").toString()));
        for (var alloca : kernel.allocas()) {
            var a = alloca.kind();
            lines.add(new Line("  " + a.space().text() + " " + a.element().text() + " " + alloca.name() + "[" + a.count() + "];", id(alloca.inst()), List.of(valueId(alloca.inst(), printer)),
                    List.of(), null, -1, -1, alloca.name()));
        }
        for (var v : kernel.locals()) {
            lines.add(new Line("  " + v.type().text() + " " + v.name() + ";", -1, List.of(), List.of(), null, -1, -1, v.name()));
        }
        statements(kernel.body(), 1, lines, printer);
        lines.add(plain("}"));
        var text = String.join("\n", lines.stream().map(Line::text).toList());
        functions.computeIfAbsent(function.name(), k -> new ArrayList<>()).add(new Snapshot("structure", null, "structured", true, 0, lines.size(), text, lines));
    }

    private static Line plain(String text) {
        return new Line(text, -1, List.of(), List.of(), null, -1, -1, null);
    }

    private void statements(List<S.Stmt> body, int depth, List<Line> lines, IrPrinter printer) {
        for (var stmt : body) {
            statement(stmt, depth, lines, printer);
        }
    }

    private void statement(S.Stmt stmt, int depth, List<Line> lines, IrPrinter printer) {
        var pad = "  ".repeat(depth);
        switch (stmt) {
            case S.Assign(var target, var value, var loc) -> lines.add(stmtLine(pad + target.name() + " = " + SPrinter.expr(value) + ";", stmt, loc, printer, target.name()));
            case S.Effect(var op, var loc) -> lines.add(stmtLine(pad + SPrinter.expr(op) + ";", stmt, loc, printer, ""));
            case S.If(var c, var t, var e) -> {
                lines.add(stmtLine(pad + "if (" + SPrinter.expr(c) + ") {", stmt, Loc.UNKNOWN, printer, ""));
                statements(t, depth + 1, lines, printer);
                if (!e.isEmpty()) {
                    lines.add(plain(pad + "} else {"));
                    statements(e, depth + 1, lines, printer);
                }
                lines.add(plain(pad + "}"));
            }
            case S.Loop(var label, var b) -> {
                lines.add(plain(pad + "loop " + label + " {"));
                statements(b, depth + 1, lines, printer);
                lines.add(plain(pad + "}"));
            }
            case S.Labelled(var label, var b) -> {
                lines.add(plain(pad + "block " + label + " {"));
                statements(b, depth + 1, lines, printer);
                lines.add(plain(pad + "}"));
            }
            case S.Break(var label) -> lines.add(plain(pad + "break " + label + ";"));
            case S.Continue(var label) -> lines.add(plain(pad + "continue " + label + ";"));
            case S.Switch(var sel, var cases, var d) -> {
                lines.add(stmtLine(pad + "switch (" + SPrinter.expr(sel) + ") {", stmt, Loc.UNKNOWN, printer, ""));
                for (var k : cases) {
                    lines.add(plain(pad + "case " + k.key() + ":"));
                    statements(k.body(), depth + 1, lines, printer);
                }
                lines.add(plain(pad + "default:"));
                statements(d, depth + 1, lines, printer);
                lines.add(plain(pad + "}"));
            }
            case S.Return(var value) -> lines.add(stmtLine(pad + value.map(v -> "return " + SPrinter.expr(v) + ";").orElse("return;"), stmt, Loc.UNKNOWN, printer, ""));
            case S.Unreachable _u -> lines.add(plain(pad + "unreachable;"));
        }
    }

    /** A statement's line: it uses every instruction printed in it; its location is the first known one. */
    private Line stmtLine(String text, S.Stmt stmt, Loc loc, IrPrinter printer, String vars) {
        var insts = new LinkedHashSet<Inst>();
        headInsts(stmt, insts);
        var uses = new ArrayList<Integer>();
        Loc where = loc;
        for (var inst : insts) {
            uses.add(valueId(inst, printer));
            if (!where.isKnown() && inst.loc().isKnown()) {
                where = inst.loc();
            }
        }
        var names = new StringBuilder(vars);
        varNames(stmt, names);
        return new Line(text, -1, List.of(), uses, where.isKnown() ? where.file() : null, where.isKnown() ? where.line() : -1, where.isKnown() ? where.bci() : -1, names.toString().trim());
    }

    /** The instructions printed in a statement's own line (a condition, not the bodies it guards). */
    private static void headInsts(S.Stmt stmt, Set<Inst> out) {
        switch (stmt) {
            case S.Assign(var target, var value, var loc) -> exprInsts(value, out);
            case S.Effect(var op, var loc) -> exprInsts(op, out);
            case S.If(var c, var t, var e) -> exprInsts(c, out);
            case S.Switch(var sel, var cases, var d) -> exprInsts(sel, out);
            case S.Return(var value) -> value.ifPresent(v -> exprInsts(v, out));
            default -> {
            }
        }
    }

    private static void exprInsts(S.Expr expr, Set<Inst> out) {
        switch (expr) {
            case S.Op(var inst, var ops) -> {
                out.add(inst);
                ops.forEach(o -> exprInsts(o, out));
            }
            case S.Const(var inst) -> out.add(inst);
            case S.ElementAddress e -> {
                exprInsts(e.address(), out);
                exprInsts(e.base(), out);
                exprInsts(e.index(), out);
            }
            case S.AllocaRef(var alloca) -> out.add(alloca.inst());
            case S.VarRef v -> {
            }
        }
    }

    private static void varNames(S.Stmt stmt, StringBuilder out) {
        switch (stmt) {
            case S.Assign(var target, var value, var loc) -> exprVars(value, out);
            case S.Effect(var op, var loc) -> exprVars(op, out);
            case S.If(var c, var t, var e) -> exprVars(c, out);
            case S.Switch(var sel, var cases, var d) -> exprVars(sel, out);
            case S.Return(var value) -> value.ifPresent(v -> exprVars(v, out));
            default -> {
            }
        }
    }

    private static void exprVars(S.Expr expr, StringBuilder out) {
        switch (expr) {
            case S.VarRef(var v) -> out.append(' ').append(v.name());
            case S.Op(var inst, var ops) -> ops.forEach(o -> exprVars(o, out));
            case S.ElementAddress e -> {
                exprVars(e.base(), out);
                exprVars(e.index(), out);
            }
            default -> {
            }
        }
    }

    @Override
    public void emitted(Function function, CEmitter.Result result) {
        var printer = new IrPrinter(false);
        printer.printFunction(function);
        var lines = new ArrayList<Line>();
        var text = result.source();
        var split = text.split("\n", -1);
        for (int i = 0; i < split.length; i++) {
            var origin = result.origin(i);
            if (origin.isEmpty()) {
                lines.add(new Line(split[i], -1, List.of(), List.of(), null, -1, -1, null));
                continue;
            }
            var line = stmtLine(split[i], origin.get(), Loc.UNKNOWN, printer, origin.get() instanceof S.Assign a ? a.target().name() : "");
            lines.add(line);
        }
        functions.computeIfAbsent(function.name(), k -> new ArrayList<>()).add(new Snapshot("emit", null, "source", true, 0, lines.size(), text, lines));
    }

    /** The recorded compilation as JSON-ready maps. */
    public Map<String, Object> json() {
        var fs = new ArrayList<Object>();
        for (var e : functions.entrySet()) {
            var snaps = new ArrayList<Object>();
            for (var s : e.getValue()) {
                var m = new LinkedHashMap<String, Object>();
                m.put("stage", s.stage());
                m.put("pass", s.pass());
                m.put("kind", s.kind());
                m.put("changed", s.changed());
                m.put("nanos", s.nanos());
                m.put("size", s.size());
                m.put("lines", s.lines().stream().map(Line::json).toList());
                snaps.add(m);
            }
            fs.add(Map.of("name", e.getKey(), "snapshots", snaps));
        }
        var vs = new LinkedHashMap<String, Object>();
        for (var v : values.values()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", v.name());
            m.put("kind", v.kind());
            m.put("type", v.type());
            m.put("op", v.op());
            if (v.file() != null) {
                m.put("f", v.file());
                m.put("l", v.line());
                m.put("b", v.bci());
            }
            vs.put(Integer.toString(v.id()), m);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("functions", fs);
        out.put("values", vs);
        return out;
    }
}
