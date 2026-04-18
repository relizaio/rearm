<template>
    <div class="featureSetParticipation">
        <h3>Feature Sets Including This {{ props.mode === 'component' ? 'Component' : 'Branch' }}</h3>
        <n-spin v-if="loading" />
        <n-data-table
            v-else-if="rows.length > 0"
            :columns="columns"
            :data="rows"
            :pagination="{ pageSize: 20 }"
            :row-key="(row: any) => row.key"
        />
        <n-empty v-else description="No feature sets include this item." />
    </div>
</template>

<script lang="ts">
export default {
    name: 'FeatureSetParticipation'
}
</script>

<script lang="ts" setup>
import { ref, h, onMounted, watch } from 'vue'
import { NDataTable, NSpin, NEmpty, NTag, DataTableColumns } from 'naive-ui'
import { RouterLink } from 'vue-router'
import graphqlClient from '../utils/graphql'
import graphqlQueries from '../utils/graphqlQueries'

const props = defineProps<{
    mode: 'component' | 'branch'
    componentUuid?: string
    branchUuid?: string
    branchName?: string
    componentName?: string
}>()

const loading = ref(false)
const rows = ref<any[]>([])

type Status = 'REQUIRED' | 'TRANSIENT' | 'IGNORED'

function statusRank (s: string): number {
    if (s === 'REQUIRED') return 3
    if (s === 'TRANSIENT') return 2
    if (s === 'IGNORED') return 1
    return 0
}

function pickStatus (candidates: string[]): Status | null {
    let best: string | null = null
    for (const c of candidates) {
        if (!c) continue
        if (best === null || statusRank(c) > statusRank(best)) best = c
    }
    return (best as Status) || null
}

function safeRegexTest (pattern: string, value: string): boolean {
    try {
        return new RegExp(pattern).test(value)
    } catch {
        return pattern === value
    }
}

function deriveComponentStatus (featureSet: any): Status | null {
    const statuses: string[] = []
    for (const dep of featureSet.dependencies || []) {
        if (dep.componentDetails?.uuid === props.componentUuid) {
            if (dep.status) statuses.push(dep.status)
        }
    }
    if (statuses.length === 0 && props.componentName) {
        for (const p of featureSet.dependencyPatterns || []) {
            if (p.pattern && safeRegexTest(p.pattern, props.componentName)) {
                if (p.defaultStatus) statuses.push(p.defaultStatus)
            }
        }
    }
    return pickStatus(statuses)
}

function deriveBranchStatus (featureSet: any): Status | null {
    const statuses: string[] = []
    for (const dep of featureSet.dependencies || []) {
        if (dep.branch === props.branchUuid) {
            if (dep.status) statuses.push(dep.status)
        }
    }
    if (statuses.length === 0 && props.branchName) {
        for (const p of featureSet.dependencyPatterns || []) {
            const nameMatch = p.targetBranchName
                ? p.targetBranchName === props.branchName
                : true
            if (nameMatch && p.defaultStatus) statuses.push(p.defaultStatus)
        }
    }
    return pickStatus(statuses)
}

function statusTagType (status: string): 'success' | 'info' | 'warning' | 'default' {
    if (status === 'REQUIRED') return 'success'
    if (status === 'TRANSIENT') return 'info'
    if (status === 'IGNORED') return 'warning'
    return 'default'
}

const columns: DataTableColumns<any> = [
    {
        title: 'Product',
        key: 'product',
        render: (row: any) => h(
            RouterLink,
            { to: { name: 'ProductsOfOrg', params: { orguuid: row.org, compuuid: row.componentUuid } } },
            { default: () => row.componentName }
        )
    },
    {
        title: 'Feature Set',
        key: 'featureSet',
        render: (row: any) => h(
            RouterLink,
            { to: { name: 'ProductsOfOrg', params: { orguuid: row.org, compuuid: row.componentUuid, branchuuid: row.branchUuid } } },
            { default: () => row.branchName }
        )
    },
    {
        title: 'Requirement Type',
        key: 'status',
        render: (row: any) => h(
            NTag,
            { type: statusTagType(row.status), size: 'small' },
            { default: () => row.status || 'UNKNOWN' }
        )
    }
]

async function load () {
    loading.value = true
    try {
        const query = props.mode === 'component'
            ? graphqlQueries.FeatureSetsUsingComponentGql
            : graphqlQueries.FeatureSetsUsingBranchGql
        const variables = props.mode === 'component'
            ? { componentUuid: props.componentUuid }
            : { branchUuid: props.branchUuid }
        const response = await graphqlClient.query({
            query,
            variables,
            fetchPolicy: 'no-cache'
        })
        const data = props.mode === 'component'
            ? response.data.featureSetsUsingComponent
            : response.data.featureSetsUsingBranch
        rows.value = (data || []).map((fs: any) => {
            const status = props.mode === 'component'
                ? deriveComponentStatus(fs)
                : deriveBranchStatus(fs)
            return {
                key: fs.uuid,
                org: fs.org,
                componentUuid: fs.componentDetails?.uuid,
                componentName: fs.componentDetails?.name || '',
                branchUuid: fs.uuid,
                branchName: fs.name,
                status: status || 'UNKNOWN'
            }
        })
    } finally {
        loading.value = false
    }
}

onMounted(load)
watch(() => [props.mode, props.componentUuid, props.branchUuid], load)
</script>

<style scoped>
.featureSetParticipation {
    padding: 8px;
}
</style>
