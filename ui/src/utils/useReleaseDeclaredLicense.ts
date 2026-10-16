import { computed, ref, type ComputedRef, type Ref } from 'vue'
import commonFunctions from './commonFunctions'
import {
    UPDATE_RELEASE_DECLARED_LICENSE, declaredLicenseDiffers, loadReleaseDeclaredLicense, releaseDeclaredLicenseVariables,
    type DeclaredLicense, type DeclaredLicenseClient,
} from './declaredLicense'

/**
 * The release page's "Declared licence for this release" block (SCORE-42, design ADR-8),
 * extracted from ReleaseView.vue so its behaviour runs in a spec.
 *
 * ReleaseView is reused across releases rather than remounted (prev/next, the route watch, the
 * embedded modal), so release-scoped state needs a reset and a request fence: a response for
 * release A landing after the operator moved to B must not show A's effective licence on B, nor
 * leave A's baseline under B's Save. A source scan of the SFC cannot prove a reset or a fence is
 * live (useReleaseSupportCoverage.ts says why); running this can.
 *
 * `load` is also the one place a read error is swallowed: it never rejects, so the view calls it
 * without await and nothing after it in onMounted or goToRelease can be skipped. Drift and a
 * failed request stay apart: drift calls `onUnsupported` (the session flag and the note
 * everywhere), a failed request only leaves this block hidden and claims nothing about the server.
 */
export interface ReleaseDeclaredLicenseState {
    /** The input; '' when inheriting. */
    override: Ref<string>
    /** The stored override as last served. */
    baseline: Ref<string>
    effective: Ref<DeclaredLicense | null>
    /** True only after a successful read for the current release. */
    loaded: Ref<boolean>
    saving: Ref<boolean>
    /** A save refusal, as the server worded it; cleared on input and on reset. */
    error: Ref<string | null>
    dirty: ComputedRef<boolean>
    isOverridden: ComputedRef<boolean>
    load: (releaseUuid?: string, orgUuid?: string) => Promise<'loaded' | 'unsupported' | 'failed' | 'skipped' | 'dropped'>
    save: (clear?: boolean) => Promise<'saved' | 'cleared' | 'unchanged' | 'refused' | 'dropped'>
    reset: () => void
}

export function useReleaseDeclaredLicense (
    client: DeclaredLicenseClient,
    hooks: { onUnsupported: () => void }
): ReleaseDeclaredLicenseState {
    const override = ref('')
    const baseline = ref('')
    const effective: Ref<DeclaredLicense | null> = ref(null)
    const loaded = ref(false)
    const saving = ref(false)
    const error: Ref<string | null> = ref(null)

    const dirty = computed((): boolean => declaredLicenseDiffers({ expression: override.value }, { expression: baseline.value }))
    const isOverridden = computed((): boolean => baseline.value.trim() !== '')

    let seq = 0
    // The release the last load was issued for; save writes to it and to nothing else.
    let ids: { releaseUuid: string, orgUuid: string } | null = null

    async function load (releaseUuid?: string, orgUuid?: string): Promise<'loaded' | 'unsupported' | 'failed' | 'skipped' | 'dropped'> {
        if (!releaseUuid) return 'skipped'
        const mySeq = ++seq
        ids = { releaseUuid, orgUuid: orgUuid || '' }
        try {
            const r = await loadReleaseDeclaredLicense(client, releaseUuid, orgUuid)
            if (mySeq !== seq) return 'dropped'
            if (!r.supported) {
                loaded.value = false
                hooks.onUnsupported()
                return 'unsupported'
            }
            override.value = r.override
            baseline.value = r.override
            effective.value = r.effective
            error.value = null
            loaded.value = true
            return 'loaded'
        } catch {
            if (mySeq !== seq) return 'dropped'
            // A failed request is not an unanswerable server: hide this block, notify nothing.
            loaded.value = false
            effective.value = null
            return 'failed'
        }
    }

    async function save (clear = false): Promise<'saved' | 'cleared' | 'unchanged' | 'refused' | 'dropped'> {
        if (clear) override.value = ''
        if (!ids) return 'unchanged'
        const vars = releaseDeclaredLicenseVariables(ids.releaseUuid, ids.orgUuid,
            { expression: override.value }, { expression: baseline.value })
        // Null means nothing to send: the clear is '' and is sent; null would leave the override.
        if (!vars) return 'unchanged'
        const mySeq = seq
        saving.value = true
        try {
            const resp = await client.mutate({ mutation: UPDATE_RELEASE_DECLARED_LICENSE, variables: vars, fetchPolicy: 'no-cache' })
            if (mySeq !== seq) return 'dropped'
            const saved = resp?.data?.updateRelease
            // From the mutation's own response: the canonical form and the level that now wins.
            baseline.value = saved?.licenseExpression || ''
            override.value = baseline.value
            effective.value = saved?.effectiveLicense ?? null
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
        override.value = ''
        baseline.value = ''
        effective.value = null
        loaded.value = false
        saving.value = false
        error.value = null
    }

    return { override, baseline, effective, loaded, saving, error, dirty, isOverridden, load, save, reset }
}
