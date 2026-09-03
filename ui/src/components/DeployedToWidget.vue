<template>
    <div class="deployedToWidget" data-testid="deployed-to-widget">
        <div class="widgetHeader">
            <h5 style="margin: 0;">Deployed to</h5>
            <n-icon class="clickable" size="18" title="Refresh" @click="load"><Refresh /></n-icon>
            <span v-if="rows.length" class="countNote">{{ rows.length }} {{ rows.length === 1 ? 'deployment' : 'deployments' }}</span>
        </div>
        <n-spin :show="loading">
            <div v-if="!supported" class="emptyNote">This ReARM backend does not provide deployment data for the DevOps view yet.</div>
            <n-data-table v-else-if="rows.length" :data="rows" :columns="columns" :row-key="rowKey" size="small" :max-height="360" data-testid="deployed-to-table" />
            <div v-else-if="!loading" class="emptyNote">Not deployed on any instance.</div>
        </n-spin>
    </div>
</template>

<script lang="ts">
export default {
    name: 'DeployedToWidget'
}
</script>
<script lang="ts" setup>
import { h, ref, watch, Ref } from 'vue'
import { useStore } from 'vuex'
import { RouterLink } from 'vue-router'
import { DataTableColumns, NDataTable, NIcon, NSpin, useNotification, NotificationType } from 'naive-ui'
import { Refresh } from '@vicons/tabler'
import commonFunctions from '@/utils/commonFunctions'

interface DeployedToRow {
    instanceUuid: string
    instanceDisplayName: string
    namespace: string | null
    environment: string | null
    featureSetUuid: string | null
    featureSetName: string | null
    releaseUuid: string | null
    version: string | null
    matched: boolean
}

const props = defineProps<{
    orgUuid: string
    componentUuid: string
    // 'COMPONENT' | 'PRODUCT' -- only affects the feature-set column title
    componentType?: string
}>()
const emit = defineEmits<{ (e: 'openRelease', releaseUuid: string): void }>()

const store = useStore()
const notification = useNotification()
const notify = (type: NotificationType, title: string, content: string) => {
    notification[type]({ content, meta: title, duration: 3500, keepAliveOnHover: true })
}

const loading = ref(false)
const supported = ref(true)
const rows: Ref<DeployedToRow[]> = ref([])

async function load () {
    if (!props.componentUuid) return
    loading.value = true
    try {
        const result = await store.dispatch('fetchDeployedTo', props.componentUuid)
        supported.value = result.supported
        rows.value = result.rows
    } catch (err: any) {
        console.error(err)
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    } finally {
        loading.value = false
    }
}

const rowKey = (row: DeployedToRow) => `${row.instanceUuid}|${row.namespace || ''}|${row.featureSetUuid || ''}|${row.releaseUuid || ''}`

const columns: DataTableColumns<DeployedToRow> = [
    {
        key: 'instanceDisplayName',
        title: 'Instance',
        render: (row) => h(RouterLink, {
            to: { name: 'Instance', params: { orguuid: props.orgUuid, instuuid: row.instanceUuid } }
        }, { default: () => row.instanceDisplayName })
    },
    { key: 'namespace', title: 'Namespace', render: (row) => row.namespace || 'default' },
    { key: 'environment', title: 'Environment', render: (row) => row.environment || '-' },
    {
        key: 'featureSetName',
        title: props.componentType === 'PRODUCT' ? 'Feature Set' : 'Branch',
        render: (row) => row.featureSetName || '-'
    },
    {
        key: 'version',
        title: 'Version',
        render: (row) => row.matched && row.releaseUuid
            ? h('a', { href: '#', onClick: (e: Event) => { e.preventDefault(); emit('openRelease', row.releaseUuid as string) } }, row.version || row.releaseUuid)
            : h('span', { class: 'notMatched' }, 'Not Matched')
    }
]

watch(() => props.componentUuid, () => load())
load()
</script>

<style scoped lang="scss">
.widgetHeader { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.countNote { color: #7a8590; font-size: 12px; }
.emptyNote { padding: 12px 0; color: #7a8590; font-style: italic; }
:deep(.notMatched) { color: #7a8590; }
</style>
