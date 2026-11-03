// What the key settings say once a key id is created (keys are created without a secret). A Free Form key's
// permissions are set before it is used, so it gets a notice and no prompt; every other type is offered its first
// secret right away.

export type KeyCreatedFollowUp =
    | { kind: 'notice', title: string, content: string }
    | { kind: 'offerSecret', titleText: string, text: string, confirmButtonText: string, cancelButtonText: string }

export const FREEFORM_CREATED_NOTICE = 'Free Form API Key created. Set its permissions, then generate a secret from the Secrets column when needed.'

/** The confirm before a Free Form key id is created. */
export const FREEFORM_CREATE_CONFIRM = 'A new Free Form API Key id will be created, with no permissions and no secret yet.'

export function keyCreatedFollowUp (apiType: string, label: string): KeyCreatedFollowUp {
    if (apiType === 'FREEFORM') return { kind: 'notice', title: 'Created', content: FREEFORM_CREATED_NOTICE }
    return {
        kind: 'offerSecret',
        titleText: `${label} created`,
        text: 'The key id exists but has no secret yet. Generate its first secret now? You can also do it later from the Secrets column.',
        confirmButtonText: 'Generate secret',
        cancelButtonText: 'Later',
    }
}
