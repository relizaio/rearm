#!/usr/bin/env python3
"""TPF-269: checks REBOM_CHILD_ENV_PASSTHROUGH of the rebom pod in a rendered chart.

Usage: helm template ... | check_rebom_child_env_passthrough.py EXPECTED [FILE]

Reads helm template output (FILE or stdin), finds the Deployment whose name
ends in -rebom-backend and requires: its containers are
rebom-backend-container only, no env name of that container is repeated, and
no REBOM_CHILD_ENV_PASSTHROUGH on an init container. EXPECTED is the expected
value (for example GODEBUG,TZ) or the word absent. With absent, no env entry
of the container is named REBOM_CHILD_ENV_PASSTHROUGH; otherwise exactly one
is, with the keys name and value, whose value is a string equal to EXPECTED.
Prints OK or the failures; exit 1 on any failure. Needs PyYAML.
"""
import sys
import yaml

NAME = 'REBOM_CHILD_ENV_PASSTHROUGH'

if len(sys.argv) not in (2, 3):
    print('usage: check_rebom_child_env_passthrough.py EXPECTED [FILE]')
    sys.exit(2)
expected = sys.argv[1]
src = open(sys.argv[2]) if len(sys.argv) > 2 else sys.stdin
docs = [d for d in yaml.safe_load_all(src) if d]

failures = 0


def fail(msg):
    global failures
    failures += 1
    print('FAIL', msg)


deps = [d for d in docs
        if d.get('kind') == 'Deployment' and d['metadata']['name'].endswith('-rebom-backend')]
if len(deps) != 1:
    fail('expected one Deployment named *-rebom-backend, found %d' % len(deps))
else:
    pod = deps[0]['spec']['template']['spec']
    containers = pod.get('containers') or []
    names = [c.get('name') for c in containers]
    if names != ['rebom-backend-container']:
        fail('containers %s, expected [rebom-backend-container]' % names)
    for c in containers:
        env = c.get('env') or []
        env_names = [e.get('name') for e in env]
        repeated = sorted({n for n in env_names if env_names.count(n) > 1})
        if repeated:
            fail('%s: env names repeated: %s' % (c.get('name'), repeated))
        if c.get('name') != 'rebom-backend-container':
            continue
        entries = [e for e in env if e.get('name') == NAME]
        if expected == 'absent':
            if entries:
                fail('%s: %d %s entries, expected none' % (c.get('name'), len(entries), NAME))
            continue
        if len(entries) != 1:
            fail('%s: %d %s entries, expected 1' % (c.get('name'), len(entries), NAME))
            continue
        entry = entries[0]
        if sorted(entry.keys()) != ['name', 'value']:
            fail('%s: %s entry has keys %s, expected [name, value]' % (c.get('name'), NAME, sorted(entry.keys())))
        value = entry.get('value')
        if not isinstance(value, str):
            fail('%s: %s value %r is not a string' % (c.get('name'), NAME, value))
        elif value != expected:
            fail('%s: %s is %r, expected %r' % (c.get('name'), NAME, value, expected))
    for c in pod.get('initContainers') or []:
        if any(e.get('name') == NAME for e in c.get('env') or []):
            fail('init container %s carries %s' % (c.get('name'), NAME))

if failures:
    sys.exit(1)
print('OK')
