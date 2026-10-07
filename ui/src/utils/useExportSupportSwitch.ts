import { computed, type ComputedRef } from 'vue'
import type { ReleaseSupportCoverage, SupportExportState } from './releaseSupportCoverage'

/**
 * The "Include support metadata" switch of the Export Release BOM dialog, out of ReleaseView.vue
 * so a test can RUN it: whether the switch is offered, which hint sits under it, and when opening
 * the dialog has to ask the server first (SCORE-21 round 2 ADR-11, after run 1 T-1).
 *
 * THE RELEASE'S ORGANIZATION DECIDES, NEVER THE ONE SELECTED IN THE HEADER. The server injects
 * the disclosure from the setting of the organization that owns the release, and
 * sbomComponentSupportCoverage(orgUuid, releaseUuid).supportExportState is that same predicate,
 * asked for this release. Reading the header organization's settings instead told a user who
 * opened another organization's release the opposite of what the downloaded file contained.
 */

/** Under the switch when the release's organization does not disclose support metadata. */
export const SUPPORT_HINT_DISABLED = 'Support disclosure is off for this organization. An organization admin turns it on in Organization Settings → Support disclosure export.'
/** Under the switch while the release's organization setting is being asked for. */
export const SUPPORT_HINT_CHECKING = 'Checking whether this organization discloses support metadata…'
/** Under the switch when the setting could not be read: request failed, UNKNOWN, retired PARTIAL. */
export const SUPPORT_HINT_UNKNOWN = 'ReARM could not determine whether this organization discloses support metadata. The export follows the organization setting.'

/**
 * The release organization's export state as the dialog reads it. No coverage (not loaded yet,
 * loading after a release change, or a failed request) is UNKNOWN: "we do not know" is the only
 * answer that cannot mislead.
 */
export function releaseExportState (coverage: ReleaseSupportCoverage | null | undefined): SupportExportState {
    return coverage?.exportState ?? 'UNKNOWN'
}

/**
 * Whether the switch can be moved: the server offers the option AND the release's organization
 * answered ENABLED. Every other state, the retired PARTIAL included, keeps it disabled, and a
 * disabled switch sends null (supportMetadataArg), the organization default.
 */
export function supportSwitchOffered (supported: boolean, exportState: SupportExportState): boolean {
    return supported && exportState === 'ENABLED'
}

/**
 * The one hint under the switch, or null for none. Never a hint when the server does not offer
 * the option: the sync-lag line speaks there, and the two never show together.
 */
export function supportSwitchHint (supported: boolean, exportState: SupportExportState, loading: boolean): string | null {
    if (!supported || exportState === 'ENABLED') return null
    if (exportState === 'DISABLED') return SUPPORT_HINT_DISABLED
    return loading ? SUPPORT_HINT_CHECKING : SUPPORT_HINT_UNKNOWN
}

export interface ExportSupportSwitchDeps {
    /** The server declares the support-disclosure setting at all (the CE sync-lag latch). */
    supported: () => boolean
    /** The release-scoped coverage, null while unknown. */
    coverage: () => ReleaseSupportCoverage | null
    /** A coverage request is in flight. */
    loading: () => boolean
    /** Load the release's coverage; reports its own failure, never throws. */
    load: () => Promise<void>
}

export interface ExportSupportSwitch {
    /** The release organization's state: ENABLED, DISABLED, PARTIAL, or UNKNOWN for no coverage. */
    exportState: ComputedRef<SupportExportState>
    /** The switch can be moved: see supportSwitchOffered. */
    offered: ComputedRef<boolean>
    /** The one hint under the switch, or null: see supportSwitchHint. */
    hint: ComputedRef<string | null>
    /**
     * The dialog is opening: when nothing has asked for this release's coverage yet, ask now,
     * without waiting. The dialog opens at once in the checking state and flips when the answer
     * lands. Never a second request while one is in flight.
     */
    loadIfUnknown: () => void
}

/**
 * Getters rather than refs: the coverage state is set up further down ReleaseView than the
 * dialog's own state, and a getter is only read once the component renders.
 */
export function useExportSupportSwitch (deps: ExportSupportSwitchDeps): ExportSupportSwitch {
    const exportState = computed((): SupportExportState => releaseExportState(deps.coverage()))
    const offered = computed((): boolean => supportSwitchOffered(deps.supported(), exportState.value))
    const hint = computed((): string | null =>
        supportSwitchHint(deps.supported(), exportState.value, deps.loading()))

    function loadIfUnknown (): void {
        if (deps.coverage() === null && !deps.loading()) void deps.load()
    }

    return { exportState, offered, hint, loadIfUnknown }
}
