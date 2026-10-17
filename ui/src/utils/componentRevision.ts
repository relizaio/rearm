import gql from 'graphql-tag'
import { ref } from 'vue'
import graphqlQueries from './graphqlQueries'
import {
    graphqlErrorsOf, isDriftRejected, isSchemaDriftError, markDriftRejected, type DriftFallbackClient
} from './graphqlDriftFallback'

/**
 * The component settings page and the stored revision of the component (TPF-62).
 *
 * <p>The page reads the revision together with its form data, sends it on its form saves
 * (UpdateComponentInput.expectedRevision), and the server refuses a save whose revision is not
 * the stored one with the error code COMPONENT_REVISION_CONFLICT (TPF-61). The page then says
 * who changed the component and when, and offers Reload and, for a writer, Force.
 *
 * <p>The revision is read through a document of its own, deliberately NOT through
 * COMPONENT_FULL_DATA. That fragment is also the response selection of the updateComponent and
 * createComponent mutations, and the same UI ships to CE, whose Component has no revision: a
 * selection the deployed schema lacks fails the whole document at validation, so putting the
 * field in the fragment would break every component page, save and create there. The same trap
 * componentDeviceWindow.ts records for the device support window.
 */

/** The page's component read WITH the stored revision. Not COMPONENT_FULL_DATA: see above. */
export const COMPONENT_FORM_QUERY = gql`
    query componentForm($componentUuid: ID!) {
        component(componentUuid: $componentUuid) {
            ${graphqlQueries.ComponentFullData}
            revision
        }
    }`

/** The same read without the revision: what a backend without the field (the CE mirror, an older Pro build) answers. */
export const COMPONENT_FORM_QUERY_CORE = gql`
    query componentFormCore($componentUuid: ID!) {
        component(componentUuid: $componentUuid) {
            ${graphqlQueries.ComponentFullData}
        }
    }`

/** What a COMPONENT_REVISION_CONFLICT refusal carries: the stored revision and who wrote it last. */
export interface RevisionConflict {
    revision: number
    lastUpdatedBy: string | null
    lastUpdatedType: string | null
    lastUpdatedActor: string | null
    lastUpdatedDate: string | null
}

export const REVISION_CONFLICT_CODE = 'COMPONENT_REVISION_CONFLICT'

function stripRevision (component: any): { component: any, revision: number | null } {
    if (!component || typeof component !== 'object') return { component, revision: null }
    const { revision, ...rest } = component
    return { component: rest, revision: typeof revision === 'number' ? revision : null }
}

/**
 * The component for the settings form and the revision it was read at, from one read.
 *
 * <p>Tries the document with the revision first. A schema-drift rejection of it (a backend
 * without the field) falls back to the core document, and the page then holds no revision and
 * saves unchecked, as before TPF-61. The rejection is remembered for SCHEMA_DRIFT_RETRY_MS only
 * once the core document has answered, the rule loadRichestServed follows: a blanket 400 from an
 * edge proxy reads as drift and fails both documents, and must not leave the page saving without
 * a revision after the proxy recovers. Any other failure surfaces, as a failed component read
 * always did. The revision is stripped from the component so that it never reaches the store,
 * the form or a save payload.
 */
export async function loadComponentForForm (client: DriftFallbackClient, componentUuid: string):
        Promise<{ component: any, revision: number | null }> {
    const variables = { componentUuid }
    let fullError: unknown = null
    if (!isDriftRejected(COMPONENT_FORM_QUERY)) {
        try {
            const res = await client.query({ query: COMPONENT_FORM_QUERY, variables, fetchPolicy: 'no-cache' })
            return stripRevision(res?.data?.component)
        } catch (err) {
            if (!isSchemaDriftError(err)) throw err
            fullError = err
        }
    }
    try {
        const res = await client.query({ query: COMPONENT_FORM_QUERY_CORE, variables, fetchPolicy: 'no-cache' })
        if (fullError) markDriftRejected(COMPONENT_FORM_QUERY)
        return { component: stripRevision(res?.data?.component).component, revision: null }
    } catch (err) {
        throw fullError ?? err
    }
}

