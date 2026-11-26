# Egress Policy of the rebom Pod

::: tip Available in both ReARM Community Edition and ReARM Pro
:::

rebom is the pod that connects to other servers on behalf of ReARM: to the TEA servers of suppliers and the hosts their answers point at (ReARM Pro), and to the enrichment endpoint. A hostile or compromised server can answer with an address inside your network, in a discovery answer, a redirect or a link to a document. With the egress policy on and enforced, the pod can open a connection to such an address only where a rule of the policy allows it. Out of the box one rule does: the DNS port, 53, is open to every address until you narrow it. "What it does not cover" below has the setting, and the other limits.

Every connection to a TEA server leaves from the rebom pod. The ReARM backend never connects to one, not even to test a connection, so this one policy governs all of them.

The policy is part of the ReARM Helm chart. It is off by default and changes nothing until you turn it on.

It is not the only guard: the ReARM CLI that rebom runs for TEA fetches refuses some addresses on its own, with or without the policy (see [The address refusal of the ReARM CLI](#the-address-refusal-of-the-rearm-cli)).

## What the policy allows

The policy is one NetworkPolicy named `<release>-rebom-backend-egress`. It selects the rebom pod and lists what the pod may connect to; what is not listed is refused.

| Destination | Ports | Set by (under `networkPolicy.rebomBackend.egress`) |
|---|---|---|
| DNS: every address, until `dns.to` names the DNS servers | 53, UDP and TCP | `dns.to`, `dns.ports` |
| The chart's rebom database pod | 5432 | |
| The chart's artifact service pod | 8083 | |
| Public IPv4 addresses | every port | `public.ipFamilies`, `public.ports`, `public.extraExcept` |
| What you add | what you add | `extraEgress` |

"Public" means every IPv4 address except `0.0.0.0/8`, `10.0.0.0/8`, `100.64.0.0/10`, `168.63.129.16/32`, `169.254.0.0/16`, `172.16.0.0/12`, `192.0.0.0/24`, `192.168.0.0/16`, `198.18.0.0/15` and `240.0.0.0/4`. So, on every port that no rule opens, the policy refuses private ranges, carrier-grade NAT, link-local addresses (the cloud metadata address `169.254.169.254` among them) and the other pods of the cluster. The exception: while `dns.to` is empty, the DNS rule opens port 53 on all of these (see [What it does not cover](#what-it-does-not-cover)).

IPv6: with the default `public.ipFamilies: [IPv4]` the public rule allows no IPv6 address. An IPv6 address is then reachable only through the other rules: on port 53 while `dns.to` is empty, the chart's two pods where the cluster gives them IPv6 addresses, and what you add in `extraEgress`. Adding `IPv6` to `public.ipFamilies` needs your own IPv6 ranges listed in `public.extraExcept` first, because IPv6 has no private ranges the chart could refuse for you. The section "IPv6" of the [chart README](https://github.com/relizaio/rearm/blob/main/deploy/helm/rearm-helm/README.md) says how.

The keys, all under `networkPolicy.rebomBackend.egress`:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Turns the policy on. |
| `dns.to` | `[]` | The peers of the DNS rule. Empty: every address on the DNS ports. |
| `dns.ports` | `[53]` | The ports the DNS pods listen on, each opened for UDP and TCP. |
| `public.ipFamilies` | `[IPv4]` | The address families of the public rule. |
| `public.ports` | `[]` | The ports of the public rule. Empty: every port and protocol; when set, the listed ports over TCP only. |
| `public.extraExcept` | `[]` | Ranges taken out of the public rule. |
| `extraEgress` | `[]` | Raw NetworkPolicy egress rules appended to the policy, each with a `to`. |

## Before you turn it on

The policy governs every outbound connection of the pod, not only TEA fetches. Each of these needs a rule in `extraEgress` first, or the pod loses the connection:

- An external rebom database (`use_external_postgres: true`). With an external database and no `extraEgress` rule at all, the chart refuses to render the policy.
- An artifact service outside the release (`rebom.backend.oci.serviceHost`).
- An enrichment endpoint on a private or in-cluster address. It is not allowed automatically.
- A TEA server, an artifact host or a registry on a private address.
- An egress proxy, or a network that hands out private or carrier-grade NAT addresses for public names.

Check the cluster's own ranges. If the pod, service or node range of your cluster is not inside the refused ranges above, list it in `public.extraExcept`: on many network plugins the public rule would otherwise let rebom reach every pod. These two commands print the pod ranges and the services with their addresses:

```bash
kubectl get nodes -o jsonpath='{.items[*].spec.podCIDRs}'
kubectl get svc -A
```

`networkPolicy.enabled` must be `true`, which is the default. With it off and the egress policy on, the chart refuses to render.

## Turning it on

Add this to your values file:

```yaml
networkPolicy:
  rebomBackend:
    egress:
      enabled: true
```

Then upgrade the release:

```bash
helm upgrade --install --create-namespace -n rearm -f rearm-values.yaml rearm oci://registry.relizahub.com/library/rearm
```

No pod restarts when the policy is turned on or off.

The policy is now listed:

```bash
kubectl get networkpolicy -n <namespace>
```

The list holds `<release>-rebom-backend-egress`. A listed policy proves nothing: a cluster whose network plugin does not enforce NetworkPolicy accepts the object and ignores it. Run the check in [Checking that it is enforced](#checking-that-it-is-enforced).

## Allowing an internal TEA server (ReARM Pro)

A supplier's TEA server on a private address is refused until its address range is in `extraEgress`, on every port but the DNS port while `dns.to` is empty (see [What it does not cover](#what-it-does-not-cover)). For a server in `10.30.4.0/24` on port 443:

```yaml
networkPolicy:
  rebomBackend:
    egress:
      enabled: true
      extraEgress:
        - to: [ { ipBlock: { cidr: 10.30.4.0/24 } } ]
          ports: [ { port: 443, protocol: TCP } ]
```

Then run the same `helm upgrade` as above.

Every address of the block becomes reachable on the listed ports, so keep the range as narrow as the server: a `/32` for one address. A server cannot be allowed by its host name; the rules know address ranges only. The hosts that the server's answers point at, an artifact host among them, need a rule too when they are on private addresses.

To see that the rule took effect, dial the address from the rebom pod:

```bash
kubectl -n <namespace> exec deploy/<release>-rebom-backend -- nc -z -w 5 <address> 443; echo exit=$?
```

It prints `exit=0`. In ReARM the **Probe** of the source then reads **Reachable**; the page [Supplier Intake over TEA](/workflows/supplier-intake) describes sources and the probe.

rebom verifies the certificate of a TEA server against the trust store of its image, and this check cannot be switched off. An internal server needs a certificate that this trust store accepts.

An HTTP proxy is not used for TEA fetches: the proxy variables are ignored, so a cluster whose only way out is an HTTP proxy cannot fetch from TEA servers.

## Checking that it is enforced

With the policy on, `helm` prints a check, filled in for your release, after every install and upgrade. To print it again:

```bash
helm get notes <release> -n <namespace>
```

The printed commands start a listener pod in the namespace and dial it twice: once from a throwaway pod that the policy does not select (`control`) and once from the rebom pod (`private`). They then dial the database and a public host from the rebom pod (`database`, `public`), and remove the listener.

Read `control` and `private` of the same address as a pair:

| `control` | `private` | Meaning |
|---|---|---|
| `exit=0` | `exit=1` | Enforced. |
| `exit=0` | `exit=0` | Not enforced, or the pod range is allowed. |
| `exit=1` | `exit=1` | Proves nothing. |

`database exit=0` and `public exit=0` show that what must work still works. On an enforcing cluster the four result lines read:

```
control <address> exit=0
private <address> exit=1
database exit=0
public exit=0
```

To prove that it is this policy that filters and not another one, upgrade with `--set networkPolicy.rebomBackend.egress.enabled=false`, repeat the `private` line, which must now print `exit=0`, and then turn the policy on again with the same `helm upgrade` without the `--set`.

The full commands, the meaning of each line and the fourth row of the table are in the [chart README](https://github.com/relizaio/rearm/blob/main/deploy/helm/rearm-helm/README.md), section "Check that it is enforced". This page does not repeat the commands: `helm get notes` prints them with your release, namespace and image filled in.

## What it does not cover

- **Docker Compose.** Nothing restricts rebom there; the only guard is the address refusal of the ReARM CLI.
- **A cluster whose network plugin does not enforce NetworkPolicy.** The object is accepted and ignored. Only the check tells.
- **The DNS port is open to every address until `dns.to` is set.** With `networkPolicy.rebomBackend.egress.dns.to: []`, the default, the DNS rule names no destination, so port 53, UDP and TCP, is open to every address: private ranges, link-local addresses, IPv6 addresses, the other pods of the cluster and its nodes included. The default is this wide so that names resolve on any cluster without a setting. The address of a TEA server, of a redirect or of a link to a document may name any port, and neither rebom nor the ReARM CLI restricts the port; the CLI does not refuse private addresses either. So with the default, a URL that names port 53 on an internal address is not stopped by the policy. To close it, name the DNS servers of your cluster in `dns.to`:

  ```yaml
  networkPolicy:
    rebomBackend:
      egress:
        enabled: true
        dns:
          to:
            - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } }
              podSelector: { matchLabels: { k8s-app: kube-dns } }
  ```

  Then run the same `helm upgrade`. Port 53 is then open to the DNS pods only, and to public addresses through the public rule like every other port. The labels are an example: `k8s-app: kube-dns` in `kube-system` is what CoreDNS and kube-dns carry on many distributions. Check yours with `kubectl get pods -n kube-system -l k8s-app=kube-dns`, which must list the DNS pods; otherwise use the namespace and labels your cluster has. A cluster with NodeLocal DNSCache also needs the address of the cache as an `ipBlock`: see the comment on `dns.to` in the chart's `values.yaml`. After the upgrade, run the check again (`helm get notes`): `database exit=0` and `public exit=0` show that names still resolve, because both lines dial a name. If names stop resolving, rebom logs `getaddrinfo EAI_AGAIN` and `dns.to` does not match the DNS servers of your cluster. The check dials port 8080 of its listener and never port 53, so it shows that names still resolve, not that port 53 is closed to other addresses.
- **The whole pod.** The policy covers every connection of the rebom pod, not only TEA fetches.
- **Host names.** It knows address ranges, not host names.
- **IPv6.** IPv6 is off by default, and once you add it, your own IPv6 ranges are not refused unless you list them.
- **Loopback and the pod's own address.** These never pass through a NetworkPolicy; for TEA fetches the ReARM CLI refuses both.
- **Other policies.** Policies add up: another policy that selects the rebom pod and allows more widens this one.
- **The node.** The node the pod runs on may stay reachable, and with it a metadata service answered on the node.
- **Forwarding.** A public address that forwards inward, a reverse proxy or an egress proxy, is a public address to the policy.
- **Who sets it.** The setting belongs to whoever runs `helm`. An administrator of an organization in ReARM cannot see or change it.

## How a blocked connection shows in ReARM (ReARM Pro)

A connection the policy refuses is not an error message of its own. On most network plugins (Calico, Cilium) it is a connect timeout, on others (kube-router, as in k3s) an immediate refusal.

| Probe result | What it means here |
|---|---|
| **Could not connect** | "No connection to the server could be opened. The name or the port may be wrong or the server may be down; the network policy of this installation may also not allow the address." rebom gives up connecting after 5 seconds by default. |
| **Timed out** | The UI adds: "If the server is on a private network, the network policy of this installation may block its address. Whoever installed ReARM with helm can allow it." |
| **Address refused** | Not the policy: the ReARM CLI's own refusal, described in the next section. |

## The address refusal of the ReARM CLI

For TEA fetches rebom runs the ReARM CLI in its machine mode, and that mode refuses some addresses on its own, on every installation, Docker Compose included:

| Class | Addresses | Lifted by local testing |
|---|---|---|
| Cloud metadata | `169.254.169.254`, `100.100.100.200`, `168.63.129.16`, `fd00:ec2::254`, `fd00:ec2::23`, `fd20:ce::254`, `fd00:c1::a9fe:a9fe` | never |
| Link-local | `169.254.0.0/16`, `fe80::/10` | never |
| Unspecified | `0.0.0.0/8`, `::` | never |
| Loopback | `127.0.0.0/8`, `::1` | yes |
| The addresses of the host or container the CLI runs on | | yes |

The refusal is made on the address a name resolves to, for every connection, redirects included.

Everything else is allowed by the CLI: public addresses, and private addresses that are not its own host's. Restricting private ranges is the job of the network policy, which leaves the DNS port open to them until `dns.to` is set. This is why both guards exist.

The commands a person runs (`rearm tea discovery`, `rearm tea full_tea_flow`) are not restricted. The [CLI document](https://github.com/relizaio/rearm-cli/blob/main/docs/tea.md) has the details.

## The local testing setting (development only)

::: warning For a developer machine only
rebom has one setting that lifts part of the address refusal: the environment variable `REBOM_TEA_LOCAL_TESTING`, which takes effect only with the exact value `ON`. With it, rebom lets the ReARM CLI connect to loopback and to the addresses of its own host, and accepts a plain `http` server root. Link-local, unspecified and cloud metadata addresses stay refused.

It exists so that a developer can run a TEA server and rebom on one machine. The Helm chart does not set it and has no value for it. It must not be set on an installation that others use: with it, a supplier's server can make rebom connect to rebom's own host and to services that listen on loopback, and credentials travel in clear text over `http`.

Nothing in the CLI's environment can switch this on: the machine mode reads no configuration file and no flag variable.
:::

## Turning it off

Set `networkPolicy.rebomBackend.egress.enabled: false` and run the same `helm upgrade`; no pod restarts. When something stopped working after the policy was turned on, the symptom table of the [chart README](https://github.com/relizaio/rearm/blob/main/deploy/helm/rearm-helm/README.md) says where to look.
