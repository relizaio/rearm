// The model catalogue page (task RD2-27): how a row reads, what its edit sends, which row a merge
// keeps, and when two rows are one model. Pure, so the page's rules are testable.

const UNKNOWN_VERSION = 'unknown'

/** The one pseudo-model placeholders resolve to (RD2-26), and the placeholder spellings older rows carry. */
export function isSyntheticRow (m: any): boolean {
    return m?.resolution === 'SYNTHETIC' || /^<?synthetic>?$/i.test(String(m?.name ?? '').trim())
}

function hasVersion (m: any): boolean {
    return !!m?.version && String(m.version).toLowerCase() !== UNKNOWN_VERSION
}

/**
 * A row's name: the canonical id of a resolved row, which is the model; the declared name of any
 * other. Never name and version run together ("claude-opus-5-5 1").
 */
export function modelLabel (m: any): string {
    if (m?.resolution === 'RESOLVED' && m?.canonicalId) return m.canonicalId
    return m?.name ?? '(unnamed)'
}

/** The second line: "declared as <name> · v<version>", the version left out when unknown. */
export function modelDeclaredAs (m: any): string {
    const name = m?.name ?? '(unnamed)'
    return `declared as ${name}${hasVersion(m) ? ` · v${m.version}` : ''}`
}

/** "3 sessions · 12 lines", or "unused" -- in the last usage.days days. */
export function usageLabel (u: { sessions?: number | null, lines?: number | null } | null | undefined): string {
    const sessions = u?.sessions ?? 0
    const lines = u?.lines ?? 0
    if (!sessions && !lines) return 'unused'
    return `${sessions} session${sessions === 1 ? '' : 's'} · ${lines} line${lines === 1 ? '' : 's'}`
}

/** The unresolved rows the banner counts: the synthetic pseudo-row is resolved by design, never "to map". */
export function unresolvedCount (models: any[]): number {
    return (models ?? []).filter(m => m?.resolution === 'UNRESOLVED' && !isSyntheticRow(m)).length
}

function hasPricing (m: any): boolean {
    return (m?.pricing ?? []).length > 0
}

/**
 * Which of two rows a merge keeps (RD2-26's rule, the one the sweep folds by): the resolved row that
 * carries pricing, else a resolved one, else the older. The other row is folded into it.
 */
export function mergeSurvivor (a: any, b: any): { survivor: any, folded: any } {
    const rank = (m: any) => (m?.resolution === 'RESOLVED' && hasPricing(m) ? 0 : m?.resolution === 'RESOLVED' ? 1 : 2)
    const ra = rank(a)
    const rb = rank(b)
    let aWins: boolean
    if (ra !== rb) aWins = ra < rb
    else aWins = String(a?.createdDate ?? '') <= String(b?.createdDate ?? '')
    return aWins ? { survivor: a, folded: b } : { survivor: b, folded: a }
}

/** The canonical ids more than one row carries: each is one model in several rows, to merge. */
export function sharedCanonicalIds (models: any[]): string[] {
    const counts = new Map<string, number>()
    for (const m of models ?? []) {
        const c = String(m?.canonicalId ?? '').trim().toLowerCase()
        if (c) counts.set(c, (counts.get(c) ?? 0) + 1)
    }
    return [...counts.entries()].filter(([, n]) => n > 1).map(([c]) => c).sort()
}

/** Whether a row shares its canonical id with another row. */
export function hasDuplicate (m: any, models: any[]): boolean {
    const c = String(m?.canonicalId ?? '').trim().toLowerCase()
    return !!c && sharedCanonicalIds(models).includes(c)
}

// ---------- the edit drawer ----------

export interface ModelDraft {
    publisher: string
    name: string
    version: string
    canonicalId: string
    tier: string | null
    strength: number | null
    description: string
    notes: string
}

/** The drawer's draft for a row; the canonical id a row lacks is prefilled with the normaliser's suggestion. */
export function modelDraftOf (m: any): ModelDraft {
    return {
        publisher: m?.publisher ?? '',
        name: m?.name ?? '',
        version: hasVersion(m) ? m.version : '',
        canonicalId: m?.canonicalId ?? m?.suggestedCanonicalId ?? '',
        tier: m?.tier ?? null,
        strength: m?.strength ?? null,
        description: m?.description ?? '',
        notes: m?.notes ?? '',
    }
}

/**
 * The ModelOntologyUpdateInput an edit sends: the uuid and only what changed, so an edit of the
 * description never re-resolves the row. A blank canonical id sends null (resolve from the name
 * again); a blank version sends '' (unknown).
 */
export function modelUpdateInput (m: any, d: ModelDraft): Record<string, any> {
    const input: Record<string, any> = { uuid: m.uuid }
    const text = (v: string) => v.trim()
    if (text(d.name) !== (m.name ?? '')) input.name = text(d.name)
    if (text(d.version) !== (hasVersion(m) ? m.version : '')) input.version = text(d.version)
    if (text(d.canonicalId) !== (m.canonicalId ?? '')) input.canonicalId = text(d.canonicalId) || null
    if (text(d.publisher) !== (m.publisher ?? '')) input.publisher = text(d.publisher)
    if (text(d.description) !== (m.description ?? '')) input.description = text(d.description)
    if (text(d.notes) !== (m.notes ?? '')) input.notes = text(d.notes)
    if ((d.tier ?? null) !== (m.tier ?? null) && d.tier) input.tier = d.tier
    if ((d.strength ?? null) !== (m.strength ?? null)) input.strength = d.strength
    return input
}

/** Whether an edit sends anything beyond the uuid. */
export function modelEditChanged (m: any, d: ModelDraft): boolean {
    return Object.keys(modelUpdateInput(m, d)).length > 1
}

/**
 * A canonical id the bundle does not know: kept as typed and the row counts as confirmed, but the
 * bundle prices none of it -- the form says so. Blank is not free text.
 */
export function canonicalIsFree (canonicalId: string | null | undefined, bundle: { canonicalId: string }[]): boolean {
    const c = String(canonicalId ?? '').trim().toLowerCase()
    return !!c && !(bundle ?? []).some(b => String(b.canonicalId).toLowerCase() === c)
}

/** The canonical id select's options: the bundle's entries, "id — name v" each. */
export function bundleOptions (bundle: { canonicalId: string, name?: string, version?: string, publisher?: string }[]):
    { label: string, value: string }[] {
    return (bundle ?? []).map(b => ({
        label: `${b.canonicalId}${b.publisher ? ` — ${b.publisher}` : ''}`,
        value: b.canonicalId,
    }))
}

/** Which field of the drawer a refusal is about, so it shows beside it; null for the form as a whole. */
export function modelFieldOfError (message: string | null | undefined): 'name' | 'canonicalId' | 'strength' | null {
    const m = (message ?? '').toLowerCase()
    if (!m) return null
    if (m.includes('canonical id')) return 'canonicalId'
    if (m.includes('already a row') || m.includes('needs a name')) return 'name'
    if (m.includes('strength')) return 'strength'
    return null
}

/**
 * The merge preview's sentence (RD2-27 run 1 T-2): which row points at which. Each row is named by its
 * label and what it was declared as, since the duplicates the hint sends people here for share a
 * canonical id -- and so a label.
 */
export function mergeDirection (plan: { survivor: any, folded: any } | null): string {
    if (!plan) return ''
    const name = (m: any) => `${modelLabel(m)} (${modelDeclaredAs(m)})`
    const why = plan.survivor?.resolution === 'RESOLVED' ? '' : ', the older row'
    return `${name(plan.folded)} will point at the survivor, ${name(plan.survivor)}${why}.`
}
