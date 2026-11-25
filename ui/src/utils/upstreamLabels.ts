// Every label table and every fixed sentence of the upstream sources feature (ReARM Pro, TPF-185
// design 3.14), complete for all the screens of the feature. One Record per enum, keyed by the
// enum's values, so a missing or an extra key is found by upstreamLabels.spec.ts against the Pro
// schema. No screen of the feature prints a raw enum value.
//
// Imports nothing, so pure modules and their specs can use it without the store or the client.

// ---- Sentences that carry a decision (verbatim; a change of meaning needs the architect) ----

export const driftStopSentence = 'Stop: a release that does not fit the version lines is held and listed as pending until you reconfigure the follow. Releases that fit, and new documents of releases already ingested, keep coming in.'

export const driftAdaptSentence = 'Adapt: ReARM works out a new depth from the release history and uses it from then on; releases already ingested keep their lines. When the history gives no clear answer, the release is held as under Stop. Either way an alert tells the administrators.'

/** The statement of the archive dialog; the name is put in unchanged and rendered as text. */
export function archiveSourceStatement (name: string): string {
    return `Archive ${name}? ReARM stops syncing its follows and deletes the stored credential. Everything ingested from it stays and is marked as no longer updated. An archived source cannot be brought back; the same server can be added again as a new source.`
}

/** The statement of the unfollow dialog; the name is put in unchanged and rendered as text. */
export function unfollowStatement (name: string): string {
    return `Unfollow ${name}? Nothing that was ingested is changed or deleted; it is marked as no longer updated. This cannot be undone. Following the item again needs a new confirmation, and its depth then stands as it is.`
}

export const probeTimeoutHint = 'If the server is on a private network, the network policy of this installation may block its address. Whoever installed ReARM with helm can allow it.'

export const probeApiKeyHint = 'Check the server root as well as the key: a wrong root gives the same answer.'

export const followUuidHint = 'The uuid of the product or component on the supplier\'s TEA server. ReARM cannot search a supplier\'s catalogue by name: ask the supplier for it, or ingest one release by its TEI and use Follow this item on the product.'

export const startingPointRequired = 'Choose a starting point.'

export const preReleaseNotStated = 'This source does not say for every release whether it is a pre-release. Releases it does not mark are treated as final.'

export const adoptionSentence = 'Adopting keeps the existing item and its releases. Releases whose version the supplier also has are taken over by the follow; nothing is deleted.'

export const oneOffFrozenSentence = 'Ingested once by TEI. It is frozen: only a refresh, or a follow of its product, updates it.'

export const notAvailableSentence = 'This page is not available in this edition.'

/** The forbidden state of design 3.5: a refused first read, whatever the cause behind it. */
export const forbiddenSentence = 'You are not allowed to see this, or it does not exist. Upstream sources need read access to the whole organization.'

/** The error state of design 3.5 for a request that got no answer from the server. */
export const networkSentence = 'The request did not reach the server. Try again.'

export const autoIntegrateSentence = 'When a release is ingested, let auto-integrate create product releases that use it, where a product is set up for that.'

export const reServingSentence = 'On: this supplier\'s documents other than analysed SBOMs are included in your own TEA publications of the releases that use them. Off: they are not. Your organization decides whether to pass them on.'

/** ImportMode, the supplier VEX choice of a source. */
export const vexModeSentence: Record<string, string> = {
    AUTO_ACCEPT: 'Accept the supplier\'s statements automatically, where the trust rules of the organization allow it.',
    STAGE: 'Offer each statement for review; nothing applies until someone accepts it.',
    REJECT: 'Do not import this supplier\'s VEX. The documents are stored, no statement is offered.',
}

/** UpstreamDriftBehaviour with the two sentences above. */
export const driftBehaviourSentence: Record<string, string> = {
    STOP: driftStopSentence,
    ADAPT: driftAdaptSentence,
}

// ---- Copies of backend sentences, where the backend serves the value without its sentence ----

