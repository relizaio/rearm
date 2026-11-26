// The model of the reconfigure dialog of a follow (ReARM Pro, TPF-185 design 3.9): one row per item
// of the follow (the root item and its components, those with held releases first), and the input
// of reconfigureUpstreamFollow built from what the administrator changed. Pure; imports nothing.

/** The depths an item can be given. */
export const RECONFIGURE_DEPTHS: ReadonlyArray<number> = [0, 1, 2, 3, 4]

/** The texts of the dialog: what the backend does, which is more than a change of depth. */
export const reconfigureAcceptSentence = 'Checking an item accepts its held releases. With the same depth they are accepted as they are. With another depth, that depth applies from now on; releases already ingested keep their lines.'
export const reconfigureRunSentence = 'A run is queued that places the accepted releases. They stay in the pending list until it has run.'
export const reconfigureDriftOnlySentence = 'Changing only the behaviour on drift accepts nothing and starts no run.'
export const proposalCaption = 'What ReARM would propose for this item\'s history as the source serves it now. This reads the source and counts against the organization\'s preparations (one at a time, 20 an hour). It follows nothing.'
export const nothingToChangeSentence = 'Nothing to change.'

/** One item of the follow in the dialog. accept and depth are what the administrator chose. */
export interface ReconfigureRow {
    /** The uuid of the upstream item row: the item argument of the input. */
    item: string
    name: string
    kind: string
    upstreamUuid: string
    currentDepth: number
    /** Held releases of this item among the pending rows the follow served. */
    held: number
    accept: boolean
    depth: number
}

export interface ReconfigureInput {
    items?: Array<{ item: string, depth: number }>
    onDrift?: string
}

/** The number of pending rows of each item, by the item's uuid. */
export function heldCountsOf (follow: any): Record<string, number> {
    const counts: Record<string, number> = {}
    for (const p of follow?.pending ?? []) {
        if (typeof p?.item !== 'string') continue
        counts[p.item] = (counts[p.item] ?? 0) + 1
    }
    return counts
}

/** The rows the dialog starts with: nothing checked, every depth at the item's current depth, held first. */
export function initialReconfigureRows (follow: any): ReconfigureRow[] {
    const counts = heldCountsOf(follow)
    const items = [follow?.item, ...(follow?.components ?? [])].filter((it: any) => it && typeof it.uuid === 'string')
    const rows = items.map((it: any): ReconfigureRow => ({
        item: it.uuid,
        name: it.upstreamName ?? '',
        kind: it.kind,
        upstreamUuid: it.upstreamUuid,
        currentDepth: it.depth,
        held: counts[it.uuid] ?? 0,
        accept: false,
        depth: it.depth,
    }))
    // A stable sort: the items with held releases first, otherwise in the follow's order.
    return rows.map((r, i) => [r, i] as const)
        .sort((a, b) => (Number(b[0].held > 0) - Number(a[0].held > 0)) || (a[1] - b[1]))
        .map(([r]) => r)
}

/**
 * The input of reconfigureUpstreamFollow: items holds { item, depth } for each checked row and is
 * left out when no row is checked; onDrift is left out when it is unchanged. An empty object means
 * there is nothing to change.
 */
export function buildReconfigureInput (rows: ReconfigureRow[], onDrift: string | null | undefined, follow: any): ReconfigureInput {
    const input: ReconfigureInput = {}
    const items = rows.filter(r => r.accept).map(r => ({ item: r.item, depth: r.depth }))
    if (items.length) input.items = items
    if (onDrift && onDrift !== follow?.onDrift) input.onDrift = onDrift
    return input
}

/** Whether an input built above changes anything. */
export function reconfigureChangesSomething (input: ReconfigureInput): boolean {
    return input.items !== undefined || input.onDrift !== undefined
}
