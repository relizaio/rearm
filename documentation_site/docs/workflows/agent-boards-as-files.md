# Task Boards as Files

A task board, and your organization's role presets, can be kept as files in version control and
applied from there: from CI with the CLI, from Terraform, from the API, or by uploading the file in
the UI. The same shape comes back out as an export, so a board configured by hand can be turned into
a file and a board can be stood up again in another organization unchanged.

## A board file

```yaml
kind: BOARD
version: 1
name: platform                  # the board's identity within the organization
description: the platform team's board
target: platform-api            # component name, not uuid
sources:
  - github:acme/platform
documentsRepo: https://github.com/acme/platform-docs
priorityType: LAX
perAgentWipLimit: 4
coordinatorPromptFile: prompts/coordinator.md   # CLI and Terraform inline this
settings:
  budgetMicros: 50000000        # USD micros
  cycleCap: 6
  noProgressRepeatsToStop: 2
  blockingPriority: 2           # findings at P1-P2 send work back
  completionPriority: 1         # findings at P1 stop completion
roles:
  - name: designer
    promptFile: prompts/designer.md
    producesOutputs:
      - specification: ARCHITECTURE
        scope: TASK
        required: true
  - file: roles/coder.yaml      # a whole role from its own file
  - name: reviewer
    prompt: |
      Review the change against the architecture...
    necessity: REQUIRED
    humanGate: ON_ANY_SIGNOFF
    requiredInputs:
      - kind: DOCUMENT
        specification: ARCHITECTURE
        scope: TASK
        minLifecycle: ASSEMBLED
    hopBudgetMicros: 2000000     # allowance per hop, USD micros: flagged when exceeded, not enforced
    strength:
      requiredStrength: 0.75
      strengthCategory: REVIEWER
      modelStrengths:
        - model: claude-opus-5-5   # a model of your organization, by id or name-version
          strength: 0.9
```

## A presets file

```yaml
kind: ROLE_PRESETS
version: 1
authoritative: true             # deactivate presets this file does not list
presets:
  - name: coordinator-tracker   # seeds boards with sources
    promptFile: presets/coordinator-tracker.md
  - name: coordinator-board-truth
    promptFile: presets/coordinator-board-truth.md
  - name: coder
    file: presets/coder.yaml
```

Presets are copied onto new boards; applying a presets file does not change existing boards.

## What applying does

- **The file wins for what it declares.** A field you leave out is not touched, so a setting tuned in
  the UI survives until the file says otherwise. A field set to `null` is cleared: `settings.budgetMicros: null`
  removes the budget, and a null priority threshold returns the board to its strict default.
- **Roles are matched by name.** A role the file does not list is deactivated, never deleted, since
  tasks and sign-offs point at it; listing it again brings it back as it was. If tasks are waiting on
  a role the file deactivates, the change set warns and names them, and the board posts an alert.
- **One file, one transaction.** If anything in the file is wrong, nothing is applied, and the result
  lists every problem rather than the first.
- **Dry run first.** A dry run returns exactly the change set the apply would, and writes nothing.
- **Provenance.** The board records the file it was last applied from (spec hash, repository, path,
  commit) and posts an event naming it. Applying the same file twice changes nothing.
- A file can be applied to a locked board. A file that names an archived board is refused.

## Applying a file

- **UI:** *AI Agents > Task boards > Apply spec* (or *Org presets > Apply presets file*). The upload
  shows the dry run's change set before you apply. It takes self-contained files only: references
  such as `file:` and `promptFile:` need the CLI or Terraform.
- **CLI:** `rearm agent board apply -f board.yaml [--dry-run]`, `rearm agent board export --board platform`,
  and `rearm agent presets apply|export`. References are resolved relative to the file and may not
  leave its directory.
- **API:** `applyBoardProgrammatic` and `applyRolePresetsProgrammatic` on the programmatic endpoint.

Applying needs the organization's admin, or a permission with the `CONFIGURATION_WRITE` function;
exporting needs read access.
