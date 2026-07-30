// Shared builder for notification-channel <n-select> options.
//
// Extracted because two surfaces now pick channels -- subscription routes and a
// Team's own channels (T3) -- and the tricky half is identical in both: a saved
// reference can point at a channel that has since been DISABLED or DELETED.
// Neither appears in the enabled list, so the select tag would render a bare
// UUID and the operator could not tell what it was, let alone clean it up.
// Duplicating that logic is how the second copy silently loses the fix.

export interface ChannelLike {
    uuid: string
    name?: string
    type?: string
    status?: string
}

export interface SelectOption {
    label: string
    value: string
}

/**
 * Generic ghost-option builder. Both channel and team pickers hide entries that
 * are no longer selectable (disabled channels, deactivated teams) while a saved
 * reference may still point at one -- and a bare uuid tag is unremovable in
 * practice because the operator cannot tell what it is. Ghosts are deliberately
 * NOT marked `disabled`: that makes the tag non-removable in n-select, trapping
 * the dangling reference the operator opened the editor to clear.
 */
export function withGhosts<T extends { uuid: string }>(
    selectable: SelectOption[],
    all: T[],
    referenced: Iterable<string>,
    ghostLabel: (found: T | undefined, uuid: string) => string,
): SelectOption[] {
    const known = new Set(selectable.map(o => o.value))
    const byUuid = new Map(all.map(x => [x.uuid, x]))
    const ghosts: SelectOption[] = []
    const seen = new Set<string>()
    for (const u of referenced) {
        if (known.has(u) || seen.has(u)) continue
        seen.add(u)
        ghosts.push({ label: ghostLabel(byUuid.get(u), u), value: u })
    }
    return [...selectable, ...ghosts]
}

export function channelLabel (c: ChannelLike, typeLabels: Record<string, string> = {}): string {
    return `${c.name} (${typeLabels[c.type || ''] || c.type})`
}

/**
 * Enabled channels, plus a labelled "ghost" option for every already-referenced
 * value that is no longer selectable.
 *
 * Ghosts are deliberately NOT marked `disabled`: a disabled option makes the
 * selected tag non-removable in n-select, which would trap the very dangling
 * reference the operator opened the editor to remove.
 *
 * @param referenced values already saved against this field, which must remain
 *                   renderable even when they are no longer valid choices
 */
export function buildChannelOptions (
    channels: ChannelLike[],
    referenced: Iterable<string>,
    typeLabels: Record<string, string> = {},
): SelectOption[] {
    const list = channels || []
    const selectable = list
        .filter(c => c.status === 'ENABLED')
        .map(c => ({ label: channelLabel(c, typeLabels), value: c.uuid }))
    return withGhosts(selectable, list, referenced, (found, uuid) => found
        ? `${channelLabel(found, typeLabels)} (disabled)`
        : `(deleted channel) ${String(uuid).slice(0, 8)}`)
}
