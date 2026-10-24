#!/usr/bin/env python3
"""TPF-90: checks a rendered chart (helm template output, file argument or stdin).

Every NetworkPolicy is ingress-only; its podSelector selects exactly one pod
template (the namespace default-deny and the Pro backups policy excepted: the
backups policy selects only CronJob pods, at least one); every pod-selector
peer in its rules matches a rendered pod template. Exit 1 on any failure.
Needs PyYAML.
"""
import sys
import yaml

src = open(sys.argv[1]) if len(sys.argv) > 1 else sys.stdin
docs = [d for d in yaml.safe_load_all(src) if d]
pods, pols = [], []
for d in docs:
    kind = d.get('kind')
    tpl = None
    if kind in ('Deployment', 'StatefulSet', 'DaemonSet', 'Job'):
        tpl = d['spec']['template']
    elif kind == 'CronJob':
        tpl = d['spec']['jobTemplate']['spec']['template']
    if tpl:
        pods.append((kind, d['metadata']['name'], tpl.get('metadata', {}).get('labels') or {}))
    if kind == 'NetworkPolicy':
        pols.append(d)


def match(sel, labels):
    if any(labels.get(k) != v for k, v in (sel.get('matchLabels') or {}).items()):
        return False
    for e in sel.get('matchExpressions') or []:
        if e['operator'] == 'In' and labels.get(e['key']) not in e['values']:
            return False
    return True


failures = 0


def fail(msg):
    global failures
    failures += 1
    print('FAIL', msg)


print('policies: %d: %s' % (len(pols), ', '.join(p['metadata']['name'] for p in pols)))
for p in pols:
    name, spec = p['metadata']['name'], p['spec']
    if spec.get('policyTypes') != ['Ingress']:
        fail('%s: policyTypes %s, expected [Ingress]' % (name, spec.get('policyTypes')))
    hit = ['%s/%s' % (k, n) for k, n, l in pods if match(spec['podSelector'], l)]
    if name.endswith('-namespace-default-deny-ingress'):
        pass
    elif name.endswith('-backups'):
        if not hit or any(not h.startswith('CronJob/') for h in hit):
            fail('%s: selects %s, expected one or more CronJob pods only' % (name, hit))
    elif len(hit) != 1:
        fail('%s: selects %s, expected exactly one pod template' % (name, hit))
    for rule in spec.get('ingress') or []:
        for peer in rule.get('from') or []:
            ps = peer.get('podSelector')
            if ps and 'namespaceSelector' not in peer and not any(match(ps, l) for _, _, l in pods):
                fail('%s: peer %s matches no rendered pod template' % (name, ps.get('matchLabels')))
    print('  %s: selects %s' % (name, hit))
for k, n, l in pods:
    if not any(match(p['spec']['podSelector'], l) for p in pols):
        print('  note: no policy selects %s/%s (labels %s)' % (k, n, l))
print('OK' if not failures else '%d failure(s)' % failures)
sys.exit(1 if failures else 0)
