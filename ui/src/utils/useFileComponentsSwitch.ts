import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { FileSwitchUnsupportedError, exportWithMetadataFallback, fileSwitchAvailable, type ExportFallbackResult } from './exportMetadataFallback'
import { SBOM_EXPORT_CORE, SBOM_EXPORT_WITH_FILE_SWITCH, SBOM_EXPORT_WITH_METADATA_FLAGS } from './releaseSbomExportDocuments'

/**
 * "Leave out file components" in the Export Release BOM dialog (SCORE-11), out of ReleaseView.vue
 * so a test can RUN it: the switch, whether this server can honour it, which export document is
 * sent with which variables, and what a refusal does to the switch. Nothing in the unit suite
 * mounts ReleaseView, and a rule left inline there is held only by scanning its source text.
 */
export interface FileComponentsSwitch {
    /** The switch, off by default and on every opening of the dialog. */
    excludeFileComponents: Ref<boolean>
    /** This server rejected the document carrying the switch. */
    exportFileSwitchUnsupported: Ref<boolean>
    /** The switch can be offered: neither latch is set. */
    exportFileSwitchAvailable: ComputedRef<boolean>
    /** What the export and the score send: the switch, counted only where it can be honoured. */
    excludeFileComponentsRequested: ComputedRef<boolean>
    /** The dialog was (re)opened: the switch starts off. The latches stay. */
    reset: () => void
    /**
     * Run the release SBOM export. With the switch requested, the switch document carrying
     * `excludeFileComponents: true`, never retried as the core document; otherwise the
     * metadata-flags document, with the existing drift fallback to the core one.
     */
    exportReleaseSbom: (input: FileSwitchExportInput) => Promise<ExportFallbackResult>
    /**
     * The export failed. When it failed because this server cannot leave files out, latch,
     * turn the switch off and answer true: the caller shows the error and exports nothing.
     */
    latchRefusal: (err: any) => boolean
}

export interface FileSwitchExportInput {
    /** Sends one export document; the dialog's graphqlClient.mutate. */
    run: (mutation: any, variables: Record<string, any>) => Promise<any>
    /** The variables every export document takes. */
    baseVariables: Record<string, any>
    /** baseVariables plus the per-export metadata flags. */
    fullVariables: Record<string, any>
    /** Classifier for "the server rejected the DOCUMENT", normally isSchemaDriftError. */
    isDriftError: (err: any) => boolean
}

/**
 * @param metadataArgsUnsupported the dialog's latch for a server that rejected the per-export
 * metadata options: such a server predates the switch too, so the switch reads unavailable.
 */
export function useFileComponentsSwitch (metadataArgsUnsupported: Ref<boolean>): FileComponentsSwitch {
    const excludeFileComponents = ref(false)
    const exportFileSwitchUnsupported = ref(false)
    const exportFileSwitchAvailable = computed((): boolean =>
        fileSwitchAvailable(metadataArgsUnsupported.value, exportFileSwitchUnsupported.value))
    const excludeFileComponentsRequested = computed((): boolean =>
        excludeFileComponents.value && exportFileSwitchAvailable.value)

    function reset (): void {
        excludeFileComponents.value = false
    }

    function exportReleaseSbom (input: FileSwitchExportInput): Promise<ExportFallbackResult> {
        const { run, baseVariables, fullVariables, isDriftError } = input
        // The file switch has its own document, sent only when it is on: see
        // releaseSbomExportDocuments for why it is not folded into the metadata one.
        const leaveOutFiles = excludeFileComponentsRequested.value
        return exportWithMetadataFallback({
            runFull: () => leaveOutFiles
                ? run(SBOM_EXPORT_WITH_FILE_SWITCH, { ...fullVariables, excludeFileComponents: true })
                : run(SBOM_EXPORT_WITH_METADATA_FLAGS, fullVariables),
            runCore: () => run(SBOM_EXPORT_CORE, baseVariables),
            flagsUnsupported: metadataArgsUnsupported.value,
            isDriftError,
            excludeFileComponents: leaveOutFiles
        })
    }

    function latchRefusal (err: any): boolean {
        if (!(err instanceof FileSwitchUnsupportedError)) return false
        // Not retried without the switch: that would quietly hand back the files the
        // operator asked to leave out. Latch and turn the switch off; the caller says why.
        exportFileSwitchUnsupported.value = true
        excludeFileComponents.value = false
        return true
    }

    return {
        excludeFileComponents,
        exportFileSwitchUnsupported,
        exportFileSwitchAvailable,
        excludeFileComponentsRequested,
        reset,
        exportReleaseSbom,
        latchRefusal
    }
}
