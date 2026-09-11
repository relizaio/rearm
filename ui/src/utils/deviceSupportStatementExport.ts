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

import { collectAddendumData, type ShipmentStatementContext } from './addendumData'
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
 * Throws nothing: a transport failure inside the collect is already returned as a refusal by
 * collectAddendumData, and a renderer that throws its own block reason is reported as BLOCKED
 * rather than escaping as an exception the caller would have to classify itself.
 */
export async function generateDeviceSupportStatement (
    client: DriftFallbackClient,
    req: DeviceSupportStatementRequest
): Promise<DeviceSupportStatementOutcome> {
    const result = await collectAddendumData(client, req.releaseUuid, req.orgUuid, req.shipment ?? null)
    if (!result.ok) return { ok: false, kind: 'FAILED', message: result.error }
    // Every refusal in one place, checked BEFORE anything is built.
    const blocked = statementBlockReason(result.data)
    if (blocked) return { ok: false, kind: 'BLOCKED', message: blocked }
    const fileName = deviceSupportStatementFileName(result.data)
    download(await renderDeviceSupportStatementBlob(result.data), fileName)
    return { ok: true, fileName }
}
