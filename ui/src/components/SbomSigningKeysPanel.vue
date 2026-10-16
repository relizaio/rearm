<template>
    <div class="sbom-signing-panel">
        <h5>{{ SECTION_TITLE }}</h5>
        <p class="text-muted">{{ SECTION_INTRO }}</p>
        <div v-if="loadError" class="sbom-signing-error" data-testid="sbom-signing-load-error">
            {{ loadError }}
            <a href="#" class="ml-2" data-testid="sbom-signing-retry" @click.prevent="loadKeys">Retry</a>
        </div>
        <n-spin :show="loading">
            <div v-if="loaded">
                <div v-if="current" class="sbom-signing-current" data-testid="sbom-signing-current">
                    <div class="sbom-signing-row">
                        <strong>Current key</strong>
                        <code data-testid="sbom-signing-current-keyid">{{ current.keyId }}</code>
                        <n-icon class="clickable" size="18" title="Copy key id" @click="copy(current.keyId, 'Key id')"><Clipboard /></n-icon>
                    </div>
                    <div class="sbom-signing-row">
                        <span>Algorithm: <span data-testid="sbom-signing-current-algorithm">{{ current.algorithm }}</span></span>
                        <span>Source: <span data-testid="sbom-signing-current-source">{{ current.source }}</span></span>
                        <span>Created: <span data-testid="sbom-signing-current-created">{{ dateCell(current.createdDate) }}</span></span>
                    </div>
                    <div class="sbom-signing-row">
                        <span>Public key (JWK):</span>
                        <code class="sbom-signing-jwk" data-testid="sbom-signing-current-jwk">{{ current.publicKeyJwk }}</code>
                        <n-icon class="clickable" size="18" title="Copy JWK" @click="copy(current.publicKeyJwk, 'JWK')"><Clipboard /></n-icon>
                    </div>
                    <div class="sbom-signing-row sbom-signing-pem">
                        <span>Public key (PEM):</span>
                        <n-input type="textarea" readonly :value="current.publicKeyPem || ''" :autosize="{ minRows: 3, maxRows: 6 }"
                            class="sbom-signing-pem-input" data-testid="sbom-signing-current-pem" />
                        <n-icon class="clickable" size="18" title="Copy PEM" @click="copy(current.publicKeyPem, 'PEM')"><Clipboard /></n-icon>
                    </div>
                </div>
                <div v-else data-testid="sbom-signing-no-key">{{ NO_KEY_TEXT }}</div>

                <div class="text-muted mt-2" data-testid="sbom-signing-public-keys-note"><em>Experimental:</em> {{ PUBLIC_KEYS_NOTE }}</div>

                <n-space v-if="canManage" class="mt-2" data-testid="sbom-signing-actions">
                    <n-button type="primary" :disabled="busy" data-testid="sbom-signing-generate" @click="generate">{{ generateLabel(!!current) }}</n-button>
                    <n-button :disabled="busy" data-testid="sbom-signing-import" @click="openImport">Import key</n-button>
                    <n-button v-if="current" type="error" :disabled="busy" data-testid="sbom-signing-revoke-current" @click="revoke(current)">Revoke current key</n-button>
                </n-space>

                <div v-if="others.length" class="mt-3">
                    <n-data-table size="small" :columns="otherColumns" :data="others" :row-key="(row: SbomSigningKey) => row.uuid"
                        data-testid="sbom-signing-others" />
                </div>
            </div>
        </n-spin>

        <n-modal preset="card" title="Import SBOM signing key" style="width: 640px;" :show="importOpen"
            @update:show="(v: boolean) => { if (!v) closeImport() }">
            <div data-testid="sbom-signing-import-modal">
                <p>{{ IMPORT_HINT }}</p>
                <p v-if="current">{{ ROTATE_CONFIRM.text }}</p>
                <n-input v-model:value="importPem" type="textarea" :autosize="{ minRows: 6, maxRows: 14 }"
                    placeholder="-----BEGIN PRIVATE KEY-----" data-testid="sbom-signing-import-pem" />
                <div v-if="importError" class="sbom-signing-error mt-2" data-testid="sbom-signing-import-error">{{ importError }}</div>
                <n-space class="mt-2">
                    <n-button type="primary" :disabled="busy || !importPem" data-testid="sbom-signing-import-submit" @click="submitImport">Import</n-button>
                    <n-button :disabled="busy" @click="closeImport">Cancel</n-button>
                </n-space>
            </div>
        </n-modal>
    </div>
</template>

<script lang="ts" setup>
// Organization settings, "SBOM export signing" (SCORE-35, design 3.2 to 3.4): the CURRENT key,
// a note that the public JWKS is not published yet, the RETIRED and REVOKED keys, and Generate (Rotate) / Import / Revoke. Its own
// component so the section mounts in a test; OrgSettings gates it on org ADMIN and latches the
// schema-drift flag from the `unsupported` event of the list query here.
import { computed, h, ref, watch } from 'vue'
import type { DataTableColumns } from 'naive-ui'
import { NButton, NDataTable, NIcon, NInput, NModal, NSpace, NSpin, NTag } from 'naive-ui'
import { Clipboard } from '@vicons/tabler'
import Swal from 'sweetalert2'
import graphqlClient from '../utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { GENERATE_SBOM_SIGNING_KEY, IMPORT_SBOM_SIGNING_KEY, REVOKE_SBOM_SIGNING_KEY, SBOM_SIGNING_KEYS } from '@/utils/sbomSigningQueries'
import {
    IMPORT_HINT, NO_KEY_TEXT, PUBLIC_KEYS_NOTE, REVOKE_CONFIRM, ROTATE_CONFIRM, SECTION_INTRO, SECTION_TITLE,
    dateCell, generateLabel, isSbomSigningUnsupported, splitKeys,
} from '@/utils/sbomSigningKeys'
import type { SbomSigningKey } from '@/utils/sbomSigningKeys'