/** UpstreamProbeResult: for sourceFailure of a sync, a preparation and a one-off. A source's own probe shows probe.message. */
export const probeResultSentence: Record<string, string> = {
    REACHABLE: 'The server answered as a TEA server and did not reject the credential.',
    AUTHENTICATION_FAILED: 'The server refused the request: it asks for a credential or rejects the stored one.',
    ADDRESS_REFUSED: 'The server\'s name leads to an address that ReARM does not connect to.',
    COULD_NOT_CONNECT: 'No connection to the server could be opened. The name or the port may be wrong or the server may be down; the network policy of this installation may also not allow the address.',
    URL_REFUSED: 'The server sent a redirect or a link that ReARM does not follow.',
    TIMED_OUT: 'The server did not answer in time.',
    CERTIFICATE_NOT_TRUSTED: 'The server\'s TLS certificate was not accepted.',
    SERVER_ERROR: 'The server answered with a server error.',
    NOT_A_TEA_SERVER: 'Something answered at this root, but not as a TEA server.',
    FETCHER_NOT_CONFIGURED: 'Fetching from TEA servers is not set up on this installation.',
    FETCHER_NOT_AVAILABLE: 'Fetching from TEA servers is not available on this installation at the moment.',
    FETCHER_BUSY: 'The fetcher was busy. Probe again later.',
    CREDENTIAL_UNREADABLE: 'The stored credential can no longer be read. Enter it again.',
    NOT_RUN: 'The probe was not run because a probe limit was reached. Probe again later.',
    NOT_COMPLETED: 'The probe did not finish. Probe again.',
    FAILED: 'The probe failed.',
}

/** UpstreamOneOffFailure: an UpstreamOneOff has no message. */
export const oneOffFailureSentence: Record<string, string> = {
    INVALID_TEI: 'The TEI must be 1 to 2048 printable ASCII characters without spaces, starting with tei://.',
    NOT_FOUND: 'The TEI resolved to no release.',
    NO_MATCHING_SOURCE: 'The TEI resolved to a server that is not an upstream source of the organization.',
    CREDENTIAL_NEEDED: 'The server asks for a credential. Resolve the TEI again naming the source that holds it.',
    SOURCE_FAILURE: 'Resolving the TEI failed.',
    TIMED_OUT: 'Resolving the TEI did not finish in time. Resolve it again.',
    BUSY: 'Resolving TEIs is busy at the moment. Resolve the TEI again later.',
}

/** UpstreamRunStop: a run serves its message; a sync of a source serves only stopReason. */
export const runStopSentence: Record<string, string> = {
    UNFOLLOWED: 'The item was unfollowed.',
    ORGANIZATION_ARCHIVED: 'The organization is archived.',
    SOURCE_ARCHIVED: 'The upstream source is archived.',
    CREDENTIAL_UNREADABLE: 'The stored credential of the source can no longer be read. Enter it again.',
    ROOT_CHANGED: 'The source now points at another server than the one the item was followed on. Follow it again.',
    ITEM_GONE: 'The source no longer knows this item.',
    TOO_MANY_RELEASES: 'This item has more releases than ReARM reads for one item (10000).',
    ANSWER_INVALID: 'The source answered with something other than what was asked for.',
    SOURCE_FAILURE: 'Reading from the source failed.',
    FETCHER_UNAVAILABLE: 'The fetcher of upstream documents is not available.',
    FETCHER_BUSY: 'The fetcher of upstream documents stayed busy. Start the run again later.',
    RELEASE_LIMIT: 'The run reached its limit of releases. Start another run to continue.',
    BYTE_LIMIT: 'The run reached its limit of bytes. Start another run to continue.',
    TIME_LIMIT: 'The run reached its time limit. Start another run to continue.',
    INTERNAL_ERROR: 'The run failed on an internal error.',
}

