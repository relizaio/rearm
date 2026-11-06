// Organization write access, mirrored in the browser so a control the server will refuse is
// rendered disabled with the reason rather than offered in vain (FDAW-14 3.9, parent F-30
// item 3). The server's refusal stays the authority; this only decides what is offered.

/**
 * True when a permission row grants READ_WRITE or ADMIN on the organization itself (scope
 * ORGANIZATION, object the organization): the right a bulk support sweep needs. A COMPONENT
 * or PERSPECTIVE row, however broad, is not organization write.
 */
export function hasOrgWrite (permissions: any[] | null | undefined, orgUuid: string | null | undefined): boolean {
    if (!orgUuid) return false
    return (permissions ?? []).some((p: any) => p?.org === orgUuid && p?.object === orgUuid
        && p?.scope === 'ORGANIZATION' && (p?.type === 'READ_WRITE' || p?.type === 'ADMIN'))
}

/** The one sentence every organization-wide control shows when the caller lacks the right. */
export function orgWriteSentence (verb: string): string {
    return `${verb} changes organization-wide data and needs organization write access, which an`
        + ' organization administrator grants in Organization Settings > Users'
}
