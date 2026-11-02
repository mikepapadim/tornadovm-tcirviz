#!/usr/bin/env bash
# Headless checks: every example compiles on CUDA, OpenCL and Metal with a complete, linked trace; then,
# when Chrome is present, the UI is driven end to end (tools/ui-check.mjs) and must log no errors.
set -euo pipefail
cd "$(dirname "$0")"
./build.sh >/dev/null
fail=0
for f in src/main/resources/tcirviz/examples/*.java; do
  for b in cuda opencl metal; do
    case "$(basename "$f")/$b" in
      # TileContext kernels are CUDA Tile C++: elsewhere the compiler must say so (TC-E4010).
      Tile*/opencl | Tile*/metal)
        bin/tcirviz --json "$f" --backend "$b" | python3 -c 'import json, sys; d = json.load(sys.stdin); sys.exit(0 if any(x["code"] == "TC-E4010" for x in d["diagnostics"]) else 1)' \
          && printf '%-16s %-7s ok (TC-E4010: CUDA only)\n' "$(basename "$f" .java)" "$b" \
          || { printf '%-16s %-7s FAILED: no TC-E4010\n' "$(basename "$f" .java)" "$b"; fail=1; } ;;
      # Device-side launches are CUDA Dynamic Parallelism: elsewhere the compiler must say so (TC-E4016).
      DynamicParallelism*/opencl | DynamicParallelism*/metal)
        bin/tcirviz --json "$f" --backend "$b" | python3 -c 'import json, sys; d = json.load(sys.stdin); sys.exit(0 if any(x["code"] == "TC-E4016" for x in d["diagnostics"]) else 1)' \
          && printf '%-16s %-7s ok (TC-E4016: CUDA only)\n' "$(basename "$f" .java)" "$b" \
          || { printf '%-16s %-7s FAILED: no TC-E4016\n' "$(basename "$f" .java)" "$b"; fail=1; } ;;
      # FP8 tiles need compute capability 9.0 (checked on 8.9 below).
      TileFP8.java/cuda) bin/tcirviz --json "$f" --backend "$b" --target computeCapability=90 | python3 tools/check-trace.py "$(basename "$f" .java)" "$b" || fail=1 ;;
      *) bin/tcirviz --json "$f" --backend "$b" | python3 tools/check-trace.py "$(basename "$f" .java)" "$b" || fail=1 ;;
    esac
  done
done
# A private array allocated in a loop is zeroed where its `new` runs (structured IR `zero <name>;`).
for b in cuda opencl metal; do
  n=$(bin/tcirviz --json src/main/resources/tcirviz/examples/PrivateArrays.java --backend "$b" | grep -oE 'zero (best|counts);' | sort -u | wc -l)
  [ "$n" -eq 2 ] && printf '%-16s %-7s ok (zero best, zero counts)\n' PrivateArrays "$b" \
    || { printf '%-16s %-7s FAILED: expected zero best/counts in the structured IR\n' PrivateArrays "$b"; fail=1; }
done
# A tile that may cross the edge of its tensor is loaded/stored masked unless every extent is a constant
# the tile shape divides (--arg fixes rows and columns as the UI's Arguments do).
masked() { bin/tcirviz --json src/main/resources/tcirviz/examples/TileRowSums.java --backend cuda "$@" \
  | python3 -c 'import json, sys; d = json.load(sys.stdin); print(json.dumps(d["functions"][0]["snapshots"][-1]).count("_masked"))'; }
