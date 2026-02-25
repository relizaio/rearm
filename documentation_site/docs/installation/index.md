# Installation of ReARM Community Edition
Open-source ReARM Community Edition (Licensed per AGPL 3.0) may be deployed using Docker Compose or via Helm Chart.

## Local Installation Via Docker Compose
Time it takes: 5 minutes.

#### Pre-requisites
1. You need to have an operational Docker engine with Docker Compose version 2.24.0 or newer on your local machine.
2. ReARM uses [OCI](https://opencontainers.org/) compatible storage to store xBOM files and other artifacts. Examples include Docker Hub, ACR, ECR, GCR. One of the quickest options is to use Reliza Hub product which gives you 1GB of free storage - see instructions how to set up [here](https://docs.relizahub.com/registry/). You would need an account with push permissions, so you would need to know OCI repository `login`, `password`, `uri` and `namespace` (optional) property. `Namespace` means relative location of your storage, i.e. if you are planning to store artifacts under `https://registry.relizahub.com/430fcdde-d7bc-4542-ad5b-4f534f4942f0-private`, then your `uri` property is `https://registry.relizahub.com` and your `namespace` property is `430fcdde-d7bc-4542-ad5b-4f534f4942f0-private`. Samples are given in our docker compose file and would be further clarified below.

#### Prepare Installation
1. Clone ReARM git repository:
```
git clone https://github.com/relizaio/rearm.git
```
2. In your terminal, cd into compose directory under your git clone:
```
cd deploy/docker-compose
```

#### Set up env files referencing OCI connectivity
As discussed above, you need to have credentials ready for OCI storage. With that you need to create 3 env files in the docker-compose directory (note that .env files in this directory are added to .gitignore). Below are samples of possible credentials, you should modify the values according to your OCI storage.

1. core.env
```
RELIZAPROP_OCIARTIFACTS_REGISTRY_NAMESPACE="430fcdde-d7bc-4542-ad5b-4f534f4942f0-private"
```

2. oci.env
```
REGISTRY_HOST="registry.relizahub.com"
REGISTRY_USERNAME="myusername"
REGISTRY_TOKEN="mypassword"
```

3. rebom.env
```
OCIARTIFACTS_REGISTRY_NAMESPACE="430fcdde-d7bc-4542-ad5b-4f534f4942f0-private"
```

#### Start the docker compose stack
```bash
docker-compose up -d
```

Then proceed to the [create administrative user](/get-started/#create-your-administrative-user-and-log-in) section.

## Installation Via Helm Chart
Time it takes: 5 minutes.
Pre-requisites: You need to have a running Kubernetes cluster.

Note: below shows quick installation method and assumes stack running on http://rearm.localhost. For various options and hardening refer to the values file of ReARM helm chart in the [GitHub repository](https://github.com/relizaio/rearm).

Create your local values file `rearm-values.yaml` specifying custom parameters, especially hostname and OCI registry credentials. Note that `registryNamespace` property means relative location of your storage, i.e. if you are planning to store artifacts under `https://registry.relizahub.com/430fcdde-d7bc-4542-ad5b-4f534f4942f0-private`, then your registry host property is `registry.relizahub.com` and your `registryNamespace` property is `430fcdde-d7bc-4542-ad5b-4f534f4942f0-private`. See sample below for the full example:

```yaml
leHost: rearm.localhost
projectHost: rearm.localhost
projectProtocol: http

keycloak:
  strict_host: true
  issuer_uri: http://rearm.localhost

ociArtifactService:
  enabled: true
  registryHost: registry.relizahub.com
  registryUser: registry_user
  registryToken: registry_token
  
rebom:
  backend:
    oci:
      enabled: "true"
      serviceHost: http://rearm-oci-artifact
      registryHost: registry.relizahub.com
      registryNamespace: 430fcdde-d7bc-4542-ad5b-4f534f4942f0-private
```

Note that there are other ways to set up the secrets in a more secure way, but we discuss the simplest approach here. In any case, make sure not to check in any secrets to a source code repository.

Once your values file is ready, run the following command to install the helm chart:

```bash
helm upgrade --install --create-namespace -n rearm -f rearm-values.yaml rearm oci://registry.relizahub.com/library/rearm-helm
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

Once you sign in and unseal the system, your user will automatically become the system administrator and the admin of the pre-created organization.