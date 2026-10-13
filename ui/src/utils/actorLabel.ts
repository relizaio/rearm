// Who updated a release, as a person reads it (task t20261013-072815-31687). The release history's
// "Updated by" printed "API Key ID: <uuid>" for a key actor; a key now reads like it does elsewhere,
// through apiKeyLabelOf. Pure: strings only, never markup.
import { apiKeyLabelOf } from './apiKeyLabel'

/**
 * The label of the actor with this uuid: "System" for an automatic change; a person as "name <email>";
 * any other actor as a key label from keys (the bare short id when the viewer cannot see that key). While
 * users is empty the uuid is returned as is: the list may not have loaded yet, and where members cannot
 * load it at all a person must not be labelled as a key.
 */
export function actorLabel (uuid: string | null | undefined, createdType: string | null | undefined, users: any[], keys: any[]): string {
    if (createdType === 'AUTO') return 'System'
    if (!uuid) return ''
    if (!(users || []).length) return uuid
    const user = users.find((u: any) => u?.uuid === uuid)
    if (user) return user.email ? `${user.name} <${user.email}>` : user.name
    return apiKeyLabelOf(uuid, keys, '')
}
