// Generate a Device Support Statement and hand it to the browser, from ONE place.
//
// Lifted out of ReleaseView's export modal when the shipment entry point landed (plan 7h).
// Two call sites now ask for the same document -- a product release, and a delivery of that
// release -- and the sequence they must follow is not obvious from the outside: collect,
// then check the block reasons BEFORE anything is built, then render. A second copy of that
// sequence is a second chance to skip the block check, and the block check is what keeps a
// patient-facing PDF with an empty section from being produced at all.
//
// The refusals are RETURNED, not shown. The two callers speak different dialects -- a modal
// in the release view, a notification on the distribution page -- and a util that reaches
// for Swal would decide that for them. They are also distinguished by KIND, because the
// release view has always shown a failed collect as an error and a refusal as a warning:
// one is "something went wrong", the other is "this document must not exist yet".

import { collectAddendumData, NO_WINDOW_IN_FORCE, type ShipmentStatementContext } from './addendumData'
import type { DriftFallbackClient } from './graphqlDriftFallback'
import {
    statementBlockReason, renderDeviceSupportStatementBlob, deviceSupportStatementFileName
} from './deviceSupportStatement'

export interface DeviceSupportStatementRequest {
    releaseUuid: string
    orgUuid: string
    /** Present when the statement is generated for a DELIVERY of that release (plan 7h). */
    shipment?: ShipmentStatementContext | null
}

export type DeviceSupportStatementOutcome =
    /** FAILED: the facts could not be assembled. Something went wrong. */
    | { ok: false, kind: 'FAILED', message: string }
    /** BLOCKED: the facts were assembled and say this document must not be produced. */
    | { ok: false, kind: 'BLOCKED', message: string }
    | { ok: true, fileName: string }

/** Hands a rendered blob to the browser as a download. Separated so tests can skip the DOM. */
function download (blob: Blob, fileName: string): void {
    const link = document.createElement('a')
    link.href = window.URL.createObjectURL(blob)
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(link.href)
}

/**
 * Collect, refuse, render, download -- in that order, once.
 *
 * Throws nothing: every failure comes back as an outcome the caller can classify without
 * inspecting an exception. The renderer enforces the block reasons a second time and throws
 * if it finds one, which is reported rather than escaped.
 */
export async function generateDeviceSupportStatement (
    client: DriftFallbackClient,
    req: DeviceSupportStatementRequest
): Promise<DeviceSupportStatementOutcome> {
    // A delivery with no window in force is a POLICY refusal, not a failure -- the same
    // fact the caller gates its control on, restated here for the case where the row went
    // stale (the window retracted on the model in another session). Checked before the
    // collect so it does not arrive as a red "something went wrong" over a walked release.
    if (req.shipment && !req.shipment.eos && !req.shipment.eol) {
        return { ok: false, kind: 'BLOCKED', message: NO_WINDOW_IN_FORCE }
    }
    const result = await collectAddendumData(client, req.releaseUuid, req.orgUuid, req.shipment ?? null)
    if (!result.ok) return { ok: false, kind: 'FAILED', message: result.error }
    // Every refusal in one place, checked BEFORE anything is built.
    const blocked = statementBlockReason(result.data)
    if (blocked) return { ok: false, kind: 'BLOCKED', message: blocked }
    const fileName = deviceSupportStatementFileName(result.data)
    try {
        download(await renderDeviceSupportStatementBlob(result.data), fileName)
    } catch (e: any) {
        return { ok: false, kind: 'FAILED', message: e?.message || String(e) }
    }
    return { ok: true, fileName }
}
