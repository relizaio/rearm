// Board enums as words (task RD2-23, sweep UI-31): one table, so a specification, a lifecycle, a hold, a
// level, an outcome or a status reads the same on every board surface instead of as its raw identifier.

/** The rule every family starts from: "DETAILED_DESIGN" reads "detailed design". Empty for nothing. */
export function enumWord (value: string | null | undefined): string {
    return String(value ?? '').toLowerCase().replace(/_/g, ' ')
}

/**
 * A document specification: "DETAILED_DESIGN" → "detailed design". The board's own types carry a BOARD_ prefix in
 * the shared enum, so the product's vocabulary stays clean; on a board surface it says nothing, and is dropped:
 * "BOARD_REVIEW_ITEMS" → "review items", "BOARD_TEST_REPORT" → "test report".
 */
export function specWord (specification: string | null | undefined): string {
    return enumWord(String(specification ?? '').replace(/^BOARD_/, ''))
}

/** A release lifecycle: "READY_TO_SHIP" → "ready to ship", "ASSEMBLED" → "assembled" (RD2-24 first named it). */
export function lifecycleWord (lifecycle: string | null | undefined): string {
    return enumWord(lifecycle)
}

/** A sign-off outcome: "PASSED" → "passed", "REJECTED" → "rejected". */
export function outcomeWord (outcome: string | null | undefined): string {
    return enumWord(outcome)
}

/** A task status: "AWAITING_COORDINATOR" → "awaiting coordinator". */
export function statusWord (status: string | null | undefined): string {
    return enumWord(status)
}

/** A hold's or a pause's level: "OPERATOR" → "operator", "COORDINATOR" → "coordinator". */
export function levelWord (level: string | null | undefined): string {
    return enumWord(level)
}

/**
 * A hold as a phrase: "human gate" for a gated sign-off, "question" for an escalated question, and the
 * level's hold ("operator hold", "coordinator hold") for a manual one, "manual hold" when no level is known.
 * A kind the table does not know reads by the common rule.
 */
export function holdWord (hold: { kind?: string | null, level?: string | null } | null | undefined): string {
    if (!hold) return ''
    if (hold.kind === 'HUMAN_GATE') return 'human gate'
    if (hold.kind === 'QUESTION') return 'question'
    if (hold.kind === 'MANUAL' || !hold.kind) return hold.level ? `${levelWord(hold.level)} hold` : 'manual hold'
    return enumWord(hold.kind)
}

/** A status-history trigger: "HUMAN_REJECT" → "human reject". */
export function triggerWord (trigger: string | null | undefined): string {
    return enumWord(trigger)
}

/**
 * A hold as the phrase a row reads (sweep UI-31): "held at the human gate", "held on a question", or the
 * level's hold ("operator hold"). Case-blind, since revision snapshots store the kind as written.
 */
export function holdPhrase (hold: { kind?: string | null, level?: string | null } | null | undefined): string {
    if (!hold) return ''
    const kind = hold.kind ? String(hold.kind).toUpperCase() : null
    const level = hold.level ? String(hold.level).toUpperCase() : null
    if (kind === 'HUMAN_GATE') return 'held at the human gate'
    if (kind === 'QUESTION') return 'held on a question'
    return holdWord({ kind, level })
}
