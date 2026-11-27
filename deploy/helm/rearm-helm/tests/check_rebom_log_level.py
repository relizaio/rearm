#!/usr/bin/env python3
"""TPF-253: checks LOG_LEVEL of the rebom container in a rendered chart.

Usage: helm template ... | check_rebom_log_level.py EXPECTED [FILE]

EXPECTED is a level (the string LOG_LEVEL must carry) or the word absent.
Reads helm template output (FILE or stdin) and requires exactly one rendered
container named rebom-backend-container. With a level, that container has
exactly one env entry LOG_LEVEL, with the keys name and value, whose value is
a string equal to EXPECTED. With absent, it has no LOG_LEVEL entry. Other
containers are not looked at (the artifact service has a LOG_LEVEL of its
own). Prints OK or the failures; exit 1 on any failure. Needs PyYAML.
"""
import sys
import yaml

if len(sys.argv) not in (2, 3):
    print('usage: check_rebom_log_level.py EXPECTED|absent [FILE]')
    sys.exit(2)
expected = sys.argv[1]
src = open(sys.argv[2]) if len(sys.argv) > 2 else sys.stdin
docs = [d for d in yaml.safe_load_all(src) if d]

failures = 0


def fail(msg):
    global failures
    failures += 1
    print('FAIL', msg)


def pod_spec(doc):
    kind = doc.get('kind')
    if kind in ('Deployment', 'StatefulSet', 'DaemonSet', 'Job'):
        return doc['spec']['template']['spec']
    if kind == 'CronJob':
        return doc['spec']['jobTemplate']['spec']['template']['spec']
    return None


rebom = []
for d in docs:
    pod = pod_spec(d)
    if not pod:
        continue
    for c in (pod.get('containers') or []) + (pod.get('initContainers') or []):
        if c.get('name') == 'rebom-backend-container':
            rebom.append(c)

if len(rebom) != 1:
    fail('expected one container named rebom-backend-container, found %d' % len(rebom))
else:
    entries = [e for e in rebom[0].get('env') or [] if e.get('name') == 'LOG_LEVEL']
    if expected == 'absent':
        if entries:
            fail('rebom-backend-container: %d LOG_LEVEL entries, expected none' % len(entries))
    elif len(entries) != 1:
        fail('rebom-backend-container: %d LOG_LEVEL entries, expected 1' % len(entries))
    else:
        entry = entries[0]
        if sorted(entry.keys()) != ['name', 'value']:
            fail('rebom-backend-container: LOG_LEVEL entry has keys %s, expected [name, value]' % sorted(entry.keys()))
        value = entry.get('value')
        if not isinstance(value, str):
            fail('rebom-backend-container: LOG_LEVEL value %r is not a string' % (value,))
        elif value != expected:
            fail('rebom-backend-container: LOG_LEVEL is %r, expected %r' % (value, expected))

if failures:
    sys.exit(1)
print('OK')
