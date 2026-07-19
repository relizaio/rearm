# Agent skills

Reusable skill definitions for AI agents working with ReARM. Each skill lives
in its own directory as a `SKILL.md` with YAML frontmatter (`name`,
`description`) followed by the instructions, per the
[Agent Skills](https://docs.claude.com/en/docs/agents-and-tools/agent-skills)
format. Point your agent runtime at a skill directory (for Claude Code, copy
or symlink it into `.claude/skills/`) to make it discoverable.

| Skill | Use for |
|---|---|
| [vex-authoring](./vex-authoring/SKILL.md) | Producing CycloneDX-VEX / OpenVEX documents that import cleanly into ReARM; debugging zero-imported / unmatched VEX uploads |
