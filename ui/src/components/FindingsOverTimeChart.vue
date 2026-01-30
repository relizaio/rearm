<template>
    <div class="findingsOverTimeChart">
        <div v-if="props.type === 'ORGANIZATION' || props.type === 'PERSPECTIVE'" style="display: flex; align-items: center; gap: 8px;">
            <h3 class="chart-title" style="margin: 0;">Findings Over Time</h3>
            <router-link 
                v-if="props.showFullPageIcon"
                :to="fullPageViewUrl"
                title="Open Full Page View"
                style="display: flex; align-items: center;"
            >
                <n-icon class="clickable" size="20">
                    <ArrowExpand20Regular />
                </n-icon>
            </router-link>
            <n-icon 
                class="clickable" 
                size="20" 
                title="View Organization Changelog"
                @click="showOrgChangelogModal = true"
            >
                <AppsList20Regular />
            </n-icon>
        </div>
        <h3 v-else class="chart-title">Findings Over Time</h3>
        <n-skeleton v-if="isLoading" height="220px" :sharp="false" />
        <n-empty v-else-if="hasNoData" style="height: 220px;" :description="`No findings reported for the last ${props.daysBack} days`" size="large" />
        <div v-else id="findingsOverTimeVis"></div>
        <vulnerability-modal
            v-model:show="showFindingsPerDayModal"
            :component-name="findingsPerDayModalTitle"
            version=""
            :data="findingsPerDayData"
            :loading="findingsPerDayLoading"
            :org-uuid="orgUuid"
            :component-uuid="props.componentUuid"
            :branch-uuid="props.branchUuid"
            :component-type="props.componentType"
            :initial-severity-filter="directFindingsDate ? directFindingsSeverity : findingsPerDaySeverity"
            :initial-type-filter="directFindingsDate ? directFindingsType : findingsPerDayType"
            :show-refresh-action="hasNoExtraContext && isGlobalAdmin"
            :on-refresh-action="refreshAnalyticsForCurrentDate"
            @update:show="(val: boolean) => { if (!val) closeFindingsPerDayModal() }"
        />
        <n-modal
            v-model:show="showOrgChangelogModal"
            preset="dialog"
            :show-icon="false"
            style="width: 90%"
        >
            <organization-changelog-view
                :org-uuid="orgUuid"
                :perspective-uuid="props.perspectiveUuid"
            />
        </n-modal>
    </div>
</template>

<script lang="ts">
export default {
    name: 'FindingsOverTimeChart'
}
</script>

<script lang="ts" setup>
import { ref, Ref, computed, onMounted, onBeforeUnmount, watch, toRaw, nextTick } from 'vue'
import { useStore } from 'vuex'
import { useRoute } from 'vue-router'
import { NSkeleton, NEmpty, NModal, useNotification, NotificationType } from 'naive-ui'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import { processMetricsData } from '@/utils/metrics'
import constants from '@/utils/constants'
import * as vegaEmbed from 'vega-embed'
import VulnerabilityModal from './VulnerabilityModal.vue'
import OrganizationChangelogView from './OrganizationChangelogView.vue'
import { AppsList20Regular, ArrowExpand20Regular } from '@vicons/fluent'
import { NIcon } from 'naive-ui'

const props = withDefaults(defineProps<{
    type: 'ORGANIZATION' | 'BRANCH' | 'COMPONENT' | 'PERSPECTIVE'
    orgUuid?: string
    branchUuid?: string
    componentUuid?: string
    perspectiveUuid?: string
    componentName?: string
    branchName?: string
    componentType?: string
    dateFrom?: Date
    dateTo?: Date
    daysBack?: number
    chartHeight?: number
    showFullPageIcon?: boolean
}>(), {
    daysBack: 60,
    componentName: '',
    branchName: '',
    componentType: '',
    chartHeight: 220,
    showFullPageIcon: true
})

const store = useStore()
const route = useRoute()
const notification = useNotification()
const myorg = computed(() => store.getters.myorg)
const myUser = computed(() => store.getters.myuser)
const isGlobalAdmin = computed(() => myUser.value?.isGlobalAdmin === true)

const notify = (type: NotificationType, title: string, content: string) => {
    notification[type]({
        content: content,
        meta: title,
        duration: 3500,
        keepAliveOnHover: true
    })
}

const orgUuid = computed(() => props.orgUuid || myorg.value?.uuid || '')
const isLoading = ref(true)
const isMounted = ref(true)
const hasNoData = ref(false)

