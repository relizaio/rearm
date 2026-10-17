# Design, round 2

## REQ-1 The publish takes the task lock

Both publishes serialise on it.

## Q-1 answered: the lock is the task's, not the board's

The board lock is taken first, as before.
