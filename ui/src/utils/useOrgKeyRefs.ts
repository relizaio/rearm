import { ref, type Ref } from 'vue'

/**
 * The org's API keys, so a key that changed something reads as its key label in a history (task
 * t20261013-191133-18220). Only an org admin or a global admin may list them; anyone else keeps the short-id
 * label and sends no request. Each view holds its own instance: the org it loaded, an answer for an org it has
 * since left is dropped, and an empty or failed answer is asked again on the next load.
 */
export interface OrgKeyRefs {
    /** The org's keys, or [] until they arrive. */
    orgKeys: Ref<any[]>
    /** Loads the keys of this org once; does nothing without an org or when the viewer may not list them. */
    load: (orgUuid: string | null | undefined, canList: boolean) => Promise<void>
}

export function useOrgKeyRefs (fetchKeys: (orgUuid: string) => Promise<any[] | null | undefined>): OrgKeyRefs {
    const orgKeys: Ref<any[]> = ref([])
    // the org orgKeys holds, or is being loaded for; empty when there is nothing to keep
    let loadedOrg = ''

    async function load (orgUuid: string | null | undefined, canList: boolean): Promise<void> {
        if (!orgUuid || loadedOrg === orgUuid) return
        loadedOrg = ''
        if (orgKeys.value.length) orgKeys.value = []
        // checked on every load until it holds: permissions may not have loaded on the first one
        if (!canList) return
        loadedOrg = orgUuid
        let keys: any[] = []
        try {
            keys = (await fetchKeys(orgUuid)) || []
        } catch (err) {
            console.error(err)
        }
        if (loadedOrg !== orgUuid) return
        orgKeys.value = keys
        // nothing back (a failed read, or an org without keys): ask again on the next load
        if (!keys.length) loadedOrg = ''
    }

    return { orgKeys, load }
}