const findingsPerDayData: Ref<any[]> = ref([])
const findingsPerDayLoading: Ref<boolean> = ref(false)
const showFindingsPerDayModal: Ref<boolean> = ref(false)
const showOrgChangelogModal: Ref<boolean> = ref(false)
const directFindingsDate: Ref<string> = ref('')
const directFindingsSeverity: Ref<string> = ref('')
const directFindingsType: Ref<string> = ref('')

// Route-based query params for findings per day display
const showFindingsPerDay = computed(() => route.query.display === 'findingsPerDay' && route.query.date)
const findingsPerDayDate = computed(() => route.query.date as string || '')
const findingsPerDaySeverity = computed(() => route.query.severity as string || '')
const findingsPerDayType = computed(() => route.query.type as string || '')

const findingsPerDayModalTitle = computed(() => {
    const date = directFindingsDate.value || findingsPerDayDate.value
    const contextParts: string[] = []
    
    if (props.type === 'BRANCH' && props.branchName) {
        const branchLabel = props.componentType === 'PRODUCT' 
            ? (myorg.value?.terminology?.featureSetLabel || 'Feature Set')
            : 'Branch'
        contextParts.push(`${branchLabel}: ${props.branchName}`)
    } else if (props.type === 'COMPONENT' && props.componentName) {
        const typeLabel = props.componentType === 'PRODUCT' ? 'Product' : 'Component'
        contextParts.push(`${typeLabel}: ${props.componentName}`)
    }
    
    if (contextParts.length > 0) {
        return `Findings for ${date} - ${contextParts.join(', ')}`
    }
    return `Findings for ${date}`
})

const hasNoExtraContext = computed(() => {
    return !(props.type === 'BRANCH' && props.branchName) && !(props.type === 'COMPONENT' && props.componentName)
})

async function computeAnalyticsMetricsForDate(date: string) {
    if (!orgUuid.value || !date) {
        notify('error', 'Error', 'Organization UUID or date is missing')
        return
    }
    try {
        const resp = await graphqlClient.mutate({
            mutation: gql`
                mutation computeAnalyticsMetricsForDate($orgUuid: ID!, $date: String!) {
                    computeAnalyticsMetricsForDate(orgUuid: $orgUuid, date: $date) {
                        lastScanned
                    }
                }
            `,
            variables: {
                orgUuid: orgUuid.value,
                date: date
            },
            fetchPolicy: 'no-cache'
        })
        const result = resp.data?.computeAnalyticsMetricsForDate
        if (result) {
            notify('success', 'Analytics Refresh', `Metrics computed. Last scanned: ${result.lastScanned || 'N/A'}`)
        } else {
            notify('warning', 'Analytics Refresh', 'No response received from analytics computation.')
        }
    } catch (e: any) {
        notify('error', 'Analytics Refresh Failed', e.message)
    }
}

const refreshAnalyticsForCurrentDate = () => {
    const date = directFindingsDate.value || findingsPerDayDate.value
    if (date) {
        computeAnalyticsMetricsForDate(date)
    }
}

// Common GraphQL fields for findings queries
const FINDINGS_FIELDS = `
    vulnerabilityDetails {
        purl
        vulnId
        severity
        analysisState
        analysisDate
        attributedAt
        aliases {
            type
            aliasId
        }
        sources {
            artifact
            release
            variant
            releaseDetails {
                version
                componentDetails {
                    name
                }
            }
            artifactDetails {
                type
            }
        }
        severities {
            source
            severity
        }
    }
    violationDetails {
        purl
        type
        license
        violationDetails
        analysisState
        analysisDate
        attributedAt
        sources {
            artifact
            release
            variant
            releaseDetails {
                version
                componentDetails {
                    name
                }
            }
            artifactDetails {
                type
            }
        }
    }
    weaknessDetails {
        cweId
        ruleId
        location
        fingerprint
        severity
        analysisState
        analysisDate
        attributedAt
        sources {
            artifact
            release
            variant
            releaseDetails {
                version
                componentDetails {
                    name
                }
            }
            artifactDetails {
                type
            }
        }
    }
`

