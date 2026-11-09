// A role's runWhen condition (task TPF-156, backend TPF-155): routing queues an OPTIONAL role only on a task whose
// tags meet the condition, and records a pass-over otherwise. What the role form edits and sends, the lock rule of
// the form, the roles table's chip text, and the hop row's text for a pass-over. Pure, so the spec runs in node.
import { normaliseTag } from './agentTaskGroups'

/** The form's draft of a role's condition: two lists of keys, copied so editing the draft leaves the role alone. */
export function runWhenDraftOf (rc: any): { runWhenAny: string[], runWhenNone: string[] } {
    return {
        runWhenAny: [...(rc?.runWhen?.taggedAny ?? [])],
        runWhenNone: [...(rc?.runWhen?.taggedNone ?? [])],
    }
}

function normaliseKeys (keys: string[] | null | undefined): string[] {
    const out: string[] = []
    for (const k of keys ?? []) {
        const t = normaliseTag(k)
        if (t && !out.includes(t)) out.push(t)
    }
    return out
}

/**
 * What the form sends as `runWhen`: each key trimmed and lower-cased, blanks and repeats dropped, in the order typed;
 * null when both lists are empty (null clears the stored condition); else both lists, an empty side sent empty.
 * The validity of a key is the server's to judge.
 */
export function runWhenPatch (draft: { runWhenAny?: string[] | null, runWhenNone?: string[] | null }):
    { taggedAny: string[], taggedNone: string[] } | null {
    const taggedAny = normaliseKeys(draft?.runWhenAny)
    const taggedNone = normaliseKeys(draft?.runWhenNone)
    return taggedAny.length || taggedNone.length ? { taggedAny, taggedNone } : null
}

/** Whether the form's condition inputs are locked (and cleared): a role that is not OPTIONAL, or carries the checkpoint. */
export function runWhenLocked (r: any): boolean {
    return r?.necessity !== 'OPTIONAL' || !!r?.coordinatorCheckpoint
}

/** The condition in words (`tagged a or b`, `not tagged c`, `tagged a and not tagged c`), or null when it names no key. */
export function runWhenLabel (rw: any): string | null {
    const any: string[] = rw?.taggedAny ?? []
    const none: string[] = rw?.taggedNone ?? []
    const parts: string[] = []
    if (any.length) parts.push('tagged ' + any.join(' or '))
    if (none.length) parts.push('not tagged ' + none.join(' or '))
    return parts.length ? parts.join(' and ') : null
}

/** One pass-over record as a line: `<role> passed over by routing: <reason>[; waives A, B][ (since ran)]`. */
export function conditionSkipLine (rec: any): string {
    let line = `${rec?.role ?? ''} passed over by routing: ${rec?.reason ?? ''}`
    const waives: string[] = rec?.waives ?? []
    if (waives.length) line += '; waives ' + waives.join(', ')
    if (rec?.live === false) line += ' (since ran)'
    return line
}
