<template>
    <div class="findingsOverTimeChart">
        <div id="findingsOverTimeVis"></div>
        <vulnerability-modal
            v-model:show="showFindingsPerDayModal"
            :component-name="findingsPerDayModalTitle"
            version=""
            :data="findingsPerDayData"
            :loading="findingsPerDayLoading"
            :org-uuid="orgUuid"
            :initial-severity-filter="directFindingsDate ? directFindingsSeverity : findingsPerDaySeverity"
            :initial-type-filter="directFindingsDate ? directFindingsType : findingsPerDayType"
            @update:show="(val: boolean) => { if (!val) closeFindingsPerDayModal() }"
        />
    </div>
</template>

<script lang="ts">
export default {
    name: 'FindingsOverTimeChart'
}
</script>

<script lang="ts" setup>
import { ref, Ref, computed, onMounted, watch, toRaw } from 'vue'
import { useStore } from 'vuex'
import { useRoute } from 'vue-router'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import { processMetricsData } from '@/utils/metrics'
import * as vegaEmbed from 'vega-embed'
import VulnerabilityModal from './VulnerabilityModal.vue'

const props = withDefaults(defineProps<{
    type: 'ORGANIZATION' | 'BRANCH' | 'COMPONENT'
    orgUuid?: string
    branchUuid?: string
    componentUuid?: string
    dateFrom?: Date
    dateTo?: Date
    daysBack?: number
}>(), {
    daysBack: 60
})

const store = useStore()
const route = useRoute()
const myorg = computed(() => store.getters.myorg)

const orgUuid = computed(() => props.orgUuid || myorg.value?.uuid || '')

const findingsPerDayData: Ref<any[]> = ref([])
const findingsPerDayLoading: Ref<boolean> = ref(false)
const showFindingsPerDayModal: Ref<boolean> = ref(false)
const directFindingsDate: Ref<string> = ref('')
const directFindingsSeverity: Ref<string> = ref('')
const directFindingsType: Ref<string> = ref('')

// Route-based query params for findings per day display
const showFindingsPerDay = computed(() => route.query.display === 'findingsPerDay' && route.query.date)
const findingsPerDayDate = computed(() => route.query.date as string || '')
const findingsPerDaySeverity = computed(() => route.query.severity as string || '')
const findingsPerDayType = computed(() => route.query.type as string || '')

const findingsPerDayModalTitle = computed(() => `Findings for ${directFindingsDate.value || findingsPerDayDate.value}`)

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
    title: 'Findings Over Time',
    height: 220,
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
            legend: null
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
                fetchPolicy: 'no-cache'
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
        }
    } catch (error) {
        console.error('Error fetching findings per day:', error)
    } finally {
        findingsPerDayLoading.value = false
    }
}

async function fetchVulnerabilityViolationAnalytics() {
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
        }
        
        renderChart()
    } catch (error) {
        console.error('Error fetching vulnerability/violation analytics:', error)
    }
}

function renderChart() {
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
    fetchVulnerabilityViolationAnalytics()
    // Check if we should open findings modal based on route query params
    if (showFindingsPerDay.value && findingsPerDayDate.value) {
        showFindingsPerDayModal.value = true
        fetchFindingsPerDay(findingsPerDayDate.value)
    }
})

watch(() => [props.orgUuid, props.branchUuid, props.componentUuid, props.dateFrom, props.dateTo], () => {
    fetchVulnerabilityViolationAnalytics()
})

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
