import { ref, type Ref } from 'vue'
import { loadSbomComponentsPage } from './sbomComponentsQuery'
import type { DriftFallbackClient } from './graphqlDriftFallback'
import type { SupportAttestationFilter } from './sbomComponentsQuery'

/**
 * Paging + filtering state for a release's SBOM component list.
 *
 * Extracted from ReleaseView.vue rather than left inline for two reasons, both found in
 * review. First, none of it was testable inside a 6,000-line SFC, and the bugs here are
 * ordering bugs that only a test can hold down. Second, the fix for those bugs is a request
 * fence, and a fence is only trustworthy if EVERY assignment goes through it -- which is
 * much easier to see, and to review, in eighty lines than scattered through a component.
 *
 * The fence: every request captures a sequence number and the release it was issued for,
 * and writes nothing back unless both still match on return. Three real failures need it:
 *
 *  - A "Load more" in flight when the filter changes. The late response would otherwise
 *    concat ALL rows onto an UNATTESTED list and overwrite the cursor, so the next page
 *    would resume from a position in the other ordering -- skipping rows and re-appending
 *    others under a filter label that does not describe them.
 *  - Two debounced searches resolving out of order, leaving the list and cursor from a
 *    stale query while the input shows the new one.
 *  - A trailing debounce firing after the user navigated to another release. The old
 *    release's components would load and mark the list loaded, so every later load
 *    early-returned and release B kept showing release A's SBOM.
 */
export interface SbomComponentsPaging {
    items: Ref<any[]>
    totalCount: Ref<number>
    hasMore: Ref<boolean>
    loading: Ref<boolean>
    loadingMore: Ref<boolean>
    loaded: Ref<boolean>
    failed: Ref<boolean>
    degraded: Ref<boolean>
    filter: Ref<SupportAttestationFilter>
    searchInput: Ref<string>
    /**
     * The filter and search the CURRENTLY DISPLAYED rows were fetched with, as opposed to
     * what the controls show. They differ during the debounce and during any in-flight
     * request, and labels must key off these: saying "3 matching" next to unfiltered rows,
     * or "no components match" while the applied search is still empty, states something
     * about the data that is not true yet.
     */
    appliedFilter: Ref<SupportAttestationFilter>
    appliedSearch: Ref<string>
    load: (force?: boolean) => Promise<void>
    loadMore: () => Promise<void>
    onFilterChange: () => void
    onSearchInput: () => void
    reset: () => void
    dispose: () => void
}

export interface SbomComponentsPagingDeps {
    client: DriftFallbackClient
    /** Read at issue time AND re-read on return; a change discards the response. */
    releaseUuid: () => string | undefined
    onError: (message: string) => void
    /** Invoked only when a load actually replaces the list, so callers can drop caches. */
    onDataReplaced?: () => void
    pageSize?: number
    debounceMs?: number
}

