---
title: "Announcing Oolong: Lightweight TEA Implementation"
date: "2025-10-30"
---

We’re excited to introduce Oolong - a lightweight, MIT-licensed implementation of [Transparency Exchange API (TEA)](https://github.com/CycloneDX/transparency-exchange-api) designed for simplicity and portability.

Oolong is available on GitHub: [https://github.com/relizaio/oolong](https://github.com/relizaio/oolong)

## Why Oolong

- **Lightweight by design**
- **No database required**
- **Git-friendly content model**

Oolong serves TEA content directly from a set of YAML files, exposed via the TEA API. It also implements TEA discovery, including .well-known endpoint. This makes it ideal for simple setups, demos, CI pipelines, and GitOps-style workflows.

## How It Works

- **Content as YAML**. You define your TEA data in YAML.
- **Served via TEA API**. Oolong reads YAML and returns it through standard TEA endpoints.
- **Discovery**. Oolong implements TEA discovery, including .well-known endpoint.
- **Straightforward operations**. No external stateful dependencies are required.

## Run It Your Way

- **Single container**
- **Docker Compose**
- **Helm chart**
- Optional: **Gitpod sidecar** that periodically syncs content from a public Git repository

## Manage Content with ReARM CLI

We added helpful commands to ReARM CLI for both Oolong content operations and TEA API interactions:

- Oolong commands: [https://github.com/relizaio/rearm-cli/blob/main/docs/oolong.md](https://github.com/relizaio/rearm-cli/blob/main/docs/oolong.md)
- TEA operations: [https://github.com/relizaio/rearm-cli/blob/main/docs/tea.md](https://github.com/relizaio/rearm-cli/blob/main/docs/tea.md)
- Install ReARM CLI: [https://github.com/relizaio/rearm-cli](https://github.com/relizaio/rearm-cli)

If you try Oolong, we’d love your feedback and contributions!