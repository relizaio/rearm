// What a sign-off reviewed and what its review did to each document (task fda2c9f1), as the hop
// row's chips. Pure, so the specs need no store.

export interface ReviewedInput {
    release?: string | null
    specification?: string | null
    round?: number | null
    promotedTo?: string | null
}

export interface RefusedPromotion {
    release?: string | null
    specification?: string | null
    round?: number | null
    reason?: string | null
}

export interface ReviewedChip {
    release: string
    label: string
    type: 'success' | 'warning' | 'default'
    /** The guard's reason when it kept the document back; null otherwise. */
    title: string | null
}

function documentName (i: { specification?: string | null, round?: number | null, release?: string | null }): string {
    const spec = i.specification ?? (i.release ? `release ${i.release.slice(0, 8)}` : 'document')
    return typeof i.round === 'number' ? `${spec} round ${i.round}` : spec
}

/**
 * One chip per reviewed document: "reviewed: DETAILED_DESIGN round 2 → READY_TO_SHIP" when the
 * review promoted it, "… — not promoted" with the guard's reason when a guard refused, and the
 * bare "reviewed: …" when the review moved nothing (a rejection, a gate not yet approved).
 */
export function reviewedChips (signOff: { reviewedInputs?: ReviewedInput[] | null,
    refusedPromotions?: RefusedPromotion[] | null } | null | undefined): ReviewedChip[] {
    const refused = new Map<string, RefusedPromotion>()
    for (const r of signOff?.refusedPromotions ?? []) if (r?.release) refused.set(r.release, r)
    return (signOff?.reviewedInputs ?? []).filter(i => !!i?.release).map(i => {
        const name = documentName(i)
        const kept = refused.get(i.release as string)
        if (i.promotedTo) {
            return { release: i.release as string, label: `reviewed: ${name} → ${i.promotedTo}`, type: 'success', title: null }
        }
        if (kept) {
            return { release: i.release as string, label: `reviewed: ${name} — not promoted`, type: 'warning',
                title: kept.reason ?? 'An organization guard kept it where it was' }
        }
        return { release: i.release as string, label: `reviewed: ${name}`, type: 'default', title: null }
    })
}
