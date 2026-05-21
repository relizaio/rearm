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

        <CommitterEditDialog
            v-model:show="showDialog"
            :org-uuid="orgUuid"
            :committer="editTarget"
            @saved="load"
        />
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import {
    NBreadcrumb, NBreadcrumbItem, NButton, NDataTable, NIcon, NPopconfirm,
    NSpace, NSpin, NTag, NTooltip, DataTableColumns, useNotification,
} from 'naive-ui'
import { QuestionCircle20Regular } from '@vicons/fluent'
import CommitterEditDialog from './CommitterEditDialog.vue'

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
const editTarget = ref<any | null>(null)

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
    editTarget.value = null
    showDialog.value = true
}

function edit (row: any) {
    editTarget.value = row
    showDialog.value = true
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
