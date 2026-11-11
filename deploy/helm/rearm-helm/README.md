# ReARM Helm chart

Every option of this chart is documented in `values.yaml`, next to the value. This file holds what needs more room than a comment.

## Network policies

The chart renders Kubernetes NetworkPolicies from the `networkPolicy` block of `values.yaml` (template `templates/networkpolicy.yaml`).

- **Ingress policies, on by default** (`networkPolicy.enabled`): every chart pod accepts connections only from the pods that need it. See the comment above `networkPolicy` in `values.yaml`.
- **One egress policy, for the rebom backend pod, off by default** (`networkPolicy.rebomBackend.egress.enabled`): described below. No other pod's outbound traffic is restricted.

NetworkPolicies are enforced only by a CNI that implements them (Calico, Cilium, Antrea, kube-router as in k3s, and others). A cluster without one accepts the objects and silently ignores them, so `kubectl get networkpolicy` listing a policy proves nothing: run the checks.

### Egress policy of the rebom pod

**What it is for.** rebom is the pod that fetches from other servers on behalf of ReARM: TEA servers and the hosts their answers point at, and the enrichment endpoint. A hostile or compromised server can answer with addresses inside your network (in a discovery answer, a redirect or an artifact link). With the policy on, the pod cannot open a connection to such an address unless you allowed it.

**What the pod may connect to when it is on.**

| Destination | Ports | Set by |
|---|---|---|
| DNS, any destination | 53 UDP and TCP | `egress.dns.to`, `egress.dns.ports` |
| The chart's rebom postgres pod | 5432 TCP | rendered unless `use_external_postgres` |
| The chart's artifact service pod | 8083 TCP (the Service's port 80) | rendered when `ociArtifactService.enabled` |
| Public IPv4 addresses: everything except `0.0.0.0/8`, `10.0.0.0/8`, `100.64.0.0/10`, `168.63.129.16/32`, `169.254.0.0/16`, `172.16.0.0/12`, `192.0.0.0/24`, `192.168.0.0/16`, `198.18.0.0/15`, `240.0.0.0/4` | every port | `egress.public.ipFamilies`, `egress.public.ports`, `egress.public.extraExcept` |
| Public IPv6 addresses, only if you add `IPv6` to `egress.public.ipFamilies` (see "IPv6" below): `2000::/3` except `2001::/32`, `2001:db8::/32`, `2002::/16`, `3fff::/20` | every port | the same |
| What you add | what you add | `egress.extraEgress` |

Everything else is refused, because it is not allowed: private ranges, carrier-grade NAT, link-local addresses (the cloud metadata address `169.254.169.254` among them), every IPv6 address by default (with IPv6 added: still unique-local `fc00::/7`, link-local `fe80::/10` and everything else outside `2000::/3`), and every other pod of the cluster, in any namespace.

**What it does not do.**

- It covers the whole pod, not only TEA fetches: the database connection, the artifact service, enrichment and the init containers go through the same rules.
- It knows address ranges, not host names. A name that resolves to a refused address is refused; a name cannot be allowed by name.
- It cannot tell an internal IPv6 address from a public one. IPv4 has private ranges and the policy refuses them; the IPv6 addresses of a cluster and of the network around it are normally global unicast, like every public host. So IPv6 is off by default, and when you add it, the ranges to refuse are yours to list ("IPv6" below).
- It does nothing on a cluster whose CNI does not enforce NetworkPolicy, and nothing under Docker Compose.
- Loopback (`127.0.0.0/8`, `::1`) never leaves the pod, and the pod can always reach itself on its own address: no NetworkPolicy stands in the way of either. For fetches from TEA servers, the rearm CLI that rebom runs refuses both itself, together with link-local and unspecified addresses and a fixed list of cloud metadata addresses.
- NetworkPolicies add up: another policy that selects the rebom pod and allows more egress (one of your own, a namespace-wide allow-all) widens this one.
- The node the pod runs on may stay reachable: Kubernetes lets a network plugin always allow traffic between a pod and its own node. Where the cloud metadata service is answered on the node itself, or a pod runs on the host network, a NetworkPolicy does not stand in the way.
- A public address that forwards to an internal service is a public address to the policy: a reverse proxy, or an egress proxy the pod is configured to use (then the policy sees only the proxy).
- A refused connection is not an error message from ReARM. It shows as a connect timeout on most CNIs (Calico, Cilium) and as an immediate "connection refused" or "host unreachable" on others (kube-router).

