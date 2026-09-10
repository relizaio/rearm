import gql from 'graphql-tag'
import type { DeviceWindowState } from './deviceSupportWindowInput'

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
    client: { query: (opts: any) => Promise<any> },
    componentUuid: string,
    isDriftError: (e: any) => boolean
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
        if (isDriftError(e)) {
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
 */
/**
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
