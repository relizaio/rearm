# Installation of ReARM Community Edition
Open-source ReARM Community Edition (Licensed per AGPL 3.0) may be deployed using Docker Compose or via Helm Chart.

## Local Installation Via Docker Compose
Time it takes: 5 minutes.

#### Pre-requisites
You need an operational Docker engine with Docker Compose version 2.24.0 or newer.

That is all. ReARM stores xBOM files and other artifacts in [OCI](https://opencontainers.org/) compatible storage, and the compose stack ships with a bundled [zot](https://zotregistry.dev) registry that is enabled by default - so no external registry, and no credentials of your own, are required to get started. The registry runs inside the stack and is not published on a host port.

If you would rather keep artifacts in a registry you already run, see [using an external OCI registry](/installation/#optional-using-an-external-oci-registry) below.

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

#### Reaching ReARM from another machine
Nothing in ReARM rejects plain http - this is a browser rule. Parts of the Web Crypto API are exposed only in a [secure context](https://developer.mozilla.org/en-US/docs/Web/Security/Secure_Contexts), and the Keycloak client library uses them to build the login request: `crypto.randomUUID` for the OIDC state and nonce, and `crypto.subtle` for the PKCE challenge. Over plain http they are undefined and the page fails immediately with `Web Crypto API is not available`.

A secure context means `https`, or an origin on `localhost` / `127.0.0.1`. So the default localhost deployment above is fine over http, and so is a remote host tunnelled to a local port - but a deployment users reach at its own hostname or IP has to serve TLS.

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
```

With `REARM_ACME_EMAIL` set and ports 80/443 reachable from the internet, certificates come from Let's Encrypt; without it traefik serves a self-signed certificate, which is fine for an IP-based evaluation.

Note that the Keycloak realm import happens on first boot only, so changing `REARM_HOST` afterwards means either wiping the Keycloak volume (`docker compose down -v`, which destroys local users) or editing the `login-app` client in the Keycloak admin console.

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

Once your values file is ready, run the following command to install the helm chart:

```bash
helm upgrade --install --create-namespace -n rearm -f rearm-values.yaml rearm oci://registry.relizahub.com/library/rearm
```

Navigate to the keycloak login path at ReARM URI with `/kauth/` suffix. In this example, this should be `http://rearm.localhost/kauth`.

Log in with default Keycloak credentials defined in Helm configuration. The defaults provided by Reliza if you have no local modifications are `admin / admin`. You may also modify these credentials or switch to a different admin account after logging in.

In the upper left part of the screen, switch realm from Keycloak to Reliza.

Go to `Clients` menu and click on the `login-app` client. Add your user-facing URI to each section: `Valid redirect URIs`, `Valid post logout redirect URIs` and `Web origins`. In our case we will be adding `http://rearm.localhost/*` in each of these sections. You may remove existing preset defaults.

Proceed to creating Administrative User.


## Create Your Administrative User and Log In
Time it takes: 5 minutes.
Pre-requisites: Installed ReARM via Docker Compose or a Helm chart.

User management is done via Keycloak. To create your first user, navigate to the Keycloak login path at your ReARM URI with `/kauth/` suffix. In example, for the base local docker compose installation this would be `http://localhost:8092/kauth/` .

Log in with default Keycloak credentials defined in docker compose or Helm configuration. The defaults provided by Reliza if you have no local modifications are `admin / admin`. You may also modify these credentials or switch to a different admin account after logging in.

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