## Rearm CLI

Open source Rearm CLI project can be found at <https://github.com/relizaio/rearm-cli>. Documentation can be found in the same repository.

This project provides variety of common integration scenarios with Reliza's ReARM and can be used in any home-made or 3rd party tool.

For how the CLI authenticates — signing in with an API key id and secret, signing in from your
browser with no secret to store, authenticating a GitHub Actions job with its own identity token
(`--auth github-oidc`), and `rearm whoami` / `rearm logout` — see
[Programmatic Access](../configure/programmatic-access). The browser and federated-identity flows
are **preview** functionality that has not shipped in a released version yet.

The CLI also carries the **`rearm agent`** subcommand family
(`session init / show / touch / close / add-artifact / inbox`,
plus `enrollkey`) — these are what an AI coding agent runs against
a ReARM Pro instance after the operator hands it the FREEFORM
`AGENT` key. See [Bootstrap an AI Agent](../workflows/agentic) for
the operator-facing setup and the agent-side contract URL.