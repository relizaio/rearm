<template>
    <div class="committersOfOrg">
        <n-breadcrumb separator="›" class="crumbs" v-if="!props.embedded">
            <n-breadcrumb-item @click="openOrgSettings">Organization Settings</n-breadcrumb-item>
            <n-breadcrumb-item>Committers</n-breadcrumb-item>
        </n-breadcrumb>
        <div class="head">
            <div class="title-row">
                <h4 v-if="!props.embedded">Committers</h4>
                <n-tooltip trigger="hover" :width="380" placement="bottom-start">
                    <template #trigger>
                        <n-icon size="16" class="info-icon">
                            <QuestionCircle20Regular/>
                        </n-icon>
                    </template>
                    Natural-person (or external-bot) commit authors the verifier
                    resolves a signed commit back to. Each committer owns one or
                    more enrolled signing keys; CEL approval policies can require
                    "all commits in this release must be signed by an approved
                    committer".
                </n-tooltip>
            </div>
            <n-button @click="createNew" type="primary">+ New committer</n-button>
        </div>

        <n-spin v-if="loading" size="small"/>
        <div v-else>
            <div v-if="!committers.length" class="empty">
                No committers enrolled yet. Click <strong>New committer</strong> to add one.
            </div>
            <n-data-table v-else :columns="columns" :data="committers" :pagination="{ pageSize: 25 }"/>
        </div>

        <!-- Create / edit dialog -->
        <n-modal v-model:show="showDialog" preset="card" :title="dialogTitle" style="width: 560px;">
            <n-form label-placement="top">
                <n-form-item label="Name" required>
                    <n-input v-model:value="draft.name" placeholder="e.g. Alex Doe"/>
                </n-form-item>
                <n-form-item label="Email" required>
                    <n-input v-model:value="draft.email" placeholder="alex@example.com"/>
                </n-form-item>
                <n-form-item label="Aliases (comma-separated)">
                    <n-input v-model:value="draft.aliasesText" placeholder="alex@old.example.com"/>
                </n-form-item>
                <n-form-item label="Linked ReARM user UUID (optional)">
                    <n-input v-model:value="draft.user" placeholder="leave blank for external contributor"/>
                </n-form-item>
            </n-form>
            <template #footer>
                <n-space>
                    <n-button @click="showDialog = false">Cancel</n-button>
                    <n-button type="primary" :loading="saving" @click="saveDraft">Save</n-button>
                </n-space>
            </template>
        </n-modal>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import {
    NBreadcrumb, NBreadcrumbItem, NButton, NDataTable, NForm, NFormItem,
    NIcon, NInput, NModal, NPopconfirm, NSpace, NSpin, NTag, NTooltip,
    DataTableColumns, useNotification,
} from 'naive-ui'
import { QuestionCircle20Regular } from '@vicons/fluent'

const props = defineProps<{ embedded?: boolean }>()

const store = useStore()
const route = useRoute()
const router = useRouter()
const notification = useNotification()

const myorg = computed(() => store.getters.myorg)
const orgUuid = computed(() => (route.params.orguuid as string) || myorg.value?.uuid)
const committers = ref<any[]>([])
const loading = ref<boolean>(true)
const showDialog = ref<boolean>(false)
const saving = ref<boolean>(false)
const draft = ref<{ uuid?: string, name: string, email: string, aliasesText: string, user: string }>({
    name: '', email: '', aliasesText: '', user: '',
})

const dialogTitle = computed(() => draft.value.uuid ? 'Edit committer' : 'New committer')

onMounted(load)

async function load () {
    loading.value = true
    try {
        committers.value = await store.dispatch('fetchCommittersOfOrg', orgUuid.value) || []
    } catch (e: any) {
        notification.error({ content: `Failed to load committers: ${e?.message ?? e}` })
    } finally {
        loading.value = false
    }
}

function openOrgSettings () {
    router.push({ name: 'OrgSettings', params: { orguuid: orgUuid.value }, query: { tab: 'committers' } })
}

function createNew () {
    draft.value = { name: '', email: '', aliasesText: '', user: '' }
    showDialog.value = true
}

