// The pure model of the confirmation page (ReARM Pro, TPF-185 design 3.8): what the form starts
// with, the starting point as the backend takes it, the rules of the Follow button and the input of
// the confirm. The scope preview and the confirm are built from the same values by the same
// functions, so what the administrator saw previewed is what is confirmed (threat model row 7).
import { startingPointRequired } from './upstreamLabels'

/** Whether the root item, or a component, is created under a name or adopts an existing item. */
export enum ItemTarget {
    CREATE = 'CREATE',
    ADOPT = 'ADOPT',
}

export enum BackfillKind {
    LATEST_PER_LINE = 'LATEST_PER_LINE',
    FROM_DATE = 'FROM_DATE',
    FROM_RELEASE = 'FROM_RELEASE',
}

export enum DriftBehaviour {
    STOP = 'STOP',
    ADAPT = 'ADAPT',
}

export const NAME_MAX = 100
export const COUNT_MIN = 1
export const COUNT_MAX = 50

export const PREVIEW_PENDING = 'The preview is being computed.'
export const NOT_READY = 'The preparation is not ready to be confirmed.'
export const EXPIRED = 'This preparation has expired. Prepare the item again.'
export const NAME_REQUIRED = 'Enter a name for the item.'
export const NAME_TOO_LONG = `The name is at most ${NAME_MAX} characters.`
export const ADOPT_REQUIRED = 'Choose the item to adopt.'
export const DEPTH_REQUIRED = 'Choose a depth.'

export function overLimitSentence (releases: number, max: number): string {
    return `This starting point takes in ${releases} releases. At most ${max} can be followed at once: choose a later starting point.`
}

export function componentAdoptRequired (name: string): string {
    return `Choose the item to adopt for the component ${name}.`
}

/** The starting point as the form holds it: a kind and the fields of every kind, only one of which is sent. */
export interface BackfillModel {
    kind: BackfillKind | null
    /** LATEST_PER_LINE: the number field, null while empty */
    count: number | null
    /** FROM_DATE: the chosen day as yyyy-MM-dd, null while empty */
    day: string | null
    /** FROM_RELEASE: the upstream uuid of the picked release */
    release: string | null
}

export interface ComponentChoice {
    upstreamUuid: string
    target: ItemTarget
    adopt: string | null
    depth: number | null
}

export interface ConfirmModel {
    target: ItemTarget
    name: string
    adopt: string | null
    depth: number | null
    components: ComponentChoice[]
    onDrift: DriftBehaviour
    backfill: BackfillModel
}

/** The starting point input of the scope query and of the confirm. */
export type BackfillInput =
    | { kind: BackfillKind.LATEST_PER_LINE, count: number }
    | { kind: BackfillKind.FROM_DATE, date: string }
    | { kind: BackfillKind.FROM_RELEASE, release: string }

/** The newest answer of the scope query, kept with the key of the values it was asked for. */
export interface ScopeState {
    key: string | null
    answer: { releases: number, withinLimit: boolean } | null
    /** The sentence of a refused scope query for that key; counts as no valid scope. */
    error: string | null
}

const DAY = /^(\d{4})-(\d{2})-(\d{2})$/

/** The UTC instant of the start of a day given as yyyy-MM-dd, or null for anything else. */
export function dayStartUtc (day: string | null | undefined): string | null {
    if (typeof day !== 'string') return null
    const m = DAY.exec(day)
    if (!m) return null
    const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])))
    if (d.getUTCFullYear() !== Number(m[1]) || d.getUTCMonth() !== Number(m[2]) - 1 || d.getUTCDate() !== Number(m[3])) return null
    return d.toISOString()
}

/**
 * The starting point as the backend takes it, or null while the choice is incomplete or out of
 * range. Only the field of the chosen kind is ever in it: the backend refuses a mixed point.
 */
export function buildBackfillInput (model: Pick<ConfirmModel, 'backfill'>): BackfillInput | null {
    const b = model.backfill
    switch (b.kind) {
    case BackfillKind.LATEST_PER_LINE:
        if (typeof b.count !== 'number' || !Number.isInteger(b.count) || b.count < COUNT_MIN || b.count > COUNT_MAX) return null
        return { kind: BackfillKind.LATEST_PER_LINE, count: b.count }
    case BackfillKind.FROM_DATE: {
        const date = dayStartUtc(b.day)
        return date === null ? null : { kind: BackfillKind.FROM_DATE, date }
    }
    case BackfillKind.FROM_RELEASE:
        if (typeof b.release !== 'string' || b.release === '') return null
        return { kind: BackfillKind.FROM_RELEASE, release: b.release }
    default:
        return null
    }
}

/** A canonical string of a depth and a starting point: two values give the same key exactly when they are the same values. */
export function scopeKey (depth: number | null, backfill: BackfillInput | null): string {
    const b: any = backfill ?? {}
    return JSON.stringify([depth, b.kind ?? null, b.count ?? null, b.date ?? null, b.release ?? null])
}

/** The depth the root is confirmed at: the standing depth of an item already in ReARM, else the chosen one. */
export function effectiveDepth (model: ConfirmModel, preparation: any): number | null {
    const existing = preparation?.item?.existingItem
    if (existing) return existing.depth
    return model.depth
}

