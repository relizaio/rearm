import gql from 'graphql-tag'
import type { DeviceWindowState } from './deviceSupportWindowInput'
// DriftFallbackClient and isSchemaDriftError imported rather than restated: an inline
// structural client type and an injected predicate are a seam this module does not need --
// both call sites passed the same function, so it bought nothing but a fake predicate in the
// spec. Every neighbour that reads a drift-guarded document (notificationInboxQuery.ts,
// changelogQueries.ts, useBulkAttest.ts) imports these directly.
import { isSchemaDriftError, type DriftFallbackClient } from './graphqlDriftFallback'

/**
 * The device support window on a PRODUCT COMPONENT (decision D7).
 *
 * <p>Its own small document, deliberately NOT part of `COMPONENT_FULL_DATA`. That fragment is
 * the response selection of the `updateComponent` MUTATION, and CE's schema declares
 * `Component.medicalProfile` without `deviceSupportWindow` inside it -- so adding the subfield
 * there would make the whole mutation document invalid on CE and break EVERY component save,
 * not just this field. That is the same trap `supportInjection` hit in #339, and the same
 * answer: read it separately, write it without selecting it back.
 */
export const COMPONENT_DEVICE_WINDOW_QUERY = gql`
    query componentDeviceWindow($componentUuid: ID!) {
        component(componentUuid: $componentUuid) {
            uuid
            medicalProfile {
                deviceSupportWindow { eos eol assertedBy assessedAt source }
            }
        }
    }`

/** What the panel needs to render, plus whether this backend can answer at all. */
export interface ComponentWindowResult {
    /** False on a backend whose schema lacks the field -- the panel must hide, not error. */
    supported: boolean
    window: DeviceWindowState
    assertedBy: string | null
    assessedAt: string | null
}

export const NOT_DECLARED: ComponentWindowResult = {
    supported: true, window: { eos: null, eol: null }, assertedBy: null, assessedAt: null
}

/**
 * Read the declared window, tolerating a backend that does not know the field.
 *
 * A CE build issuing this document gets a VALIDATION error, not a null field, so the failure
 * has to be caught and reported as "unsupported" rather than as "not declared". Those are
 * different facts: the first means this build cannot show or edit the window at all, the
 * second is a statement about the device.
 */
export async function loadComponentDeviceWindow (
    client: DriftFallbackClient,
    componentUuid: string
): Promise<ComponentWindowResult> {
    try {
        const resp = await client.query({
            query: COMPONENT_DEVICE_WINDOW_QUERY,
            variables: { componentUuid },
            fetchPolicy: 'no-cache'
        })
        const w = resp?.data?.component?.medicalProfile?.deviceSupportWindow ?? null
        return {
            supported: true,
            window: { eos: w?.eos ?? null, eol: w?.eol ?? null },
            assertedBy: w?.assertedBy ?? null,
            assessedAt: w?.assessedAt ?? null
        }
    } catch (e: any) {
        if (isSchemaDriftError(e)) {
            return { ...NOT_DECLARED, supported: false }
        }
        throw e
    }
}

/**
 * The `updateComponent` input for a window edit.
 *
 * Returns `clearDeviceSupportWindow` when BOTH dates have been emptied and something was
 * declared before -- an operator blanking both pickers means "retract", and the flag is the
 * only way to say it. An empty window object is deliberately NOT a clear on the server, so
 * sending one here would silently leave the old value in force.
 *
 * Returns null when nothing changed, so an unrelated save cannot rewrite the window and stamp
 * fresh provenance on a claim nobody touched.
 *
 * @param componentName REQUIRED. `UpdateComponentInput.name` is `String!`, so even the
 *   narrowest partial must carry it or graphql-java rejects the whole mutation at variable
 *   coercion, BEFORE the resolver is reached -- the save then fails every time, for a reason
 *   no server-side log explains. `deviceSupportWindowInput.ts` documents the identical trap
 *   for `org` on `ReleaseInput`; this module missed it despite being modelled on that one.
 */
export function deviceWindowMutationInput (
    componentUuid: string,
    componentName: string,
    edited: DeviceWindowState,
    baseline: DeviceWindowState
): Record<string, unknown> | null {
    const changed = edited.eos !== baseline.eos || edited.eol !== baseline.eol
    if (!changed) return null
    const hadSomething = !!baseline.eos || !!baseline.eol
    const hasSomething = !!edited.eos || !!edited.eol
    if (!hasSomething) {
        return hadSomething
            ? { uuid: componentUuid, name: componentName, clearDeviceSupportWindow: true }
            : null
    }
    return {
        uuid: componentUuid,
        name: componentName,
        deviceSupportWindow: { eos: edited.eos || null, eol: edited.eol || null }
    }
}

/**
 * The same three rules as `deviceWindowMutationInput`, for the SHIPMENT override.
 *
 * Extracted rather than left inline in `DistributionOfOrg.vue` because it IS the same rule --
 * unchanged sends nothing, blank-both-after-something sends the clear flag, and an empty
 * window object is never sent because the server does not read one as a retraction. Two copies
 * of that in two files is two chances for them to disagree about what "retract" means, on a
 * section 524B commitment. The inline copy was covered only by a spec that regex-matched the
 * `.vue` source, which cannot tell whether the rule is right -- only whether the text moved.
 *
 * Mutates `input` and returns it, matching how the surrounding shipment builder is written.
 */
export function applyShipmentWindowToInput (
    input: Record<string, unknown>,
    edited: DeviceWindowState,
    baseline: DeviceWindowState
): Record<string, unknown> {
    const changed = edited.eos !== baseline.eos || edited.eol !== baseline.eol
    if (!changed) return input
    const hasSomething = !!edited.eos || !!edited.eol
    const hadSomething = !!baseline.eos || !!baseline.eol
    if (hasSomething) {
        input.deviceSupportWindow = { eos: edited.eos || null, eol: edited.eol || null }
    } else if (hadSomething) {
        input.clearDeviceSupportWindow = true
    }
    return input
}

/**
 * The window in force for a shipment, named by WHERE it was declared rather than by whether
 * it is an override.
 *
 * "this batch" / "the product component" is the whole point: two shipments of the same device
 * model can legitimately carry different dates, and a reader with no way to tell why would
 * reasonably conclude one of them is wrong. Returns '' when nothing is declared, which the
 * caller must distinguish from "we have not loaded a shipment yet" -- on a NEW shipment there
 * is nothing to resolve through, and saying "no window is declared for this device model"
 * there is a false claim about the product made exactly when it would invite a needless
 * override.
 */
export function effectiveWindowLabel (
    w: { eos?: string | null, eol?: string | null, source?: string | null } | null | undefined
): string {
    if (!w || (!w.eos && !w.eol)) return ''
    const where = w.source === 'SHIPMENT' ? 'this batch' : 'the product component'
    return `EOS ${w.eos || 'not declared'}, EOL ${w.eol || 'not declared'} (from ${where})`
}
