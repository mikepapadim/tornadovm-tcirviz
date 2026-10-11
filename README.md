# tcirviz

An interactive explorer for **TC**, the Graal-free TornadoVM compiler. Type a Java kernel and watch
it become GPU code, one stage at a time. You can follow any line, bytecode or IR value through
every stage to the CUDA or OpenCL statement it ends up in.

```
tornado --tcirviz [MyKernels.java]
```

It opens `http://127.0.0.1:7878/` in the browser. Compilation is headless, so no GPU is needed.

![Following a value from Java to CUDA](docs/img/follow-a-value.png)

## What it shows

| | |
|---|---|
| **Java** | An editor with eight examples. Lines that reach the generated code are marked in the gutter. |
| **Bytecode** | What `javac` produced, with each bytecode index (bci) tied to its Java line. |
| **TC IR** | Shown after every pipeline stage (frontend, inline, specialise, parallel loops, legality, ABI lowering, ...) and after **every optimisation pass** (canonicalize, cfg-simplify, dce, unroll, gvn, licm, math-peepholes), with the IR size and time of each. |
| **Structured IR** | The kernel after SSA destruction and control-flow structuring: if/while/break, still target-neutral. |
| **Target code** | CUDA C++ or OpenCL C, exactly as the TornadoVM runtime would install it. Metal is listed and stops at TC-E4001 until TC has a Metal dialect. |

**Follow anything.** Click a Java line, a bytecode, an IR value (`%x`) or a line of target code:
- Every pane highlights what belongs to it: the lines that define it, the lines that use it, and the lines from the same Java line.
- The **Journey** bar lists its path. It says where the value is created, which pass changes it, and which one removes or *replaces* it (ABI lowering turns `%x = array.load` into `%x = load`; the trail continues). It ends at the target-code lines that use it.

**Diff any stage.** Δ marks what a stage or pass added, changed or removed against the previous one.
Lines are matched by instruction identity, not by text.

**Learn as you go.** Each stage and pass has a one-paragraph explanation, and hovering an opcode
explains it. Values show their type, origin (Java line and bci), and where they are created and
removed.

**Specialise like the runtime.** TornadoVM compiles every task for its actual arguments. The
*Arguments* row sets array lengths, scalar values and object sizes, so you can see constant loop
bounds and folded sizes appear. Object arguments such as `Matrix2DFloat` are built for real, and
their field layouts are read from the JVM, exactly as the runtime does it.

**Targets.** Switch between CUDA (with the compute capability, fp64 and fp16 as options), OpenCL and
Metal. See [docs/architecture.md](docs/architecture.md) for adding a target.

| | |
|---|---|
| ![ABI lowering diff](docs/img/abi-diff.png) | ![OpenCL, dark theme](docs/img/opencl-dark.png) |

## Install

You need a TornadoVM SDK built from `mikepapadim/tornadovm-compiler` (branch `compiler/main`, with
`TcTrace`), and JDK 21 or newer.

```bash
source ~/tornadovm-compiler/setvars.sh      # TORNADOVM_HOME, JAVA_HOME
./install.sh                                # builds build/tcirviz.jar and copies it into the SDK
tornado --tcirviz                           # or: tornado --tcirviz MyKernels.java
tornado --tcirviz --params "--port 9000 --no-open"
```

Without the launcher, run `bin/tcirviz [File.java] [--port N] [--no-open]`. To print the trace as
JSON for scripts, run `bin/tcirviz --json File.java [--method m] [--backend cuda|opencl|metal]`.

There are no dependencies beyond the JDK and the SDK's jars:
- the server is the JDK's `HttpServer`, bound to loopback only;
- the UI is plain ES modules, with no CDN and no build step;
- Java is compiled in process with `javax.tools`.

## Keys

| Key | Action |
|---|---|
| `Ctrl+Enter` | compile |
| `←` / `→` | step pane A through the pipeline |
| `Shift`-click a chip | open the stage in the right pane |
| `Esc` | clear the selection |
| `?` | help |

## Test

```bash
./test.sh
```

It compiles every example for CUDA and OpenCL and validates each trace with
`tools/check-trace.py`: every stage kind is present, lines are linked and values are known. When
Chrome and Node are available, it also drives the UI headlessly (`tools/ui-check.mjs`, over the
DevTools protocol) and fails on any console error.

## How it works

TC exposes a `TcTrace` hook. It receives the compiler's live IR after every stage and pass, plus
the structured kernel and the emitted source with per-line origins. Passes change the IR in place,
so an instruction keeps its object identity from stage to stage. tcirviz gives each one an id the
first time it sees it. Every printed line then records the ids it defines and uses, its Java line
and its bci, and that is all the UI needs to link panes.

Details are in [docs/architecture.md](docs/architecture.md).