const analyticsMetrics: Ref<any> = ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v6.json',
    background: 'white',
    height: props.chartHeight,
    width: 'container',
    data: {
        values: []
    },
    mark: {
        type: 'line',
        point: {
            "filled": false,
            "fill": "white",
            "cursor": "pointer"
        },
        tooltip: true,
        cursor: 'pointer'
    },
    transform: [
        {
            calculate: "utcFormat(datum.createdDate, '%Y-%m-%d')",
            as: "dateStr"
        },
        {
            calculate: "datum.type && indexof(datum.type, 'Vulnerabilities') >= 0 ? upper(split(datum.type, ' ')[0]) : ''",
            as: "severityParam"
        },
        {
            calculate: "datum.type && indexof(datum.type, 'Vulnerabilities') >= 0 ? 'Vulnerability' : (datum.type && indexof(datum.type, 'Violations') >= 0 ? 'Violation' : '')",
            as: "typeParam"
        },
        {
            calculate: "'?display=findingsPerDay&date=' + datum.dateStr + (datum.severityParam ? '&severity=' + datum.severityParam : '') + (datum.typeParam ? '&type=' + datum.typeParam : '')",
            as: "url"
        }
    ],
    encoding: {
        y: {
            field: 'num',
            type: 'quantitative',
            aggregate: 'max',
            axis: {
                title: null
            },
            title: 'Occurrences'
        },
        x: {
            field: 'createdDate',
            type: 'temporal',
            timeUnit: 'utcyearmonthdate',
            axis: {
                title: null
            },
            title: 'Date'
        },
        color: {
            field: 'type',
            legend: null,
            scale: constants.FindingsChartColors
        },
        tooltip: [
            {field: "createdDate", type: "temporal", title: "Date"},
            {field: "num", type: "quantitative", title: "Occurrences"},
            {field: "type", type: "nominal", title: "Type"}
        ]
    }
})

function closeFindingsPerDayModal() {
    showFindingsPerDayModal.value = false
    // Clear query params silently without triggering watchers (only if there are params)
    if (window.location.search) {
        window.history.replaceState(window.history.state, '', window.location.pathname)
    }
}

const fullPageViewUrl = computed(() => {
    const params = new URLSearchParams()
    if (props.perspectiveUuid) {
        params.set('perspective', props.perspectiveUuid)
    }
    return `/findingsOverTime/${orgUuid.value}${params.toString() ? '?' + params.toString() : ''}`
})

async function openFindingsModal(date: string, severity: string = '', typeParam: string = '') {
    directFindingsDate.value = date
    directFindingsSeverity.value = severity
    directFindingsType.value = typeParam
    showFindingsPerDayModal.value = true
    
    // Update URL without triggering watchers so page reload works
    const params = new URLSearchParams()
    params.set('display', 'findingsPerDay')
    params.set('date', date)
    if (severity) params.set('severity', severity)
    if (typeParam) params.set('type', typeParam)
    window.history.replaceState({}, '', `${window.location.pathname}?${params.toString()}`)
    
    await fetchFindingsPerDay(date)
}

async function fetchFindingsPerDay(dateOverride?: string) {
    const dateToUse = dateOverride
    if (!dateToUse) return
    
    findingsPerDayLoading.value = true
    try {
        let response
        if (props.type === 'ORGANIZATION') {
            if (!orgUuid.value) return
            response = await graphqlClient.query({
                query: gql`
                    query findingsPerDay($orgUuid: ID!, $date: String!) {
                        findingsPerDay(orgUuid: $orgUuid, date: $date) {
                            ${FINDINGS_FIELDS}
                        }
                    }
                `,
                variables: {
                    orgUuid: orgUuid.value,
                    date: dateToUse
                },
                fetchPolicy: 'network-only'
            })
            
            if (response.data.findingsPerDay) {
                findingsPerDayData.value = processMetricsData(response.data.findingsPerDay)
            }
        } else if (props.type === 'BRANCH') {
            if (!props.branchUuid) return
            response = await graphqlClient.query({
                query: gql`
                    query findingsPerDayForBranch($branchUuid: ID!, $date: String!) {
                        findingsPerDayForBranch(branchUuid: $branchUuid, date: $date) {
                            ${FINDINGS_FIELDS}
                        }
                    }
                `,
                variables: {
                    branchUuid: props.branchUuid,
                    date: dateToUse
                },
                fetchPolicy: 'no-cache'
            })
            
            if (response.data.findingsPerDayForBranch) {
                findingsPerDayData.value = processMetricsData(response.data.findingsPerDayForBranch)
            }
        } else if (props.type === 'COMPONENT') {
            if (!props.componentUuid) return
            response = await graphqlClient.query({
                query: gql`
                    query findingsPerDayForComponent($componentUuid: ID!, $date: String!) {
                        findingsPerDayForComponent(componentUuid: $componentUuid, date: $date) {
                            ${FINDINGS_FIELDS}
                        }
                    }
                `,
                variables: {
                    componentUuid: props.componentUuid,
                    date: dateToUse
                },
                fetchPolicy: 'no-cache'
            })
            
            if (response.data.findingsPerDayForComponent) {
                findingsPerDayData.value = processMetricsData(response.data.findingsPerDayForComponent)
            }
        } else if (props.type === 'PERSPECTIVE') {
            if (!orgUuid.value || !props.perspectiveUuid) return
            response = await graphqlClient.query({
                query: gql`
                    query findingsPerDayByPerspective($perspectiveUuid: ID!, $date: String!) {
                        findingsPerDayByPerspective(perspectiveUuid: $perspectiveUuid, date: $date) {
                            ${FINDINGS_FIELDS}
                        }
                    }
                `,
                variables: {
                    perspectiveUuid: props.perspectiveUuid,
                    date: dateToUse
                },
                fetchPolicy: 'network-only'
            })
            
            if (response.data.findingsPerDayByPerspective) {
                findingsPerDayData.value = processMetricsData(response.data.findingsPerDayByPerspective)
            }
        }
    } catch (error) {
        console.error('Error fetching findings per day:', error)
    } finally {
        findingsPerDayLoading.value = false
    }
}

