<template>
    <n-modal
        v-model:show="show"
        preset="dialog"
        :show-icon="false"
        :title="`Releases Affected by ${cveId}`"
        style="width: 95%; max-width: 1400px;"
    >
        <n-spin :show="loading">
            <n-data-table
                :columns="columns"
                :data="releases"
                :pagination="{ pageSize: 20 }"
                :row-key="(row: any) => row.uuid"
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
import { NModal, NSpin, NDataTable, NSpace, DataTableColumns } from 'naive-ui'
import { RouterLink } from 'vue-router'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import constants from '@/utils/constants'

const props = defineProps<{
    show: boolean
    cveId: string
    orgUuid: string
}>()

const emit = defineEmits(['update:show'])

const loading = ref(false)
const releases = ref<any[]>([])

const show = computed({
    get: () => props.show,
    set: (value) => emit('update:show', value)
})

watch(() => props.show, async (newVal) => {
    if (newVal && props.cveId && props.orgUuid) {
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
                        version
                        marketingVersion
                        createdDate
                        lifecycle
                        componentDetails {
                            uuid
                            name
                            type
                        }
                        branchDetails {
                            uuid
                            name
                        }
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
            `,
            variables: {
                org: props.orgUuid,
                cveId: props.cveId
            },
            fetchPolicy: 'no-cache'
        })
        releases.value = (response.data as any).searchReleasesByCveId || []
    } catch (error) {
        console.error('Error fetching releases by CVE ID:', error)
        releases.value = []
    } finally {
        loading.value = false
    }
}

const columns: DataTableColumns<any> = [
    {
        title: 'Component / Product',
        key: 'componentName',
        width: 200,
        render: (row: any) => {
            if (!row.componentDetails?.uuid || !row.componentDetails?.name) {
                return 'N/A'
            }
            const routeName = row.componentDetails.type === 'PRODUCT' ? 'ProductsOfOrg' : 'ComponentsOfOrg'
            return h(
                RouterLink,
                {
                    to: {
                        name: routeName,
                        params: {
                            orguuid: props.orgUuid,
                            compuuid: row.componentDetails.uuid
                        }
                    }
                },
                { default: () => row.componentDetails.name }
            )
        }
    },
    {
        title: 'Branch / Feature Set',
        key: 'branchName',
        width: 180,
        render: (row: any) => {
            if (!row.branchDetails?.uuid || !row.branchDetails?.name || !row.componentDetails?.uuid) {
                return 'N/A'
            }
            const routeName = row.componentDetails.type === 'PRODUCT' ? 'ProductsOfOrg' : 'ComponentsOfOrg'
            return h(
                RouterLink,
                {
                    to: {
                        name: routeName,
                        params: {
                            orguuid: props.orgUuid,
                            compuuid: row.componentDetails.uuid,
                            branchuuid: row.branchDetails.uuid
                        }
                    }
                },
                { default: () => row.branchDetails.name }
            )
        }
    },
    {
        title: 'Type',
        key: 'type',
        width: 120,
        render: (row: any) => row.componentDetails?.type || 'N/A'
    },
    {
        title: 'Version',
        key: 'version',
        width: 150,
        render: (row: any) => {
            if (!row.uuid || !row.version) {
                return 'N/A'
            }
            return h(
                RouterLink,
                {
                    to: {
                        name: 'ReleaseView',
                        params: {
                            uuid: row.uuid
                        }
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
    },
    {
        title: 'Vulnerabilities',
        key: 'vulnerabilities',
        width: 200,
        render: (row: any) => {
            if (row.metrics) {
                const criticalEl = h('div', { 
                    title: 'Critical Severity Vulnerabilities', 
                    class: 'circle', 
                    style: 'background: #f86c6b;' 
                }, row.metrics.critical || 0)
                const highEl = h('div', { 
                    title: 'High Severity Vulnerabilities', 
                    class: 'circle', 
                    style: 'background: #fd8c00;' 
                }, row.metrics.high || 0)
                const medEl = h('div', { 
                    title: 'Medium Severity Vulnerabilities', 
                    class: 'circle', 
                    style: 'background: #ffc107;' 
                }, row.metrics.medium || 0)
                const lowEl = h('div', { 
                    title: 'Low Severity Vulnerabilities', 
                    class: 'circle', 
                    style: 'background: #4dbd74;' 
                }, row.metrics.low || 0)
                const unassignedEl = h('div', { 
                    title: 'Vulnerabilities with Unassigned Severity', 
                    class: 'circle', 
                    style: 'background: #777;' 
                }, row.metrics.unassigned || 0)
                return h(NSpace, { size: 1 }, () => [criticalEl, highEl, medEl, lowEl, unassignedEl])
            }
            return 'N/A'
        }
    },
    {
        title: 'Violations',
        key: 'violations',
        width: 180,
        render: (row: any) => {
            if (row.metrics) {
                const licenseEl = h('div', { 
                    title: 'Licensing Policy Violations', 
                    class: 'circle', 
                    style: 'background: blue;' 
                }, row.metrics.policyViolationsLicenseTotal || 0)
                const securityEl = h('div', { 
                    title: 'Security Policy Violations', 
                    class: 'circle', 
                    style: 'background: red;' 
                }, row.metrics.policyViolationsSecurityTotal || 0)
                const operationalEl = h('div', { 
                    title: 'Operational Policy Violations', 
                    class: 'circle', 
                    style: 'background: grey;' 
                }, row.metrics.policyViolationsOperationalTotal || 0)
                return h(NSpace, { size: 1 }, () => [licenseEl, securityEl, operationalEl])
            }
            return 'N/A'
        }
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