/** UpstreamPreparationFailure: a preparation serves its message; a component that could not be read serves only failure. */
export const preparationFailureSentence: Record<string, string> = {
    ITEM_NOT_FOUND: 'The source does not know this item.',
    TOO_MANY_RELEASES: 'This item has more releases than ReARM reads for one item (10000). It cannot be followed.',
    ANSWER_INVALID: 'The source answered with something other than what was asked for.',
    ROOT_CHANGED: 'The source was pointed at another server while it was read. Prepare again.',
    SOURCE_ARCHIVED: 'The upstream source is archived.',
    CREDENTIAL_UNREADABLE: 'The stored credential of the source can no longer be read. Enter it again.',
    SOURCE_FAILURE: 'Reading from the source failed.',
    TIMED_OUT: 'Reading the item took too long. Prepare again.',
    NOT_COMPLETED: 'The preparation did not finish. Prepare again.',
}

// ---- Words of the UI's own, for enums the backend serves without any text ----

export const driftKindLabel: Record<string, string> = {
    UNPLACEABLE: 'its version cannot be placed on a version line',
    NUMBERING_RESTART: 'the numbering restarted',
    SHAPE_CHANGE: 'the shape of the version changed',
}

export const depthBasisLabel: Record<string, string> = {
    HISTORY: 'the shallowest depth at which every line of the release history moves forward in time',
    DEFAULT_TOO_LITTLE_HISTORY: 'too few releases to tell, so the default',
    DEFAULT_NOTHING_FITS: 'no depth fits the whole history, so the default',
    NO_LINE_STRUCTURE: 'the versions show no line structure',
}

export const depthFitLabel: Record<string, string> = {
    FITS: 'fits the history',
    DOES_NOT_FIT: 'does not fit: some line would go back in time',
    NO_LINE_STRUCTURE: 'no lines',
}

export const depthOriginLabel: Record<string, string> = {
    CHOSEN: 'chosen by an administrator',
    PROPOSED: 'proposed by ReARM and applied',
    ADAPTED: 'adapted after drift',
}

export const itemUpdateStateLabel: Record<string, string> = {
    UPDATED: 'Kept up to date',
    NO_LONGER_UPDATED: 'No longer updated',
}

/**
 * UpstreamPresence. The screens show nothing for PRESENT and the tag for GONE; the PRESENT word
 * exists so that the table is complete and no value of it is empty.
 */
export const presenceLabel: Record<string, string> = {
    PRESENT: 'Present upstream',
    GONE: 'Gone upstream',
}

export const releaseStateLabel: Record<string, string> = {
    INGESTED: 'Ingested',
    DOCUMENTS_PENDING: 'Ingested, documents pending',
    SKIPPED_PRE_RELEASE: 'Skipped: pre-release',
    FAILED: 'Failed',
    HELD: 'Held: does not fit the version lines',
}

export const runStateLabel: Record<string, string> = {
    QUEUED: 'Queued',
    RUNNING: 'Running',
    COMPLETED: 'Completed',
    COMPLETED_WITH_FAILURES: 'Completed with failures',
    STOPPED: 'Stopped',
    FAILED: 'Failed',
}

/** A sync is never queued and never stopped, so its table has no such key. */
export const syncStateLabel: Record<string, string> = {
    RUNNING: 'Running',
    COMPLETED: 'Completed',
    COMPLETED_WITH_FAILURES: 'Completed with failures',
    FAILED: 'Failed',
}

export const runKindLabel: Record<string, string> = {
    BACKFILL: 'Backfill',
    SYNC: 'Sync',
    ONE_OFF: 'One-off',
}

export const runOriginLabel: Record<string, string> = {
    CONFIRM: 'started by the confirmation',
    MANUAL: 'started by hand',
    SYNC: 'started by the schedule',
}

export const syncOriginLabel: Record<string, string> = {
    SCHEDULED: 'Scheduled',
    MANUAL: 'By hand',
}

export const oneOffStateLabel: Record<string, string> = {
    RESOLVING: 'Resolving',
    AMBIGUOUS: 'Choose a release',
    REFUSED: 'Refused',
    QUEUED: 'Queued',
    INGESTED: 'Ingested',
    FAILED: 'Failed',
    ADOPTED: 'Taken over by a follow',
}

