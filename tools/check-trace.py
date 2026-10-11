#!/usr/bin/env python3
"""Validates one --json trace: it compiled, every stage kind is present, and lines are linked."""
import json
import sys

name, backend = sys.argv[1], sys.argv[2]
j = json.load(sys.stdin)
errors = []
if not j.get('ok'):
    errors.append('not ok: %s %s' % (j.get('diagnostics'), j.get('problems')))
else:
    f = j['functions'][0]
    kinds = [s['kind'] for s in f['snapshots']]
    for k in ('bytecode', 'ir', 'structured', 'source'):
        if k not in kinds:
            errors.append('no %s snapshot' % k)
    src = [s for s in f['snapshots'] if s['kind'] == 'source'][-1]
    linked = [l for l in src['lines'] if l.get('u')]
    if not linked:
        errors.append('no target line links back to the IR')
    ids = {int(k) for k in j['values']}
    for s in f['snapshots']:
        for l in s['lines']:
            for v in l.get('d', []) + l.get('u', []):
                if v not in ids:
                    errors.append('%s/%s: value %d has no facts' % (s['stage'], s['pass'], v))
                    break
    bc = [s for s in f['snapshots'] if s['kind'] == 'bytecode'][0]
    if not any(l.get('b') is not None and l.get('l') for l in bc['lines']):
        errors.append('bytecode lines carry no bci/line')
print('%-16s %-7s %s' % (name, backend, 'ok' if not errors else 'FAILED: ' + '; '.join(errors[:3])))
sys.exit(1 if errors else 0)
