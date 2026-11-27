// The upstream sources feature (ReARM Pro, TPF-185 design 3.2 and 3.5): the one edition gate of
// every tab, menu entry, page, request and link of the feature, the uuid test for values that go
// into a route, and the reading of a refused request.
import { isProEdition } from './editionCapabilities'
import { graphqlErrorEntries, NULL_PROPAGATION_CLASSIFICATION } from './graphqlErrorText'
import { networkSentence } from './upstreamLabels'

/**
 * Whether the upstream sources feature exists in this edition. Stricter than isProEdition on
 * purpose: before the user object has loaded installationType is undefined, and a request sent in
 * that moment would be a Pro-only query against what may be a CE backend. Unknown therefore reads
 * as not available; a computed over this re-evaluates when the user arrives.
 */
export function upstreamSourcesAvailable (installationType: string | undefined | null): boolean {
    return typeof installationType === 'string' && installationType !== '' && isProEdition(installationType)
}

/** How often a screen reads a source again while its probe is PENDING: the Sources tab and the start of a follow. */
export const PROBE_POLL_MS = 2000

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const NIL_UUID = '00000000-0000-0000-0000-000000000000'

/** A uuid in either case, and not the nil uuid. Nothing else may go into a path of the feature. */
export function isUuid (text: unknown): boolean {
    return typeof text === 'string' && UUID_PATTERN.test(text) && text !== NIL_UUID
}

/** A refused request as the screens show it: the sentence, and the typed refusal's code and reason when it has them. */
export interface UpstreamRefusal {
    text: string
    code: string | null
    reason: string | null
}

/**
 * The refusal of a failed GraphQL call: the first error entry that is not graphql-java's
 * null-propagation entry and has a message, with its extensions.code and extensions.reason. A
 * call that got no GraphQL entries at all did not reach the server and gives the network sentence.
 */
export function refusalOf (err: unknown): UpstreamRefusal {
    const entries = graphqlErrorEntries(err)
    if (!entries.length) return { text: networkSentence, code: null, reason: null }
    const entry = entries.find((x: any) =>
        x?.extensions?.classification !== NULL_PROPAGATION_CLASSIFICATION
        && typeof x?.message === 'string' && x.message.trim() !== '')
    if (!entry) {
        const own = (err as any)?.message
        return { text: typeof own === 'string' && own.trim() !== '' ? own : networkSentence, code: null, reason: null }
    }
    const code = typeof entry.extensions?.code === 'string' ? entry.extensions.code : null
    const reason = typeof entry.extensions?.reason === 'string' ? entry.extensions.reason : null
    return { text: entry.message, code, reason }
}

/** The backend's answer for a missing permission, a row of another organization and an unknown uuid alike. */
export const NOT_AUTHORIZED = 'Not authorized'

/**
 * Whether a refused first read shows the forbidden state of design 3.5: only when the chosen entry
 * says exactly "Not authorized" and carries no extensions.code. Every other failure is the error
 * state, which shows the sentence itself.
 */
export function isForbidden (refusal: UpstreamRefusal): boolean {
    return refusal.text === NOT_AUTHORIZED && refusal.code === null
}

/**
 * Whether an artifact of the release belongs to the snapshot an upstream source ingested: the
 * artifacts its collection versions name. The release view leaves the actions that would change
 * such an artifact out of its row; the backend refuses them anyway.
 */
export function isSnapshotArtifact (artifactUuid: string, snapshotUuids: ReadonlySet<string> | null | undefined): boolean {
    return !!snapshotUuids && typeof artifactUuid === 'string' && snapshotUuids.has(artifactUuid)
}