for c in ":2" "rows=64 columns=100:1" "rows=64 columns=256:0"; do
  args=${c%:*}; want=${c##*:}; flags=""; for a in $args; do flags="$flags --arg $a"; done
  got=$(masked $flags)
  [ "$got" = "$want" ] && printf '%-16s %-7s ok (%s masked accesses with %s)\n' TileRowSums cuda "$want" "${args:-no arguments}" \
    || { printf '%-16s %-7s FAILED: %s masked accesses with %s, expected %s\n' TileRowSums cuda "$got" "${args:-no arguments}" "$want"; fail=1; }
done
# readsLengths: MatrixMultiply takes its sizes as arguments (one kernel for every array size); VectorAdd reads c.getSize().
for c in "MatrixMultiply:False" "VectorAdd:True"; do
  got=$(bin/tcirviz --json "src/main/resources/tcirviz/examples/${c%%:*}.java" --backend cuda | python3 -c 'import json, sys; print(json.load(sys.stdin)["readsLengths"])')
  [ "$got" = "${c##*:}" ] && printf '%-16s %-7s ok (readsLengths %s)\n' "${c%%:*}" cuda "$got" \
    || { printf '%-16s %-7s FAILED: readsLengths %s, expected %s\n' "${c%%:*}" cuda "$got" "${c##*:}"; fail=1; }
done
# KernelContext.launch: the parent's CUDA launches the child, which is compiled as a kernel of its own.
bin/tcirviz --json src/main/resources/tcirviz/examples/DynamicParallelism.java --backend cuda | python3 -c '
import json, sys
d = json.load(sys.stdin)
names = [f["name"] for f in d["functions"]]
parent = json.dumps(d["functions"][0]["snapshots"][-1])
ok = d["ok"] and d["method"] == "scaleRows" and "DynamicParallelism.scaleRow_dp" in names and "scaleRow_dp<<<_dp_grid, _dp_block>>>" in parent and "tc_device_launch_check" in parent
print("%-16s %-7s %s" % ("DynamicParallelism", "cuda", "ok (scaleRows launches scaleRow_dp<<<>>>)" if ok else "FAILED: " + str(names)))
sys.exit(0 if ok else 1)' || fail=1
# FP8 tiles: refused below compute capability 9.0 (TC-E4011); on 9.0 every operation widens to float and
# rounds back with element_cast, the scale factor included, and the compare is done in float.
fp8() { bin/tcirviz --json src/main/resources/tcirviz/examples/TileFP8.java --backend cuda "$@"; }
fp8 | python3 -c 'import json, sys; d = json.load(sys.stdin); sys.exit(0 if not d["ok"] and any(x["code"] == "TC-E4011" and "9.0" in x["message"] for x in d["diagnostics"]) else 1)' \
  && printf '%-16s %-7s ok (sm_89: TC-E4011, needs 9.0)\n' TileFP8 cuda || { printf '%-16s %-7s FAILED: no TC-E4011 on sm_89\n' TileFP8 cuda; fail=1; }
fp8 --target computeCapability=90 | python3 -c '
import json, sys
d = json.load(sys.stdin)
cuda = json.dumps(d["functions"][0]["snapshots"][-1]) if d["ok"] else ""
ok = "ct::element_cast<__nv_fp8_e4m3>(ct::full<" in cuda and "ct::element_cast<float>(x) > (float) 0.3" in cuda
print("%-16s %-7s %s" % ("TileFP8", "cuda", "ok (sm_90: FP8 through element_cast, scalar factor too)" if ok else "FAILED on sm_90: " + str([x["code"] for x in d.get("diagnostics", [])])))
sys.exit(0 if ok else 1)' || fail=1
# Every stage and pass the examples' traces show has an explanation in docs.js (a new TC stage or pass
# would otherwise appear as a bare name).
python3 - <<'PY' || fail=1
import json, re, subprocess, glob, sys
docs = open('src/main/resources/tcirviz/web/docs.js').read()
def keys(name):
    block = docs[docs.index('export const ' + name):]
    block = block[:block.index('\n};')]
    return set(re.findall(r"^  '?([\w.\-]+)'?:", block, re.M))
stages, passes = keys('STAGES'), keys('PASSES')
seen_stages, seen_passes = set(), set()
for f in sorted(glob.glob('src/main/resources/tcirviz/examples/*.java')):
    extra = ['--target', 'computeCapability=90'] if 'FP8' in f else []
    extra += ['--method', 'scaleRows'] if 'DynamicParallelism' in f else []
    for b in ['cuda', 'opencl', 'metal']:
        d = json.loads(subprocess.run(['bin/tcirviz', '--json', f, '--backend', b] + extra, capture_output=True, text=True).stdout)
        for fn in d.get('functions', []):
            for s in fn['snapshots']:
                if s.get('pass'):
                    seen_passes.add(s['pass'])
                elif s.get('stage'):
                    seen_stages.add(s['stage'])
missing = sorted(seen_stages - stages) + sorted(seen_passes - passes)
if missing:
    print('Docs FAILED: no explanation in docs.js for', missing); sys.exit(1)
print('Docs             all     ok (%d stages, %d passes explained)' % (len(seen_stages), len(seen_passes)))
PY
# The same compile through the SDK's argfile (java @tornado-argfile: the SDK's jars on the module path).
if [ -f "$TORNADOVM_HOME/tornado-argfile" ]; then
  "${JAVA_HOME:+$JAVA_HOME/bin/}java" @"$TORNADOVM_HOME/tornado-argfile" -cp build/tcirviz.jar io.github.mikepapadim.tcirviz.TcIrViz \
    --json src/main/resources/tcirviz/examples/Saxpy.java --backend opencl | python3 tools/check-trace.py "Saxpy (argfile)" opencl || fail=1
fi
if [ -z "${CHROME:-}" ]; then
  for c in google-chrome chromium "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"; do
    if command -v "$c" >/dev/null 2>&1 || [ -x "$c" ]; then CHROME="$c"; break; fi
  done
fi
export CHROME
if [ -n "${CHROME:-}" ] && command -v node >/dev/null; then
  port=$((7900 + RANDOM % 90))
  bin/tcirviz --no-open --port "$port" > build/server.log 2>&1 &
  server=$!
  trap 'kill $server 2>/dev/null' EXIT
  for _ in $(seq 50); do curl -sf "http://127.0.0.1:$port/api/info" >/dev/null && break; sleep 0.2; done
  node tools/ui-check.mjs "http://127.0.0.1:$port/" build/ui-check > build/ui-check.json
  python3 - <<'PY' || fail=1
import json, sys
r = json.load(open('build/ui-check.json'))
problems = [k for k in ('chips', 'javaLineSteps', 'valueSteps', 'defHighlights', 'diffAdded') if not r.get(k)]
if 'only on CUDA' not in r.get('tileNotice', '') or not r.get('tileMetalMarked') or 'CUDA' not in r.get('tileStatus', '') or r.get('tileContextInput', True):
    problems.append('tile')
if 'compute capability 9.0' not in r.get('fp8Notice', '') or not r.get('fp8Status', '').startswith('scaleAndCompare') or not r.get('fp8Casts'):
    problems.append('fp8')
if not r.get('importStatus', '').startswith('scale'):
    problems.append('import')
if 'array size' not in r.get('status', '') and 'array lengths' not in r.get('status', ''):
    problems.append('array-size status')
if r['errors'] or problems or not r['openclHasKernel'] or 'Metal' not in r['metalStatus'] or 'TC-E' in r['metalStatus']:
    print('UI check FAILED:', r['errors'], problems); sys.exit(1)
print('UI check ok: %d chips, journey %d steps, no console errors' % (r['chips'], r['valueSteps']))
PY
fi
exit $fail
