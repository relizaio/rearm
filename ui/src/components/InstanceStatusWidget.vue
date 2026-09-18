<template>
    <div class="instanceStatusWidget" data-testid="instance-status-widget">
        <div class="widgetHeader">
            <h3 style="margin: 0;">Instance Status</h3>
            <n-icon class="clickable" size="20" title="Refresh" @click="load"><Refresh /></n-icon>
            <span v-if="props.perspectiveName" class="scopeNote">Perspective: {{ props.perspectiveName }}</span>
        </div>
        <div class="summaryStrip" data-testid="instance-status-summary">
            <button class="statTile" :class="{ active: !filters.driftOnly && !filters.health }" @click="clearQuickFilters" title="All instances">
                <span class="statValue">{{ summary.instances }}</span><span class="statLabel">instances</span>
            </button>
            <button class="statTile ok" disabled title="Instances whose products all match their targets">
                <span class="statValue">{{ summary.matched }}</span><span class="statLabel">in sync</span>
            </button>
            <button class="statTile warn" :class="{ active: filters.driftOnly }" @click="toggleDriftOnly" title="Show only instances with drift or unmatched products">
                <span class="statValue">{{ summary.drifted + summary.notMatched }}</span><span class="statLabel">drifted / unmatched</span>
            </button>
            <button class="statTile err" :class="{ active: filters.health === 'FAILING' }" @click="toggleHealth('FAILING')" title="Show only failing instances">
                <span class="statValue">{{ summary.failing }}</span><span class="statLabel">failing</span>
            </button>
            <button class="statTile warn" :class="{ active: filters.health === 'DEGRADED' }" @click="toggleHealth('DEGRADED')" title="Show only degraded instances">
                <span class="statValue">{{ summary.degraded }}</span><span class="statLabel">degraded</span>
            </button>
            <button class="statTile err" disabled>
                <span class="statValue">{{ summary.openFailures }}</span><span class="statLabel">open CD failures</span>
            </button>
        </div>
        <div class="filterRow">
            <n-select v-model:value="filters.environment" :options="environmentOptions" clearable filterable placeholder="Environment" style="width: 180px;" data-testid="instance-status-env" />
            <n-input v-model:value="filters.text" clearable placeholder="Filter by URI, namespace, product" style="width: 280px;" data-testid="instance-status-text" />
            <span class="resultCount">{{ filteredRows.length }} of {{ rows.length }}</span>
        </div>
        <n-spin :show="loading">
            <n-data-table :data="filteredRows" :columns="columns" :row-key="rowKey" size="small" :max-height="520" data-testid="instance-status-table" />
            <div v-if="!loading && !rows.length" class="emptyNote">
                {{ props.perspectiveName ? 'No instance deploys a product of this perspective.' : 'No instances in this organization.' }}
            </div>
        </n-spin>
    </div>
</template>

<script lang="ts">
export default {
    name: 'InstanceStatusWidget'
}
</script>
<script lang="ts" setup>
import { computed, h, ref, watch, Ref, ComputedRef } from 'vue'
import { useStore } from 'vuex'
import { RouterLink } from 'vue-router'
import { DataTableColumns, NDataTable, NIcon, NInput, NPopover, NSelect, NSpin, NTag, useNotification, NotificationType } from 'naive-ui'
import { Refresh } from '@vicons/tabler'
import commonFunctions from '@/utils/commonFunctions'
import {
    buildInstanceStatusRows, summarizeInstanceStatus, filterInstanceStatusRows, PLAN_TYPE_INTEGRATE,
    InstanceStatusRow, InstanceStatusFilters, ProductStatusRow, DeploymentHealth, ProductSyncState, InstanceSyncState
} from '@/utils/instanceStatusRollup'

const props = defineProps<{
    orgUuid: string
    // undefined = whole org; otherwise only instances deploying a product of the perspective
    perspectiveUuid?: string
    perspectiveName?: string
}>()

