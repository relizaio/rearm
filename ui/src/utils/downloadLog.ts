// The Download Log's words for a download type (task FDAW-122). The keys are values of the GraphQL enum
// DownloadType; a value this build does not know is shown by its name. The three package types are served
// by ReARM Pro only (FDAW-117, FDAW-119, FDAW-120); the labels need no gate, a server that never writes a
// type never sends it.
export const DOWNLOAD_TYPE_LABELS: Record<string, string> = {
    ARTIFACT_DOWNLOAD: 'Artifact Download',
    RAW_ARTIFACT_DOWNLOAD: 'Raw Artifact Download',
    VDR_EXPORT: 'VDR Export',
    VEX_EXPORT: 'VEX Export',
    SBOM_EXPORT: 'SBOM Export',
    CLE_EXPORT: 'CLE Export',
    TEA_DOWNLOAD: 'TEA Download',
    PACKAGE_ZIP_DOWNLOAD: 'Package Zip Download',
    RECORD_OF_REVIEW_EXPORT: 'Record of Review Export',
    DRAFT_ZIP_DOWNLOAD: 'Draft Zip Download',
}

export function downloadTypeLabel (type: string | null | undefined): string {
    if (!type) return ''
    return Object.prototype.hasOwnProperty.call(DOWNLOAD_TYPE_LABELS, type) ? DOWNLOAD_TYPE_LABELS[type] : type
}