/**
 * The updateComponent input with the expected revision, when there is one. 0 is a revision (a
 * component nobody saved since it was created). The key is never sent as null or undefined:
 * the CE input has no such field, and graphql-java refuses an unknown input field at variable
 * coercion whatever its value.
 */
export function withExpectedRevision<T extends object> (input: T, revision: number | null | undefined):
        T | (T & { expectedRevision: number }) {
    return typeof revision === 'number' ? { ...input, expectedRevision: revision } : input
}

function stringOrNull (v: unknown): string | null {
    return typeof v === 'string' ? v : null
}

/**
 * The revision conflict an error carries, or null. Switches on extensions.code only, never on
 * the message or on the classification (graphql-java adds BAD_REQUEST to every such refusal).
 */
export function revisionConflictOf (err: unknown): RevisionConflict | null {
    for (const e of graphqlErrorsOf(err)) {
        const x = e?.extensions
        if (x?.code === REVISION_CONFLICT_CODE && typeof x.revision === 'number') {
            return {
                revision: x.revision,
                lastUpdatedBy: stringOrNull(x.lastUpdatedBy),
                lastUpdatedType: stringOrNull(x.lastUpdatedType),
                lastUpdatedActor: stringOrNull(x.lastUpdatedActor),
                lastUpdatedDate: stringOrNull(x.lastUpdatedDate)
            }
        }
    }
    return null
}

export interface UserOption { label: string, value: string }

/**
 * Who changed the component, in words. The server sends uuids only: names come from the
 * organisation user list the page already reads under the viewer's own permission, and
 * without a match a short uuid prefix or a generic word is shown.
 */
export function describeLastUpdater (conflict: RevisionConflict, users: UserOption[] | null | undefined): string {
    const list = Array.isArray(users) ? users : []
    const labelOf = (uuid: string | null) => uuid ? list.find(u => u?.value === uuid)?.label ?? null : null
    if (conflict.lastUpdatedType === 'AUTO') return 'System'
    const actor = labelOf(conflict.lastUpdatedActor)
    if (actor) return `${actor} (through an API key)`
    const by = labelOf(conflict.lastUpdatedBy)
    if (by) return by
    if (conflict.lastUpdatedBy) {
        const short = conflict.lastUpdatedBy.slice(0, 8)
        return conflict.lastUpdatedType === 'API' ? `an API key (${short})` : `a user (${short})`
    }
    return 'someone else'
}

/**
 * The page's revision bookkeeping. The revision a save sends is the one the form was read at,
 * plus one for every write of the component row this page made since and saw succeed: every
 * committed write adds exactly one, so the stored revision equals the count only if nobody
 * else wrote. Taking it from a write's answer or a refetch instead would absorb another
 * writer's change, and the next save would overwrite it without a word.
 */
export function useComponentRevision () {
    const expected = ref<number | null>(null)
    const conflict = ref<{ info: RevisionConflict, retry: (revision: number) => Promise<void> } | null>(null)
    /** After a fresh read. */
    function baseline (revision: number | null) {
        expected.value = revision
        conflict.value = null
    }
    /** After an accepted form save that sent this revision. */
    function saved (sent: number | null) {
        expected.value = sent === null ? null : sent + 1
        conflict.value = null
    }
    /**
     * After another write of the row by this page. A pending refusal's revision moves too: the
     * page's own write is the only change after the refusal it knows of, so Force still
     * overwrites exactly the change it showed, and a further change by someone else is refused.
     */
    function ownWrite () {
        if (expected.value !== null) expected.value += 1
        if (conflict.value) conflict.value.info.revision += 1
    }
    /** True when the error is a revision refusal; the panel then shows it and Force re-runs retry. */
    function refused (err: unknown, retry: (revision: number) => Promise<void>): boolean {
        const info = revisionConflictOf(err)
        if (!info) return false
        conflict.value = { info, retry }
        return true
    }
    /** Re-runs the refused save with the revision from the refusal. */
    async function force () {
        const c = conflict.value
        if (c) await c.retry(c.info.revision)
    }
    return { expected, conflict, baseline, saved, ownWrite, refused, force }
}
