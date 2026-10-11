#!/usr/bin/env bash
# Headless checks: every example compiles on CUDA and OpenCL with a complete, linked trace; then,
# when Chrome is present, the UI is driven end to end (tools/ui-check.mjs) and must log no errors.
set -euo pipefail
cd "$(dirname "$0")"
./build.sh >/dev/null
fail=0
for f in src/main/resources/tcirviz/examples/*.java; do
  for b in cuda opencl; do
    bin/tcirviz --json "$f" --backend "$b" | python3 tools/check-trace.py "$(basename "$f" .java)" "$b" || fail=1
  done
done
if command -v google-chrome >/dev/null && command -v node >/dev/null; then
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
if r['errors'] or problems or not r['openclHasKernel'] or 'TC-E4001' not in r['metalStatus']:
    print('UI check FAILED:', r['errors'], problems); sys.exit(1)
print('UI check ok: %d chips, journey %d steps, no console errors' % (r['chips'], r['valueSteps']))
PY
fi
exit $fail
