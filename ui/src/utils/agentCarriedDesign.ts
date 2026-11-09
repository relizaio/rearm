// The words for a split child's carried design (task TPF-164): the parent's reviewed rounds that stand as
// the child's design and the parent passes they rest on. The header chip, its tooltip and the hop log's row
// share these, so they cannot disagree. A role's signing session is never printed: it is null for a person's
// pass and on a blind read, and "a person" is keyed on reviewedBy only. Kept apart from agentTaskLabels
// because the per-role phrase uses the board's one time format, and agentTaskFormat imports agentTaskLabels.
import { actorLabel } from './agentActors'
import { ts } from './agentTaskFormat'

/** The task's carried design, or null on a task that carries none. */
export function carriedDesignOf (task: any): any | null {
    return task?.carriedDesign ?? null
}

/** The header chip's text, or null: "design carried from <parentKey>". */
export function carriedDesignTag (task: any): string | null {
    const cd = carriedDesignOf(task)
    return cd ? `design carried from ${cd.parentKey}` : null
}

/** One carried round, "ARCHITECTURE round 2"; the specification alone when the round is unknown. */
export function carriedRoundLabel (round: any): string {
    return round?.round != null ? `${round.specification} round ${round.round}` : `${round?.specification ?? ''}`
}

/** The carried rounds, comma-separated; "no rounds" for an empty list. */
export function carriedRoundsLine (cd: any): string {
    const rounds = (cd?.rounds ?? []).map(carriedRoundLabel)
    return rounds.length ? rounds.join(', ') : 'no rounds'
}

/**
 * One carried pass: "passed by <role> (<when>)", then " by <person>" from a non-null reviewedBy only,
 * ", decided over by a person" when the parent's REJECTED was decided over and reviewedBy is null, and
 * " (no longer carried)" when the server reads the role as no longer live. Nothing comes from session.
 */
export function carriedRolePhrase (role: any): string {
    const when = role?.signedOffAt ? ` (${ts(role.signedOffAt)})` : ''
    const person = role?.reviewedBy ? actorLabel(role.reviewedBy) : ''
    const by = person ? ` by ${person}` : ''
    const decided = role?.decidedOver && !role?.reviewedBy ? ', decided over by a person' : ''
    const stale = role?.live === false ? ' (no longer carried)' : ''
    return `passed by ${role?.role}${when}${by}${decided}${stale}`
}

/** The carried passes, one phrase per role, "; "-separated; "none" for an empty list. */
export function carriedRolesLine (cd: any): string {
    const roles = (cd?.roles ?? []).map(carriedRolePhrase)
    return roles.length ? roles.join('; ') : 'none'
}

/** The hop log row's words: "design carried from <parentKey>: <rounds>; passes carried: <roles>". '' without a record. */
export function carriedDesignLine (task: any): string {
    const cd = carriedDesignOf(task)
    if (!cd) return ''
    return `design carried from ${cd.parentKey}: ${carriedRoundsLine(cd)}; passes carried: ${carriedRolesLine(cd)}`
}

/** Whether the header chip is muted: the server reads the carried design as no longer standing. */
export function carriedDesignMuted (task: any): boolean {
    return carriedDesignOf(task)?.standing === false
}

/** The chip's tooltip: the row's words, and that the design no longer stands when it does not. '' without a record. */
export function carriedDesignTooltip (task: any): string {
    const line = carriedDesignLine(task)
    if (!line) return ''
    return carriedDesignMuted(task)
        ? `${line}; the carried design no longer stands: a reopen or a newer round of this task since the split`
        : line
}
