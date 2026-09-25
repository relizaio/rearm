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

/**
 * What the form sends as deliveryPolicy: nothing when the draft matches the board, null to restore
 * the default, else the policy. Attest only means something on NONE, so it is sent only there.
 */
export function deliveryPolicyPatch (original: any, mode: string | null | undefined, attest: boolean):
    { changed: boolean, value: { mode: string, attest: boolean } | null } {
    const before = original?.deliveryPolicy ?? null
    const m = mode || null
    const value = m ? { mode: m, attest: m === 'NONE' && !!attest } : null
    const same = (before === null && value === null) ||
        (before !== null && value !== null && before.mode === value.mode && !!before.attest === value.attest)
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
