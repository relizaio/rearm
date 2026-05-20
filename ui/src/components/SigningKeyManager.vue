<template>
    <div class="signingKeyManager">
        <div class="head">
            <div class="title-row">
                <h5>Signing keys</h5>
                <n-tooltip trigger="hover" :width="380" placement="bottom-start">
                    <template #trigger>
                        <n-icon size="14" class="info-icon">
                            <QuestionCircle20Regular/>
                        </n-icon>
                    </template>
                    Public keys the verifier matches signatures against. The
                    fingerprint is the trust anchor — ReARM never trusts the
                    public key embedded in a signature blob, only what's
                    enrolled here. Revoked keys keep verifying historical
                    SCEs but never bind new commits.
                </n-tooltip>
            </div>
            <n-button size="small" type="primary" @click="showEnroll = true">+ Enrol key</n-button>
        </div>
        <div v-if="!keys.length" class="empty">No keys enrolled.</div>
        <n-data-table v-else :columns="columns" :data="keys" :pagination="{ pageSize: 10 }" size="small"/>

        <n-modal v-model:show="showEnroll" preset="card" title="Enrol public key" style="width: 640px;">
            <n-form label-placement="top">
                <n-form-item label="Format" required>
                    <n-radio-group v-model:value="draft.format">
                        <n-radio-button value="SSH">SSH</n-radio-button>
                        <n-radio-button value="GPG">GPG</n-radio-button>
                    </n-radio-group>
                </n-form-item>
                <n-form-item label="Public key" required>
                    <n-input v-model:value="draft.pubKey" type="textarea" :rows="6"
                             :placeholder="draft.format === 'SSH'
                                 ? 'ssh-ed25519 AAAAC3... user@example.com'
                                 : '-----BEGIN PGP PUBLIC KEY BLOCK-----\\n…\\n-----END PGP PUBLIC KEY BLOCK-----'"
                             style="font-family: monospace;"/>
                </n-form-item>
            </n-form>
            <template #footer>
                <n-space>
                    <n-button @click="showEnroll = false">Cancel</n-button>
                    <n-button type="primary" :loading="enrolling" :disabled="!canEnrol" @click="enrol">Enrol</n-button>
                </n-space>
            </template>
        </n-modal>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { useStore } from 'vuex'
import {
    NButton, NDataTable, NForm, NFormItem, NIcon, NInput, NModal, NPopconfirm,
    NRadioButton, NRadioGroup, NSpace, NTag, NTooltip,
    DataTableColumns, useNotification,
} from 'naive-ui'
import { QuestionCircle20Regular } from '@vicons/fluent'

const props = defineProps<{
    org: string,
    ownerType: 'AGENT' | 'COMMITTER',
    ownerUuid: string,
}>()

const store = useStore()
const notification = useNotification()

const keys = ref<any[]>([])
const showEnroll = ref<boolean>(false)
const enrolling = ref<boolean>(false)
const draft = ref<{ format: 'SSH' | 'GPG', pubKey: string }>({
    format: 'SSH', pubKey: '',
})

const canEnrol = computed(() => !!draft.value.pubKey.trim())

onMounted(load)
watch(() => props.ownerUuid, load)

async function load () {
    if (!props.org || !props.ownerType || !props.ownerUuid) return
    try {
        keys.value = await store.dispatch('fetchSigningKeysOfOwner', {
            orgUuid: props.org,
            ownerType: props.ownerType,
            ownerUuid: props.ownerUuid,
        }) || []
    } catch (e: any) {
        notification.error({ content: `Failed to load keys: ${e?.message ?? e}` })
    }
}

async function enrol () {
    enrolling.value = true
    try {
        const input: any = {
            org: props.org,
            format: draft.value.format,
            ownerType: props.ownerType,
            ownerUuid: props.ownerUuid,
            pubKey: draft.value.pubKey.trim(),
        }
        const saved = await store.dispatch('enrollSigningKey', input)
        notification.success({ content: `Enrolled ${saved.format} key ${saved.fingerprint}` })
        showEnroll.value = false
        draft.value = { format: 'SSH', pubKey: '' }
        await load()
    } catch (e: any) {
        notification.error({ content: `Enrol failed: ${e?.message ?? e}`, duration: 8000 })
    } finally {
        enrolling.value = false
    }
}

async function revoke (row: any) {
    try {
        await store.dispatch('revokeSigningKey', row.uuid)
        notification.success({ content: `Revoked key ${row.fingerprint}` })
        await load()
    } catch (e: any) {
        notification.error({ content: `Revoke failed: ${e?.message ?? e}` })
    }
}

function formatDate (s: any) {
    return s ? new Date(s).toLocaleString('en-CA') : '—'
}

const columns = computed<DataTableColumns<any>>(() => [
    {
        title: 'Format',
        key: 'format',
        width: 80,
        render: (row: any) => h(NTag, { size: 'small', type: row.format === 'SSH' ? 'info' : 'default' },
            { default: () => row.format }),
    },
    {
        title: 'Fingerprint',
        key: 'fingerprint',
        render: (row: any) => h('code', { class: 'fp' }, row.fingerprint),
    },
    {
        title: 'Identity',
        key: 'identity',
        render: (row: any) => row.identity ? h('code', null, row.identity) : '—',
    },
    {
        title: 'Status',
        key: 'status',
        width: 110,
        render: (row: any) => row.revokedAt
            ? h(NTag, { size: 'tiny', type: 'error' }, { default: () => `REVOKED ${formatDate(row.revokedAt)}` })
            : h(NTag, { size: 'tiny', type: 'success' }, { default: () => 'ACTIVE' }),
    },
    { title: 'Enrolled', key: 'createdDate', width: 170, render: (row: any) => formatDate(row.createdDate) },
    {
        title: '',
        key: 'actions',
        width: 100,
        render: (row: any) => row.revokedAt ? null : h(NPopconfirm, {
            onPositiveClick: () => revoke(row),
        }, {
            trigger: () => h(NButton, { size: 'tiny', quaternary: true, type: 'error' }, { default: () => 'Revoke' }),
            default: () => `Revoke this key? Past verdicts keep their VERIFIED state; future commits signed with this key will land KEY_REVOKED.`,
        }),
    },
])
</script>

<style scoped>
.signingKeyManager { padding: 8px 0; }
.head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.title-row { display: flex; align-items: center; gap: 6px; }
.title-row h5 { margin: 0; }
.info-icon { color: #888; cursor: help; }
.empty { color: var(--n-text-color-3, #666); font-style: italic; padding: 8px 0; }
:deep(.fp) { font-size: 11px; word-break: break-all; }
</style>