export const fileOutcomeLabel: Record<string, string> = {
    STORED_SBOM: 'Stored as an SBOM',
    STORED_FILE: 'Stored',
    NOT_KEPT: 'Not kept',
    NOT_SELECTED: 'Left out by the source\'s document choice',
}

export const vexImportStateLabel: Record<string, string> = {
    PENDING: 'Import pending',
    IMPORTED: 'Imported',
    NOT_IMPORTED: 'Not imported',
    FAILED: 'Import failed',
}

export const vexImportReasonLabel: Record<string, string> = {
    SOURCE_REJECTS: 'the source is set not to import VEX',
    SOURCE_ARCHIVED: 'the source is archived',
    WAITING_FOR_INVENTORY: 'waiting for the release\'s SBOM to be analysed',
    RETRYING: 'being tried again',
    NO_INVENTORY: 'the release has no analysed SBOM to match against',
    TOO_LARGE: 'the document is too large',
    UNSUPPORTED_FORMAT: 'not a VEX format ReARM imports',
    DOCUMENT_UNREADABLE: 'the document could not be read',
    TOO_MANY_STATEMENTS: 'too many statements',
    TOO_MANY_PRODUCTS: 'too many products',
    NEWER_VERSION_STORED: 'a newer version of this document is stored',
    IMPORT_FAILED: 'the import failed',
    HELD_FOR_REVIEW: 'held for review by the trust rules although the source is set to accept automatically',
}

// ---- The words the screens of the feature use for the other enums ----

export const preparationStateLabel: Record<string, string> = {
    PENDING: 'Reading the item',
    READY: 'Ready to confirm',
    FAILED: 'Failed',
    CONSUMED: 'Confirmed',
}

export const sourceStatusLabel: Record<string, string> = {
    ACTIVE: 'Active',
    ARCHIVED: 'Archived',
}

export const sourceKindLabel: Record<string, string> = {
    TEA: 'TEA server',
}

export const followStatusLabel: Record<string, string> = {
    ACTIVE: 'Following',
    UNFOLLOWED: 'Unfollowed',
}

export const itemKindLabel: Record<string, string> = {
    PRODUCT: 'Product',
    COMPONENT: 'Component',
}

export const credentialKindLabel: Record<string, string> = {
    NONE: 'None',
    BEARER_TOKEN: 'Bearer token',
    API_KEY: 'API key',
}

export const documentClassLabel: Record<string, string> = {
    SBOM: 'SBOMs',
    VEX: 'VEX documents',
    OTHER: 'Other documents',
}

/** ImportMode in short, for the Documents column of the sources list; the form shows vexModeSentence. */
export const vexModeLabel: Record<string, string> = {
    AUTO_ACCEPT: 'VEX accepted automatically',
    STAGE: 'VEX offered for review',
    REJECT: 'VEX not imported',
}

export const backfillKindLabel: Record<string, string> = {
    LATEST_PER_LINE: 'The newest releases of each version line',
    FROM_DATE: 'Every release dated on or after a day',
    FROM_RELEASE: 'From a release on',
}

export const driftBehaviourLabel: Record<string, string> = {
    STOP: 'Stop',
    ADAPT: 'Adapt',
}

export const itemMappingLabel: Record<string, string> = {
    CREATED: 'Created by the follow',
    ADOPTED: 'It existed here before and was adopted by the follow.',
}

export const releaseIntegrityLabel: Record<string, string> = {
    CONSISTENT: 'As ingested',
    UPSTREAM_REWROTE_COLLECTION: 'The supplier changed a collection version after it was ingested. The version ingested first is kept.',
}

export const preReleaseLabel: Record<string, string> = {
    YES: 'pre-release',
    NO: 'final',
    UNSTATED: 'not stated',
}

export const probeStateLabel: Record<string, string> = {
    PENDING: 'Probe in progress',
    DONE: 'Probed',
}

