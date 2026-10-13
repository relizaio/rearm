// Who updated a release, as a person reads it (task t20261013-072815-31687). The release history's
// "Updated by" printed "API Key ID: <uuid>" for a key actor; a key now reads like it does elsewhere,
// through apiKeyLabelOf. Pure: strings only, never markup.
import { apiKeyLabelOf } from './apiKeyLabel'

/** A person as "name <email>", else the name, else the email, else the uuid. */
function personLabel (user: any, uuid: string): string {
    if (user.name && user.email) return `${user.name} <${user.email}>`
    return user.name || user.email || uuid
}

/**
 * The label of the actor with this uuid on a release change: "System" for an automatic change; a person
 * among users as "name <email>"; a key among keys as its key label. Not found: an API change (the server
 * records the key, or a personal key's owner) reads as a key by short id; any other change keeps the
 * uuid, since a person missing from users (not loaded yet, not loadable for members, or departed) must
 * not be labelled as a key.
 */
export function releaseActorLabel (uuid: string | null | undefined, createdType: string | null | undefined, users: any[], keys: any[]): string {
    if (createdType === 'AUTO') return 'System'
    if (!uuid) return ''
    const user = (users || []).find((u: any) => u?.uuid === uuid)
    if (user) return personLabel(user, uuid)
    if ((keys || []).some((k: any) => k?.uuid === uuid)) return apiKeyLabelOf(uuid, keys)
    if (createdType === 'API' && (users || []).length) return apiKeyLabelOf(uuid, keys)
    return uuid
}