/** The variables of the scope query for the values in the form, or null while they are incomplete. */
export function scopeVariables (model: ConfirmModel, preparation: any): { preparation: string, depth: number, backfill: BackfillInput } | null {
    const depth = effectiveDepth(model, preparation)
    const backfill = buildBackfillInput(model)
    if (depth === null || backfill === null) return null
    return { preparation: preparation.uuid, depth, backfill }
}

/** The key of the values now in the form. */
export function currentScopeKey (model: ConfirmModel, preparation: any): string {
    return scopeKey(effectiveDepth(model, preparation), buildBackfillInput(model))
}

/** What the form starts with: create under the proposed name, the proposed depths, on-drift Stop and no starting point. */
export function initialConfirmModel (preparation: any): ConfirmModel {
    const item = preparation?.item ?? {}
    const components: ComponentChoice[] = (preparation?.components ?? []).map((c: any) => ({
        upstreamUuid: c.upstreamUuid,
        target: ItemTarget.CREATE,
        adopt: null,
        depth: c.existingItem ? c.existingItem.depth : c.proposedDepth,
    }))
    return {
        target: ItemTarget.CREATE,
        name: item.rearmName ?? '',
        adopt: null,
        depth: item.existingItem ? item.existingItem.depth : (typeof item.proposedDepth === 'number' ? item.proposedDepth : null),
        components,
        onDrift: DriftBehaviour.STOP,
        backfill: { kind: null, count: null, day: null, release: null },
    }
}

/** Whether a component of the preparation can be chosen for: read, and not already in ReARM. */
export function componentIsChoosable (component: any): boolean {
    return !component?.failure && !component?.existingItem
}

/** Whether the browser's clock is past the preparation's expiry. */
export function isExpired (preparation: any, now: Date): boolean {
    const expires = Date.parse(preparation?.expires ?? '')
    return Number.isFinite(expires) && now.getTime() >= expires
}

/**
 * Everything that still blocks the Follow button, as sentences; the button is enabled only when
 * the list is empty, and the confirm is not sent while it is not.
 */
export function confirmBlockers (model: ConfirmModel, preparation: any, scope: ScopeState, now: Date): string[] {
    const blockers: string[] = []
    if (preparation?.state !== 'READY') blockers.push(NOT_READY)
    else if (isExpired(preparation, now)) blockers.push(EXPIRED)

    if (!preparation?.item?.existingItem) {
        if (model.target === ItemTarget.ADOPT) {
            if (!model.adopt) blockers.push(ADOPT_REQUIRED)
        } else {
            const name = model.name.trim()
            if (!name) blockers.push(NAME_REQUIRED)
            else if (name.length > NAME_MAX) blockers.push(NAME_TOO_LONG)
        }
    }
    const components: any[] = preparation?.components ?? []
    for (const choice of model.components) {
        const c = components.find(x => x.upstreamUuid === choice.upstreamUuid)
        if (!c || !componentIsChoosable(c)) continue
        if (choice.target === ItemTarget.ADOPT && !choice.adopt) blockers.push(componentAdoptRequired(c.upstreamName))
    }

    const depth = effectiveDepth(model, preparation)
    if (depth === null) blockers.push(DEPTH_REQUIRED)
    const backfill = buildBackfillInput(model)
    if (backfill === null) blockers.push(startingPointRequired)

    if (depth !== null && backfill !== null) {
        const key = scopeKey(depth, backfill)
        if (scope.key !== key) {
            blockers.push(PREVIEW_PENDING)
        } else if (scope.error) {
            blockers.push(scope.error)
        } else if (!scope.answer) {
            blockers.push(PREVIEW_PENDING)
        } else if (!scope.answer.withinLimit) {
            blockers.push(overLimitSentence(scope.answer.releases, preparation?.maxBackfillReleases))
        }
    }
    return blockers
}

/**
 * UpstreamFollowConfirmInput for the values in the form. depth and backfill are the very values
 * the scope query is sent with (scopeVariables); name only when it differs from the proposal,
 * adopt only for an adoption, and per component only what differs from its proposal.
 */
export function buildConfirmInput (model: ConfirmModel, preparation: any): Record<string, any> {
    const item = preparation?.item ?? {}
    const input: Record<string, any> = {
        depth: effectiveDepth(model, preparation),
        backfill: buildBackfillInput(model),
        onDrift: model.onDrift,
    }
    if (!item.existingItem) {
        if (model.target === ItemTarget.ADOPT) {
            if (model.adopt) input.adopt = model.adopt
        } else {
            const name = model.name.trim()
            if (name !== item.rearmName) input.name = name
        }
    }
    const choices: Record<string, any>[] = []
    for (const c of preparation?.components ?? []) {
        if (!componentIsChoosable(c)) continue
        const choice = model.components.find(x => x.upstreamUuid === c.upstreamUuid)
        if (!choice) continue
        const entry: Record<string, any> = { upstreamUuid: c.upstreamUuid }
        if (choice.depth !== null && choice.depth !== c.proposedDepth) entry.depth = choice.depth
        if (choice.target === ItemTarget.ADOPT && choice.adopt) entry.adopt = choice.adopt
        if (Object.keys(entry).length > 1) choices.push(entry)
    }
    if (choices.length) input.components = choices
    return input
}
