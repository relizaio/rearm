import { h } from 'vue'
import { NButton, NTag } from 'naive-ui'
import gql from 'graphql-tag'
import Swal from 'sweetalert2'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'

/**
 * Shared API key status + secrets controls (kill switch, two-secret rotation, delete).
 * Used by the org settings key tables and by the user's own keys on the profile page.
 * status: INACTIVE refuses every secret and every token of the key id. Secrets: up to two per id;
 * regenerate replaces one in place (its tokens die), retire keeps it on file but refused, delete frees the slot.
 */
export interface ApiKeyControlOptions {
    notify: (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void
    reload: () => void | Promise<void>
    /** whether the current viewer may operate the controls (org admin, or owner of a USER key) */
    canManage: (row: any) => boolean
}

async function confirmThen (title: string, text: string, confirmButtonText: string): Promise<boolean> {
    const r = await Swal.fire({ title, text, icon: 'warning', showCancelButton: true, confirmButtonText, cancelButtonText: 'Cancel' })
    return !!r.value
}

export async function showMintedSecret (forUser: any, title: string) {
    await Swal.fire({ title, customClass: { popup: 'swal-wide' }, html: commonFunctions.getGeneratedApiKeyHTML(forUser), icon: 'success' })
}

export function createApiKeyControls (opts: ApiKeyControlOptions) {
    const { notify, reload, canManage } = opts
    const fail = (e: any) => notify('error', 'Error', commonFunctions.parseGraphQLError(e.message))

    async function mintSecret (apiKeyUuid: string, title: string) {
        try {
            const resp: any = await graphqlClient.mutate({ mutation: gql`mutation addApiKeySecret($apiKeyUuid: ID!) { addApiKeySecret(apiKeyUuid: $apiKeyUuid) { id apiKey authorizationHeader } }`, variables: { apiKeyUuid }, fetchPolicy: 'no-cache' })
            await showMintedSecret(resp.data.addApiKeySecret, title); await reload()
        } catch (e: any) { fail(e) }
    }
    async function addApiKeySecret (row: any) {
        const first = !(row.secrets || []).length
        if (!first && !(await confirmThen('Add a second secret?', 'Both secrets work until you retire or regenerate one. Move your clients to the new secret, then retire the old one.', 'Add secret'))) return
        await mintSecret(row.uuid, first ? 'Secret generated' : 'Secret added')
    }
    async function regenerateApiKeySecret (row: any, slot: number) {
        if (!(await confirmThen(`Regenerate secret #${slot}?`, 'The current secret in this slot stops working immediately, and so does every access token exchanged with it. The other secret, if any, is unaffected.', 'Regenerate'))) return
        try {
            const resp: any = await graphqlClient.mutate({ mutation: gql`mutation regenerateApiKeySecret($apiKeyUuid: ID!, $slot: Int!) { regenerateApiKeySecret(apiKeyUuid: $apiKeyUuid, slot: $slot) { id apiKey authorizationHeader } }`, variables: { apiKeyUuid: row.uuid, slot }, fetchPolicy: 'no-cache' })
            await showMintedSecret(resp.data.regenerateApiKeySecret, `Secret #${slot} regenerated`); await reload()
        } catch (e: any) { fail(e) }
    }
    async function setApiKeySecretActive (row: any, slot: number, active: boolean) {
        if (!active && !(await confirmThen(`Retire secret #${slot}?`, 'It stays on file and can be enabled again, but it is refused until then, and so are access tokens exchanged with it.', 'Retire'))) return
        try {
            await graphqlClient.mutate({ mutation: gql`mutation setApiKeySecretActive($apiKeyUuid: ID!, $slot: Int!, $active: Boolean!) { setApiKeySecretActive(apiKeyUuid: $apiKeyUuid, slot: $slot, active: $active) { uuid } }`, variables: { apiKeyUuid: row.uuid, slot, active }, fetchPolicy: 'no-cache' })
            notify('success', active ? 'Enabled' : 'Retired', `Secret #${slot} ${active ? 'enabled' : 'retired'}`); await reload()
        } catch (e: any) { fail(e) }
    }
    async function deleteApiKeySecret (row: any, slot: number) {
        if (!(await confirmThen(`Delete secret #${slot}?`, 'This cannot be undone. The secret and every access token exchanged with it stop working; the slot becomes free for a new secret.', 'Delete'))) return
        try {
            await graphqlClient.mutate({ mutation: gql`mutation deleteApiKeySecret($apiKeyUuid: ID!, $slot: Int!) { deleteApiKeySecret(apiKeyUuid: $apiKeyUuid, slot: $slot) { uuid } }`, variables: { apiKeyUuid: row.uuid, slot }, fetchPolicy: 'no-cache' })
            notify('success', 'Deleted', `Secret #${slot} deleted`); await reload()
        } catch (e: any) { fail(e) }
    }
    async function setApiKeyStatus (row: any, status: string) {
        if (status === 'INACTIVE' && !(await confirmThen('Deactivate this key?', 'Every secret and every access token of this key id are refused until you activate it again. Nothing is deleted.', 'Deactivate'))) return
        try {
            await graphqlClient.mutate({ mutation: gql`mutation setApiKeyStatus($apiKeyUuid: ID!, $status: ApiKeyStatus!) { setApiKeyStatus(apiKeyUuid: $apiKeyUuid, status: $status) { uuid status } }`, variables: { apiKeyUuid: row.uuid, status }, fetchPolicy: 'no-cache' })
            notify('success', status === 'INACTIVE' ? 'Deactivated' : 'Activated', `Key ${status.toLowerCase()}`); await reload()
        } catch (e: any) { fail(e) }
    }
    async function deleteApiKey (row: any, label: string = 'this key') {
        if (!(await confirmThen(`Delete ${label}?`, 'The key id, its secrets and every access token stop working. This cannot be undone.', 'Delete'))) return
        try {
            await graphqlClient.mutate({ mutation: gql`mutation deleteApiKey($apiKeyUuid: ID!) { deleteApiKey(apiKeyUuid: $apiKeyUuid) }`, variables: { apiKeyUuid: row.uuid }, fetchPolicy: 'no-cache' })
            notify('success', 'Deleted', 'Key deleted'); await reload()
        } catch (e: any) { fail(e) }
    }

    const statusCell = (row: any) => {
        const inactive = row.status === 'INACTIVE'
        const children: any[] = [h(NTag, { size: 'small', type: inactive ? 'error' : 'success', style: 'margin-right: 6px;' }, { default: () => inactive ? 'INACTIVE' : 'ACTIVE' })]
        if (canManage(row)) {
            children.push(h(NButton, { size: 'tiny', type: inactive ? 'primary' : 'warning', onClick: () => setApiKeyStatus(row, inactive ? 'ACTIVE' : 'INACTIVE') },
                { default: () => inactive ? 'Activate' : 'Deactivate' }))
        }
        return h('div', { style: 'display: flex; align-items: center; white-space: nowrap;' }, children)
    }
    const secretsCell = (row: any) => {
        const secrets: any[] = row.secrets || []
        const manage = canManage(row)
        const lines = secrets.map((sec: any) => {
            // a legacy slot 1 predates per-secret dates: fall back to the key's own creation date
            const created = sec.createdDate || (sec.slot === 1 ? row.createdDate : null)
            const meta = `created ${created ? String(created).slice(0, 10) : 'n/a'} · last used ${sec.lastUsedDate ? String(sec.lastUsedDate).slice(0, 10) : 'never'}`
            const kids: any[] = [
                h('strong', { style: 'margin-right: 4px;' }, `#${sec.slot}`),
                h(NTag, { size: 'tiny', type: sec.active ? 'success' : 'default', style: 'margin-right: 6px;' }, { default: () => sec.active ? 'active' : 'retired' }),
                h('span', { class: 'subtle', style: 'margin-right: 6px;' }, meta)
            ]
            if (manage) {
                kids.push(h(NButton, { size: 'tiny', style: 'margin-right: 4px;', onClick: () => regenerateApiKeySecret(row, sec.slot) }, { default: () => 'Regenerate' }))
                kids.push(h(NButton, { size: 'tiny', type: sec.active ? 'warning' : 'primary', style: 'margin-right: 4px;', onClick: () => setApiKeySecretActive(row, sec.slot, !sec.active) }, { default: () => sec.active ? 'Retire' : 'Enable' }))
                kids.push(h(NButton, { size: 'tiny', type: 'error', onClick: () => deleteApiKeySecret(row, sec.slot) }, { default: () => 'Delete' }))
            }
            return h('div', { style: 'display: flex; align-items: center; white-space: nowrap; margin: 2px 0;' }, kids)
        })
        if (manage && secrets.length < 2) {
            lines.push(h(NButton, { size: 'tiny', dashed: true, style: 'margin-top: 2px;', onClick: () => addApiKeySecret(row) }, { default: () => secrets.length ? 'Add second secret (rotation)' : 'Add secret' }))
        }
        return h('div', lines)
    }

    return { statusCell, secretsCell, mintSecret, addApiKeySecret, regenerateApiKeySecret, setApiKeySecretActive, deleteApiKeySecret, setApiKeyStatus, deleteApiKey }
}

/** The id string a client presents for this key (Basic user name / client_id). */
export function apiKeyIdOf (row: any): string {
    let keyId = row.type + '__' + row.object
    if (row.keyOrder) keyId += '__ord__' + row.keyOrder
    return keyId
}