export function useSbomComponentsPaging (deps: SbomComponentsPagingDeps): SbomComponentsPaging {
    const pageSize = deps.pageSize ?? 50
    const debounceMs = deps.debounceMs ?? 300

    const items: Ref<any[]> = ref([])
    const totalCount = ref(0)
    const hasMore = ref(false)
    const loading = ref(false)
    const loadingMore = ref(false)
    const loaded = ref(false)
    const failed = ref(false)
    const degraded = ref(false)
    const filter: Ref<SupportAttestationFilter> = ref('ALL')
    const searchInput = ref('')
    const appliedFilter: Ref<SupportAttestationFilter> = ref('ALL')
    const appliedSearch = ref('')

    let cursor: string | null = null
    let seq = 0
    let debounceTimer: ReturnType<typeof setTimeout> | null = null

    /** Invalidates every in-flight request. Returns the token for the new one. */
    function nextSeq (): number {
        seq += 1
        return seq
    }

    function stale (mySeq: number, myRelease: string | undefined): boolean {
        return mySeq !== seq || myRelease !== deps.releaseUuid()
    }

    async function load (force: boolean = false): Promise<void> {
        const releaseUuid = deps.releaseUuid()
        if (!releaseUuid) return
        if (loaded.value && !force) return
        const mySeq = nextSeq()
        cursor = null
        loading.value = true
        const reqFilter = filter.value
        const reqSearch = searchInput.value
        try {
            const page = await loadSbomComponentsPage(deps.client, releaseUuid, {
                attestation: reqFilter, search: reqSearch, limit: pageSize
            })
            if (stale(mySeq, releaseUuid)) return
            items.value = page.items
            totalCount.value = page.totalCount
            cursor = page.endCursor
            hasMore.value = page.hasMore
            degraded.value = page.degraded
            // A degraded page is the WHOLE release, unfiltered. Reporting the requested
            // filter as applied would label rows the server never filtered.
            appliedFilter.value = page.degraded ? 'ALL' : reqFilter
            appliedSearch.value = page.degraded ? '' : reqSearch
            loaded.value = true
            failed.value = false
            if (deps.onDataReplaced) deps.onDataReplaced()
        } catch (err: any) {
            if (stale(mySeq, releaseUuid)) return
            // Clear rather than leave the previous filter's rows on screen under the new
            // filter's label -- that reads as a successful, differently-filtered result.
            items.value = []
            totalCount.value = 0
            hasMore.value = false
            cursor = null
            failed.value = true
            loaded.value = true
            appliedFilter.value = reqFilter
            appliedSearch.value = reqSearch
            deps.onError(err?.message || String(err))
        } finally {
            if (!stale(mySeq, releaseUuid)) loading.value = false
        }
    }

    async function loadMore (): Promise<void> {
        const releaseUuid = deps.releaseUuid()
        // A null cursor with hasMore true would send after: null and append page one again,
        // forever. Guard on the cursor, not just on hasMore.
        if (!releaseUuid || !hasMore.value || !cursor || loadingMore.value || loading.value) return
        const mySeq = nextSeq()
        const after = cursor
        loadingMore.value = true
        try {
            const page = await loadSbomComponentsPage(deps.client, releaseUuid, {
                attestation: appliedFilter.value, search: appliedSearch.value,
                limit: pageSize, after
            })
            if (stale(mySeq, releaseUuid)) return
            items.value = items.value.concat(page.items)
            totalCount.value = page.totalCount
            cursor = page.endCursor
            hasMore.value = page.hasMore
        } catch (err: any) {
            if (stale(mySeq, releaseUuid)) return
            // Cursor and hasMore deliberately untouched: the button stays and a retry
            // resumes from the correct position rather than stranding the walk.
            deps.onError(err?.message || String(err))
        } finally {
            if (!stale(mySeq, releaseUuid)) loadingMore.value = false
        }
    }

    function reloadFromStart (): void {
        loaded.value = false
        load(true)
    }

    function onFilterChange (): void {
        cancelDebounce()
        reloadFromStart()
    }

    function onSearchInput (): void {
        cancelDebounce()
        debounceTimer = setTimeout(() => { debounceTimer = null; reloadFromStart() }, debounceMs)
    }

    function cancelDebounce (): void {
        if (debounceTimer) {
            clearTimeout(debounceTimer)
            debounceTimer = null
        }
    }

    /** Release changed. Everything about the old release is now wrong, including in flight. */
    function reset (): void {
        cancelDebounce()
        nextSeq()
        items.value = []
        totalCount.value = 0
        hasMore.value = false
        cursor = null
        loading.value = false
        loadingMore.value = false
        loaded.value = false
        failed.value = false
        degraded.value = false
        appliedFilter.value = filter.value
        appliedSearch.value = searchInput.value
    }

    function dispose (): void {
        cancelDebounce()
        // Bump the fence so anything already awaiting discards its result instead of
        // writing to refs on a component that is gone.
        nextSeq()
    }

    return {
        items, totalCount, hasMore, loading, loadingMore, loaded, failed, degraded,
        filter, searchInput, appliedFilter, appliedSearch,
        load, loadMore, onFilterChange, onSearchInput, reset, dispose
    }
}
