// Organization Settings tabs that moved under Programmatic Access. An old link (?tab=freeFormKeys, ?tab=apiKeyGroups)
// lands on Programmatic Access with that sub-tab selected.

/** The Programmatic Access sub-tabs, in the order they show. API Key Groups follows Free Form Keys: groups hold FREEFORM keys. */
export const PROGRAMMATIC_SUBTABS = ['freeFormKeys', 'apiKeyGroups', 'userKeys', 'scopedKeys', 'federatedIdentities', 'externalKeys'] as const
export type ProgrammaticSubTab = typeof PROGRAMMATIC_SUBTABS[number]

/** The tab every former top-level key tab now lives under. */
export const PROGRAMMATIC_TAB = 'programmaticAccess'

const FORMER_TOP_LEVEL: readonly ProgrammaticSubTab[] = ['freeFormKeys', 'apiKeyGroups']

/** Where a requested top-level tab lands: a former key tab becomes Programmatic Access with its sub-tab; any other tab as is. */
export function resolveOrgSettingsTab (tab: string): { tab: string, programmaticSubTab: ProgrammaticSubTab | null } {
    const sub = FORMER_TOP_LEVEL.find((t) => t === tab) ?? null
    return sub ? { tab: PROGRAMMATIC_TAB, programmaticSubTab: sub } : { tab, programmaticSubTab: null }
}