**Before you enable it.** Add a rule to `networkPolicy.rebomBackend.egress.extraEgress` for each of these that applies to your install; without it the pod loses that connection:

1. `use_external_postgres: true`: the address of `rebom_postgres_host`, public or private, on ports 5432 (the migration init container always dials 5432) and `rebom_postgres_port`. The chart refuses to render the policy with `use_external_postgres` and no `extraEgress` rule at all.
2. `rebom.backend.oci.serviceHost` pointing at an artifact service outside this release.
3. An enrichment (BEAR) endpoint on a private or in-cluster address. The endpoints are set per organization and stored in the rebom database: `select organization, config->>'uri' from rebom.integrations where config->>'type' = 'BEAR';`
4. A TEA server, an artifact host or a registry on a private address.
5. An egress proxy the pod uses, or a network that hands out addresses from a private or carrier-grade NAT range for public names (some zero-trust and transparent-proxy products do).

And one value instead of a rule: on an IPv6-only cluster, or if rebom must reach a host that has only IPv6 addresses, add `IPv6` to `egress.public.ipFamilies`, after reading "IPv6" below. With the default, `[IPv4]`, no public IPv6 address is reachable.

Then check the cluster's own IPv4 ranges. The policy assumes pod, service and node addresses are inside the refused ranges above. Look at them (`kubectl get pods -A -o wide`, `kubectl get svc -A`, `kubectl get nodes -o wide`); the ranges themselves come from `kubectl get nodes -o jsonpath='{.items[*].spec.podCIDRs}'`, `kubectl get servicecidrs` (Kubernetes 1.33 and later) or your provider's console. If any of them is a public-looking range, list it in `networkPolicy.rebomBackend.egress.public.extraExcept`: on many CNIs the public rule would otherwise let rebom reach every pod. `-o wide` shows one address per pod and node, the primary family only; the two range commands show both families.

```yaml
networkPolicy:
  rebomBackend:
    egress:
      enabled: true
      public:
        extraExcept: [ 11.0.0.0/8 ]            # only if your pod, service or node range is public-looking
      extraEgress:
        - to: [ { ipBlock: { cidr: 10.20.0.15/32 } } ]      # external rebom postgres
          ports: [ { port: 5432, protocol: TCP } ]
        - to: [ { ipBlock: { cidr: 10.30.4.0/24 } } ]       # an internal TEA server and its artifact host
          ports: [ { port: 443, protocol: TCP } ]
        - to:                                               # an enrichment endpoint in this cluster
            - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: bear } }
              podSelector: { matchLabels: { app: bear } }
          ports: [ { port: 8080, protocol: TCP } ]
```

An `ipBlock` in `extraEgress` is the widest thing in the policy: every address in it becomes reachable on the ports you list, so keep it as narrow as the service it is for. For pods prefer selectors; whether an `ipBlock` matches a pod address depends on the CNI. A rule without `to` is refused by the chart.

**IPv6: off until you add it, and what to list first.** With the default, `egress.public.ipFamilies: [IPv4]`, no IPv6 address is reachable from the pod, apart from DNS on port 53, the chart's two pods and your `extraEgress` rules. `[IPv4, IPv6]`, or `[IPv6]` on an IPv6-only cluster, adds public IPv6 addresses: `2000::/3`, which is all of global unicast. Before you set it:

