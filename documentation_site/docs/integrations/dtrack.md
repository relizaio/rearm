
# Dependency-Track

ReARM relies on [Dependency-Track](https://dependencytrack.org) for SBOM analysis, including vulnerability scans and policy violations. If you are using ReARM Pro, Dependency Track integration will be set up for you by Reliza. If you are using ReARM Community Edition or your organization on [ReARM Public Demo](https://demo.rearmhq.com), follow these instructions below:

## Pre-requisites
You need to have a running instance of Dependency-Track.

## Dependency-Track Part
1. In your Dependency-Track instance, open `Administration` section in the menu bar on the left.
2. Open `Access Management`->`Teams` in the next menu.
3. Create new Team called ReARM.
4. Click on ReARM Team and on `Plus` icon in the `API Keys` section. This will create an API Key - note this key.
5. Click on the `Plus` icon in the `Permissions` section and select following permissions:
- BOM_UPLOAD
- PORTFOLIO_MANAGEMENT
- PROJECT_CREATION_UPLOAD
- VIEW_POLICY_VIOLATION
- VIEW_PORTFOLIO
- VIEW_VULNERABILITY
6. Note that Dependency-Track would use configured Violation Policies and Vulnerability Scan Settings - refer to [Dependency-Track documentation](https://docs.dependencytrack.org/) to configure those for your needs.


## ReARM Part
1. In ReARM, open Organization Settings from the menu on the left.
2. In the Integrations section, click on "Add Dependency-Track Integration" button.
3. Enter your Dependency-Track API Server URI (depending on your Dependency-Track installation, this may or may not be the same as your Frontend Server URI).
4. Enter your Dependency-Track Frontend URI (depending on your Dependency-Track installation, this may or may not be the same as your API Server URI).
5. Enter your API Key established in the Dependency-Track part above.
6. Click `Submit` - your integration is now configured.