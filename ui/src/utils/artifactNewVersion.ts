// Rules for uploading a new version of an artifact (ReleaseView's "Upload New Artifact Version",
// then CreateArtifact in isUpdateExistingBom mode).
//
// EXTRACTED SO IT CAN BE RUN: nothing mounts ReleaseView.vue or CreateArtifact.vue in the unit
// suite, so a rule left inline there is only ever asserted by reading source text. Same reasoning
// and same fix as exportFormatSelection.ts.

/**
 * The file the user has actually chosen in the artifact form, if any.
 *
 * The form's fileList ref is written by two handlers: n-upload's v-model gives it the selected
 * UploadFileInfo list, and CreateArtifact's onFileChange overwrites it with the change payload,
 * whose `file` still names a file the user has just removed. Read the selection in either shape:
 * the first entry of whichever list is there, and the payload's `file` only if it has no list.
 */
export function chosenUploadFile (fileListRef: any): File | undefined {
    const selected = Array.isArray(fileListRef) ? fileListRef : fileListRef?.fileList
    if (Array.isArray(selected)) return selected[0]?.file ?? undefined
    return fileListRef?.file?.file ?? undefined
}

/**
 * A CycloneDX BOM that ReARM should hold but has lost the reference to: type BOM (the only type
 * the backend keeps an internalBom for; a CycloneDX VEX, VDR or attestation never has one), no
 * internalBom (so no serial number or version to show), and no download links (so it is not an
 * externally stored BOM either). A "new version" submitted without a file used to leave BOMs in
 * this state (fixed in rearm-saas#608); uploading a file onto one restores it.
 */
export function hasLostStoredBom (art: any, isCycloneDxBomArtifact: boolean): boolean {
    return isCycloneDxBomArtifact && art?.type === 'BOM' && !art?.internalBom && !(art?.downloadLinks?.length)
}

/**
 * What the artifact belongs to, as the addArtifactManual belongsTo input, for a row of the release
 * page's artifact table. The page labels rows for display; a BOM normally carries the value in its
 * internalBom, which a lost one no longer has. Undefined for rows of other releases.
 */
export function artifactBelongsToForRow (rowBelongsTo: string | undefined): string | undefined {
    switch (rowBelongsTo) {
    case 'Release': return 'RELEASE'
    case 'Source Code Entry': return 'SCE'
    case 'Deliverable': return 'DELIVERABLE'
    default: return undefined
    }
}
