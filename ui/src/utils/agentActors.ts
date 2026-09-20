/**
 * Rendering a board actor.
 *
 * These four fields -- a lock's lockedBy, a board event's actor, a hold's heldBy and a
 * sign-off's reviewedBy -- used to be strings the UI printed verbatim, which meant it
 * displayed "coordinator-session:2f9c…" and the literal "operator" to human readers. They
 * are objects now, so this decides what a person should see: the name when the writer
 * recorded one, and a short identity when it did not.
 */

export interface AgentActor {
    kind?: string | null
    uuid?: string | null
    name?: string | null
}

const KIND_WORDS: Record<string, string> = {
    SESSION: 'session',
    USER: 'user',
    SYSTEM: 'system',
}

/**
 * One line naming an actor, or '' when there is none.
 *
 * A USER's name is its email, which is the useful thing to show; a SESSION rarely carries
 * one, so it falls back to the kind and a short uuid rather than an empty span. Rows written
 * before actors existed arrive decoded by the server, so nothing here parses text.
 */
export function actorLabel (actor: AgentActor | null | undefined): string {
    if (!actor) return ''
    if (actor.name) return actor.name
    const word = KIND_WORDS[actor.kind ?? ''] ?? (actor.kind ?? '').toLowerCase()
    if (actor.uuid) return `${word} ${actor.uuid.slice(0, 8)}`
    return word
}

/** True when the actor is a human, which is what the sign-off list marks with a hand. */
export function isHuman (actor: AgentActor | null | undefined): boolean {
    return actor?.kind === 'USER'
}
