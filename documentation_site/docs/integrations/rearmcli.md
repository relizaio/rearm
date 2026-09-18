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

### Rebuilds and SBOM serial numbers

A rebuild uploads a new SBOM. If your generator pins `serialNumber`, it must also increment
`version` per build, otherwise the upload is refused; alternatively let the generator mint a fresh
serial number.

The refusal names the serial number and both versions, and applies to `rearm addrelease --rebuild`
and to any `rearm addartifact` that re-uses an existing serial. It exists because a stored raw SBOM
is immutable: ReARM keeps the bytes it was given as the record of what was actually uploaded and
scanned, so a second upload claiming the same identity cannot replace them.
