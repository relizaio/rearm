<template>
    <n-modal
        v-model:show="show"
        preset="dialog"
        :show-icon="false"
        :title="modalTitle"
        style="width: 95%; max-width: 1400px;"
        :auto-focus="false"
    >
        <n-spin :show="loading">
            <component-branches-table
                :data="componentData"
                :org-uuid="props.orgUuid"
                :feature-set-label="props.featureSetLabel"
                :show-is-latest-column="props.showIsLatestColumn ?? true"
            />
        </n-spin>
    </n-modal>
</template>

<script lang="ts">
export default {
    name: 'ReleasesByCve'
}
</script>

<script lang="ts" setup>
import { ref, computed, watch, h } from 'vue'
import { NModal, NSpin, useNotification } from 'naive-ui'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import Swal from 'sweetalert2'
import ComponentBranchesTable from './ComponentBranchesTable.vue'

const notification = useNotification()

const props = defineProps<{
    show: boolean
    cveId: string
    orgUuid: string
    perspectiveUuid?: string
    perspectiveName?: string
    featureSetLabel?: string
    showIsLatestColumn?: boolean
}>()

const emit = defineEmits(['update:show'])

const loading = ref(false)
const componentData = ref<any[]>([])

const confirmAndOpen = async (e: Event, href: string) => {
    e.preventDefault()
    try {
        const LS_KEY = 'rearm_external_link_consent_until'
        const now = Date.now()
        const stored = localStorage.getItem(LS_KEY)
        if (stored && Number(stored) > now) {
            window.open(href, '_blank')
            return
        }

        const result = await Swal.fire({
            icon: 'info',
            title: 'Open external link?\n',
            text: 'This will open a vulnerability database resource external to ReARM. Please confirm that you want to proceed.',
            showCancelButton: true,
            confirmButtonText: 'Open',
            cancelButtonText: 'Cancel',
            input: 'checkbox',
            inputValue: 0,
            inputPlaceholder: "Don't ask me again for 15 days"
        })
        if (result.isConfirmed) {
            if (result.value === 1) {
                const fifteenDaysMs = 15 * 24 * 60 * 60 * 1000
                localStorage.setItem(LS_KEY, String(now + fifteenDaysMs))
            }
            window.open(href, '_blank')
        }
    } catch (err) {
        window.open(href, '_blank')
    }
}

const modalTitle = computed(() => {
    const cveId = props.cveId
    const perspectiveSuffix = props.perspectiveName ? `, Perspective: ${props.perspectiveName}` : ''
    
    if (cveId.startsWith('CVE-') || cveId.startsWith('GHSA-')) {
        const href = `https://osv.dev/vulnerability/${cveId}`
        return () => h('span', [
            'Releases Affected by ',
            h('a', {
                href,
                target: '_blank',
                rel: 'noopener noreferrer',
                onClick: (e: Event) => confirmAndOpen(e, href),
                style: 'color: #18a058; text-decoration: underline;'
            }, cveId),
            perspectiveSuffix
        ])
    }
    
    return `Releases Affected by ${cveId}${perspectiveSuffix}`
})

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
                query searchReleasesByCveId($org: ID!, $cveId: String!, $perspectiveUuid: ID) {
                    searchReleasesByCveId(org: $org, cveId: $cveId, perspectiveUuid: $perspectiveUuid) {
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
                cveId: props.cveId,
                perspectiveUuid: props.perspectiveUuid || null
            },
            fetchPolicy: 'no-cache'
        })
        componentData.value = (response.data as any).searchReleasesByCveId || []
    } catch (error: any) {
        console.error('Error fetching releases by CVE ID:', error)
        componentData.value = []
        notification.error({
            title: 'Error',
            content: `Failed to fetch releases for ${props.cveId}: ${commonFunctions.parseGraphQLError(error.message)}`,
            duration: 5000,
            keepAliveOnHover: true
        })
    } finally {
        loading.value = false
    }
}
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
