// Device support window form state -> updateRelease variables, honouring the mutation's
// PATCH contract.

export interface DeviceWindowState {
    eos: string | null
    eol: string | null
}

/**
 * Build the NARROW partial for a device-support-window write.
 *
 * The payload is { uuid, org } plus only the dates that actually changed. That narrowness
 * matters beyond tidiness: updateRelease treats a null list as "no change"
 * (Utils.diffUuidLists), so omitting artifacts and commits leaves them attached, whereas
 * posting a whole stale release object could DETACH them. The spec asserts the exact key
 * set for that reason -- it is the durable guard against a future widening.
 *
 * org is ID! on ReleaseInput, so even the narrowest partial must carry it: a { uuid, eos }
 * payload is rejected at GraphQL validation before the resolver is reached.
 *
 * Clearing needs an explicit flag because null on this path means "keep". Only a date that
 * WAS set can be cleared; going from unset to unset sends nothing at all.
 */
export function deviceWindowVariables (
    uuid: string,
    org: string,
    current: DeviceWindowState,
    baseline: DeviceWindowState
): Record<string, unknown> {
    const vars: Record<string, unknown> = { uuid, org }
    if (current.eos !== baseline.eos) {
        if (current.eos) vars.eos = current.eos
        else if (baseline.eos) vars.clearEos = true
    }
    if (current.eol !== baseline.eol) {
        if (current.eol) vars.eol = current.eol
        else if (baseline.eol) vars.clearEol = true
    }
    return vars
}