const store = useStore()
const notification = useNotification()
const notify = (type: NotificationType, title: string, content: string) => {
    notification[type]({ content, meta: title, duration: 3500, keepAliveOnHover: true })
}

const loading = ref(false)
const rows: Ref<InstanceStatusRow[]> = ref([])
const filters: Ref<InstanceStatusFilters> = ref({ environment: undefined, health: '', driftOnly: false, text: '' })

async function load () {
    if (!props.orgUuid) return
    loading.value = true
    // Same rule as MostRecentReleasesWidget: the store's 'default' sentinel
    // means the whole org, not a perspective to look up.
    const usePerspective = props.perspectiveUuid && props.perspectiveUuid !== 'default'
    try {
        const [instances, perspectiveComponents] = await Promise.all([
            store.dispatch('fetchInstanceStatus', props.orgUuid),
            usePerspective ? store.dispatch('fetchComponentsOfPerspective', props.perspectiveUuid) : Promise.resolve(null)
        ])
        const scope = perspectiveComponents ? new Set<string>(perspectiveComponents.map((c: any) => c.uuid)) : null
        rows.value = buildInstanceStatusRows(instances, scope)
    } catch (err: any) {
        console.error(err)
        notify('error', 'Error', commonFunctions.parseGraphQLError(err.message))
    } finally {
        loading.value = false
    }
}

const summary = computed(() => summarizeInstanceStatus(rows.value))
const filteredRows: ComputedRef<InstanceStatusRow[]> = computed(() => filterInstanceStatusRows(rows.value, filters.value))
const environmentOptions = computed(() => {
    const envs = new Set<string>()
    rows.value.forEach(r => { if (r.environment) envs.add(r.environment) })
    return Array.from(envs).sort().map(e => ({ label: e, value: e }))
})

function toggleDriftOnly () { filters.value.driftOnly = !filters.value.driftOnly }
function toggleHealth (health: DeploymentHealth) { filters.value.health = filters.value.health === health ? '' : health }
function clearQuickFilters () { filters.value.driftOnly = false; filters.value.health = '' }

const rowKey = (row: InstanceStatusRow) => row.uuid

const SYNC_TAG: Record<ProductSyncState | InstanceSyncState, { type: 'success' | 'warning' | 'error' | 'default', label: string }> = {
    MATCHED: { type: 'success', label: 'in sync' },
    DRIFT: { type: 'warning', label: 'drift' },
    NOT_MATCHED: { type: 'error', label: 'not matched' },
    NOT_APPLICABLE: { type: 'default', label: 'n/a' },
    EMPTY: { type: 'default', label: 'no products' }
}
const HEALTH_TAG: Record<DeploymentHealth, { type: 'success' | 'warning' | 'error', label: string }> = {
    HEALTHY: { type: 'success', label: 'healthy' },
    DEGRADED: { type: 'warning', label: 'degraded' },
    FAILING: { type: 'error', label: 'failing' }
}

// Hover detail: the old one-row-per-product view, shown on demand so the
// widget itself stays one line per instance.
function productDetailTable (products: ProductStatusRow[]) {
    const cols: DataTableColumns<ProductStatusRow> = [
        { key: 'namespace', title: 'Namespace', render: (p: ProductStatusRow) => p.namespace || 'default' },
        { key: 'productName', title: 'Product' },
        { key: 'featureSetName', title: 'Feature Set' },
        { key: 'actualVersion', title: 'Actual', render: (p: ProductStatusRow) => p.actualVersion || '-' },
        { key: 'targetVersion', title: 'Target', render: (p: ProductStatusRow) => p.planType === PLAN_TYPE_INTEGRATE ? 'n/a' : (p.targetVersion || 'not set') },
        { key: 'sync', title: 'Match', render: (p: ProductStatusRow) => h(NTag, { size: 'small', round: true, type: SYNC_TAG[p.sync].type }, () => SYNC_TAG[p.sync].label) }
    ]
    return h(NDataTable, { data: products, columns: cols, size: 'small', bordered: false, style: 'min-width: 640px;' })
}

