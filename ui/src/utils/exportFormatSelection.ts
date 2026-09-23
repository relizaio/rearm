// Which documents the export modal can produce for a release, and which format stays selected
// when the BOM type changes.
//
// EXTRACTED SO IT CAN BE RUN. The release page has no vue-tsc and nothing mounts it in the
// unit suite, so a rule left inline in ReleaseView.vue is only ever asserted by scanning the
// component's source text -- which passes on a comment, passes on an inverted condition, and
// (demonstrated on this very change) passes on a component that throws during setup and
// renders nothing at all. The same conclusion useReleaseSupportCoverage.ts reached, for the
// same reason, and the same fix.

/**
 * The support documents. A union rather than bare strings, matching LevelOfSupport /
 * SupportParty in supportAttestationInput.ts and SupportInjectionState in orgSettingsCommit.ts
 * -- a renamed member then fails to compile instead of silently never matching.
 *
 * The values are the wire media types the export handler routes on; only the LABELS dropped
 * the agency's name.
 */
export type SupportDocumentType = 'FDA_ADDENDUM' | 'FDA_ADDENDUM_PDF' | 'DEVICE_STATEMENT'

/** The encodings of the BOM itself. */
export type BomMediaType = 'JSON' | 'CSV' | 'EXCEL'

/** The BOM types the modal offers. SUPPORT is a type, not a format -- see supportExportFormats. */
export type ExportBomType = 'SBOM' | 'OBOM' | 'VDR' | 'VEX' | 'CLE' | 'SUPPORT'

export const BOM_MEDIA_TYPES: readonly BomMediaType[] = ['JSON', 'CSV', 'EXCEL']

export interface SupportExportFormat {
    value: SupportDocumentType
    label: string
}

/**
 * The support documents THIS release can produce, in modal order.
 *
 * One list, read by the radio group and by the "is there a Support type at all" test, so a
 * release that can produce nothing cannot end up offering an empty radio group behind a type
 * button. The previous arrangement wrote the PRODUCT rule out separately in an alert, a
 * computed and a disabled attribute.
 *
 * The addendum is RELEASE-scoped and is producible from a component release: it lists that
 * release's own components. The device statement is a statement about a DEVICE and is not --
 * the product release that ships the component is where it is generated.
 *
 * So the list is never empty today, and the caller's emptiness check is therefore never true.
 * That is deliberate rather than an oversight: the check is derived from this list rather than
 * asserted separately, so if a future rule does make a release produce nothing, the type
 * disappears with no second place to update.
 */
export function supportExportFormats (isProductRelease: boolean): SupportExportFormat[] {
    const formats: SupportExportFormat[] = [
        { value: 'FDA_ADDENDUM', label: 'Support addendum (CSV)' },
        { value: 'FDA_ADDENDUM_PDF', label: 'Support addendum (PDF)' }
    ]
    if (isProductRelease) {
        formats.push({ value: 'DEVICE_STATEMENT', label: 'Device support statement (PDF)' })
    }
    return formats
}

/**
 * The format that must be selected after the BOM type changes to {@code bomType}.
 *
 * Both radio groups write ONE media-type ref, because the Export button is one handler and the
 * media type is what decides which document gets built. Without this reset, picking Support
 * and going back to SBOM leaves 'FDA_ADDENDUM' selected under a radio group that does not
 * offer it: naive-ui renders no selection, and Export fires the addendum while the form on
 * screen says CycloneDX.
 *
 * Returns the CURRENT value when it is already valid, so switching away and back does not
 * silently discard a choice the operator made.
 *
 * @param bomType the type now selected
 * @param current the media type selected before the change
 * @param isProductRelease whether the device statement is on offer
 */
export function mediaTypeForBomType (
    bomType: string, current: string, isProductRelease: boolean
): string {
    if (bomType === 'SUPPORT') {
        const valid = supportExportFormats(isProductRelease)
            .map((f: SupportExportFormat): string => f.value)
        return valid.includes(current) ? current : valid[0]
    }
    return (BOM_MEDIA_TYPES as readonly string[]).includes(current) ? current : 'JSON'
}
