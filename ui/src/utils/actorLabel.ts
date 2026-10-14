// Who updated a release, as a person reads it (task t20261013-072815-31687). The release history's
// "Updated by" printed "API Key ID: <uuid>" for a key actor; a key now reads like it does elsewhere,
// through apiKeyLabelOf. Pure: strings only, never markup.
import { apiKeyLabelOf, cleanLabelText } from './apiKeyLabel'

/**
 * A person as "name <email>", else the name, else the email, else the uuid. Cleaned like key text, so a
 * name cannot hide or reorder what sits around it in the column.
 */
function personLabel (user: any, uuid: string): string {
    const name = cleanLabelText(user.name)
    const email = cleanLabelText(user.email)
    if (name && email) return `${name} <${email}>`
    return name || email || uuid
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

/**
 * The org's keys for labelling key actors in a history. The server lets only an org admin (or a global admin)
 * list them, so anyone else gets [] without a request. A failed read is [] too. Each caller keeps its own guard
 * against loading the same org twice.
 */
export async function loadOrgKeyRefs (store: any, orgUuid: string | null | undefined, isAdmin: boolean): Promise<any[]> {
    if (!orgUuid || !isAdmin) return []
    return (await store.dispatch('fetchApiKeyRefsOfOrg', orgUuid)) || []
}