function edit (row: any) {
    draft.value = {
        uuid: row.uuid,
        name: row.name ?? '',
        email: row.email ?? '',
        aliasesText: (row.aliases ?? []).join(', '),
        user: row.user ?? '',
    }
    showDialog.value = true
}

async function saveDraft () {
    if (!draft.value.name.trim() || !draft.value.email.trim()) {
        notification.warning({ content: 'Name and email are required' })
        return
    }
    saving.value = true
    try {
        const input: any = {
            org: orgUuid.value,
            name: draft.value.name.trim(),
            email: draft.value.email.trim().toLowerCase(),
        }
        if (draft.value.uuid) input.uuid = draft.value.uuid
        if (draft.value.user.trim()) input.user = draft.value.user.trim()
        const aliases = draft.value.aliasesText.split(',').map((a) => a.trim().toLowerCase()).filter(Boolean)
        if (aliases.length) input.aliases = aliases
        const saved = await store.dispatch('upsertCommitter', input)
        notification.success({ content: `Saved "${saved.name}"` })
        showDialog.value = false
        await load()
    } catch (e: any) {
        notification.error({ content: `Save failed: ${e?.message ?? e}` })
    } finally {
        saving.value = false
    }
}

async function archive (row: any) {
    try {
        await store.dispatch('archiveCommitter', row.uuid)
        notification.success({ content: `Archived "${row.name}"` })
        await load()
    } catch (e: any) {
        notification.error({ content: `Archive failed: ${e?.message ?? e}` })
    }
}

function openCommitter (uuid: string) {
    router.push({ name: 'CommitterView', params: { uuid } })
}

function formatDate (s: any) {
    return s ? new Date(s).toLocaleString('en-CA') : '—'
}

const columns = computed<DataTableColumns<any>>(() => [
    {
        title: 'Name',
        key: 'name',
        render: (row: any) => h('a', {
            href: '#',
            onClick: (e: Event) => { e.preventDefault(); openCommitter(row.uuid) },
        }, row.name),
    },
    { title: 'Email', key: 'email', render: (row: any) => h('code', null, row.email) },
    {
        title: 'Keys',
        key: 'keys',
        width: 90,
        render: (row: any) => {
            const ks = row.signingKeys ?? []
            const active = ks.filter((k: any) => !k.revokedAt).length
            return `${active} active${ks.length > active ? ` / ${ks.length} total` : ''}`
        },
    },
    {
        title: 'Status',
        key: 'status',
        width: 110,
        render: (row: any) => h(NTag, { size: 'small', type: row.status === 'ACTIVE' ? 'success' : 'default' },
            { default: () => row.status }),
    },
    { title: 'Created', key: 'createdDate', width: 170, render: (row: any) => formatDate(row.createdDate) },
    {
        title: '',
        key: 'actions',
        width: 160,
        render: (row: any) => h(NSpace, { size: 'small' }, {
            default: () => [
                h(NButton, { size: 'tiny', quaternary: true, onClick: () => edit(row) }, { default: () => 'Edit' }),
                row.status === 'ACTIVE'
                    ? h(NPopconfirm, {
                        onPositiveClick: () => archive(row),
                    }, {
                        trigger: () => h(NButton, { size: 'tiny', quaternary: true, type: 'warning' }, { default: () => 'Archive' }),
                        default: () => `Archive "${row.name}"? Past verdicts keep their owner uuid; new verdicts won't bind to a revoked-only committer.`,
                    })
                    : null,
            ].filter(Boolean),
        }),
    },
])
</script>

<style scoped>
.committersOfOrg { padding: 16px; }
.crumbs { margin-bottom: 12px; font-size: 13px; }
.crumbs :deep(.n-breadcrumb-item__link) { cursor: pointer; }
.head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.title-row { display: flex; align-items: center; gap: 8px; }
.title-row h4 { margin: 0; }
.info-icon { color: #888; cursor: help; }
.empty { color: var(--n-text-color-3, #666); font-style: italic; padding: 12px 0; }
</style>
