/**
 * A role's model-strength settings as the role and preset editors hold them, and the conversions
 * to and from the API. The server reads a strength field sent as null as "clear it", so the
 * editors always send all four: the form shows the whole state, and what it shows is what is saved.
 */
export interface StrengthDraft {
    requiredStrength: number | null
    strengthHeadroom: number | null
    strengthCategory: string | null
    modelStrengths: { model: string | null, strength: number | null }[]
}

export function strengthDraft (r: any): StrengthDraft {
    return {
        requiredStrength: r?.requiredStrength ?? null,
        strengthHeadroom: r?.strengthHeadroom ?? 0,
        strengthCategory: r?.strengthCategory ?? null,
        modelStrengths: (r?.modelStrengths ?? []).map((o: any) => ({ model: o.model, strength: o.strength })),
    }
}

/** The API input for a draft. Throws on an override row that names no model or no strength. */
export function strengthInput (d: StrengthDraft) {
    const incomplete = d.modelStrengths.find(o => !o.model || o.strength == null)
    if (incomplete) throw new Error('Each model override needs a model and a strength')
    return {
        requiredStrength: d.requiredStrength ?? null,
        strengthHeadroom: d.requiredStrength == null ? 0 : (d.strengthHeadroom ?? 0),
        strengthCategory: d.strengthCategory ?? null,
        modelStrengths: d.modelStrengths.map(o => ({ model: o.model, strength: o.strength })),
    }
}

/** One-line summary for tables: "≥ 3.5 (+0.5) · ARCHITECT · 2 overrides". */
export function strengthSummary (r: any): string {
    const parts: string[] = []
    if (r?.requiredStrength != null) {
        parts.push(`≥ ${r.requiredStrength}` + (r.strengthHeadroom ? ` (+${r.strengthHeadroom})` : ''))
    }
    if (r?.strengthCategory) parts.push(r.strengthCategory)
    const n = r?.modelStrengths?.length ?? 0
    if (n) parts.push(`${n} override${n === 1 ? '' : 's'}`)
    return parts.join(' · ') || '—'
}

/**
 * The outputs to save for a role, given what it had and which types the editor now selects.
 * The editor only picks types, so an output the role already had keeps its scope and whether it
 * is required -- rewriting every one as TASK-scoped and required would turn an optional output
 * into one the role cannot sign off without. Defaults apply to newly added types only.
 */
export function mergeOutputs (existing: any[] | null | undefined, selected: string[]) {
    const bySpec = new Map((existing ?? []).map((o: any) => [o?.specification, o]))
    return selected.map(spec => {
        const had = bySpec.get(spec)
        return had
            ? { specification: spec, scope: had.scope ?? 'TASK', required: had.required !== false }
            : { specification: spec, scope: 'TASK', required: true }
    })
}
