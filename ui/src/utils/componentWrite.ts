// Component-page write access, mirrored in the browser so a control the server will refuse is
// not offered (FDAW-66). The server's refusal stays the authority; this only decides what is offered.
import { hasOrgWrite } from './orgWrite'

/**
 * The grant types the server ranks at or above write: `permissionClearsCall` compares a scoped
 * grant's type by ordinal (NONE < ESSENTIAL_READ < READ_ONLY < READ_WRITE < ADMIN), so an ADMIN
 * grant clears every call its READ_WRITE counterpart clears.
 */
const WRITE_TYPES: readonly string[] = ['READ_WRITE', 'ADMIN']

function isWriteType (type: any): boolean {
    return WRITE_TYPES.includes(type)
}

/**
 * True when the caller may write on a component's pages: an organization READ_WRITE or ADMIN row
 * of this organization; a PERSPECTIVE READ_WRITE or ADMIN row of this organization on the current
 * (non-default) perspective; or a COMPONENT READ_WRITE or ADMIN row of this organization on this
 * component. Every row is tested (`some`), as the server walks every grant.
 */
export function componentPageWritable (permissions: any[] | null | undefined, orgUuid: string | null | undefined,
    perspective: string | null | undefined, componentUuid: string | null | undefined): boolean {
    if (!orgUuid || !permissions || !permissions.length) return false
    if (hasOrgWrite(permissions, orgUuid)) return true
    if (perspective && perspective !== 'default' && permissions.some((p: any) => p?.scope === 'PERSPECTIVE'
        && p?.org === orgUuid && p?.object === perspective && isWriteType(p?.type))) return true
    return !!componentUuid && permissions.some((p: any) => p?.scope === 'COMPONENT'
        && p?.org === orgUuid && p?.object === componentUuid && isWriteType(p?.type))
}
