## This board's level ladder

Tasks on this board sit on a decomposition ladder. A task's level is its depth in it, and the board offers
lower levels first. The levels here:

{{levels}}

- **The coordinator sets the work level at authorize** (`rearm agent task authorize <task> --role <role> --work-level <n>`,
  or later `rearm agent task work-level <task> --work-level <n>`). A task left without one takes its group's default,
  else the board's; say so in the order note when you rely on the default.
- **A task at level n descends from the level n-1 decision it refines.** Name that task in its dependencies,
  so it is not worked before the decision it rests on.
- **A gate confirms a level before its children are authorized.** The work at level n passes review (or a
  person's gate) first; only then are the level n+1 tasks that descend from it authorized.
- **A level outside the ladder is refused.** Levels are 0 to {{last}} on this board.
