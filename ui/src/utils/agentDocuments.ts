// Presentation helpers for document handoff: the documents a hop produced, and the findings
// still open on a task.
//
// Pure and dependency-free, so the awkward parts — turning a canonical repository string back into
// a browsable URL, and ordering findings whose priority scale is per-org — are testable without
// mounting anything.

export interface DocumentRef {
    specification?: string | null
    path?: string | null
    digest?: string | null
    mediaType?: string | null
    indexPath?: string | null
    task?: string | null
    session?: string | null
    round?: number | null
    findings?: Record<string, any> | null
    /** The element index of a prose document (gaps §2.A); see agentElements.ts. */
    elements?: Record<string, any> | null
    /** On a CHECK_REPORT round: the element checks' report; see agentChecks.ts. */
    checks?: Record<string, any> | null
}

export interface DocumentRelease {
    uuid?: string | null
    version?: string | null
    lifecycle?: string | null
    createdDate?: string | null
    document?: DocumentRef | null
    sourceCodeEntryDetails?: {
        commit?: string | null
        commitMessage?: string | null
        vcsRepository?: { uri?: string | null, name?: string | null } | null
    } | null
}

export interface Finding {
    id?: string | null
    priority?: number | null
    status?: string | null
    title?: string | null
    location?: { path?: string | null, line?: number | null, ref?: string | null, element?: string | null } | null
    resolvedBy?: string | null
    resolution?: string | null
    /** Who last decided it -- a person or an agent session. Written by the server. */
    decidedBy?: { kind?: string | null, uuid?: string | null, name?: string | null } | null
    decidedIn?: string | null
    decidedAt?: string | null
}

/** Types whose releases carry a findings index. */
export const INDEXED_TYPES = ['REVIEW_FINDINGS', 'TEST_REPORT']

export function isIndexed (spec?: string | null): boolean {
    return !!spec && INDEXED_TYPES.includes(spec)
}

/**
 * Blob-URL shapes for hosts we can construct a link for.
 *
 * Deliberately a short list, and deliberately not required. A git repository can live anywhere —
 * self-hosted Gitea, a corporate GitLab, a bare remote over ssh — and there is no general way to
 * turn a repository and a commit into a browsable URL. Guessing would produce links that 404,
 * which is worse than no link: a dead link looks like the document is missing rather than like the
 * UI not knowing the host.
 *
 * So an unknown host renders the path as plain text, with the commit beside it, which is enough to
 * find the file by hand.
 */
