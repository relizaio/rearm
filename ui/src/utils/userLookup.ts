import { Ref, ref, watch } from 'vue'
import gql from 'graphql-tag'
import graphqlClient from './graphql'

interface OrgUserBrief {
    uuid: string
    name?: string | null
    email?: string | null
}

const FETCH_ORG_USERS = gql`
    query FetchOrgUsersBrief($orgUuid: ID!) {
        users(orgUuid: $orgUuid, includeInactive: true) {
            uuid
            name
            email
        }
    }
`

const cache = new Map<string, Map<string, OrgUserBrief>>()
const inflight = new Map<string, Promise<Map<string, OrgUserBrief>>>()

async function loadIntoCache (orgUuid: string): Promise<Map<string, OrgUserBrief>> {
    if (cache.has(orgUuid)) return cache.get(orgUuid)!
    if (inflight.has(orgUuid)) return inflight.get(orgUuid)!
    const p = (async () => {
        try {
            const r = await graphqlClient.query({
                query: FETCH_ORG_USERS,
                variables: { orgUuid },
                fetchPolicy: 'cache-first'
            })
            const list: OrgUserBrief[] = ((r.data as any)?.users ?? []) as OrgUserBrief[]
            const map = new Map<string, OrgUserBrief>()
            for (const u of list) if (u.uuid) map.set(u.uuid, u)
            cache.set(orgUuid, map)
            return map
        } finally {
            inflight.delete(orgUuid)
        }
    })()
    inflight.set(orgUuid, p)
    return p
}

/**
 * Reactive org-user lookup. Returns a reactive map keyed by user UUID;
 * caller can use `format(uuid)` to render a friendly string with a UUID fallback.
 *
 * Cached per-org for the page's lifetime — actedBy / attestedBy are stable IDs so
 * cache-first is fine.
 */
export function useOrgUsersIndex (orgUuid: Ref<string | undefined>) {
    const byUuid = ref<Record<string, OrgUserBrief>>({})

    const reload = async () => {
        if (!orgUuid.value) return
        const map = await loadIntoCache(orgUuid.value)
        const obj: Record<string, OrgUserBrief> = {}
        for (const [k, v] of map.entries()) obj[k] = v
        byUuid.value = obj
    }

    watch(orgUuid, reload, { immediate: true })

    function format (uuid: string | null | undefined): string {
        if (!uuid) return '—'
        const u = byUuid.value[uuid]
        if (!u) return uuid
        if (u.name && u.email) return `${u.name} (${u.email})`
        return u.name || u.email || uuid
    }

    return { byUuid, format, reload }
}
