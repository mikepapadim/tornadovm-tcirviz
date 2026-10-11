# tcirviz architecture

```
browser (web/*.js)          tcirviz server (Java 21)                    TC (TornadoVM SDK jars)
──────────────────          ────────────────────────                    ───────────────────────
editor, pipeline rail  ──►  POST /api/compile
panes, diff, journey        ├─ JavaSources: javac in process (-g -parameters --enable-preview)
                            ├─ Arguments: facts like the runtime's (lengths, scalars, layouts)
                            ├─ Backend: TargetCaps for the chosen target
                            └─ Pipeline ── TcTrace.with(Recorder) ──►  TcCompiler.compileJava
                                              ▲                          │ ir(stage, pass, fn)
                                              └──── Recorder ◄───────────┤ structured(fn, kernel)
                                                    ids, lines, refs     └ emitted(fn, result)
```

## The trace model

`Recorder` implements `TcTrace`. It produces, per function, a list of **snapshots**:

| kind | when | lines carry |
|---|---|---|
| `bytecode` | TC's class-file listing | bci, Java line |
| `ir` | after each pipeline stage, and after each optimisation pass inside `optimize` and `optimize.post-abi` | defined ids `d`, used ids `u`, instruction id `i`, Java file/line/bci |
| `structured` | after structuring | ids used by the statement (its expression tree), variable names |
| `source` | after emission | the same, through `CEmitter.Result.origin(line)` |

**Ids.** An `IdentityHashMap` from IR objects to integers. TC's passes mutate the IR in place, so the
same `Inst` object is the same value from one stage to the next. A pass that rewrites a value
creates a new object, which gets a new id. The UI links the two again by Java line, bci and name
(`chainOf` in `app.js`), so a value can be followed across ABI lowering.

**Unchanged passes** are kept, with `changed = false` and the previous lines. The rail hides them
unless asked. A stage whose IR equals its last pass's IR is not repeated.

`values` holds per-id facts: name, type, opcode and origin. The UI derives where each id is first
and last defined, and the snapshot that removed it.

## Adding a target

Implement `io.github.mikepapadim.tcirviz.backend.Backend`:

```java
public final class MyTarget implements Backend {
    public String id() { return "spirv"; }
    public String label() { return "SPIR-V"; }
    public String language() { return "c"; }                       // highlighting family
    public Map<String, List<String>> options() { return Map.of("fp64", List.of("yes", "no")); }
    public TargetCaps caps(Map<String, String> o) { return new TargetCaps(...); }
}
```

List it in `META-INF/services/io.github.mikepapadim.tcirviz.backend.Backend` and put its jar on the
classpath, for example via `tornado --tcirviz --jvm "-cp ..."`. It then appears in the UI with its
options. The code still comes from TC: a target is a set of TC capabilities plus a TC dialect. Until
TC has the dialect, the IR stages still show, and generation stops with TC's diagnostic, as Metal
does today.

Other languages for the highlighter go in `web/highlight.js` (`forKind`).

## Adding a stage or pass

Nothing to do. New TC stages and passes come through `TcTrace` under their own names. A description
in `web/docs.js` (`STAGES`, `PASSES`) makes the UI explain them.

## API

| endpoint | body | returns |
|---|---|---|
| `GET /api/info` | | backends with options, examples, version |
| `GET /api/example?name=` | | example source |
| `POST /api/kernels` | `{source}` | kernel methods, javac problems |
| `GET /api/classes?q=` | | classes of the application classpath (`--classpath`) matching `q` |
| `GET /api/open?name=` | | that class's source (from `--sourcepath` or a `-sources.jar`) and kernel methods |
| `POST /api/compile` | `{source, className, method, backend, targetOptions, mathOptimizations, arguments}` (blank `source` + `className`: compile the class file) | the trace: `functions[].snapshots[].lines[]`, `values`, `params`, `diagnostics`, `stages`, timings |

## Design choices

- **A local web UI, not Swing or JavaFX.** Modern rendering and theming, text selection and search
  for free, and it runs on any machine with a browser. The server is the JDK's `HttpServer`; the
  page is static ES modules with no dependency and no build step.
- **Headless TC, in process.** No GPU and no TornadoVM runtime are needed. Compilation uses the
  same `TcCompiler.compileJava` and argument facts as the runtime, so what you see is what the
  device would get.
- **Identity, not text.** Diffs and journeys follow `Inst` identity. Printed names (`%17`) change
  between stages and would mislead.
