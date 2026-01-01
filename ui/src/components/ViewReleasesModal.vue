<template>
    <n-modal
        v-model:show="show"
        preset="dialog"
        :show-icon="false"
        :title="`Releases Affected by ${cveId}`"
        style="width: 95%; max-width: 1400px;"
        :auto-focus="false"
    >
        <n-spin :show="loading">
            <n-data-table
                :columns="columns"
                :data="tableData"
                :pagination="{ pageSize: 20 }"
                :row-key="(row: any) => row.key"
            />
        </n-spin>
    </n-modal>
</template>

<script lang="ts">
export default {
    name: 'ViewReleasesModal'
}
</script>

<script lang="ts" setup>
import { ref, computed, watch, h } from 'vue'
import { NModal, NSpin, NDataTable, NSpace, NCheckbox, DataTableColumns } from 'naive-ui'
import { RouterLink } from 'vue-router'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import constants from '@/utils/constants'

// Transform component data into flat table rows
const tableData = computed(() => {
    const rows: any[] = []
    componentData.value.forEach((component: any) => {
        component.branches?.forEach((branch: any) => {
            if (branch.releases && branch.releases.length > 0) {
                // Sort releases to get earliest and latest
                const sortedReleases = [...branch.releases].sort((a: any, b: any) => 
                    new Date(a.createdDate).getTime() - new Date(b.createdDate).getTime()
                )
                const earliestRelease = sortedReleases[0]
                const latestRelease = sortedReleases[sortedReleases.length - 1]
                
                rows.push({
                    key: `${component.uuid}-${branch.uuid}`,
                    componentUuid: component.uuid,
                    componentName: component.name,
                    componentType: component.type,
                    branchUuid: branch.uuid,
                    branchName: branch.name,
                    branchStatus: branch.status,
                    earliestVersion: earliestRelease.version,
                    earliestReleaseUuid: earliestRelease.uuid,
                    latestVersion: latestRelease.version,
                    latestReleaseUuid: latestRelease.uuid,
                    latestReleaseVersion: branch.latestReleaseVersion,
                    isLatest: latestRelease.version === branch.latestReleaseVersion,
                    releases: branch.releases
                })
            }
        })
    })
    return rows
})

const props = defineProps<{
    show: boolean
    cveId: string
    orgUuid: string
}>()

const emit = defineEmits(['update:show'])

const loading = ref(false)
const componentData = ref<any[]>([])

const show = computed({
    get: () => props.show,
    set: (value) => emit('update:show', value)
})

watch(() => props.show, async (newVal) => {
    if (newVal && props.cveId && props.orgUuid) {
        await fetchReleases()
    }
})

// Also watch for cveId changes to trigger search when modal is already open
watch(() => props.cveId, async (newVal) => {
    if (props.show && newVal && props.orgUuid) {
        await fetchReleases()
    }
})

const fetchReleases = async () => {
    loading.value = true
    try {
        const response = await graphqlClient.query({
            query: gql`
                query searchReleasesByCveId($org: ID!, $cveId: String!) {
                    searchReleasesByCveId(org: $org, cveId: $cveId) {
                        uuid
                        name
                        type
                        versionSchema
                        branches {
                            uuid
                            name
                            status
                            versionSchema
                            latestReleaseVersion
                            releases {
                                uuid
                                version
                                createdDate
                                lifecycle
                                metrics {
                                    critical
                                    high
                                    medium
                                    low
                                    unassigned
                                    policyViolationsLicenseTotal
                                    policyViolationsSecurityTotal
                                    policyViolationsOperationalTotal
                                }
                            }
                        }
                    }
                }
            `,
            variables: {
                org: props.orgUuid,
                cveId: props.cveId
            },
            fetchPolicy: 'no-cache'
        })
        componentData.value = (response.data as any).searchReleasesByCveId || []
    } catch (error) {
        console.error('Error fetching releases by CVE ID:', error)
        componentData.value = []
    } finally {
        loading.value = false
    }
}

// Nested table columns for expanded releases
const releaseColumns: DataTableColumns<any> = [
    {
        title: 'Release Version',
        key: 'version',
        width: 150,
        render: (row: any) => {
            return h(
                RouterLink,
                {
                    to: {
                        name: 'ReleaseView',
                        params: { uuid: row.uuid }
                    }
                },
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

// Main table columns with expandable rows
const columns: DataTableColumns<any> = [
    {
        type: 'expand',
        expandable: (row: any) => row.releases && row.releases.length > 0,
        renderExpand: (row: any) => {
            return h(NDataTable, {
                data: row.releases,
                columns: releaseColumns,
                pagination: false
            })
        }
    },
    {
        title: 'Component / Product',
        key: 'componentName',
        width: 200,
        render: (row: any) => {
            const routeName = row.componentType === 'PRODUCT' ? 'ProductsOfOrg' : 'ComponentsOfOrg'
            return h(
                RouterLink,
                {
                    to: {
                        name: routeName,
                        params: {
                            orguuid: props.orgUuid,
                            compuuid: row.componentUuid
                        }
                    }
                },
                { default: () => row.componentName }
            )
        }
    },
    {
        title: 'Branch / Feature Set',
        key: 'branchName',
        width: 180,
        render: (row: any) => {
            const routeName = row.componentType === 'PRODUCT' ? 'ProductsOfOrg' : 'ComponentsOfOrg'
            return h(
                RouterLink,
                {
                    to: {
                        name: routeName,
                        params: {
                            orguuid: props.orgUuid,
                            compuuid: row.componentUuid,
                            branchuuid: row.branchUuid
                        }
                    }
                },
                { default: () => row.branchName }
            )
        }
    },
    {
        title: 'Type',
        key: 'componentType',
        width: 120,
        filter: true,
        filterOptions: [
            { label: 'COMPONENT', value: 'COMPONENT' },
            { label: 'PRODUCT', value: 'PRODUCT' }
        ],
        filterMultiple: true
    },
    {
        title: 'Status',
        key: 'branchStatus',
        width: 100,
        render: (row: any) => row.branchStatus || 'N/A',
        filter: true,
        filterOptions: [
            { label: 'ACTIVE', value: 'ACTIVE' },
            { label: 'ARCHIVED', value: 'ARCHIVED' }
        ],
        filterMultiple: true
    },
    {
        title: 'Earliest Version',
        key: 'earliestVersion',
        width: 150,
        render: (row: any) => {
            return h(
                RouterLink,
                {
                    to: {
                        name: 'ReleaseView',
                        params: { uuid: row.earliestReleaseUuid }
                    }
                },
                { default: () => row.earliestVersion }
            )
        }
    },
    {
        title: 'Newest Version',
        key: 'latestVersion',
        width: 150,
        render: (row: any) => {
            return h(
                RouterLink,
                {
                    to: {
                        name: 'ReleaseView',
                        params: { uuid: row.latestReleaseUuid }
                    }
                },
                { default: () => row.latestVersion }
            )
        }
    },
    {
        title: 'Is Latest?',
        key: 'isLatest',
        width: 100,
        render: (row: any) => {
            return h(NCheckbox, {
                checked: row.isLatest,
                disabled: true
            })
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
    }
]
</script>

<style scoped>
.circle {
    display: inline-block;
    min-width: 24px;
    height: 24px;
    border-radius: 50%;
    text-align: center;
    line-height: 24px;
    color: white;
    font-size: 12px;
    padding: 0 4px;
}
</style>
