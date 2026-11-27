#!/usr/bin/env python3
"""TPF-251: checks NODE_ENV of the rebom pod in a rendered chart.

Usage: helm template ... | check_rebom_node_env.py EXPECTED [FILE]

Reads helm template output (FILE or stdin), finds the Deployment whose name
ends in -rebom-backend and requires: its containers are
rebom-backend-container only, no env name of that container is repeated,
exactly one NODE_ENV entry with the keys name and value, whose value is a
string equal to EXPECTED, and no NODE_ENV on an init container. Prints OK or
the failures; exit 1 on any failure. Needs PyYAML.
"""
import sys
import yaml

if len(sys.argv) not in (2, 3):
    print('usage: check_rebom_node_env.py EXPECTED [FILE]')
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
        node_env = [e for e in env if e.get('name') == 'NODE_ENV']
        if c.get('name') != 'rebom-backend-container':
            continue
        if len(node_env) != 1:
            fail('%s: %d NODE_ENV entries, expected 1' % (c.get('name'), len(node_env)))
            continue
        entry = node_env[0]
        if sorted(entry.keys()) != ['name', 'value']:
            fail('%s: NODE_ENV entry has keys %s, expected [name, value]' % (c.get('name'), sorted(entry.keys())))
        value = entry.get('value')
        if not isinstance(value, str):
            fail('%s: NODE_ENV value %r is not a string' % (c.get('name'), value))
        elif value != expected:
            fail('%s: NODE_ENV is %r, expected %r' % (c.get('name'), value, expected))
    for c in pod.get('initContainers') or []:
        if any(e.get('name') == 'NODE_ENV' for e in c.get('env') or []):
            fail('init container %s carries NODE_ENV' % c.get('name'))

if failures:
    sys.exit(1)
print('OK')
