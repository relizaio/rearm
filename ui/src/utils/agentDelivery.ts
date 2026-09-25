// A task's linked PRs as the board shows them (task 9af9d722): a task whose roles have passed waits
// in DELIVERING until every linked PR has merged. Pure, so the chip rules are testable alone.

export interface PrChip {
    url: string
    /** owner/repo/pull/N, or the URL's tail */
    label: string
    /** merged | open | closed | unregistered */
    state: string
    /** naive-ui tag type */
    type: 'success' | 'warning' | 'error' | 'default'
    title: string
    /**
     * The head the newest passing review or test covered against the PR's current head (task
     * 3b97ccfd), when the read carries them; moved when the PR is past the tested head.
     */
    heads?: string
    moved?: boolean
}

/** A PR URL as the board matches it: no query, fragment, trailing slash or .git, and case-folded. */
export function prKey (url: string): string {
    return String(url ?? '').trim().replace(/[?#].*$/, '').replace(/\/+$/, '').replace(/\.git$/, '').toLowerCase()
}

const short = (sha: string): string => String(sha ?? '').slice(0, 7)

function sameCommit (a: string, b: string): boolean {
    const x = String(a ?? '').toLowerCase()
    const y = String(b ?? '').toLowerCase()
    return !!x && !!y && (x.startsWith(y) || y.startsWith(x))
}

/** The tested and current heads of one PR, in words; empty when the read carries neither. */
export function headLine (tested: string | undefined, head: string | undefined): { heads?: string, moved?: boolean } {
    if (tested && head) {
        return sameCommit(tested, head)
            ? { heads: `tested ${short(tested)} · the PR is at it`, moved: false }
            : { heads: `tested ${short(tested)} · now ${short(head)}: moved past the tested head`, moved: true }
    }
    if (tested) return { heads: `tested ${short(tested)}` }
    if (head) return { heads: `head ${short(head)} · no passing review or test names a head` }
    return {}
}

export function shortPr (url: string): string {
    const parts = String(url ?? '').replace(/\/+$/, '').split('/')
    return parts.slice(-4).join('/')
}

/**
 * One chip per linked PR, from the server's resolved pullRequests when present; a task read without
 * them (an older query) still shows its prUrls, as unknown.
 */
export function prChips (task: any): PrChip[] {
    const resolved: any[] = task?.pullRequests ?? []
    const tested = new Map<string, string>((task?.testedHeads ?? []).map((t: any) => [prKey(t.pr), t.head]))
    if (resolved.length) {
        // An attestation settles the chip (task 18c5c293); the heads line stays on it (task 3b97ccfd).
        return resolved.map((pr: any) => ({ ...(attestationChip(pr) ?? chipOf(pr)), ...headLine(tested.get(prKey(pr.url)), pr.head) }))
    }
    return (task?.prUrls ?? []).map((url: string) => ({ url, label: shortPr(url), state: 'linked', type: 'default',
        title: 'linked PR' }))
}

function actorName (a: any): string {
    if (!a) return 'someone'
    return a.name || [String(a.kind ?? '').toLowerCase(), String(a.uuid ?? '').slice(0, 8)].filter(Boolean).join(' ')
}

/**
 * A PR whose delivery was attested (task 18c5c293): merged where this ReARM cannot see it, or
 * abandoned. The newest attestation settles it, whatever the row says.
 */
export function attestationChip (pr: any): PrChip | null {
    const a = pr?.attestation
    if (!a) return null
    const note = a.note ? `: ${a.note}` : ''
    if (a.outcome === 'ABANDONED') {
        return { url: pr.url, label: shortPr(pr.url), state: 'abandoned', type: 'error',
            title: `attested abandoned by ${actorName(a.by)}${note}` }
    }
    return { url: pr.url, label: shortPr(pr.url), state: 'merged', type: 'success',
        title: `attested by ${actorName(a.by)} at ${String(a.commit ?? '').slice(0, 7)}${note}` }
}

/** How a board proves delivery (task 18c5c293): the form's options, one line of help each. */
export const DELIVERY_MODE_OPTIONS: { value: string, label: string, help: string }[] = [
    { value: 'PR_ROWS', label: 'PRs registered here (default)',
        help: 'A linked PR delivers when its row on this ReARM merges, or when it is attested.' },
    { value: 'ATTESTED', label: 'PRs registered elsewhere',
        help: 'Each linked PR is attested as merged (task delivered); a merged row here counts too.' },
    { value: 'NONE', label: 'No PRs',
        help: 'The task completes at its last pass; with attest, once a push or release is attested.' }
]

/** Who merges a board's PRs (task 71a3dd22): the form's options, one line of help each. */
export const MERGE_BY_OPTIONS: { value: string, label: string, help: string }[] = [
    { value: 'COORDINATOR', label: 'the coordinator',
        help: 'The coordinator merges; the board has to list PR_MERGE among what the coordinator covers.' },
    { value: 'ROLE', label: 'a role',
        help: 'The named role merges; it has to be active and carry PR_MERGE.' },
    { value: 'PERSON', label: 'a person',
        help: 'A person merges; agents leave passed tasks in DELIVERING and say when one waits.' }
]

export const MERGE_METHOD_OPTIONS: { value: string, label: string }[] = [
    { value: 'MERGE', label: 'merge commit (default)' },
    { value: 'SQUASH', label: 'squash' },
    { value: 'REBASE', label: 'rebase' },
    { value: 'FAST_FORWARD', label: 'fast-forward' }
]

export const MERGE_ORDER_OPTIONS: { value: string, label: string }[] = [
    { value: 'NOTE_ORDER', label: 'as the notes say (default)' },
    { value: 'OLDEST_PASS_FIRST', label: 'oldest pass first' }
]

/** The form's merge fields: `by` is COORDINATOR, ROLE or PERSON, the role's name apart. */
export interface MergeDraft {
    by: string | null
    byRole: string
    method: string | null
    atTestedHead: boolean
    requireAttestation: boolean
    order: string | null
}

/** A board's declared merge procedure as the form edits it; nothing declared is every default. */
export function mergeDraftOf (policy: any): MergeDraft {
    const m = policy?.merge ?? null
    const by: string | null = m?.by ?? null
    const isRole = typeof by === 'string' && by.trim().toUpperCase().startsWith('ROLE:')
    return {
        by: isRole ? 'ROLE' : by,
        byRole: isRole ? by!.trim().slice(5).trim() : '',
        method: m?.method ?? null,
        atTestedHead: m?.atTestedHead !== false,
        requireAttestation: !!m?.requireAttestation,
        order: m?.order ?? null
    }
}

/**
 * The merge procedure the form sends: only what differs from the defaults, null when nothing does.
 * A role without a name is not sent; attestation is not sent on an ATTESTED board, which attests
 * every merge and refuses false.
 */
export function mergeOf (draft: MergeDraft, mode: string | null | undefined): Record<string, any> | null {
    const by = draft.by === 'ROLE' ? (draft.byRole.trim() ? 'ROLE:' + draft.byRole.trim() : null) : draft.by
    const merge = {
        by,
        method: draft.method,
        atTestedHead: draft.atTestedHead ? null : false,
        requireAttestation: mode === 'ATTESTED' || !draft.requireAttestation ? null : true,
        order: draft.order
    }
    return Object.values(merge).every(v => v === null) ? null : merge
}

/**
 * What the form sends as deliveryPolicy: nothing when the draft matches the board, null to restore
 * the default, else the policy. Attest only means something on NONE, so it is sent only there. The
 * merge procedure (task 71a3dd22) travels with it; without a draft the board's own is kept.
 */
export function deliveryPolicyPatch (original: any, mode: string | null | undefined, attest: boolean, draft?: MergeDraft):
    { changed: boolean, value: { mode: string | null, attest: boolean, merge?: Record<string, any> } | null } {
    const before = original?.deliveryPolicy ?? null
    const m = mode || null
    const merge = draft ? mergeOf(draft, m) : (before?.merge ? mergeOf(mergeDraftOf(before), m) : null)
    const value = m || merge
        ? { mode: m, attest: m === 'NONE' && !!attest, ...(merge ? { merge } : {}) }
        : null
    const beforeMerge = before?.merge ? mergeOf(mergeDraftOf(before), before.mode ?? null) : null
    const same = (before === null && value === null) ||
        (before !== null && value !== null && (before.mode ?? null) === value.mode && !!before.attest === value.attest &&
            JSON.stringify(beforeMerge) === JSON.stringify(merge))
    return { changed: !same, value }
}

/** The state chip of one resolved PR. */
function chipOf (pr: any): PrChip {
    if (!pr.registered) {
        return { url: pr.url, label: shortPr(pr.url), state: 'unregistered', type: 'default',
            title: 'CI has not reported this PR, so the board cannot see it merge' }
    }
    const s = String(pr.state ?? '').toUpperCase()
    if (s === 'MERGED') {
        return { url: pr.url, label: shortPr(pr.url), state: 'merged', type: 'success',
            title: `merged (CI)${pr.targetBranch ? ' into ' + pr.targetBranch : ''}${pr.mergedDate ? ' · ' + pr.mergedDate : ''}` }
    }
    if (s === 'CLOSED') {
        return { url: pr.url, label: shortPr(pr.url), state: 'closed', type: 'error',
            title: 'closed without merging' }
    }
    return { url: pr.url, label: shortPr(pr.url), state: 'open', type: 'warning',
        title: `open${pr.targetBranch ? ' against ' + pr.targetBranch : ''}` }
}
