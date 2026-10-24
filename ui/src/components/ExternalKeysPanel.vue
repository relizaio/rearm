<template>
    <div class="external-keys-panel">
        <n-alert data-testid="external-keys-banner" type="warning" :closable="false" class="mt-4">
            External keys are meant for parties outside your organization. A party holding one can only read what this
            organization published on the Transparency Exchange API (TEA): no GraphQL, no downloads, no CLI. Hand the key id
            and a secret to the party; they exchange them for a token at:
            <div class="mt-2">
                <template v-if="tokenUrl">
                    <code data-testid="external-keys-token-url">{{ tokenUrl }}</code>
                    <n-button size="tiny" class="ml-2" data-testid="external-keys-token-url-copy" @click="copyTokenUrl">Copy</n-button>
                </template>
                <span v-else data-testid="external-keys-token-url-missing">
                    the organization's TEA id is minted when the first external key is created
                </span>
            </div>
        </n-alert>
        <div class="programmaticAccessBlock mt-4">
            <h5>External Keys</h5>
            <n-spin :show="loading">
                <n-data-table data-testid="external-keys-table" :columns="externalKeyFields" :data="rows" :scroll-x="2200"
                    :row-key="(r: any) => r.uuid" class="table-hover" />
            </n-spin>
            <n-icon v-if="isOrgAdmin" class="clickable" data-testid="external-key-create" title="Create External Key" size="24"
                @click="createExternalKey"><CirclePlus /></n-icon>
        </div>
        <ApiKeyPermissionsModal v-model:show="showGrants" :api-key="grantsKey" :org-uuid="orgUuid" :notify="notify" @saved="reload" />
    </div>
</template>