const BLOB_PATTERNS: { match: RegExp, build: (repo: string, commit: string, path: string) => string }[] = [
    { match: /^github\.com\//, build: (r, c, p) => `https://${r}/blob/${c}/${p}` },
    { match: /^gitlab\.com\//, build: (r, c, p) => `https://${r}/-/blob/${c}/${p}` },
    { match: /^bitbucket\.org\//, build: (r, c, p) => `https://${r}/src/${c}/${p}` },
    { match: /^codeberg\.org\//, build: (r, c, p) => `https://${r}/src/commit/${c}/${p}` },
]

/**
 * A browsable URL for a document at the commit its release pins, or null when the host is unknown.
 *
 * The repository string arrives canonicalised by the server (scheme and credentials stripped), so
 * it is a bare `host/owner/repo`.
 */
export function documentFileUrl (release?: DocumentRelease | null): string | null {
    const repo = release?.sourceCodeEntryDetails?.vcsRepository?.uri
    const commit = release?.sourceCodeEntryDetails?.commit
    const path = release?.document?.path
    if (!repo || !commit || !path) return null
    const bare = repo.replace(/^[a-z+]+:\/\//, '').replace(/\.git$/, '')
    for (const p of BLOB_PATTERNS) {
        if (p.match.test(bare)) return p.build(bare, commit, path)
    }
    return null
}

/** The verdict an indexed document recorded, or null. */
export function documentVerdict (release?: DocumentRelease | null): string | null {
    const v = release?.document?.findings?.verdict
    return typeof v === 'string' ? v : null
}

/** Counts from a test report envelope, when present. */
export function testCounts (release?: DocumentRelease | null): { passed: number, failed: number, skipped: number } | null {
    const c = release?.document?.findings?.counts
    if (!c || typeof c !== 'object') return null
    return {
        passed: Number(c.passed ?? 0),
        failed: Number(c.failed ?? 0),
        skipped: Number(c.skipped ?? 0),
    }
}

/** Short label for a document release: type, round, verdict. */
export function documentLabel (release?: DocumentRelease | null): string {
    const spec = release?.document?.specification
    if (!spec) return '—'
    const pretty = spec.toLowerCase().replace(/_/g, ' ')
    const round = release?.document?.round
    return round ? `${pretty} · round ${round}` : pretty
}

/**
 * Findings of one index, newest-first ordering being the caller's job.
 *
 * Returns [] rather than null for anything unindexed, so a caller can concatenate without guards.
 */
export function findingsOf (release?: DocumentRelease | null): Finding[] {
    const f = release?.document?.findings?.findings
    return Array.isArray(f) ? f as Finding[] : []
}

/** Lifecycles in which a round is not a round yet, or no longer one: a reservation mid-cut or abandoned. */
const UNSETTLED = ['PENDING', 'CANCELLED', 'REJECTED']

/**
 * The newest round of one indexed type among a task's documents, which the server returns newest
 * first. The newest round is the current state: each one carries forward what the last left open.
 */
export function latestRound (documents: DocumentRelease[] | null | undefined, spec: string): DocumentRelease | null {
    return (documents ?? []).find(d => d?.document?.specification === spec && !!d?.document?.findings
        && !UNSETTLED.includes(d?.lifecycle ?? '')) ?? null
}

/** Task states in which a person may decide findings; on a hold the server also refuses a new blocking item. */
export const DECIDABLE_STATUSES = ['QUEUED', 'AWAITING_COORDINATOR', 'ON_HOLD']

/**
 * The open findings that stop a person completing a task: over the newest REVIEW_FINDINGS and
 * TEST_REPORT rounds, at or above the board's completion priority (1 is highest, so at or above
 * means a number no greater than it). A null threshold, or a finding with no priority, counts every
 * open item. Mirrors the server's check so the complete dialog can offer to decide them first.
 */
export function completionBlockers (documents: DocumentRelease[] | null | undefined,
    completionPriority?: number | null): { specification: string, finding: Finding }[] {
    const out: { specification: string, finding: Finding }[] = []
    for (const spec of INDEXED_TYPES) {
        for (const f of sortFindings(openFindingsOf(latestRound(documents, spec)))) {
            if (completionPriority == null || typeof f.priority !== 'number' || f.priority <= completionPriority) {
                out.push({ specification: spec, finding: f })
            }
        }
    }
    return out
}

/** Open findings only. */
export function openFindingsOf (release?: DocumentRelease | null): Finding[] {
    return findingsOf(release).filter(f => f?.status === 'OPEN')
}

/**
 * Sort findings for display: priority ascending (1 is highest), then id.
 *
 * A priority outside the org's current scale still sorts as the integer it is, rather than being
 * dropped or pushed to the end. Lowering the level count is validated at publish only, so history
 * legitimately carries higher numbers and a reader that hid them would hide real findings.
 */
export function sortFindings (findings: Finding[]): Finding[] {
    return [...findings].sort((a, b) => {
        const pa = typeof a?.priority === 'number' ? a.priority : Number.MAX_SAFE_INTEGER
        const pb = typeof b?.priority === 'number' ? b.priority : Number.MAX_SAFE_INTEGER
        if (pa !== pb) return pa - pb
        return String(a?.id ?? '').localeCompare(String(b?.id ?? ''))
    })
}

/** Findings grouped by priority, lowest number first. */
export function groupByPriority (findings: Finding[]): { priority: number | null, findings: Finding[] }[] {
    const groups = new Map<number | null, Finding[]>()
    for (const f of sortFindings(findings)) {
        const p = typeof f?.priority === 'number' ? f.priority : null
        if (!groups.has(p)) groups.set(p, [])
        groups.get(p)!.push(f)
    }
    return Array.from(groups.entries()).map(([priority, fs]) => ({ priority, findings: fs }))
}

/** Where a finding points, as a short string. */
export function findingLocation (f?: Finding | null): string {
    const loc = f?.location
    if (!loc) return ''
    if (loc.ref) return String(loc.ref)
    if (loc.path) return loc.line ? `${loc.path}:${loc.line}` : String(loc.path)
    return ''
}

/**
 * Everything a finding's location says, for its tooltip: the file position and the ref together,
 * "path:line — ref". The short form above shows the ref alone when there is one, so without this
 * the path of a finding with a ref would be shown nowhere.
 */
export function findingLocationFull (f?: Finding | null): string {
    const loc = f?.location
    if (!loc) return ''
    const at = loc.path ? (loc.line ? `${loc.path}:${loc.line}` : String(loc.path)) : ''
    const ref = loc.ref ? String(loc.ref) : ''
    return at && ref ? `${at} — ${ref}` : (at || ref)
}

/** Tag colour for a finding status. */
export function statusType (status?: string | null): 'success' | 'warning' | 'error' | 'info' | 'default' {
    switch (status) {
    case 'OPEN': return 'error'
    case 'RESOLVED': return 'success'
    case 'ACCEPTED': return 'warning'
    case 'WITHDRAWN': return 'default'
    default: return 'default'
    }
}

/** Tag colour for a verdict. */
export function verdictType (verdict?: string | null): 'success' | 'error' | 'default' {
    if (verdict === 'PASSED') return 'success'
    if (verdict === 'REJECTED') return 'error'
    return 'default'
}

/**
 * The document releases a hop recorded as its outputs, resolved against the task's documents.
 *
 * A hop stores uuids; the task carries the releases. Resolving here keeps the drawer from holding
 * two shapes of the same thing, and silently skips an id the task no longer lists rather than
 * rendering a blank row.
 */
export function outputsOfHop (outputs: string[] | null | undefined,
    documents: DocumentRelease[] | null | undefined): DocumentRelease[] {
    if (!outputs?.length || !documents?.length) return []
    const byUuid = new Map(documents.filter(d => d?.uuid).map(d => [d.uuid as string, d]))
    return outputs.map(u => byUuid.get(u)).filter(Boolean) as DocumentRelease[]
}

/** Path templates the server falls back to, shown as placeholders in board settings. */
/** The built-in index types, which every board's template form lists. */
export const INDEX_DOCUMENT_TYPES = ['REVIEW_FINDINGS', 'TEST_REPORT', 'QUESTIONS']

/**
 * The rows of a board's path-template form: every index type and every type an active role
 * produces, each with the server's template after overrides and scope defaults as its placeholder
 * (AgentBoard.effectiveDocumentPaths). No default is worked out here -- the server owns the rule
 * (gaps §1.18), so the form cannot show a path the CLI would not use.
 */
export function templateRows (roles: any[] | null | undefined,
    effective: Record<string, string> | null | undefined): { spec: string, placeholder: string }[] {
    const produced = (roles ?? []).filter((r: any) => r?.active !== false)
        .flatMap((r: any) => (r?.producesOutputs ?? []).map((p: any) => p?.specification))
        .filter(Boolean) as string[]
    return [...new Set([...INDEX_DOCUMENT_TYPES, ...produced])]
        .map(spec => ({ spec, placeholder: effective?.[spec] ?? 'the default for its scope' }))
}

/**
 * What a board document's lifecycle means (operator-actions D13-D15, task 0192a587): written,
 * handed over by its producer's sign-off, or reviewed by a reviewer's pass or a person at a gate.
 * Later stages are people's, and read as themselves.
 */
export function documentLifecycleLabel (release?: DocumentRelease | null): { label: string, type: 'default' | 'info' | 'success' } | null {
    const lc = release?.lifecycle
    if (!lc) return null
    if (lc === 'DRAFT') return { label: 'draft', type: 'default' }
    if (lc === 'ASSEMBLED') return { label: 'handed over', type: 'info' }
    if (lc === 'READY_TO_SHIP') return { label: 'reviewed', type: 'success' }
    return { label: lc.toLowerCase().replace(/_/g, ' '), type: 'default' }
}
