<template>
    <div class="notificationChannelsTab">
        <div class="head">
            <div class="title-row">
                <h4>Notification channels</h4>
                <n-tooltip trigger="hover" :width="400" placement="bottom-start">
                    <template #trigger>
                        <n-icon size="16" class="info-icon">
                            <QuestionCircle20Regular/>
                        </n-icon>
                    </template>
                    Destinations that the notifications framework dispatches to —
                    Slack incoming-webhook URLs and generic HTTPS endpoints (with
                    NONE / BEARER / HMAC_SHA256 auth). Channels are referenced by
                    one or more subscriptions; the "Test" button fires a curated
                    synthetic event through the full pipeline so you can confirm
                    the destination is reachable before relying on real events.
                </n-tooltip>
            </div>
            <n-space>
                <n-button @click="reload" size="small">Refresh</n-button>
                <n-button type="primary" @click="openCreate">+ New channel</n-button>
            </n-space>
        </div>

        <n-spin v-if="loading" size="small"/>
        <div v-else>
            <div v-if="!channels.length" class="empty">
                No notification channels configured yet. Click <strong>+ New channel</strong> to add one.
            </div>
            <n-data-table v-else :columns="columns" :data="channels" :pagination="{ pageSize: 25 }"/>
        </div>

        <NotificationChannelEditDialog
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
import NotificationChannelEditDialog from './NotificationChannelEditDialog.vue'

const store = useStore()
const route = useRoute()
const notification = useNotification()

const myorg = computed(() => store.getters.myorg)
const orgUuid = computed(() => (route.params.orguuid as string) || myorg.value?.uuid)
const channels = ref<any[]>([])
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
        channels.value = await store.dispatch('fetchNotificationChannelsOfOrg', orgUuid.value) || []
    } catch (e: any) {
        notification.error({ content: `Failed to load channels: ${e?.message ?? e}` })
    } finally {
        loading.value = false
    }
}

async function toggleStatus (row: any) {
    const nextStatus = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
    try {
        await store.dispatch('setNotificationChannelStatus', { uuid: row.uuid, status: nextStatus })
        notification.success({ content: `Channel "${row.name}" → ${nextStatus}` })
        await reload()
    } catch (e: any) {
        notification.error({ content: `Status flip failed: ${e?.message ?? e}` })
    }
}

async function deleteChannel (row: any) {
    try {
        await store.dispatch('deleteNotificationChannel', row.uuid)
        notification.success({ content: `Deleted channel "${row.name}"` })
        await reload()
    } catch (e: any) {
        notification.error({ content: `Delete failed: ${e?.message ?? e}` })
    }
}

async function testChannel (row: any) {
    try {
        const event = await store.dispatch('testNotificationChannel', row.uuid)
        // Event UUID lets the operator find the resulting delivery in the
        // history tab. Dispatch is asynchronous (up to ~10s).
        notification.success({
            content: `Test event injected for "${row.name}". Event uuid: ${event.uuid}. The actual webhook POST happens on the next fan-out tick (~5s) plus channel-worker tick.`,
            duration: 8000,
        })
    } catch (e: any) {
        notification.error({ content: `Test channel failed: ${e?.message ?? e}` })
    }
}

const columns = computed<DataTableColumns<any>>(() => [
    { title: 'Name', key: 'name', render: (row: any) => h('strong', null, row.name) },
    {
        title: 'Type',
        key: 'type',
        width: 110,
        render: (row: any) => h(NTag, { size: 'small', bordered: false }, { default: () => row.type }),
    },
    {
        title: 'Status',
        key: 'status',
        width: 110,
        render: (row: any) => h(NTag,
            { size: 'small', type: row.status === 'ENABLED' ? 'success' : 'warning' },
            { default: () => row.status }),
    },
    {
        title: 'UUID',
        key: 'uuid',
        render: (row: any) => h('code', { style: 'font-size: 11px; color: #888;' }, row.uuid),
    },
    {
        title: '',
        key: 'actions',
        width: 340,
        render: (row: any) => h(NSpace, { size: 'small' }, {
            default: () => [
                h(NButton, {
                    size: 'tiny', type: 'info', secondary: true,
                    onClick: () => testChannel(row),
                }, { default: () => 'Test' }),
                h(NButton, {
                    size: 'tiny', quaternary: true,
                    onClick: () => openEdit(row),
                }, { default: () => 'Edit' }),
                h(NButton, {
                    size: 'tiny', quaternary: true,
                    onClick: () => toggleStatus(row),
                }, { default: () => row.status === 'ENABLED' ? 'Disable' : 'Enable' }),
                h(NPopconfirm, {
                    onPositiveClick: () => deleteChannel(row),
                }, {
                    trigger: () => h(NButton, { size: 'tiny', quaternary: true, type: 'error' },
                        { default: () => 'Delete' }),
                    default: () => `Delete channel "${row.name}"?`,
                }),
            ],
        }),
    },
])
</script>

<style scoped>
.notificationChannelsTab {
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
