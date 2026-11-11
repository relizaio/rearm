#!/usr/bin/env python3
"""TPF-90, TPF-179: checks a rendered chart (helm template output, file argument or stdin).

Every NetworkPolicy is ingress-only, except <release>-rebom-backend-egress,
which is egress-only (TPF-179): an ipBlock of it that admits a public
sample address admits none of the non-public samples below, and every rule
names peers or ports. Each podSelector selects exactly one pod
template (the namespace default-deny and the Pro backups policy excepted: the
backups policy selects only CronJob pods, at least one); every pod-selector
peer in its rules matches a rendered pod template. Exit 1 on any failure.
Needs PyYAML.
"""
import ipaddress
import sys
import yaml

# Sample addresses: a block that admits one of PUBLIC is a public block and
# must admit none of NON_PUBLIC. The 64:ff9b:: samples are IPv4 addresses
# behind the well-known NAT64 prefix (1.1.1.1, and 10.0.0.1, 169.254.169.254,
# 192.168.0.1): a block that opens that prefix must except the refused IPv4
# ranges in their embedded form. 2001:0:..., 2001:db8::, 2002:... and 3fff::
# are Teredo, documentation and 6to4 space inside 2000::/3.
NON_PUBLIC = ['0.0.0.1', '10.0.0.1', '100.64.0.1', '168.63.129.16', '169.254.20.10', '169.254.169.254',
              '172.16.0.1', '192.0.0.1', '192.168.0.1', '198.18.0.1', '240.0.0.1',
              '64:ff9b::a00:1', '64:ff9b::a9fe:a9fe', '64:ff9b::c0a8:1',
              '2001:0:a00:1::1', '2001:db8::1', '2002:a00:1::1', '3fff::1',
              'fd00::1', 'fd00:ec2::254', 'fe80::1', 'ff02::1']
PUBLIC = ['1.1.1.1', '8.8.8.8', '2606:4700:4700::1111', '64:ff9b::101:101']

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


def admits(block, addr):
    a, net = ipaddress.ip_address(addr), ipaddress.ip_network(block['cidr'])
    if a.version != net.version or a not in net:
        return False
    return not any(a in ipaddress.ip_network(e) for e in block.get('except') or [])


failures = 0


def fail(msg):
    global failures
    failures += 1
    print('FAIL', msg)


print('policies: %d: %s' % (len(pols), ', '.join(p['metadata']['name'] for p in pols)))
for p in pols:
    name, spec = p['metadata']['name'], p['spec']
    egress = name.endswith('-rebom-backend-egress')
    want = ['Egress'] if egress else ['Ingress']
    if spec.get('policyTypes') != want:
        fail('%s: policyTypes %s, expected %s' % (name, spec.get('policyTypes'), want))
    if ('ingress' if egress else 'egress') in spec:
        fail('%s: carries %s rules' % (name, 'ingress' if egress else 'egress'))
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
    public = []
    for i, rule in enumerate(spec.get('egress') or []):
        if not rule.get('to') and not rule.get('ports'):
            fail('%s: egress rule %d has neither to nor ports: it allows everything' % (name, i))
        for peer in rule.get('to') or []:
            ps = peer.get('podSelector')
            if ps and 'namespaceSelector' not in peer and not any(match(ps, l) for _, _, l in pods):
                fail('%s: peer %s matches no rendered pod template' % (name, ps.get('matchLabels')))
            ib = peer.get('ipBlock')
            if not ib:
                continue
            bad = [a for a in NON_PUBLIC if admits(ib, a)]
            if any(admits(ib, a) for a in PUBLIC):
                public.append(ib)
                if bad:
                    fail('%s: public block %s admits %s' % (name, ib['cidr'], bad))
            elif bad:
                print('  note: %s: rule %d ipBlock %s admits non-public %s (not a public block)' % (name, i, ib['cidr'], bad))
    print('  %s: selects %s' % (name, hit))
    if egress:
        print('  %s: %d rules; public blocks %s; public samples admitted: %s' % (
            name, len(spec.get('egress') or []), [b['cidr'] for b in public],
            [a for a in PUBLIC if any(admits(b, a) for b in public)]))
for k, n, l in pods:
    if not any(match(p['spec']['podSelector'], l) for p in pols):
        print('  note: no policy selects %s/%s (labels %s)' % (k, n, l))
print('OK' if not failures else '%d failure(s)' % failures)
sys.exit(1 if failures else 0)