const columns: DataTableColumns<InstanceStatusRow> = [
    {
        key: 'displayName',
        title: 'Instance',
        render: (row: InstanceStatusRow) => h(RouterLink, {
            to: { name: 'Instance', params: { orguuid: props.orgUuid, instuuid: row.uuid } }
        }, { default: () => row.displayName })
    },
    { key: 'environment', title: 'Environment', render: (row: InstanceStatusRow) => row.environment || '-' },
    {
        key: 'namespaces',
        title: 'Namespaces',
        render: (row: InstanceStatusRow) => row.namespaces.length
            ? h('span', { title: row.namespaces.join(', ') }, row.namespaces.length === 1 ? row.namespaces[0] : `${row.namespaces.length}`)
            : '-'
    },
    {
        key: 'products',
        title: 'Products',
        render: (row: InstanceStatusRow) => row.products.length
            ? h(NPopover, { trigger: 'hover', placement: 'bottom-start', style: 'padding: 6px;' }, {
                trigger: () => h('span', { class: 'productsCell', 'data-testid': 'instance-status-products' },
                    `${row.products.length} ${row.products.length === 1 ? 'product' : 'products'}`),
                default: () => productDetailTable(row.products)
            })
            : h('span', '-')
    },
    {
        key: 'sync',
        title: 'Sync',
        render: (row: InstanceStatusRow) => {
            const counted = row.matched + row.drifted + row.notMatched
            const els: any[] = [h(NTag, { size: 'small', round: true, type: SYNC_TAG[row.sync].type }, () => SYNC_TAG[row.sync].label)]
            if (counted) els.push(h('span', { class: 'syncCount' }, `${row.matched}/${counted}`))
            return h('span', { class: 'syncCell' }, els)
        }
    },
    {
        key: 'health',
        title: 'Health',
        render: (row: InstanceStatusRow) => {
            const els: any[] = [h(NTag, { size: 'small', round: true, type: HEALTH_TAG[row.health].type }, () => HEALTH_TAG[row.health].label)]
            if (row.openFailures) els.push(h('span', { class: 'syncCount', title: 'open ReARM CD failures' }, `${row.openFailures} ${row.openFailures === 1 ? 'failure' : 'failures'}`))
            if (row.releasesInError) els.push(h('span', { class: 'syncCount', title: 'deployed releases reported in error' }, `${row.releasesInError} in error`))
            return h('span', { class: 'syncCell' }, els)
        }
    }
]

watch(() => [props.orgUuid, props.perspectiveUuid], () => load())
load()
</script>

<style scoped lang="scss">
.widgetHeader { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.scopeNote { color: #7a8590; font-size: 13px; }
.summaryStrip { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
.statTile {
    display: inline-flex; flex-direction: column; align-items: flex-start;
    min-width: 96px; padding: 6px 12px;
    border: 1px solid #e3e8ec; border-radius: 8px; background: #fff;
    cursor: pointer; text-align: left;
    &:disabled { cursor: default; }
    &.active { border-color: #2b3540; box-shadow: 0 0 0 1px #2b3540 inset; }
    &.ok .statValue { color: #2da44e; }
    &.warn .statValue { color: #9a6700; }
    &.err .statValue { color: #cf222e; }
}
.statValue { font-size: 20px; font-weight: 600; line-height: 1.1; }
.statLabel { font-size: 11px; color: #5b6770; }
.filterRow { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; flex-wrap: wrap; }
.resultCount { color: #7a8590; font-size: 12px; }
.emptyNote { padding: 16px 0; color: #7a8590; font-style: italic; }
:deep(.productsCell) { cursor: default; text-decoration: underline dotted; }
:deep(.syncCell) { display: inline-flex; align-items: center; gap: 6px; }
:deep(.syncCount) { font-size: 12px; color: #5b6770; }
</style>
