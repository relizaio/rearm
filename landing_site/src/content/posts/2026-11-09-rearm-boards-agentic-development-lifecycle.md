---
title: "Agentic Development Lifecycle with ReARM Boards: Integration Branch per Feature"
date: "2026-11-09"
---

Our team has been working with various iterations of agentic workflows for about a year and a half now. During this time, like many others, we have had our ups and downs: things that work great, and problems in the process.

At this point we believe that our understanding of the Agentic Development Lifecycle (ADLC) is fairly stable. It resulted in ReARM Boards being added to ReARM, and here we would like to lay out the philosophy behind how we see the new ADLC.

## Brief History of ADLC

Let's first go through the iterations of how we handle development, and how each of them changed earlier assumptions.

- Traditional SDLC - people write software. We start in the late 2000s, the Google / StackOverflow era. People write software, and a lot of problem solving comes from search and collaboration through these resources. Certain rules get established, such as peer reviews and, correspondingly, small commits and contained changes.

- Beginning of ADLC - a chat window used for questions and for writing code snippets. Traditional SDLC was still holding, things just got a little bit faster.

- Agentic IDE - then came Cursor, Windsurf, VS Code with Copilot and other similar tools. Instead of typing code, developers could type prompts and agents would write the code, with developers having various degrees of control over what was written. This was the first 10x velocity improvement point, which largely broke traditional peer review assumptions, and human review altogether - the foundations of SDLC.

- Agents in Terminal - around the same time tools like Claude Code and OpenCode appeared. They worked similarly to agentic IDEs, except there was no IDE. The code was just written (agents don't really need an IDE). At that point agentic IDEs were largely seen as having an edge over agents in terminal, since they provided what was called "human oversight".

- Squads of Agents - very soon people started experimenting with LLMs controlling other LLMs and agents overseeing other agents. The premise was that if velocity increases 10x and more and human reviews are no longer possible, then let agents control agents and have "AI turtles all the way down". This is where we are now, with this idea substantially more developed.

## Basic Agentic Squad

Pretty much anyone doing basic agentic work has recognized that anything written from the first prompt or series of prompts is incomplete. It has quality issues and it's buggy. We should add that in many cases it is much better than a human's first take on the code. But it's still not production ready.

As discussed above, because of the huge volume of this new code, human review at scale is very hard to organize. Therefore, a lot of workflows started to look something like this: agent 1 writes the code and opens a Pull Request (PR) to main. Then a human asks agent 2 to review and test this code. Agent 2 finds some issues and either posts them as comments on the PR or reports them back in chat to the human, who copies them over to agent 1. This is how we started, and this is the most basic agentic squad in motion, controlled by a human.

Soon we realized that copying text between agents is neither productive nor a good control. Letting agents read and act on PR comments directly is even more problematic:

- Security: anyone who can comment on a PR can put instructions in front of the agent. A PR comment becomes a prompt injection channel, and an agent that acts on it can be hijacked.
- Control record: PR comments do not live in code. They are scattered across the VCS hosting, they can be edited or deleted, and they don't give a reliable record of who decided what and why.

So we started building ReARM Boards.

## 5P Rule: Proper Planning Prevents Potential Problems

As in SDLC, in ADLC planning is king. What changed is the unit of work. In SDLC, the unit of work was a ticket (a single bug or improvement). In ADLC, the unit of work is a feature (a story or an epic). It used to be considered extremely bad practice to create and merge a huge PR touching more than 10 files. Now it's customary to merge PRs spanning hundreds of files.

## Feature-based ADLC

What becomes important is feature separation: identifying pieces of work that have minimal connection to each other and putting them to work. Microservice architecture helps naturally, but even within monoliths it is perfectly possible to plan for isolated features.

Once we identify a feature to work on, it gets its own ReARM Board with an agentic squad assigned to it. Roles are configured per board, and a typical squad looks like this:

- Coordinator - holds the coordinator seat of the board. It authorizes tasks to roles, splits tasks that are too big, creates the integration branches and merges task PRs into them.
- Architect - produces the design for a task and recommends how to split it into smaller tasks.
- Coder - implements a task and opens a PR.
- Reviewer and Tester - check the work and either pass it on or return it with findings.

Tasks, designs, review findings and test reports all travel through the board rather than through PR comments or copy-paste. Every hand-over is recorded on the board and in the handoff Git repository, and every commit is attributed to the agent session that made it.

For each VCS repository touched by the feature, the Coordinator creates a new integration branch from main. The work is done via per-task PRs against this integration branch rather than against main, and the Coordinator merges them as tasks pass review and testing. Once the feature is complete, PRs are opened from the integration branches against main. These PRs are merged by humans.

### Integration branches are short-lived

Feature branches have a bad reputation: they live for weeks, drift away from main, and end in merge hell. That is the problem trunk-based development was invented to solve.

With agentic development, this changes. A feature that used to take a human team weeks is completed by an agentic squad in days or even hours. So an integration branch lives only as long as its feature does - which is short - and is merged and done with before it has time to drift far from main.

Integration branches of parallel features may still touch the same code. This is where separation at the planning phase pays off: when features are planned to be isolated, the overlap stays small and the remaining conflicts are small as well.

### Many repositories, one product

A feature often spans several repositories - for example, a backend, a UI and a CLI. ReARM core functionality helps here: it [integrates](https://docs.rearmhq.com/workflows/bundling.html) components into products. For a feature, ReARM tracks the integration branch of each component and combines them into product releases, so the feature is built, tested and evaluated as a whole, not as a set of unrelated branches.

ReARM can also be configured to track which PRs are open and which are merged on each branch, so Coordinator's job becomes easier.

### What the human reviews

A PR to main spanning hundreds of files can't be meaningfully reviewed line by line. However, line-level review and testing were already done by the agentic squad per each task, and recorded on the board and in the handoff Git repository.

The human reviews the feature. In ReARM, the integration branches produce releases like any other branch, and these releases are combined into a product release. So before merging to main, the human looks at the actual product release built from the integration branches: whether it works, what tests and scans found, which PRs went into it, and what the squad's design, review and test reports say. The merge to main then becomes a decision about a feature.

## Git Still Standing

In the course of agentic squad experiments, opinions appeared that git is outdated. Efforts like [Cursor Origin](https://x.com/cursor_ai/status/2089399057659596847), [GitLab's next-gen SCM](https://about.gitlab.com/blog/gitlab-next-gen-scm/) and others are underway.

Note that everything described above runs on plain git: branches, PRs and merges. The problems these efforts point at - many agents writing to the same codebase at once, constant conflicts, a flood of changes no one can review - are not problems of the version control protocol. They are problems of coordination: who works on what, where the work lands, and who decides it is done. In our approach, this layer lives in ReARM Boards and ReARM, while git keeps doing what it has always done well.

Our view is that a lot can and should be done on the git backend side, especially to support larger repository sizes and partial checkouts, but there is no significant need to modify the git protocol.

## Putting It Together

With this structure we can have several large features worked on in parallel without overlap. Within a feature, the Architect and the Coordinator are responsible for getting the design properly split. Between features, it's the human planning phase that should get it right.

With a proper planning structure like this, our internal velocity statistics show 100x velocity over SDLC with better quality, proper isolation and separation.
