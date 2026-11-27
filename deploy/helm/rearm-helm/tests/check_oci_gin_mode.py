#!/usr/bin/env python3
"""TPF-252: checks GIN_MODE of the oci-artifact pod in a rendered chart.

Usage: helm template ... | check_oci_gin_mode.py EXPECTED [FILE]

Reads helm template output (FILE or stdin) and requires: among the containers
and init containers of every rendered pod template, exactly one carries an
env entry GIN_MODE; it is the container named rearm-oci-artifact; the entry
occurs once there, has the keys name and value, and its value is a string
equal to EXPECTED. Prints OK or the failures; exit 1 on any failure. Needs
PyYAML.
"""
import sys
import yaml

if len(sys.argv) not in (2, 3):
    print('usage: check_oci_gin_mode.py EXPECTED [FILE]')
    sys.exit(2)
expected = sys.argv[1]
src = open(sys.argv[2]) if len(sys.argv) > 2 else sys.stdin
docs = [d for d in yaml.safe_load_all(src) if d]

failures = 0


def fail(msg):
    global failures
    failures += 1
    print('FAIL', msg)


carriers = []
for d in docs:
    kind = d.get('kind')
    tpl = None
    if kind in ('Deployment', 'StatefulSet', 'DaemonSet', 'Job'):
        tpl = d['spec']['template']
    elif kind == 'CronJob':
        tpl = d['spec']['jobTemplate']['spec']['template']
    if not tpl:
        continue
    pod = tpl.get('spec') or {}
    for c in (pod.get('containers') or []) + (pod.get('initContainers') or []):
        entries = [e for e in c.get('env') or [] if e.get('name') == 'GIN_MODE']
        if entries:
            carriers.append((kind, d['metadata']['name'], c.get('name'), entries))

if len(carriers) != 1:
    fail('expected one container with GIN_MODE, found %d: %s'
         % (len(carriers), ['%s/%s/%s' % (k, n, c) for k, n, c, _ in carriers]))
for kind, name, cname, entries in carriers:
    if cname != 'rearm-oci-artifact':
        fail('%s/%s: container %s carries GIN_MODE, expected rearm-oci-artifact' % (kind, name, cname))
        continue
    if len(entries) != 1:
        fail('%s: %d GIN_MODE entries, expected 1' % (cname, len(entries)))
        continue
    entry = entries[0]
    if sorted(entry.keys()) != ['name', 'value']:
        fail('%s: GIN_MODE entry has keys %s, expected [name, value]' % (cname, sorted(entry.keys())))
    value = entry.get('value')
    if not isinstance(value, str):
        fail('%s: GIN_MODE value %r is not a string' % (cname, value))
    elif value != expected:
        fail('%s: GIN_MODE is %r, expected %r' % (cname, value, expected))

if failures:
    sys.exit(1)
print('OK')
