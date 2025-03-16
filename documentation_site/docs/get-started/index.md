# Getting Started

## Installation of ReARM Community Edition
Open-source ReARM Community Edition (Licensed per AGPL 3.0) may be deployed using Docker Compose or via Helm Chart.

### Local Installation Via Docker Compose
Time it takes: 5 minutes.

##### Pre-requisites:
1. You need to have an operational Docker engine with Docker Compose version 2.24.0 or newer on your local machine.
2. ReARM uses [OCI](https://opencontainers.org/) compatible storage to store xBOM files and other artifacts. Examples include Docker Hub, ACR, ECR, GCR. One of the quickest options is to use Reliza Hub product which gives you 1GB of free storage - see instructions how to set up [here](https://docs.relizahub.com/registry/). You would need an account with push permissions, so you would need to know OCI repository `login`, `password`, `uri` and `namespace` (optional) property. `Namespace` means relative location of your storage, i.e. if you are planning to store artifacts under `https://registry.relizahub.com/430fcdde-d7bc-4542-ad5b-4f534f4942f0-private`, then your `uri` property is `https://registry.relizahub.com` and your `namespace` property is `430fcdde-d7bc-4542-ad5b-4f534f4942f0-private`. Samples are given in our docker compose file and would be further clarified below.
3. Clone ReARM git repository:
```
git clone https://github.com/relizaio/rearm.git
```
4. In your terminal, cd into compose directory under your git clone:
```
cd deploy/docker-compose
```

##### Set up env files referencing OCI connectivity
As discussed above, you need to have credentials ready for OCI storage. With that you need to create 3 env files in the docker-compose directory (note that .env files in this directory are added to .gitignore). Below are samples of possible credentials, you should modify the values according to your OCI storage.

1. core.env
```
RELIZAPROP_OCIARTIFACTS_REGISTRY_HOST="registry.relizahub.com"
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
OCIARTIFACTS_REGISTRY_HOST="https://registry.relizahub.com"
OCIARTIFACTS_REGISTRY_NAMESPACE="430fcdde-d7bc-4542-ad5b-4f534f4942f0-private"
```

##### Start the docker compose stack
```
docker-compose up -d
```

Then proceed to the [create administrative user](/get-started/#create-your-administrative-user-and-log-in) section.

### Installation Via Helm Chart
Time it takes: 5 minutes.
Pre-requisites: You need to have a running Kubernetes cluster.
This section will be updated later..

## Create Your Administrative User and Log In
Time it takes: 5 minutes.
Pre-requisites: Installed ReARM via Docker Compose or a Helm chart.

User management is done via Keycloak. To create your first user, navigate to the Keycloak login path at your ReARM URI with `/kauth/` suffix. In example, for the base local docker compose installation this would be http://localhost:8092/kauth/ .

Log in with default Keycloak credentials defined in docker compose or Helm configuration. The defaults provided by Reliza if you have no local modifications are `admin / admin`.

In the upper left part of the screen, switch realm from Keycloak to Reliza. Click on `Users`, then `Add user`. Set `Email verified` to on, enter your email and optionally First and Last Name and click 'Create'.

Then click on `Credentials` tab and click `Set password`. Enter your desired password and set `Temporary` to `Off`, then click 'Save'.

Your user is now created. Sign out of Keycloak by clicking on `admin` user name in the top right and selecting `Sign out`. Then navigate to the home URI of your ReARM installation - default for docker compose is http://localhost:8092 .

From there, sign in with the new user account you just created. On the first sign in the system will prompt you to perform unseal procedure. For this enter unseal secret from the ReARM application settings. The default provided by Reliza and used by Docker Compose installation is `r3liza`. The Helm chart installation will generate random secret on installation as noted in the Helm installation section above.

Once you sign in and unseal the system, your user will automatically become the system administrator and the admin of the pre-created organization.

## Create First Component
Once your first organization is created, select `Components` from the menu on the left.

![Components Menu](./create-component-menu.png)

Once on the Components page, click on the `plus-circle button` to Add Component.

![Add Component Icon](./create-component-plus-circle.png)

In the following form:
- Enter desired name for your component - for the purpose of this tutorial we will call it *Test Component 1*.
- Select main branch name (*main* or *master*). We will use *main* in this tutorial.
- Choose version schema for the component from the suggested options or set a custom one - refer to [Reliza Versioning](https://github.com/relizaio/versioning) for options. We will use *semver* in this tutorial.
- If a separate marketing version is needed, toggle corresponding switch and choose marketing version schema from the suggested options or set a custom one
- Choose version schema for feature branches from the suggested options or set a custom one - refer to [Reliza Versioning](https://github.com/relizaio/versioning) for options
- Choose `Add new repository` in the `Select VCS Repo` field and input URL of your base Version Control Repository. If it is hosted on GitHub.com, GitLab.com or Bitbucket.org, it will be parsed automatically, otherwise you would need to input additional data in the required fields. Click `Create VCS Repository` once everything looks right.
- Click `Submit` to finalize creation of the component.

## Create Your First Component Release
When you created your component, its main branch was created automatically by ReARM and you are seeing the *main* branch view that currently has no Releases.

To create your first Release, in the Branch view on the right part of the screen, click on the `plus-circle button`.