<script setup lang="ts">
/**
 * The External Keys sub-tab of Programmatic Access (task TEA-3): EXTERNAL keys, handed to a party
 * outside the organization, which exchange at the TEA token endpoint and read what the organization
 * published on TEA, nothing else. Pro only (the host gates it with teaProfilesAvailable), and it
 * reads its own documents, since the CE schema has none of these fields. Its own component for the
 * reason TeaOrgPanel is: OrgSettings is large enough.
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NDataTable, NIcon, NSpin } from 'naive-ui'
import { CirclePlus, Key as KeyIcon, Pencil, Trash } from '@vicons/tabler'
import { useStore } from 'vuex'
import Swal from 'sweetalert2'
import graphqlClient from '@/utils/graphql'
import graphqlQueries from '@/utils/graphqlQueries'
import commonFunctions from '@/utils/commonFunctions'
import {
    apiKeyIdsColumn, apiKeyTypeColumn, createApiKeyControls, externalGrantsSummary, EXTERNAL_GRANTS_NONE, teaTokenUrl
} from '@/utils/apiKeyControls'
import ApiKeyPermissionsModal from './ApiKeyPermissionsModal.vue'

const props = withDefaults(defineProps<{
    orgUuid: string
    notify: (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void
    isOrgAdmin?: boolean
}>(), {
    isOrgAdmin: false,
})

const HOLDER_NAME_MAX = 200

const store = useStore()
const loading = ref(false)
const rows = ref<any[]>([])
const discovery = ref<any>(null)
const showGrants = ref(false)
const grantsKey = ref<any>(null)

const tokenUrl = computed(() => teaTokenUrl(discovery.value?.apiBase))
const productUuids = computed(() => new Set<string>((store.getters.productsOfOrg(props.orgUuid) || []).map((p: any) => p.uuid)))

const fail = (e: any) => props.notify('error', 'Error', commonFunctions.parseGraphQLError(e.message))

async function reload () {
    loading.value = true
    try {
        const [keys, d]: any[] = await Promise.all([
            graphqlClient.query({ query: graphqlQueries.ExternalApiKeysGql, variables: { orgUuid: props.orgUuid }, fetchPolicy: 'no-cache' }),
            graphqlClient.query({ query: graphqlQueries.TeaOrgDiscoveryGql, variables: { org: props.orgUuid }, fetchPolicy: 'no-cache' }),
        ])
        rows.value = keys.data.externalApiKeys ?? []
        discovery.value = d.data.teaOrgDiscovery
    } catch (e: any) {
        console.error(e)
        fail(e)
    } finally {
        loading.value = false
    }
}

async function loadProducts () {
    try {
        await store.dispatch('fetchProducts', props.orgUuid)
    } catch (e: any) {
        // the grants summary then counts every COMPONENT grant as a component
        console.error(e)
    }
}

const controls = createApiKeyControls({
    notify: props.notify,
    reload,
    canManage: () => props.isOrgAdmin,
    canMint: () => props.isOrgAdmin,
    isAdmin: () => props.isOrgAdmin,
})

function fmtDate (d: any, empty: string): string {
    return d ? new Date(d).toLocaleString('en-CA') : empty
}

function holderNameError (v: string): string | null {
    const t = (v || '').trim()
    if (!t) return 'The holder name is required'
    if (t.length > HOLDER_NAME_MAX) return `At most ${HOLDER_NAME_MAX} characters`
    return null
}

async function createExternalKey () {
    const r = await Swal.fire({
        title: 'Create an external key',
        html: '<p style="text-align:left">The key id is created with no secret, for one party outside the organization.</p>'
            + '<label style="display:block;text-align:left;margin-top:8px">Holder (the outside party, required)</label>'
            + `<input id="swal-external-holder" class="swal2-input" style="width: 80%" maxlength="${HOLDER_NAME_MAX}">`
            + '<label style="display:block;text-align:left;margin-top:8px">Notes (optional)</label>'
            + '<textarea id="swal-external-notes" class="swal2-textarea" style="width: 80%"></textarea>',
        showCancelButton: true,
        confirmButtonText: 'Create',
        cancelButtonText: 'Cancel',
        preConfirm: () => {
            const holderName = (document.getElementById('swal-external-holder') as HTMLInputElement).value
            const notes = (document.getElementById('swal-external-notes') as HTMLTextAreaElement).value
            const err = holderNameError(holderName)
            if (err) { Swal.showValidationMessage(err); return false }
            return { holderName: holderName.trim(), notes: notes.trim() }
        }
    })
    if (!r.isConfirmed || !r.value) return
    const { holderName, notes } = r.value as { holderName: string, notes: string }
    try {
        await graphqlClient.mutate({
            mutation: graphqlQueries.CreateExternalApiKeyGql,
            variables: { orgUuid: props.orgUuid, holderName, notes: notes || null },
            fetchPolicy: 'no-cache'
        })
    } catch (e: any) {
        fail(e)
        return
    }
    await reload()
    await Swal.fire({
        title: 'External key created',
        icon: 'success',
        text: `Key id created with no secret. Mint its first secret from the Secrets column, set its grants, and hand the id and the secret to ${holderName}.`
    })
}

async function editHolderName (row: any) {
    const r = await Swal.fire({
        title: 'Holder of this external key',
        input: 'text',
        inputValue: row.holderName || '',
        inputAttributes: { maxlength: String(HOLDER_NAME_MAX) },
        showCancelButton: true,
        confirmButtonText: 'Save',
        cancelButtonText: 'Cancel',
        inputValidator: (v: string) => holderNameError(v)
    })
    if (!r.isConfirmed) return
    try {
        await graphqlClient.mutate({
            mutation: graphqlQueries.SetApiKeyHolderNameGql,
            variables: { apiKeyUuid: row.uuid, holderName: String(r.value).trim() },
            fetchPolicy: 'no-cache'
        })
        props.notify('success', 'Saved', 'Holder name saved')
        await reload()
    } catch (e: any) { fail(e) }
}

function openGrants (row: any) {
    grantsKey.value = commonFunctions.deepCopy(row)
    showGrants.value = true
}

const externalKeyFields = computed(() => [
    apiKeyTypeColumn,
    apiKeyIdsColumn(),
    {
        key: 'holderName', width: 220, title: 'Holder',
        render: (row: any) => h('div', { style: 'display: flex; align-items: center;' }, [
            h('span', { 'data-testid': 'external-key-holder' }, row.holderName || ''),
            props.isOrgAdmin
                ? h(NIcon, { title: 'Rename the holder', class: 'icons clickable', size: 18, style: 'margin-left: 6px;',
                    'data-testid': 'external-key-holder-edit', onClick: () => editHolderName(row) }, { default: () => h(Pencil) })
                : null
        ])
    },
    { key: 'createdDate', width: 180, title: 'Created', render: (row: any) => fmtDate(row.createdDate, '') },
    { key: 'accessDate', width: 180, title: 'Last Accessed', render: (row: any) => fmtDate(row.accessDate, 'Never') },
    {
        key: 'grants', width: 260, title: 'Grants',
        render: (row: any) => {
            const summary = externalGrantsSummary(row.permissions?.permissions, props.orgUuid, productUuids.value)
            return h('span', { 'data-testid': 'external-key-grants', style: summary === EXTERNAL_GRANTS_NONE ? 'color: #f0a020;' : '' }, summary)
        }
    },
    { key: 'status', width: 200, title: 'Status', render: controls.statusCell },
    { key: 'secrets', width: 420, title: 'Secrets', render: controls.secretsCell },
    { key: 'notes', width: 180, title: 'Notes' },
    {
        key: 'controls', title: 'Manage',
        render: (row: any) => {
            if (!props.isOrgAdmin) return h('div')
            return h('div', [
                h(NIcon, { title: 'Set grants', class: 'icons clickable', size: 25, 'data-testid': 'external-key-grants-edit',
                    onClick: () => openGrants(row) }, { default: () => h(KeyIcon) }),
                h(NIcon, { title: 'Delete key', class: 'icons clickable', size: 25, 'data-testid': 'external-key-delete',
                    onClick: () => controls.deleteApiKey(row, 'this external key') }, { default: () => h(Trash) })
            ])
        }
    }
])

async function copyTokenUrl () {
    await navigator.clipboard.writeText(tokenUrl.value ?? '')
    props.notify('success', 'Copied', 'Token URL copied')
}

onMounted(() => { reload(); loadProducts() })
watch(() => props.orgUuid, () => { reload(); loadProducts() })
</script>
