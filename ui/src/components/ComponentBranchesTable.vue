<template>
    <n-data-table
        :columns="componentColumns"
        :data="componentTableData"
        :pagination="{ pageSize: 20 }"
        :row-key="(row: any) => row.key"
        :loading="loading"
    />
</template>

<script lang="ts">
export default {
    name: 'ComponentBranchesTable'
}
</script>

<script lang="ts" setup>
import { computed, h } from 'vue'
import { NDataTable, NIcon, NTooltip, DataTableColumns } from 'naive-ui'
import { RouterLink } from 'vue-router'
import { Check } from '@vicons/tabler'
import constants from '@/utils/constants'

const props = defineProps<{
    data: any[]
    orgUuid: string
    loading?: boolean
    featureSetLabel?: string
    showIsLatestColumn?: boolean
}>()

interface BranchRow {
    key: string
    branchUuid: string
    branchName: string
    branchStatus: string
    earliestVersion: string
    earliestReleaseUuid: string
    latestVersion: string
    latestReleaseUuid: string
    latestReleaseVersion: string
    isLatest: boolean
    releases: any[]
    componentType: string
    componentUuid: string
}

interface ComponentRow {
    key: string
    componentUuid: string
    componentName: string
    componentType: string
    totalReleases: number
    branches: BranchRow[]
}

function transformToComponentRows(data: any[]): ComponentRow[] {
    return data.map((component: any) => {
        const branches: BranchRow[] = []
        let totalReleases = 0
        
        component.branches?.forEach((branch: any) => {
            if (branch.releases && branch.releases.length > 0) {
                totalReleases += branch.releases.length
                
                const sortedReleases = [...branch.releases].sort((a: any, b: any) => 
                    new Date(a.createdDate).getTime() - new Date(b.createdDate).getTime()
                )
                const earliestRelease = sortedReleases[0]
                const latestRelease = sortedReleases[sortedReleases.length - 1]
                
                branches.push({
                    key: branch.uuid,
                    branchUuid: branch.uuid,
                    branchName: branch.name,
                    branchStatus: branch.status,
                    earliestVersion: earliestRelease.version,
                    earliestReleaseUuid: earliestRelease.uuid,
                    latestVersion: latestRelease.version,
                    latestReleaseUuid: latestRelease.uuid,
                    latestReleaseVersion: branch.latestReleaseVersion,
                    isLatest: latestRelease.version === branch.latestReleaseVersion,
                    releases: branch.releases,
                    componentType: component.type,
                    componentUuid: component.uuid
                })
            }
        })
        
        return {
            key: component.uuid,
            componentUuid: component.uuid,
            componentName: component.name,
            componentType: component.type,
            totalReleases,
            branches
        }
    }).filter(c => c.branches.length > 0)
}

function getReleaseColumns(): DataTableColumns<any> {
    return [
        {
            title: 'Release Version',
            key: 'version',
            width: 150,
            render: (row: any) => {
                return h(
                    RouterLink,
                    { to: { name: 'ReleaseView', params: { uuid: row.uuid } } },
                    { default: () => row.version }
                )
            }
        },
        {
            title: 'Created',
            key: 'createdDate',
            width: 180,
            render: (row: any) => (new Date(row.createdDate)).toLocaleString('en-CA', { hour12: false })
        },
        {
            title: 'Lifecycle',
            key: 'lifecycle',
            width: 120,
            render: (row: any) => constants.LifecycleOptions.find((lo: any) => lo.key === row.lifecycle)?.label || row.lifecycle
        }
    ]
}

