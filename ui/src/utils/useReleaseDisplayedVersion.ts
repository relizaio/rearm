import { computed, ref, type ComputedRef, type Ref } from 'vue'
import commonFunctions from './commonFunctions'
import {
    UPDATE_RELEASE_DISPLAYED_VERSION, displayedVersionVariables, loadReleaseDisplayedVersion,
    type DisplayedVersionClient,
} from './displayedVersion'

/**
 * The release page's "Displayed version" block (FDAW-74 3.7), the shape of
 * useReleaseDeclaredLicense.ts: ReleaseView is reused across releases, so the state has a reset and
 * a request fence, and `load` never rejects. A server without the field leaves the block hidden and
 * prints no note.
 */
export interface ReleaseDisplayedVersionState {
    /** The input; '' when not set. */
    value: Ref<string>
    /** The stored value as last served; '' when not set. */
    baseline: Ref<string>
    /** True only after a successful read for the current release. */
    loaded: Ref<boolean>
    saving: Ref<boolean>
    /** A save refusal, as the server worded it; cleared on a later success and on reset. */
    error: Ref<string | null>
    dirty: ComputedRef<boolean>
    isSet: ComputedRef<boolean>
    load: (releaseUuid?: string, orgUuid?: string) => Promise<'loaded' | 'unsupported' | 'failed' | 'skipped' | 'dropped'>
    save: (clear?: boolean) => Promise<'saved' | 'cleared' | 'unchanged' | 'refused' | 'dropped'>
    reset: () => void
}

export function useReleaseDisplayedVersion (client: DisplayedVersionClient): ReleaseDisplayedVersionState {
    const value = ref('')
    const baseline = ref('')
    const loaded = ref(false)
    const saving = ref(false)
    const error: Ref<string | null> = ref(null)

    const dirty = computed((): boolean => value.value.trim() !== baseline.value.trim())
    const isSet = computed((): boolean => baseline.value.trim() !== '')

    let seq = 0
    // The release the last load was issued for; save writes to it and to nothing else.
    let ids: { releaseUuid: string, orgUuid: string } | null = null

    async function load (releaseUuid?: string, orgUuid?: string): Promise<'loaded' | 'unsupported' | 'failed' | 'skipped' | 'dropped'> {
        if (!releaseUuid) return 'skipped'
        const mySeq = ++seq
        ids = { releaseUuid, orgUuid: orgUuid || '' }
        try {
            const r = await loadReleaseDisplayedVersion(client, releaseUuid, orgUuid)
            if (mySeq !== seq) return 'dropped'
            if (!r.supported) {
                loaded.value = false
                return 'unsupported'
            }
            value.value = r.value || ''
            baseline.value = r.value || ''
            error.value = null
            loaded.value = true
            return 'loaded'
        } catch {
            if (mySeq !== seq) return 'dropped'
            loaded.value = false
            return 'failed'
        }
    }

    async function save (clear = false): Promise<'saved' | 'cleared' | 'unchanged' | 'refused' | 'dropped'> {
        if (clear) value.value = ''
        if (!ids) return 'unchanged'
        const vars = displayedVersionVariables(ids.releaseUuid, ids.orgUuid, value.value, baseline.value)
        if (!vars) return 'unchanged'
        const mySeq = seq
        saving.value = true
        try {
            const resp = await client.mutate({ mutation: UPDATE_RELEASE_DISPLAYED_VERSION, variables: vars, fetchPolicy: 'no-cache' })
            if (mySeq !== seq) return 'dropped'
            const stored = resp?.data?.updateRelease?.displayedVersion
            baseline.value = typeof stored === 'string' ? stored : ''
            value.value = baseline.value
            error.value = null
            return baseline.value ? 'saved' : 'cleared'
        } catch (e: any) {
            if (mySeq !== seq) return 'dropped'
            error.value = commonFunctions.extractGraphQLErrorMessage(e)
            return 'refused'
        } finally {
            if (mySeq === seq) saving.value = false
        }
    }

    /** Release changed: everything about the old one is wrong, including anything in flight. */
    function reset (): void {
        seq += 1
        ids = null
        value.value = ''
        baseline.value = ''
        loaded.value = false
        saving.value = false
        error.value = null
    }

    return { value, baseline, loaded, saving, error, dirty, isSet, load, save, reset }
}
