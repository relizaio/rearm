<template>
    <div class="notificationDeliveriesTab">
        <div class="head">
            <div class="title-row">
                <h4>Delivery history</h4>
                <n-tooltip trigger="hover" :width="400" placement="bottom-start">
                    <template #trigger>
                        <n-icon size="16" class="info-icon">
                            <QuestionCircle20Regular/>
                        </n-icon>
                    </template>
                    Each row is one (event × channel) dispatch attempt. PENDING
                    rows are waiting for the next channel-worker tick or a
                    backoff window. FAILED rows hit MAX_ATTEMPTS=7 or a
                    non-retriable error (most often a misconfigured webhook
                    URL); the <code>lastError</code> column has the operator-
                    facing diagnostic.
                </n-tooltip>
            </div>
            <n-space>
                <n-select v-model:value="statusFilter"
                          :options="statusOptions"
                          placeholder="Status: any" size="small" style="width: 160px;" clearable/>
                <n-select v-model:value="originFilter"
                          :options="originOptions"
                          placeholder="Origin: any" size="small" style="width: 140px;" clearable/>
                <n-button @click="reload" size="small">Refresh</n-button>
            </n-space>
        </div>

        <n-spin v-if="loading" size="small"/>
        <div v-else>
            <div v-if="!page.items?.length" class="empty">
                No deliveries match the current filter.
            </div>
            <n-data-table v-else
                          :columns="columns"
                          :data="page.items"
                          :pagination="paginationCfg"
                          remote
                          :loading="loading"/>
            <div class="total-row">Total: {{ page.totalCount }} deliveries</div>
        </div>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { useRoute } from 'vue-router'
import {
    NButton, NDataTable, NIcon, NSelect, NSpace, NSpin, NTag, NTooltip,
    DataTableColumns, useNotification,
} from 'naive-ui'
import { QuestionCircle20Regular } from '@vicons/fluent'

const store = useStore()
const route = useRoute()
const notification = useNotification()

const myorg = computed(() => store.getters.myorg)
const orgUuid = computed(() => (route.params.orguuid as string) || myorg.value?.uuid)

const page = ref<any>({ items: [], totalCount: 0, limit: 25, offset: 0 })
const loading = ref<boolean>(true)
const statusFilter = ref<string | null>(null)
const originFilter = ref<string | null>(null)
const currentPage = ref(1)
const pageSize = 25

const statusOptions = [
    { label: 'PENDING', value: 'PENDING' },
    { label: 'SENT', value: 'SENT' },
    { label: 'FAILED', value: 'FAILED' },
    { label: 'ACKED', value: 'ACKED' },
]
const originOptions = [
    { label: 'REAL', value: 'REAL' },
    { label: 'SYNTHETIC', value: 'SYNTHETIC' },
]

const paginationCfg = computed(() => ({
    page: currentPage.value,
    pageSize,
    itemCount: page.value.totalCount,
    showSizePicker: false,
    onChange: (p: number) => {
        currentPage.value = p
        reload()
    },
}))

watch([statusFilter, originFilter], () => {
    currentPage.value = 1
    reload()
})

onMounted(reload)

async function reload () {
    if (!orgUuid.value) return
    loading.value = true
    try {
        page.value = await store.dispatch('fetchNotificationDeliveries', {
            orgUuid: orgUuid.value,
            status: statusFilter.value,
            origin: originFilter.value,
            limit: pageSize,
            offset: (currentPage.value - 1) * pageSize,
        }) || { items: [], totalCount: 0, limit: pageSize, offset: 0 }
    } catch (e: any) {
        notification.error({ content: `Failed to load deliveries: ${e?.message ?? e}` })
    } finally {
        loading.value = false
    }
}

function formatDate (s: any) {
    return s ? new Date(s).toLocaleString('en-CA') : '—'
}

function statusType (s: string) {
    if (s === 'SENT' || s === 'ACKED') return 'success'
    if (s === 'FAILED') return 'error'
    if (s === 'PENDING') return 'info'
    return 'default'
}

const columns = computed<DataTableColumns<any>>(() => [
    {
        title: 'Status',
        key: 'status',
        width: 100,
        render: (row: any) => h(NTag, { size: 'small', type: statusType(row.status) },
            { default: () => row.status }),
    },
    {
        title: 'Origin',
        key: 'origin',
        width: 110,
        render: (row: any) => h(NTag,
            { size: 'small', bordered: false, type: row.origin === 'SYNTHETIC' ? 'info' : 'default' },
            { default: () => row.origin }),
    },
    {
        title: 'Channel',
        key: 'channelUuid',
        render: (row: any) => h('code', { style: 'font-size: 11px;' },
            row.channelUuid ? row.channelUuid.substring(0, 8) + '…' : '—'),
    },
    { title: 'Attempts', key: 'attemptCount', width: 90 },
    {
        title: 'Created',
        key: 'createdDate',
        width: 170,
        render: (row: any) => formatDate(row.createdDate),
    },
    {
        title: 'Sent',
        key: 'sentAt',
        width: 170,
        render: (row: any) => formatDate(row.sentAt),
    },
    {
        title: 'Last error',
        key: 'lastError',
        render: (row: any) => row.lastError
            ? h('span', { style: 'color: #c00; font-size: 12px;' }, row.lastError)
            : '—',
    },
])
</script>

<style scoped>
.notificationDeliveriesTab {
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
.total-row {
    margin-top: 8px;
    font-size: 12px;
    color: #888;
}
</style>