/** UpstreamProbeResult in short: the tag of a probe; the sentence beside it is probe.message. */
export const probeResultLabel: Record<string, string> = {
    REACHABLE: 'Reachable',
    AUTHENTICATION_FAILED: 'Authentication failed',
    ADDRESS_REFUSED: 'Address refused',
    COULD_NOT_CONNECT: 'Could not connect',
    URL_REFUSED: 'Link refused',
    TIMED_OUT: 'Timed out',
    CERTIFICATE_NOT_TRUSTED: 'Certificate not trusted',
    SERVER_ERROR: 'Server error',
    NOT_A_TEA_SERVER: 'Not a TEA server',
    FETCHER_NOT_CONFIGURED: 'Fetcher not set up',
    FETCHER_NOT_AVAILABLE: 'Fetcher not available',
    FETCHER_BUSY: 'Fetcher busy',
    CREDENTIAL_UNREADABLE: 'Credential unreadable',
    NOT_RUN: 'Not run',
    NOT_COMPLETED: 'Not completed',
    FAILED: 'Failed',
}

export const documentReServingLabel: Record<string, string> = {
    WITHHELD: 'Not passed on',
    RE_SERVED: 'Passed on',
}

/**
 * Every label table above with the name of the schema enum it is keyed by. The spec checks each
 * pair against the Pro schema and that no exported table is missing from this list.
 */
export const UPSTREAM_LABEL_TABLES: ReadonlyArray<readonly [string, Record<string, string>]> = [
    ['ImportMode', vexModeSentence],
    ['UpstreamDriftBehaviour', driftBehaviourSentence],
    ['UpstreamProbeResult', probeResultSentence],
    ['UpstreamOneOffFailure', oneOffFailureSentence],
    ['UpstreamRunStop', runStopSentence],
    ['UpstreamPreparationFailure', preparationFailureSentence],
    ['UpstreamDriftKind', driftKindLabel],
    ['UpstreamDepthBasis', depthBasisLabel],
    ['UpstreamDepthFit', depthFitLabel],
    ['UpstreamDepthOrigin', depthOriginLabel],
    ['UpstreamItemUpdateState', itemUpdateStateLabel],
    ['UpstreamPresence', presenceLabel],
    ['UpstreamReleaseState', releaseStateLabel],
    ['UpstreamRunState', runStateLabel],
    ['UpstreamSyncState', syncStateLabel],
    ['UpstreamRunKind', runKindLabel],
    ['UpstreamRunOrigin', runOriginLabel],
    ['UpstreamSyncOrigin', syncOriginLabel],
    ['UpstreamOneOffState', oneOffStateLabel],
    ['UpstreamFileOutcome', fileOutcomeLabel],
    ['UpstreamVexImportState', vexImportStateLabel],
    ['UpstreamVexImportReason', vexImportReasonLabel],
    ['UpstreamPreparationState', preparationStateLabel],
    ['UpstreamSourceStatus', sourceStatusLabel],
    ['UpstreamSourceKind', sourceKindLabel],
    ['UpstreamFollowStatus', followStatusLabel],
    ['UpstreamItemKind', itemKindLabel],
    ['UpstreamCredentialKind', credentialKindLabel],
    ['UpstreamDocumentClass', documentClassLabel],
    ['ImportMode', vexModeLabel],
    ['UpstreamBackfillKind', backfillKindLabel],
    ['UpstreamDriftBehaviour', driftBehaviourLabel],
    ['UpstreamItemMapping', itemMappingLabel],
    ['UpstreamReleaseIntegrity', releaseIntegrityLabel],
    ['UpstreamPreRelease', preReleaseLabel],
    ['UpstreamProbeState', probeStateLabel],
    ['UpstreamProbeResult', probeResultLabel],
    ['UpstreamDocumentReServing', documentReServingLabel],
]

/** The word for an enum value, or the empty string for a value the table does not know. Never the raw value. */
export function labelOf (table: Record<string, string>, value: string | null | undefined): string {
    if (typeof value !== 'string') return ''
    return Object.prototype.hasOwnProperty.call(table, value) ? table[value] : ''
}
