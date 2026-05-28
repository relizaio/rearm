## Rearm CLI

Open source Rearm CLI project can be found at <https://github.com/relizaio/rearm-cli>. Documentation can be found in the same repository.

This project provides variety of common integration scenarios with Reliza's ReARM and can be used in any home-made or 3rd party tool.

The CLI also carries the **`rearm agent`** subcommand family
(`session init / show / touch / close / add-artifact / inbox`,
plus `enrollkey`) — these are what an AI coding agent runs against
a ReARM Pro instance after the operator hands it the FREEFORM
`AGENT` key. See [Bootstrap an AI Agent](../workflows/agentic) for
the operator-facing setup and the agent-side contract URL.