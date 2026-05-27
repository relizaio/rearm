<template>
    <div class="notificationSubscriptionsTab">
        <div class="head">
            <div class="title-row">
                <h4>Subscriptions</h4>
                <n-tooltip trigger="hover" :width="400" placement="bottom-start">
                    <template #trigger>
                        <n-icon size="16" class="info-icon">
                            <QuestionCircle20Regular/>
                        </n-icon>
                    </template>
                    Customer-authored rules that route notification events to
                    channels. Each subscription scopes a CEL filter expression
                    (e.g. <code>event.severity == "CRITICAL"</code>) plus one or
                    more routes; matched events fan out to every channel listed
                    on a matching route. Subscriptions in
                    <strong>DISABLED</strong> or <strong>PREVIEW</strong> status
                    are excluded from live fan-out.
                </n-tooltip>
            </div>
            <n-space>
                <n-button @click="reload" size="small">Refresh</n-button>
                <n-button type="primary" @click="openCreate">+ New subscription</n-button>
            </n-space>
        </div>

        <n-spin v-if="loading" size="small"/>
        <div v-else>
            <div v-if="!subs.length" class="empty">
                No subscriptions configured yet. Click <strong>+ New subscription</strong> to add one.
            </div>
            <n-data-table v-else :columns="columns" :data="subs" :pagination="{ pageSize: 25 }"
                          :row-key="(r: any) => r.uuid"/>
        </div>

        <NotificationSubscriptionEditDialog
            v-model:show="showDialog"
            :org-uuid="orgUuid"
            :original="editTarget"
            @saved="onSaved"/>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import { useRoute } from 'vue-router'
import {
    NAlert, NButton, NDataTable, NIcon, NPopconfirm, NSpace, NSpin, NTag,
    NTooltip, DataTableColumns, useNotification,
} from 'naive-ui'
import { QuestionCircle20Regular } from '@vicons/fluent'
import NotificationSubscriptionEditDialog from './NotificationSubscriptionEditDialog.vue'

const store = useStore()
const route = useRoute()
const notification = useNotification()

const myorg = computed(() => store.getters.myorg)
const orgUuid = computed(() => (route.params.orguuid as string) || myorg.value?.uuid)
const subs = ref<any[]>([])
const loading = ref<boolean>(true)
const showDialog = ref<boolean>(false)
const editTarget = ref<any | null>(null)

onMounted(reload)

function openCreate () {
    editTarget.value = null
    showDialog.value = true
}

function openEdit (row: any) {
    editTarget.value = row
    showDialog.value = true
}

async function onSaved () {
    await reload()
}

async function reload () {
    if (!orgUuid.value) return
    loading.value = true
    try {
        subs.value = await store.dispatch('fetchNotificationSubscriptionsOfOrg', orgUuid.value) || []
    } catch (e: any) {
        notification.error({ content: `Failed to load subscriptions: ${e?.message ?? e}` })
    } finally {
        loading.value = false
    }
}

async function setStatus (row: any, nextStatus: string) {
    try {
        await store.dispatch('setNotificationSubscriptionStatus', { uuid: row.uuid, status: nextStatus })
        notification.success({ content: `Subscription "${row.name}" → ${nextStatus}` })
        await reload()
    } catch (e: any) {
        notification.error({ content: `Status flip failed: ${e?.message ?? e}` })
    }
}

async function deleteSub (row: any) {
    try {
        await store.dispatch('deleteNotificationSubscription', row.uuid)
        notification.success({ content: `Deleted subscription "${row.name}"` })
        await reload()
    } catch (e: any) {
        notification.error({ content: `Delete failed: ${e?.message ?? e}` })
    }
}

function summarizeFilter (raw: string | null): string {
    if (!raw) return 'match all'
    try {
        const f = JSON.parse(raw)
        if (f.celExpression) return `CEL: ${f.celExpression}`
        if (f.mode) return `${f.mode} preset`
        return 'configured'
    } catch (e) { return 'configured' }
}

function summarizeRoutes (raw: string | null): string {
    if (!raw) return '—'
    try {
        const rs = JSON.parse(raw) as any[]
        if (!rs.length) return 'no routes'
        const channelCount = rs.reduce((a, r) => a + (r.channels?.length ?? 0), 0)
        return `${rs.length} route${rs.length === 1 ? '' : 's'}, ${channelCount} channel${channelCount === 1 ? '' : 's'}`
    } catch (e) { return 'configured' }
}

const columns = computed<DataTableColumns<any>>(() => [
    { title: 'Name', key: 'name', render: (row: any) => h('strong', null, row.name) },
    {
        title: 'Status',
        key: 'status',
        width: 110,
        render: (row: any) => h(NTag,
            { size: 'small', type: row.status === 'ACTIVE' ? 'success' : row.status === 'PREVIEW' ? 'info' : 'warning' },
            { default: () => row.status }),
    },
    {
        title: 'Event types',
        key: 'eventTypes',
        render: (row: any) => h('span', { style: 'font-size: 12px;' },
            (row.eventTypes ?? []).join(', ') || '—'),
    },
    {
        title: 'Filter',
        key: 'filter',
        render: (row: any) => h('code', { style: 'font-size: 11px; color: #555;' },
            summarizeFilter(row.filter)),
    },
    {
        title: 'Routes',
        key: 'routes',
        width: 160,
        render: (row: any) => h('span', { style: 'font-size: 12px;' }, summarizeRoutes(row.routes)),
    },
    {
        title: '',
        key: 'actions',
        width: 340,
        render: (row: any) => {
            const buttons = []
            buttons.push(h(NButton, {
                size: 'tiny', quaternary: true,
                onClick: () => openEdit(row),
            }, { default: () => 'Edit' }))
            if (row.status !== 'ACTIVE') {
                buttons.push(h(NButton, {
                    size: 'tiny', quaternary: true,
                    onClick: () => setStatus(row, 'ACTIVE'),
                }, { default: () => 'Activate' }))
            }
            if (row.status !== 'DISABLED') {
                buttons.push(h(NButton, {
                    size: 'tiny', quaternary: true,
                    onClick: () => setStatus(row, 'DISABLED'),
                }, { default: () => 'Disable' }))
            }
            buttons.push(h(NPopconfirm, {
                onPositiveClick: () => deleteSub(row),
            }, {
                trigger: () => h(NButton, { size: 'tiny', quaternary: true, type: 'error' },
                    { default: () => 'Delete' }),
                default: () => `Delete subscription "${row.name}"?`,
            }))
            return h(NSpace, { size: 'small' }, { default: () => buttons })
        },
    },
])
</script>

<style scoped>
.notificationSubscriptionsTab {
    margin-top: 8px;
}
.head {
    display: flex;
    justify-content: space-between;
    align-items: center;
}
.title-row {
    display: flex;
    align-items: center;
    gap: 8px;
}
.empty {
    color: #888;
    font-size: 14px;
    padding: 16px 0;
}
.info-icon {
    cursor: help;
    color: #888;
}
</style>