- **List your own IPv6 ranges in `egress.public.extraExcept`. This is not optional.** IPv6 has no private ranges that the chart could refuse for you. The pod, service and node ranges of a dual-stack or IPv6 cluster, and the other hosts of your network or cloud VPC, normally have global unicast addresses, inside `2000::/3`, exactly like public hosts. Whatever you do not list, rebom can reach. List every IPv6 range of the cluster (`kubectl get nodes -o jsonpath='{.items[*].spec.podCIDRs}'` and `kubectl get servicecidrs` print both families; node addresses: `kubectl get nodes -o custom-columns=NAME:.metadata.name,ADDRESSES:.status.addresses[*].address`) and every IPv6 range of your network that the internet must not reach through rebom. The check below finds a missing pod range. It cannot find a missing service, node or network range.
- **Refused in any case**, also with IPv6 on: everything outside `2000::/3` (unique-local, link-local and IPv4-mapped addresses among them); 6to4 (`2002::/16`) and Teredo (`2001::/32`), whose addresses carry an IPv4 address; and the documentation prefixes (`2001:db8::/32`, `3fff::/20`), which are never public hosts and which example cluster configurations use for pod and service ranges.
- **NAT64: an IPv6 address that carries an IPv4 address.** On a network that reaches IPv4 hosts through a NAT64 translator, an address under the translator's prefix is delivered to the IPv4 address embedded in it: under a /96 prefix, `<prefix>::a00:5` is `10.0.0.5` and `<prefix>::a9fe:a9fe` is `169.254.169.254`. The packet is IPv6, so the IPv4 exceptions of this policy never see it, and whether the translator refuses private IPv4 addresses itself depends on the translator.
  - A prefix of your own, inside `2000::/3`: the public rule allows it as soon as IPv6 is on. List the refused IPv4 ranges, in their embedded form under your prefix, in `extraExcept`.
  - The well-known prefix `64:ff9b::/96` (and the local-use prefix `64:ff9b:1::/48`): outside `2000::/3`, so refused unless you open it. If rebom must reach IPv4 hosts through it, open it in `extraEgress` as an `ipBlock` with the same embedded forms as its `except`. Never open it bare: a bare `64:ff9b::/96` makes every IPv4 address reachable, the private ranges and the metadata address included.

In this example `2001:db8:...` stands for your own ranges (the chart refuses `2001:db8::/32` itself), and the NAT64 prefix of the network is `2001:db8:64::/96`:

```yaml
networkPolicy:
  rebomBackend:
    egress:
      enabled: true
      public:
        ipFamilies: [ IPv4, IPv6 ]
        extraExcept:
          - "2001:db8:42::/56"            # IPv6 pod range of the cluster
          - "2001:db8:43::/112"           # IPv6 service range of the cluster
          - "2001:db8:1000::/48"          # IPv6 range of your network or VPC
          # a NAT64 prefix of your own, here 2001:db8:64::/96: the refused IPv4 ranges under it
          - "2001:db8:64::/104"           # 0.0.0.0/8
          - "2001:db8:64::a00:0/104"      # 10.0.0.0/8
          - "2001:db8:64::6440:0/106"     # 100.64.0.0/10
          - "2001:db8:64::7f00:0/104"     # 127.0.0.0/8
          - "2001:db8:64::a83f:8110/128"  # 168.63.129.16/32
          - "2001:db8:64::a9fe:0/112"     # 169.254.0.0/16
          - "2001:db8:64::ac10:0/108"     # 172.16.0.0/12
          - "2001:db8:64::c000:0/120"     # 192.0.0.0/24
          - "2001:db8:64::c0a8:0/112"     # 192.168.0.0/16
          - "2001:db8:64::c612:0/111"     # 198.18.0.0/15
          - "2001:db8:64::f000:0/100"     # 240.0.0.0/4
      extraEgress:
        # the well-known NAT64 prefix, only if rebom needs it: never without the except list
        - to:
            - ipBlock:
                cidr: "64:ff9b::/96"
                except:
                  - "64:ff9b::/104"           # 0.0.0.0/8
                  - "64:ff9b::a00:0/104"      # 10.0.0.0/8
                  - "64:ff9b::6440:0/106"     # 100.64.0.0/10
                  - "64:ff9b::7f00:0/104"     # 127.0.0.0/8
                  - "64:ff9b::a83f:8110/128"  # 168.63.129.16/32
                  - "64:ff9b::a9fe:0/112"     # 169.254.0.0/16
                  - "64:ff9b::ac10:0/108"     # 172.16.0.0/12
                  - "64:ff9b::c000:0/120"     # 192.0.0.0/24
                  - "64:ff9b::c0a8:0/112"     # 192.168.0.0/16
                  - "64:ff9b::c612:0/111"     # 198.18.0.0/15
                  - "64:ff9b::f000:0/100"     # 240.0.0.0/4
```