const props = defineProps<{
    orgUuid: string
    canManage: boolean
    notify: (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void
}>()
const emit = defineEmits<{ (e: 'unsupported'): void }>()

const keys = ref<SbomSigningKey[]>([])
const loaded = ref(false)
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)
const importOpen = ref(false)
const importPem = ref('')
const importError = ref('')

const split = computed(() => splitKeys(keys.value))
const current = computed((): SbomSigningKey | null => split.value.current)
const others = computed((): SbomSigningKey[] => split.value.others)

const messageOf = (e: any): string => commonFunctions.parseGraphQLError(e?.message || String(e))

async function loadKeys () {
    if (!props.orgUuid) return
    loading.value = true
    try {
        const resp: any = await graphqlClient.query({ query: SBOM_SIGNING_KEYS, variables: { orgUuid: props.orgUuid }, fetchPolicy: 'network-only' })
        keys.value = resp.data?.sbomSigningKeys || []
        loaded.value = true
        loadError.value = ''
    } catch (e: any) {
        if (isSbomSigningUnsupported(e)) {
            // A backend without SBOM signing: OrgSettings replaces the section with its note.
            emit('unsupported')
            return
        }
        loadError.value = messageOf(e)
        props.notify('error', 'Error', loadError.value)
    } finally {
        loading.value = false
    }
}
watch(() => props.orgUuid, () => loadKeys(), { immediate: true })

/** One mutation; the list is reloaded from the server after it, never patched from the reply. */
async function runMutation (mutation: any, variables: Record<string, any>, success: string): Promise<boolean> {
    busy.value = true
    try {
        await graphqlClient.mutate({ mutation, variables, fetchPolicy: 'no-cache' })
        props.notify('success', SECTION_TITLE, success)
        await loadKeys()
        return true
    } finally {
        busy.value = false
    }
}

async function confirm (options: { title: string, text: string, confirmButtonText: string }): Promise<boolean> {
    const r = await Swal.fire({ ...options, icon: 'warning', showCancelButton: true, cancelButtonText: 'Cancel' })
    return !!r?.isConfirmed
}

async function generate () {
    const rotating = !!current.value
    if (rotating && !(await confirm(ROTATE_CONFIRM))) return
    try {
        await runMutation(GENERATE_SBOM_SIGNING_KEY, { orgUuid: props.orgUuid }, rotating ? 'Key rotated' : 'Key generated')
    } catch (e: any) {
        props.notify('error', 'Error', messageOf(e))
    }
}

async function revoke (key: SbomSigningKey) {
    if (!(await confirm(REVOKE_CONFIRM))) return
    try {
        await runMutation(REVOKE_SBOM_SIGNING_KEY, { uuid: key.uuid }, 'Key revoked')
    } catch (e: any) {
        props.notify('error', 'Error', messageOf(e))
    }
}

function openImport () {
    importPem.value = ''
    importError.value = ''
    importOpen.value = true
}

function closeImport () {
    importOpen.value = false
    importError.value = ''
}

async function submitImport () {
    importError.value = ''
    try {
        // Sent as typed: the backend parses the PEM.
        await runMutation(IMPORT_SBOM_SIGNING_KEY, { orgUuid: props.orgUuid, privateKeyPem: importPem.value }, 'Key imported')
        closeImport()
    } catch (e: any) {
        importError.value = messageOf(e)
    }
}

async function copy (text: string | null | undefined, what: string) {
    if (!text) return
    // navigator.clipboard exists only on secure origins; a plain-HTTP install has none.
    if (!navigator.clipboard?.writeText) {
        props.notify('warning', 'Copy unavailable', 'Select the text and copy it.')
        return
    }
    try {
        await navigator.clipboard.writeText(text)
        props.notify('success', 'Copied', `${what} copied`)
    } catch (e: any) {
        props.notify('error', 'Copy failed', e?.message || 'Could not copy to clipboard')
    }
}

const otherColumns = computed((): DataTableColumns<SbomSigningKey> => {
    const cols: DataTableColumns<SbomSigningKey> = [
        { key: 'keyId', title: 'Key id', render: (row) => h('code', row.keyId) },
        { key: 'status', title: 'Status', render: (row) => h(NTag, { size: 'small', type: row.status === 'REVOKED' ? 'error' : 'default' }, { default: () => row.status }) },
        { key: 'source', title: 'Source' },
        { key: 'createdDate', title: 'Created', render: (row) => dateCell(row.createdDate) },
        { key: 'retiredAt', title: 'Retired', render: (row) => dateCell(row.retiredAt) },
        { key: 'revokedAt', title: 'Revoked', render: (row) => dateCell(row.revokedAt) },
    ]
    if (props.canManage) {
        cols.push({
            key: 'actions',
            title: 'Actions',
            render: (row) => row.status === 'RETIRED'
                ? h(NButton, { size: 'tiny', type: 'error', disabled: busy.value, 'data-testid': 'sbom-signing-revoke-row', onClick: () => revoke(row) }, { default: () => 'Revoke' })
                : null,
        })
    }
    return cols
})

defineExpose({ reload: loadKeys })
</script>

<style scoped>
.sbom-signing-row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 4px;
}
.sbom-signing-jwk {
    word-break: break-all;
}
.sbom-signing-pem-input {
    max-width: 620px;
    font-family: monospace;
}
.sbom-signing-error {
    color: #d03050;
}
</style>
