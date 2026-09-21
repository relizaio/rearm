// Sending the per-export metadata flags to a backend that may not declare them yet.
//
// EXTRACTED SO IT CAN BE RUN. Nothing in the unit suite mounts ReleaseView, so a rule left
// inline there is asserted only by scanning its source -- and the scan passes on a latch that
// fires for the wrong reason, on a retry that is never verified, and on a component that
// throws during setup. All three of those shipped on this feature before this file existed.

/** What one export attempt did, so the caller can say it out loud. */
export interface ExportFallbackResult {
    /** The client's response from whichever document actually succeeded. */
    data: any
    /** True when the metadata flags were NOT sent -- the export used the server's defaults. */
    usedCore: boolean
    /**
     * True only on the attempt that DISCOVERED the backend cannot take the flags. The caller
     * latches and tells the operator once on this; every later export is `usedCore` without
     * being `justDiscovered`, and must be reflected in the form rather than re-announced.
     */
    justDiscovered: boolean
}

export interface ExportFallbackInput {
    /** The document carrying includeSupportMetadata / includeInternalMetadata. */
    runFull: () => Promise<any>
    /** The document every backend has always accepted. */
    runCore: () => Promise<any>
    /** Whether a previous attempt already proved this backend rejects the full document. */
    flagsUnsupported: boolean
    /** Classifier for "the server rejected the DOCUMENT", normally isSchemaDriftError. */
    isDriftError: (err: any) => boolean
}

/**
 * Run the export, falling back to the flagless document only for a schema-drift rejection.
 *
 * <p>WHY A RETRY IS SAFE HERE AND NOT ON MUTATIONS IN GENERAL: a backend that does not declare
 * the two arguments rejects the document during VALIDATION, before any resolver runs. Nothing
 * was exported and no download was logged, so the retry is not a second write. A failure from
 * anywhere else is re-thrown untouched.
 *
 * <p>THE SERVER'S DELIBERATE REFUSAL MUST NOT LAND HERE. Asking for support metadata an
 * organization has disabled is answered with a business error, not a validation error, and
 * `isDriftError` returns false for it -- so it propagates to the operator instead of being
 * retried into the very document-without-the-disclosure that the refusal exists to prevent.
 * That is a property of the classifier, which is why it is injected and asserted rather than
 * assumed.
 *
 * <p>`justDiscovered` is only true when the CORE retry actually SUCCEEDED. Latching on the
 * rejection alone would disable the flags permanently on the strength of a failure that might
 * have had nothing to do with them -- this mutation also carries two enum arguments, which are
 * the same drift shape -- and the operator would then get flagless exports forever from one
 * unrelated bad request.
 */
export async function exportWithMetadataFallback (
    input: ExportFallbackInput
): Promise<ExportFallbackResult> {
    const { runFull, runCore, flagsUnsupported, isDriftError } = input
    if (flagsUnsupported) {
        return { data: await runCore(), usedCore: true, justDiscovered: false }
    }
    try {
        return { data: await runFull(), usedCore: false, justDiscovered: false }
    } catch (err: any) {
        if (!isDriftError(err)) throw err
        // If THIS fails too, it was never about the arguments: let the real error surface
        // rather than reporting "metadata options ignored" over an unrelated fault.
        const data = await runCore()
        return { data, usedCore: true, justDiscovered: true }
    }
}