How an embedded form is made, for a /96 prefix: the IPv4 range `a.b.c.d/n` becomes the prefix followed by the four bytes in hexadecimal as two groups, with the length `96 + n`. `10.0.0.0/8` is `0a00:0000`, so `<prefix>::a00:0/104`. Add the form of every IPv4 range you put in `extraExcept` yourself. `127.0.0.0/8` is in the lists because behind a translator it is the translator's own loopback. With a prefix of another length (/32, /40, /48, /56, /64) the IPv4 bits sit elsewhere in the address and skip bits 64 to 71; RFC 6052, section 2.2, has the layout.

**Check that it is enforced.** `helm` prints these commands, filled in, after every install and upgrade with the policy on (`helm get notes <r> -n <ns>` prints them again). `<ns>` is the namespace, `<r>` the release name, `<image>` a busybox image your cluster can pull.

<!-- These command lines are the ones templates/NOTES.txt prints, with <ns>, <r> and <image> filled in. This file is the source: a change to one is a change to both. -->

```bash
echo '{"apiVersion":"networking.k8s.io/v1","kind":"NetworkPolicy","metadata":{"name":"np-egress-target"},"spec":{"podSelector":{"matchLabels":{"run":"np-egress-target"}},"policyTypes":["Ingress"],"ingress":[{"ports":[{"port":8080,"protocol":"TCP"}]}]}}' | kubectl -n <ns> apply -f -
kubectl -n <ns> run np-egress-target --restart=Never --image=<image> -- httpd -f -p 8080 -h /tmp
kubectl -n <ns> wait --for=condition=Ready pod/np-egress-target
n=0; for T in $(kubectl -n <ns> get pod np-egress-target -o jsonpath='{.status.podIPs[*].ip}'); do n=$((n+1)); kubectl -n <ns> run np-egress-probe-$n --rm -i --restart=Never --image=<image> -- nc -z -w 5 "$T" 8080; echo "control $T exit=$?"; done
for T in $(kubectl -n <ns> get pod np-egress-target -o jsonpath='{.status.podIPs[*].ip}'); do kubectl -n <ns> exec deploy/<r>-rebom-backend -- nc -z -w 5 "$T" 8080; echo "private $T exit=$?"; done
kubectl -n <ns> exec deploy/<r>-rebom-backend -- nc -z -w 5 <r>-rebom-postgres 5432; echo database exit=$?
kubectl -n <ns> exec deploy/<r>-rebom-backend -- nc -z -w 5 registry.relizahub.com 443; echo public exit=$?
kubectl -n <ns> delete pod/np-egress-target networkpolicy/np-egress-target
```

What they do, line by line:

- Line 1: a temporary NetworkPolicy that admits every pod to port 8080 of the listener. NetworkPolicies add up, so this lifts a default-deny of the namespace (`networkPolicy.namespaceDefaultDeny: true`, or one of your own) for the listener and for nothing else. Without it such a namespace refuses the listener to everybody, and a refused connection would say nothing about egress.
- Lines 2 and 3: the listener, a pod that serves an empty directory on port 8080. Its addresses are pod addresses, outside everything the egress policy allows. It is not the node and not on the host network, so it may sit on the same node as rebom.
- Line 4, `control`: for each address of the listener, a throwaway pod that the egress policy does not select dials it. This shows whether the listener can be reached at all on that address.
- Line 5, `private`: for each address of the listener, the rebom pod dials it.
- Lines 6 and 7, `database` and `public`: the rebom pod dials its database by name and a public host. `helm` leaves the database line out with `use_external_postgres`.
- Line 8 removes the listener and the temporary policy.

Lines 4 and 5 print one result per address of the listener, with the address in it: one address on a single-stack cluster, an IPv4 and an IPv6 address on a dual-stack cluster. A pod has both there, and the policy treats the two families by different rules, so both are probed. Read `control` and `private` of the same address as a pair:

| `control` | `private` | Meaning |
|---|---|---|
| `exit=0` | `exit=1` | **Enforced for this address family.** A plain pod reaches the listener and the rebom pod does not: egress from the rebom pod is filtered. The policy is enforced only if **every** address shows this pair. |
| `exit=0` | `exit=0` | **Not enforced, or allowed.** Your CNI does not enforce NetworkPolicy egress for this family; or the pod range of this family is missing from `public.extraExcept` (IPv4: a public-looking range; IPv6: with IPv6 added, any pod range you did not list); or a rule of `extraEgress`, or another policy that selects the rebom pod, allows the pod range. |
| `exit=1` | `exit=1` | **Proves nothing for this address family.** The listener cannot be reached from anywhere on this address, so rebom's refusal says nothing about egress. Repeat line 4 after a few seconds (a new pod may wait for its rules). If it stays `exit=1`: the listener is not running (`kubectl -n <ns> get pod np-egress-target`), the probe pod was not admitted or could not pull its image, a policy outside NetworkPolicy refuses the listener (a cluster-wide policy of Calico or Cilium, an AdminNetworkPolicy), or an egress policy of your own selects the probe pod. Do not read `private exit=1` as enforcement until `control` prints `exit=0`. |
| `exit=1` | `exit=0` | **Not enforced, or allowed**, as the second row: the rebom pod reached a pod address it must not reach. That the control failed only says the probe pod is restricted by something of yours. |

To see that it is this policy that filters and not another one of yours, turn it off (`helm upgrade` with `--set networkPolicy.rebomBackend.egress.enabled=false`), repeat line 5, which must now print `exit=0` for every address, and turn it on again; no pod restarts.

The check probes a pod address. It says nothing about the other ranges you had to list for IPv6 (services, nodes, your network, a NAT64 prefix): for those, dial an address of each range from the rebom pod the same way (`kubectl -n <ns> exec deploy/<r>-rebom-backend -- nc -z -w 5 <address> <port>; echo exit=$?`) and expect `exit=1`.

`database exit=0` and `public exit=0` show that DNS, the database and a public address work, and that `nc` runs in the rebom container: if neither prints `exit=0`, `private exit=1` proves nothing either. `database exit=1`: see the table below. `public exit=1` is expected when the policy has no public IPv4 rule on port 443 (`public.ipFamilies` without `IPv4`, or `public.ports` without 443; the probe host has IPv4 addresses only) and on a cluster with no direct route to the internet; to tell, run the same `nc` from a plain pod (`kubectl -n <ns> run np-egress-probe-0 --rm -i --restart=Never --image=<image> -- nc -z -w 5 registry.relizahub.com 443; echo exit=$?`).

**If something stops working after you enable it.**

| Symptom | Cause | Fix |
|---|---|---|
| rebom logs `getaddrinfo EAI_AGAIN <name>`; `nslookup <r>-rebom-postgres` in the pod fails | the DNS pods do not listen on port 53 (OpenShift: 5353), or `dns.to` is too narrow | `egress.dns.ports`, `egress.dns.to` |
| the rebom pod does not start: the `rebom-backend-migrations` init container fails to connect | an external database that is not in `extraEgress` | item 1 above |
| enrichment fails for an organization | its enrichment endpoint is on a private or in-cluster address | item 3 above |
| fetching from a TEA server fails with a connect timeout (or an immediate refusal) | the server, a host it redirects to, or an artifact host is on a refused address | item 4 above, if you trust that address |
| every public destination fails | an egress proxy, or public names resolving to private or carrier-grade NAT addresses | item 5 above |
| a host that has only IPv6 addresses cannot be reached, or every public destination fails on an IPv6-only cluster | IPv6 is not in `egress.public.ipFamilies` (the default) | "IPv6" above |

To turn it off: `networkPolicy.rebomBackend.egress.enabled: false`. No pod restarts when it is turned on or off.

**Upgrading.** An upgrade to a chart version that has this policy changes nothing: it is off until you set `networkPolicy.rebomBackend.egress.enabled: true`. It needs `networkPolicy.enabled: true`; the chart refuses to render with the egress policy on and `networkPolicy.enabled` off, so to switch every policy off set both to `false`.
