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
    /** whether the current viewer may operate the controls (org admin, owner of a USER key, holder of a free-form key) */
    canManage: (row: any) => boolean
    /** whether the viewer may mint (add / regenerate), which reveals cleartext: on a held free-form key only the holder; defaults to canManage */
    canMint?: (row: any) => boolean
}

function fmtDate (d: any): string { return d ? new Date(d).toLocaleString('en-CA', { hour12: false }).slice(0, 16) : '' }
function isExpired (sec: any): boolean { return !!sec.expiresDate && new Date(sec.expiresDate).getTime() <= Date.now() }
/** local datetime-local value -> ISO instant, or null when empty */
function toIso (local: string): string | null { return local ? new Date(local).toISOString() : null }

/** Confirmation that also asks for an optional expiry (date and time, local). Returns null when cancelled. */
async function askExpiry (title: string, text: string, confirmButtonText: string, current?: string | null, allowClear: boolean = false): Promise<{ expiresDate: string | null, clear: boolean } | null> {
    const cur = current ? new Date(current) : null
    const curLocal = cur ? new Date(cur.getTime() - cur.getTimezoneOffset() * 60000).toISOString().slice(0, 16) : ''
    const r = await Swal.fire({
        title, icon: 'warning', showCancelButton: true, confirmButtonText, cancelButtonText: 'Cancel',
        showDenyButton: allowClear, denyButtonText: 'Clear expiry',
        html: `<p style="text-align:left">${text}</p><label style="display:block;text-align:left;margin-top:8px">Expiry (optional, local time; the secret is refused after it and its tokens end there at the latest)</label><input id="swal-expiry" type="datetime-local" class="swal2-input" style="width: 80%" value="${curLocal}">`,
        preConfirm: () => {
            const v = (document.getElementById('swal-expiry') as HTMLInputElement).value
            if (v && new Date(v).getTime() <= Date.now()) { Swal.showValidationMessage('Expiry must be in the future'); return false }
            return v
        }
    })
    if (r.isDenied) return { expiresDate: null, clear: true }
    if (!r.isConfirmed) return null
    return { expiresDate: toIso(r.value as string), clear: false }
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
    const canMint = opts.canMint || canManage
    const fail = (e: any) => notify('error', 'Error', commonFunctions.parseGraphQLError(e.message))

    async function mintSecret (apiKeyUuid: string, title: string, expiresDate: string | null = null) {
        try {
            const resp: any = await graphqlClient.mutate({ mutation: gql`mutation addApiKeySecret($apiKeyUuid: ID!, $expiresDate: DateTime) { addApiKeySecret(apiKeyUuid: $apiKeyUuid, expiresDate: $expiresDate) { id apiKey authorizationHeader } }`, variables: { apiKeyUuid, expiresDate }, fetchPolicy: 'no-cache' })
            await showMintedSecret(resp.data.addApiKeySecret, title); await reload()
        } catch (e: any) { fail(e) }
    }
    async function addApiKeySecret (row: any) {
        const first = !(row.secrets || []).length
        const a = await askExpiry(first ? 'Generate the first secret?' : 'Add a second secret?', first ? 'The secret is shown once.' : 'Both secrets work until you retire or regenerate one. Move your clients to the new secret, then retire the old one.', first ? 'Generate' : 'Add secret')
        if (!a) return
        await mintSecret(row.uuid, first ? 'Secret generated' : 'Secret added', a.expiresDate)
    }
    async function regenerateApiKeySecret (row: any, slot: number) {
        const a = await askExpiry(`Regenerate secret #${slot}?`, 'The current secret in this slot stops working immediately, and so does every access token exchanged with it. The other secret, if any, is unaffected.', 'Regenerate')
        if (!a) return
        try {
            const resp: any = await graphqlClient.mutate({ mutation: gql`mutation regenerateApiKeySecret($apiKeyUuid: ID!, $slot: Int!, $expiresDate: DateTime) { regenerateApiKeySecret(apiKeyUuid: $apiKeyUuid, slot: $slot, expiresDate: $expiresDate) { id apiKey authorizationHeader } }`, variables: { apiKeyUuid: row.uuid, slot, expiresDate: a.expiresDate }, fetchPolicy: 'no-cache' })
            await showMintedSecret(resp.data.regenerateApiKeySecret, `Secret #${slot} regenerated`); await reload()
        } catch (e: any) { fail(e) }
    }
    async function setApiKeySecretExpiry (row: any, slot: number) {
        const sec = (row.secrets || []).find((x: any) => x.slot === slot)
        const a = await askExpiry(`Expiry of secret #${slot}`, 'Change when this secret stops working. Tokens already exchanged with it end at the new expiry at the latest.', 'Save', sec?.expiresDate, !!sec?.expiresDate)
        if (!a) return
        try {
            await graphqlClient.mutate({ mutation: gql`mutation setApiKeySecretExpiry($apiKeyUuid: ID!, $slot: Int!, $expiresDate: DateTime) { setApiKeySecretExpiry(apiKeyUuid: $apiKeyUuid, slot: $slot, expiresDate: $expiresDate) { uuid } }`, variables: { apiKeyUuid: row.uuid, slot, expiresDate: a.clear ? null : a.expiresDate }, fetchPolicy: 'no-cache' })
            notify('success', 'Saved', a.clear ? `Secret #${slot} no longer expires` : `Secret #${slot} expires ${fmtDate(a.expiresDate)}`); await reload()
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
        if (row.status === 'REQUESTED' || row.status === 'DENIED') {
            return h(NTag, { size: 'small', type: row.status === 'REQUESTED' ? 'warning' : 'error' }, { default: () => row.status })
        }
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
        if (row.status === 'REQUESTED' || row.status === 'DENIED') {
            return h('span', { class: 'text-muted' }, row.status === 'REQUESTED' ? 'available once approved' : '—')
        }
        const manage = canManage(row)
        const mint = canMint(row)
        const lines = secrets.map((sec: any) => {
            // a legacy slot 1 predates per-secret dates: fall back to the key's own creation date
            const created = sec.createdDate || (sec.slot === 1 ? row.createdDate : null)
            const expired = isExpired(sec)
            const meta = `created ${created ? String(created).slice(0, 10) : 'n/a'} · last used ${sec.lastUsedDate ? String(sec.lastUsedDate).slice(0, 10) : 'never'}` + (sec.expiresDate ? ` · expires ${fmtDate(sec.expiresDate)}` : '')
            const kids: any[] = [
                h('strong', { style: 'margin-right: 4px;' }, `#${sec.slot}`),
                h(NTag, { size: 'tiny', type: expired ? 'error' : (sec.active ? 'success' : 'default'), style: 'margin-right: 6px;' }, { default: () => expired ? 'expired' : (sec.active ? 'active' : 'retired') }),
                h('span', { class: 'subtle', style: 'margin-right: 6px;' }, meta)
            ]
            if (mint) kids.push(h(NButton, { size: 'tiny', style: 'margin-right: 4px;', onClick: () => regenerateApiKeySecret(row, sec.slot) }, { default: () => 'Regenerate' }))
            if (manage) {
                kids.push(h(NButton, { size: 'tiny', style: 'margin-right: 4px;', onClick: () => setApiKeySecretExpiry(row, sec.slot) }, { default: () => 'Expiry' }))
                kids.push(h(NButton, { size: 'tiny', type: sec.active ? 'warning' : 'primary', style: 'margin-right: 4px;', onClick: () => setApiKeySecretActive(row, sec.slot, !sec.active) }, { default: () => sec.active ? 'Retire' : 'Enable' }))
                kids.push(h(NButton, { size: 'tiny', type: 'error', onClick: () => deleteApiKeySecret(row, sec.slot) }, { default: () => 'Delete' }))
            }
            return h('div', { style: 'display: flex; align-items: center; white-space: nowrap; margin: 2px 0;' }, kids)
        })
        if (row.holder && !mint && manage) {
            lines.push(h('span', { class: 'subtle' }, 'held key: only the holder mints its secrets'))
        }
        if (mint && secrets.length < 2) {
            lines.push(h(NButton, { size: 'tiny', dashed: true, style: 'margin-top: 2px;', onClick: () => addApiKeySecret(row) }, { default: () => secrets.length ? 'Add second secret (rotation)' : 'Add secret' }))
        }
        return h('div', lines)
    }

    return { statusCell, secretsCell, mintSecret, addApiKeySecret, regenerateApiKeySecret, setApiKeySecretExpiry, setApiKeySecretActive, deleteApiKeySecret, setApiKeyStatus, deleteApiKey, askExpiry }
}

/** The id string a client presents for this key (Basic user name / client_id). */
export function apiKeyIdOf (row: any): string {
    let keyId = row.type + '__' + row.object
    if (row.keyOrder) keyId += '__ord__' + row.keyOrder
    return keyId
}
