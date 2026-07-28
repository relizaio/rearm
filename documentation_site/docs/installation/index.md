# Installation of ReARM Community Edition
Open-source ReARM Community Edition (Licensed per AGPL 3.0) can be installed two ways: with [Docker Compose](#installation-via-docker-compose), or with the [Helm chart](#installation-via-helm-chart) on Kubernetes.

The Helm chart is the recommended option for production. Docker Compose is the quickest way to get an instance running on a single host, and suits evaluations, demos and smaller self-hosted deployments.

## Installation Via Docker Compose
Time it takes: 5 minutes.

The compose stack runs wherever Docker does - your laptop, a VM, or a server your team reaches at its own domain. The walkthrough below brings it up on localhost first because that needs no configuration at all; deploying on a remote host or domain is a couple of settings on top that are described [below](#deploying-on-a-remote-host-or-domain).

#### Pre-requisites
You need an operational Docker engine with Docker Compose version 2.24.0 or newer.

That is all. ReARM stores BOM files and other artifacts in [OCI](https://opencontainers.org/) compatible storage, and the compose stack ships with a bundled [zot](https://zotregistry.dev) registry that is enabled by default - so no external registry, and no credentials of your own, are required to get started. The registry runs inside the stack and is not published on a host port.

If you would rather keep artifacts in a registry you already run, see [using an external OCI registry](/installation/#optional-using-an-external-oci-registry) below.

#### Recommended: a Dependency-Track instance
ReARM runs perfectly well without one - you can store and version BOMs, manage releases and track dependencies between them. What you do not get is SBOM analysis: vulnerability scanning and policy violations, including licensing policies, come either from [Dependency-Track](https://dependencytrack.org) or from VDR files imported from other sources - without any of these releases carry no findings.

Therefore, connecting Dependency-Track is highly recommended. If you already run an instance, see [Dependency-Track integration](/integrations/dtrack). If you do not, we publish a [Dependency-Track 5 Helm chart](/integrations/dtrackChart) that serves the UI and API on a single hostname. Alternatively, you can use Docker Compose installation, as described in Dependency-Track's own [Quick Start tutorial](https://dependencytrack.github.io/docs/next/tutorials/quickstart/). 

Note, that Dependency-Track installation and integration can be added at any time, before or after installing ReARM.

#### Prepare Installation
1. Clone ReARM git repository:
```
git clone https://github.com/relizaio/rearm.git
```
2. In your terminal, cd into compose directory under your git clone:
```
cd deploy/docker-compose
```

#### Start the docker compose stack
```bash
docker compose up -d
```

Open `http://localhost:8092` in your browser. No configuration file is needed for a localhost deployment: every setting has a working default.

#### Deploying on a remote host or domain
Nothing about the compose stack is localhost-only. To serve it to other machines, point it at the host users will type in the browser and enable the TLS front.

ReARM's security settings require TLS for anything outside localhost: the login flow depends on browser APIs that are unavailable on a plain-http page, so login fails immediately without it. An origin on `localhost` or `127.0.0.1` counts as secure, which is why the deployment above needs no TLS.

The stack includes an optional TLS front for this. Copy the template and set the host:

```bash
cp .env.example .env
```

```
REARM_HOST=rearm.example.com     # what users type in the browser
REARM_PROTOCOL=https
REARM_TLS_HOST=rearm.example.com # bare host or IP, no port
REARM_ACME_EMAIL=you@example.com # omit for a self-signed certificate
COMPOSE_PROFILES=tls
KEYCLOAK_ADMIN_PASSWORD=...      # do not leave this at the default
```

With `REARM_ACME_EMAIL` set and ports 80/443 reachable from the internet, certificates come from Let's Encrypt; without it traefik serves a self-signed certificate, which is fine for an IP-based evaluation.

Set `REARM_HOST` before the stack first starts: the Keycloak realm is imported once, with the login URIs derived from it, so changing it later means either wiping the Keycloak volume (`docker compose down -v`, which destroys local users) or fixing the URIs by hand - see [Configuring login URIs](/installation/#configuring-login-uris).

#### Optional: using an external OCI registry
Skip this if you are happy with the bundled registry.

To store artifacts in your own registry, set the following in `.env`. `OCI_USE_PLAIN_HTTP=false` matters: the default of `true` suits only the in-network bundled registry and would otherwise send credentials in the clear.

```
OCI_REGISTRY_HOST=registry.example.com
OCI_REGISTRY_USERNAME=myusername
OCI_REGISTRY_TOKEN=mypassword
OCI_REGISTRY_NAMESPACE=my-namespace
OCI_USE_PLAIN_HTTP=false
OCI_BUNDLED_REGISTRY_REPLICAS=0
```

`OCI_REGISTRY_NAMESPACE` is the relative location inside the registry: for artifacts under `https://registry.example.com/my-namespace`, the host is `registry.example.com` and the namespace is `my-namespace`. `OCI_BUNDLED_REGISTRY_REPLICAS=0` stops the bundled registry from starting, since nothing will be using it.

`.env` is gitignored and carries credentials - keep it that way, and consider `chmod 600`. Earlier releases used separate `core.env`, `oci.env` and `rebom.env` files; those are still honoured for backward compatibility, but `.env` is the supported place for new installations. See [`.env.example`](https://github.com/relizaio/rearm/blob/main/deploy/docker-compose/.env.example) for every available setting.

Then proceed to the [create administrative user](/installation/#create-your-administrative-user-and-log-in) section.

## Installation Via Helm Chart
Time it takes: 5 minutes.
Pre-requisites: You need to have a running Kubernetes cluster.

As with the compose installation, a [Dependency-Track](https://dependencytrack.org) instance is highly recommended but not required - without one ReARM works, but releases carry no vulnerability or policy-violation findings. See [Dependency-Track integration](/integrations/dtrack) to connect an existing instance, or our [Dependency-Track 5 Helm chart](/integrations/dtrackChart) to deploy one alongside ReARM.

Note: below shows quick installation method and assumes stack running on http://rearm.localhost. For various options and hardening refer to the values file of ReARM helm chart in the [GitHub repository](https://github.com/relizaio/rearm).

Create your local values file `rearm-values.yaml` specifying custom parameters, especially the hostname and where artifacts are stored.

As with the compose stack, the chart can run a bundled [zot](https://zotregistry.dev) registry in the cluster, so no external registry or credentials are needed. Unlike compose it is **off by default**, because switching it on for an existing installation would repoint it away from the registry it already uses. The two options are shown below - pick one.

##### Option A: bundled registry (no external storage needed)

```yaml
leHost: rearm.localhost
projectHost: rearm.localhost
projectProtocol: http

keycloak:
  strict_host: true
  issuer_uri: http://rearm.localhost

ociArtifactService:
  enabled: true
  bundledRegistry:
    enabled: true
    storage: 20Gi

rebom:
  backend:
    oci:
      enabled: "true"
      serviceHost: http://rearm-oci-artifact
      registryNamespace: rearm
```

With the bundled registry, `registryNamespace` is a plain repository prefix such as `rearm`. A registry password is generated on first install and preserved across upgrades, so artifacts stay reachable; the credentials Secret and the registry volume both survive `helm uninstall`, since the stored artifacts outlive the release.

##### Option B: external OCI registry

Here `registryNamespace` is the relative location inside your registry: for artifacts under `https://registry.example.com/my-namespace`, the registry host is `registry.example.com` and the namespace is `my-namespace`.

```yaml
leHost: rearm.localhost
projectHost: rearm.localhost
projectProtocol: http

keycloak:
  strict_host: true
  issuer_uri: http://rearm.localhost

ociArtifactService:
  enabled: true
  registryHost: registry.example.com
  registryUser: registry_user
  registryToken: registry_token

rebom:
  backend:
    oci:
      enabled: "true"
      serviceHost: http://rearm-oci-artifact
      registryHost: registry.example.com
      registryNamespace: my-namespace
```

Note that there are other ways to set up the secrets in a more secure way, but we discuss the simplest approach here. In any case, make sure not to check in any secrets to a source code repository.

#### Installing the chart
Once your values file is ready, run the following command to install the helm chart:

```bash
helm upgrade --install --create-namespace -n rearm -f rearm-values.yaml rearm oci://registry.relizahub.com/library/rearm
```

Unless you are deploying on the chart's default host, set the login URIs next - see [Configuring login URIs](/installation/#configuring-login-uris). Then proceed to creating your Administrative User.


## Keycloak Admin Credentials
Time it takes: 2 minutes.
Applies to: every installation.

ReARM delegates user management to Keycloak, whose admin console is served at your ReARM URI with the `/kauth/` suffix. Reliza ships `admin / admin` so that a first evaluation works out of the box.

Keep those defaults only where nothing else can reach the deployment - a localhost evaluation on your own machine. Anywhere else, change them: the console is reachable wherever the deployment is, and whoever reaches it controls the identity provider that guards every ReARM account.

Set them before the first start. They are bootstrapped once, so editing the configuration afterwards has no effect:

- **Compose**: `KEYCLOAK_ADMIN_USER` and `KEYCLOAK_ADMIN_PASSWORD` in `.env`
- **Helm**: `keycloak.secrets.adminpassword` in your values file

If the deployment is already running, change the password from the console instead: log in, and in the `master` realm go to `Users` -> `admin` -> `Credentials` -> `Reset password`.

### Rotating after the first login

Whatever you configure above is bootstrap material, and it stays readable where you put it - in `.env` on the compose host, or in your values file and the Kubernetes Secret the chart renders from it, which is base64-encoded rather than encrypted. Anyone with the file, the repository it is committed to, or read access to Secrets in the namespace can recover it, and `docker inspect` will show it on a compose host.

So for anything beyond a localhost evaluation we strongly recommend logging in once and rotating the password from the console, in the `master` realm: `Users` -> `admin` -> `Credentials` -> `Reset password`. That leaves the live credential somewhere the configuration never recorded it. Nothing enforces this, so it is worth doing while the installation is still fresh in mind.

If you would rather not hold the bootstrap value in plain text at all, the Helm chart can take it as a SealedSecret instead - set `keycloak.create_secret_in_chart` to `sealed` and supply kubeseal ciphertext, or to `none` and provision the Secret yourself.

## Configuring Login URIs
Time it takes: 2 minutes.
Applies to: every Helm installation, and Docker Compose deployments whose `REARM_HOST` changed after first boot.

Keycloak only redirects back to URIs its `login-app` client already knows. If those do not match the address users type in the browser, login fails with an invalid redirect error instead of returning to ReARM.

**Docker Compose** does this for you. The realm is imported with the URIs rewritten to your `REARM_PROTOCOL` and `REARM_HOST` on first boot, so nothing is needed provided those were set before the stack first started. They are seeded only once, so if you change `REARM_HOST` afterwards you must either wipe the Keycloak volume (`docker compose down -v`, which also destroys local users) or apply the manual steps below.

**Helm** does not rewrite them, so this step is always required. The chart ships a placeholder host, which will not be the one your users type.

To set them by hand:

1. Navigate to the Keycloak login path at your ReARM URI with the `/kauth/` suffix - for example `https://rearm.example.com/kauth`.
2. Log in with your [Keycloak admin credentials](/installation/#keycloak-admin-credentials). This section applies to deployments that are not on localhost, so if they are still `admin / admin`, change them.
3. In the upper left of the screen, switch realm from Keycloak to Reliza.
4. Go to the `Clients` menu and click the `login-app` client. Add your user-facing URI to `Valid redirect URIs`, `Valid post logout redirect URIs` and `Web origins` - for example `https://rearm.example.com/*` in each. You may remove the existing preset defaults.

## Create Your Administrative User and Log In
Time it takes: 5 minutes.
Pre-requisites: Installed ReARM via Docker Compose or a Helm chart.

User management is done via Keycloak. To create your first user, navigate to the Keycloak login path at your ReARM URI with `/kauth/` suffix. In example, for the base local docker compose installation this would be `http://localhost:8092/kauth/` .

Log in with your [Keycloak admin credentials](/installation/#keycloak-admin-credentials). If this deployment is reachable by anyone other than you and they are still `admin / admin`, change them before going further.

In the upper left part of the screen, switch realm from Keycloak to Reliza. Click on `Users`, then `Add user`. Set `Email verified` to on, enter your email and optionally First and Last Name and click 'Create'.

Then click on `Credentials` tab and click `Set password`. Enter your desired password and set `Temporary` to `Off`, then click 'Save'.

Your user is now created. Sign out of Keycloak by clicking on `admin` user name in the top right and selecting `Sign out`. Then navigate to the home URI of your ReARM installation - default for docker compose is `http://localhost:8092` .

From there, sign in with the new user account you just created. On the first sign in the system will prompt you to perform unseal procedure. For this enter unseal secret from the ReARM application settings. The default provided by Reliza and used by Docker Compose installation is `r3liza`. The Helm chart installation will generate random secret on installation.

To obtain it, use command shown in the status section of the Helm chart installation. Sample command for `rearm` namespace is

```
echo $(kubectl get secret --namespace rearm system-secret -o jsonpath="{.data.systemSecret}" | base64 --decode)
```

Note, if you're getting repeated error, try restarting (deleting) `rearm-backend` pod and then retry the unseal process.

Once you sign in and unseal the system, your user will automatically become the system administrator and the admin of the pre-created organization.
