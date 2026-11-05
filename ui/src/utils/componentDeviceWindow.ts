import gql from 'graphql-tag'
import type { DeviceWindowState } from './deviceSupportWindowInput'
// DriftFallbackClient and isSchemaDriftError imported rather than restated: an inline
// structural client type and an injected predicate are a seam this module does not need --
// both call sites passed the same function, so it bought nothing but a fake predicate in the
// spec. Every neighbour that reads a drift-guarded document (notificationInboxQuery.ts,
// changelogQueries.ts, useBulkAttest.ts) imports these directly.
import {
    isSchemaDriftError, loadWithSchemaDriftFallback, withoutAdditiveFields, type DriftFallbackClient
} from './graphqlDriftFallback'

/**
 * The device support window on a PRODUCT COMPONENT (decision D7).
 *
 * <p>Its own small document, deliberately NOT part of `COMPONENT_FULL_DATA`. That fragment is
 * the response selection of the `updateComponent` MUTATION, and a backend that declares
 * `Component.medicalProfile` without `deviceSupportWindow` inside it (in practice a Pro build
 * older than the field) would reject the whole mutation document and break EVERY component save,
 * not just this field. That is the same trap `supportInjection` hit in #339, and the same
 * answer: read it separately, write it without selecting it back.
 *
 * <p>`warnings` (FDAW-3, B3) is an ADDITIVE field: the derived observation about the declared
 * dates (EOS_BEFORE_EOL). A backend that has the window but not the warning answers the core
 * document, `withoutAdditiveFields(COMPONENT_DEVICE_WINDOW_QUERY)`, and the panel shows the
 * window with no warning.
 */
export const COMPONENT_DEVICE_WINDOW_QUERY = gql`
    query componentDeviceWindow($componentUuid: ID!) {
        component(componentUuid: $componentUuid) {
            uuid
            medicalProfile {
                deviceSupportWindow {
                    eos eol assertedBy assessedAt source
                    warnings { code message }  #additive-field
                }
            }
        }
    }`

/** One derived observation about the declared dates, as the server words it. */
export interface DeviceWindowWarning {
    code: string
    message: string
}

/** What the panel needs to render, plus whether this backend can answer at all. */
export interface ComponentWindowResult {
    /** False on a backend whose schema lacks the field -- the panel must hide, not error. */
    supported: boolean
    window: DeviceWindowState
    assertedBy: string | null
    assessedAt: string | null
    /** What is unusual about the stored dates; empty when nothing is, or when degraded. */
    warnings: DeviceWindowWarning[]
}

export const NOT_DECLARED: ComponentWindowResult = {
    supported: true, window: { eos: null, eol: null }, assertedBy: null, assessedAt: null, warnings: []
}

/**
 * Read the declared window, tolerating a backend that does not know the field.
 *
 * A CE build issuing this document gets a VALIDATION error, not a null field, so the failure
 * has to be caught and reported as "unsupported" rather than as "not declared". Those are
 * different facts: the first means this build cannot show or edit the window at all, the
 * second is a statement about the device.
 *
 * Two documents: the full one (with the additive `warnings`) first, the core one on a drift
 * refusal of it. Only a refusal of the core document too means "unsupported". `no-cache`, as
 * before: `component(componentUuid:)` is also the root field the settings form caches.
 */
export async function loadComponentDeviceWindow (
    client: DriftFallbackClient,
    componentUuid: string
): Promise<ComponentWindowResult> {
    try {
        const r = await loadWithSchemaDriftFallback(client, {
            fullQuery: COMPONENT_DEVICE_WINDOW_QUERY,
            coreQuery: withoutAdditiveFields(COMPONENT_DEVICE_WINDOW_QUERY),
            variables: { componentUuid },
            extractPath: (d: any) => d?.component?.medicalProfile?.deviceSupportWindow ?? null,
            fetchPolicy: 'no-cache'
        })
        const w = r.data
        return {
            supported: true,
            window: { eos: w?.eos ?? null, eol: w?.eol ?? null },
            assertedBy: w?.assertedBy ?? null,
            assessedAt: w?.assessedAt ?? null,
            warnings: deviceWindowWarningsOf(w)
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
 * The one `updateComponent` input of the Device tab's single Save (FDAW-3, B8): the class and
 * the window together.
 *
 * `{ uuid, name }`, plus `deviceClass` when it changed, plus the window part of
 * `deviceWindowMutationInput` (its `deviceSupportWindow` or `clearDeviceSupportWindow`) when the
 * draft class is not NONE and the dates changed. The server applies `deviceClass` before the
 * window patch inside one `updateComponent`, so picking a class and its dates in one save is
 * valid. A draft class of NONE sends the class only: the server retracts the window with it, and
 * a window on a non-device would be refused. Null when nothing changed.
 *
 * @param componentName REQUIRED, for the reason `deviceWindowMutationInput` gives.
 */
export function deviceSaveInput (
    componentUuid: string,
    componentName: string,
    classEdit: string,
    classBaseline: string,
    window: DeviceWindowState,
    baseline: DeviceWindowState
): Record<string, unknown> | null {
    const input: Record<string, unknown> = { uuid: componentUuid, name: componentName }
    let changed = false
    if (classEdit !== classBaseline) {
        input.deviceClass = classEdit
        changed = true
    }
    if (classEdit !== 'NONE') {
        const w = deviceWindowMutationInput(componentUuid, componentName, window, baseline)
        if (w) {
            if ('deviceSupportWindow' in w) input.deviceSupportWindow = w.deviceSupportWindow
            if ('clearDeviceSupportWindow' in w) input.clearDeviceSupportWindow = w.clearDeviceSupportWindow
            changed = true
        }
    }
    return changed ? input : null
}

/** The warnings on a read window, normalised: an absent or null list is an empty one. */
export function deviceWindowWarningsOf (w: any): DeviceWindowWarning[] {
    const list = Array.isArray(w?.warnings) ? w.warnings : []
    return list.filter((x: any) => x && typeof x.message === 'string')
        .map((x: any) => ({ code: String(x.code ?? ''), message: x.message }))
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