async function fetchVulnerabilityViolationAnalytics() {
    isLoading.value = true
    try {
        let resp
        if (props.type === 'ORGANIZATION') {
            if (!orgUuid.value) return
            const dateFromValue = props.dateFrom || new Date(new Date().setDate(new Date().getDate() - props.daysBack))
            const dateToValue = props.dateTo || new Date()
            
            resp = await graphqlClient.query({
                query: gql`
                    query vulnerabilitiesViolationsOverTime($orgUuid: ID!, $dateFrom: DateTime!, $dateTo: DateTime!) {
                        vulnerabilitiesViolationsOverTime(orgUuid: $orgUuid, dateFrom: $dateFrom, dateTo: $dateTo) {
                            createdDate
                            num
                            type
                        }
                    }
                `,
                variables: {
                    orgUuid: orgUuid.value,
                    dateFrom: dateFromValue,
                    dateTo: dateToValue
                }
            })
            
            if (resp.data.vulnerabilitiesViolationsOverTime) {
                analyticsMetrics.value.data.values = resp.data.vulnerabilitiesViolationsOverTime.map((item: any) => ({
                    ...item,
                    createdDate: item.createdDate.split('[')[0]
                }))
            }
        } else if (props.type === 'BRANCH') {
            if (!props.branchUuid) return
            const dateFromValue = props.dateFrom || new Date(new Date().setDate(new Date().getDate() - props.daysBack))
            const dateToValue = props.dateTo || new Date()
            
            resp = await graphqlClient.query({
                query: gql`
                    query vulnerabilitiesViolationsOverTimeByBranch($branchUuid: ID!, $dateFrom: DateTime!, $dateTo: DateTime!) {
                        vulnerabilitiesViolationsOverTimeByBranch(branchUuid: $branchUuid, dateFrom: $dateFrom, dateTo: $dateTo) {
                            createdDate
                            num
                            type
                        }
                    }
                `,
                variables: {
                    branchUuid: props.branchUuid,
                    dateFrom: dateFromValue,
                    dateTo: dateToValue
                }
            })
            
            if (resp.data.vulnerabilitiesViolationsOverTimeByBranch) {
                analyticsMetrics.value.data.values = resp.data.vulnerabilitiesViolationsOverTimeByBranch.map((item: any) => ({
                    ...item,
                    createdDate: item.createdDate.split('[')[0]
                }))
            }
        } else if (props.type === 'COMPONENT') {
            if (!props.componentUuid) return
            const dateFromValue = props.dateFrom || new Date(new Date().setDate(new Date().getDate() - props.daysBack))
            const dateToValue = props.dateTo || new Date()
            
            resp = await graphqlClient.query({
                query: gql`
                    query vulnerabilitiesViolationsOverTimeByComponent($componentUuid: ID!, $dateFrom: DateTime!, $dateTo: DateTime!) {
                        vulnerabilitiesViolationsOverTimeByComponent(componentUuid: $componentUuid, dateFrom: $dateFrom, dateTo: $dateTo) {
                            createdDate
                            num
                            type
                        }
                    }
                `,
                variables: {
                    componentUuid: props.componentUuid,
                    dateFrom: dateFromValue,
                    dateTo: dateToValue
                }
            })
            
            if (resp.data.vulnerabilitiesViolationsOverTimeByComponent) {
                analyticsMetrics.value.data.values = resp.data.vulnerabilitiesViolationsOverTimeByComponent.map((item: any) => ({
                    ...item,
                    createdDate: item.createdDate.split('[')[0]
                }))
            }
        } else if (props.type === 'PERSPECTIVE') {
            if (!orgUuid.value || !props.perspectiveUuid) return
            const dateFromValue = props.dateFrom || new Date(new Date().setDate(new Date().getDate() - props.daysBack))
            const dateToValue = props.dateTo || new Date()
            
            resp = await graphqlClient.query({
                query: gql`
                    query vulnerabilitiesViolationsOverTimeByPerspective($perspectiveUuid: ID!, $dateFrom: DateTime!, $dateTo: DateTime!) {
                        vulnerabilitiesViolationsOverTimeByPerspective(perspectiveUuid: $perspectiveUuid, dateFrom: $dateFrom, dateTo: $dateTo) {
                            createdDate
                            num
                            type
                        }
                    }
                `,
                variables: {
                    perspectiveUuid: props.perspectiveUuid,
                    dateFrom: dateFromValue,
                    dateTo: dateToValue
                }
            })
            
            if (resp.data.vulnerabilitiesViolationsOverTimeByPerspective) {
                analyticsMetrics.value.data.values = resp.data.vulnerabilitiesViolationsOverTimeByPerspective.map((item: any) => ({
                    ...item,
                    createdDate: item.createdDate.split('[')[0]
                }))
            }
        }
        
        isLoading.value = false
        hasNoData.value = !analyticsMetrics.value.data.values || analyticsMetrics.value.data.values.length === 0
        await nextTick()
        if (!hasNoData.value) {
            renderChart()
        }
    } catch (error) {
        console.error('Error fetching vulnerability/violation analytics:', error)
        isLoading.value = false
    }
}