function getBranchColumns(orgUuid: string, featureSetLabel?: string, showIsLatestColumn?: boolean): DataTableColumns<any> {
    const releaseColumns = getReleaseColumns()
    
    const columns: any[] = [
        {
            type: 'expand',
            expandable: (row: any) => row.releases && row.releases.length > 0,
            renderExpand: (row: any) => {
                return h(NDataTable, {
                    data: row.releases,
                    columns: releaseColumns,
                    pagination: false,
                    size: 'small'
                })
            }
        },
        {
            title: `Branch / ${featureSetLabel || 'Feature Set'}`,
            key: 'branchName',
            width: 180,
            render: (row: any) => {
                const routeName = row.componentType === 'PRODUCT' ? 'ProductsOfOrg' : 'ComponentsOfOrg'
                return h(
                    RouterLink,
                    { to: { name: routeName, params: { orguuid: orgUuid, compuuid: row.componentUuid, branchuuid: row.branchUuid } } },
                    { default: () => row.branchName }
                )
            }
        },
        {
            title: 'Status',
            key: 'branchStatus',
            width: 100,
            render: (row: any) => row.branchStatus || 'N/A',
            filter(value: any, row: any) { return row.branchStatus === value },
            filterOptions: [
                { label: 'ACTIVE', value: 'ACTIVE' },
                { label: 'ARCHIVED', value: 'ARCHIVED' }
            ],
            filterMultiple: true
        },
        {
            title: 'From Version',
            key: 'earliestVersion',
            width: 150,
            render: (row: any) => {
                return h(
                    RouterLink,
                    { to: { name: 'ReleaseView', params: { uuid: row.earliestReleaseUuid } } },
                    { default: () => row.earliestVersion }
                )
            }
        },
        {
            title: 'To Version',
            key: 'latestVersion',
            width: 150,
            render: (row: any) => {
                return h(
                    RouterLink,
                    { to: { name: 'ReleaseView', params: { uuid: row.latestReleaseUuid } } },
                    { default: () => row.latestVersion }
                )
            }
        }
    ]
    
    if (showIsLatestColumn) {
        columns.push({
            title: () => {
                return h(NTooltip, null, {
                    trigger: () => 'In Latest?',
                    default: () => 'Checkmark indicates that the latest known release is affected'
                })
            },
            key: 'isLatest',
            width: 100,
            render: (row: any) => {
                if (!row.isLatest) return null
                return h(NIcon, { component: Check, color: '#d03050', size: 20 })
            },
            filter: (value: any, row: any) => {
                if (value === 'true') return row.isLatest === true
                if (value === 'false') return row.isLatest === false
                return true
            },
            filterOptions: [
                { label: 'Yes', value: 'true' },
                { label: 'No', value: 'false' }
            ]
        })
    }
    
    return columns
}

function getComponentColumns(orgUuid: string, featureSetLabel?: string, showIsLatestColumn?: boolean): DataTableColumns<any> {
    const branchColumns = getBranchColumns(orgUuid, featureSetLabel, showIsLatestColumn)
    
    return [
        {
            type: 'expand',
            expandable: (row: any) => row.branches && row.branches.length > 0,
            renderExpand: (row: any) => {
                return h(NDataTable, {
                    data: row.branches,
                    columns: branchColumns,
                    pagination: false,
                    rowKey: (r: any) => r.key,
                    size: 'small'
                })
            }
        },
        {
            title: 'Component / Product',
            key: 'componentName',
            width: 250,
            render: (row: any) => {
                const routeName = row.componentType === 'PRODUCT' ? 'ProductsOfOrg' : 'ComponentsOfOrg'
                return h(
                    RouterLink,
                    { to: { name: routeName, params: { orguuid: orgUuid, compuuid: row.componentUuid } } },
                    { default: () => row.componentName }
                )
            }
        },
        {
            title: 'Type',
            key: 'componentType',
            width: 120,
            filter(value: any, row: any) { return row.componentType === value },
            filterOptions: [
                { label: 'COMPONENT', value: 'COMPONENT' },
                { label: 'PRODUCT', value: 'PRODUCT' }
            ],
            filterMultiple: true
        },
        {
            title: 'Number of Releases',
            key: 'totalReleases',
            width: 150
        }
    ]
}

const componentTableData = computed(() => transformToComponentRows(props.data))

const componentColumns = computed(() => getComponentColumns(
    props.orgUuid,
    props.featureSetLabel,
    props.showIsLatestColumn ?? true
))
</script>