function renderChart() {
    if (!isMounted.value) {
        return
    }
    const element = document.querySelector('#findingsOverTimeVis')
    if (!element) {
        console.warn('Chart element #findingsOverTimeVis not found in DOM')
        return
    }
    
    vegaEmbed.default('#findingsOverTimeVis', toRaw(analyticsMetrics.value), {
        actions: {
            editor: false
        },
        theme: 'powerbi'
    }).then((result: any) => {
        result.view.addEventListener('click', (event: any, item: any) => {
            if (item && item.datum) {
                const datum = item.datum
                let severity = ''
                let typeParam = ''
                if (datum.type && datum.type.indexOf('Vulnerabilities') >= 0) {
                    severity = datum.type.split(' ')[0].toUpperCase()
                    typeParam = 'Vulnerability'
                } else if (datum.type && datum.type.indexOf('Violations') >= 0) {
                    typeParam = 'Violation'
                }
                const dateObj = new Date(datum.createdDate)
                const date = dateObj.toISOString().split('T')[0]
                if (date) {
                    openFindingsModal(date, severity, typeParam)
                }
            }
        })
    }).catch((error: any) => {
        console.error('Error rendering Vega chart:', error)
    })
}

onMounted(() => {
    isMounted.value = true
    fetchVulnerabilityViolationAnalytics()
    // Check if we should open findings modal based on route query params
    if (showFindingsPerDay.value && findingsPerDayDate.value) {
        showFindingsPerDayModal.value = true
        fetchFindingsPerDay(findingsPerDayDate.value)
    }
})

onBeforeUnmount(() => {
    isMounted.value = false
})

watch(() => [props.orgUuid, props.branchUuid, props.componentUuid, props.perspectiveUuid, props.dateFrom, props.dateTo], () => {
    if (props.type === 'BRANCH') return
    if (isMounted.value) {
        fetchVulnerabilityViolationAnalytics()
    }
}, { flush: 'post' })

// Watch for route changes to handle findings modal opening via URL
watch(showFindingsPerDay, (newVal) => {
    if (newVal && findingsPerDayDate.value) {
        showFindingsPerDayModal.value = true
        fetchFindingsPerDay(findingsPerDayDate.value)
    }
})
</script>

<style scoped lang="scss">
.findingsOverTimeChart {
    display: grid;
}
</style>
