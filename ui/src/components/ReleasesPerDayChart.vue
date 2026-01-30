<template>
    <div class="releasesPerDayChart">
        <div v-if="props.type === 'ORGANIZATION' || props.type === 'PERSPECTIVE'" style="display: flex; align-items: center; gap: 8px;">
            <h3 class="chart-title" style="margin: 0;">Releases Per Day</h3>
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
        </div>
        <h3 v-else class="chart-title">Releases Per Day</h3>
        <div class="charts">
            <n-skeleton v-if="isLoading" height="220px" :sharp="false" />
            <n-empty v-else-if="hasNoData" style="height: 220px;" :description="`No releases added for the last ${props.daysBack} days`" size="large" />
            <div v-else id="releaseCreationVisHome"></div>
        </div>
        <releases-by-date-range
            v-model:show="showReleasesModal"
            :org-uuid="orgUuid"
            :perspective-uuid="props.perspectiveUuid"
            :perspective-name="props.perspectiveName"
            :component-uuid="props.componentUuid"
            :branch-uuid="props.branchUuid"
            :component-name="props.componentName"
            :branch-name="props.branchName"
            :component-type="props.componentType"
            :initial-start-date="releasesModalStartDate"
            :initial-end-date="releasesModalEndDate"
            @update:show="(val: boolean) => { if (!val) closeReleasesModal() }"
            @update:dates="onDatesUpdate"
        />
    </div>
</template>

<script lang="ts">
export default {
    name: 'ReleasesPerDayChart'
}
</script>

<script lang="ts" setup>
import { ref, Ref, computed, onMounted, onBeforeUnmount, watch, toRaw, nextTick } from 'vue'
import { useStore } from 'vuex'
import { useRoute } from 'vue-router'
import { NSkeleton, NEmpty, NIcon } from 'naive-ui'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import * as vegaEmbed from 'vega-embed'
import ReleasesByDateRange from './ReleasesByDateRange.vue'
import { ArrowExpand20Regular } from '@vicons/fluent'

const props = withDefaults(defineProps<{
    type: 'ORGANIZATION' | 'BRANCH' | 'COMPONENT' | 'PERSPECTIVE'
    orgUuid?: string
    branchUuid?: string
    componentUuid?: string
    perspectiveUuid?: string
    perspectiveName?: string
    componentName?: string
    branchName?: string
    componentType?: string
    daysBack?: number
    chartHeight?: number
    showFullPageIcon?: boolean
}>(), {
    daysBack: 60,
    perspectiveName: '',
    componentName: '',
    branchName: '',
    componentType: '',
    chartHeight: 220,
    showFullPageIcon: true
})

const store = useStore()
const route = useRoute()
const myorg = computed(() => store.getters.myorg)

const orgUuid = computed(() => props.orgUuid || myorg.value?.uuid || '')
const isLoading = ref(true)
const isMounted = ref(true)
const hasNoData = ref(false)

const showReleasesModal: Ref<boolean> = ref(false)
const releasesModalStartDate: Ref<number | undefined> = ref(undefined)
const releasesModalEndDate: Ref<number | undefined> = ref(undefined)

// Route-based query params for releases per day display
const showReleasesPerDay = computed(() => route.query.display === 'releasesPerDay' && route.query.fromDate && route.query.toDate)
const releasesPerDayFromDate = computed(() => route.query.fromDate as string || '')
const releasesPerDayToDate = computed(() => route.query.toDate as string || '')

const releaseVisData: Ref<any> = ref({
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
    encoding: {
        y: {
            field: 'num',
            type: 'quantitative',
            aggregate: 'sum',
            axis: {
                title: null
            },
            title: 'Releases'
        },
        x: {
            field: 'date',
            type: 'temporal',
            timeUnit: 'utcyearmonthdate',
            axis: {
                title: null
            },
            title: 'Date'
        }
    }
})

async function fetchReleaseAnalytics() {
    isLoading.value = true
    const cutOffDate = new Date()
    cutOffDate.setDate(cutOffDate.getDate() - props.daysBack)
    
    try {
        let resp: any
        if (props.type === 'ORGANIZATION') {
            if (!orgUuid.value) return
            resp = await graphqlClient.query({
                query: gql`
                    query releaseAnalytics($orgUuid: ID!, $cutOffDate: DateTime!) {
                        releaseAnalytics(orgUuid: $orgUuid, cutOffDate: $cutOffDate) {
                            date
                            num
                        }
                    }
                `,
                variables: { 
                    orgUuid: orgUuid.value,
                    cutOffDate
                },
                fetchPolicy: 'no-cache'
            })
            
            resp.data.releaseAnalytics.map((item: any) => {
                item.date = item.date.split('[')[0]
            })
            
            releaseVisData.value.data.values = resp.data.releaseAnalytics
        } else if (props.type === 'COMPONENT') {
            if (!props.componentUuid) return
            resp = await graphqlClient.query({
                query: gql`
                    query releaseAnalyticsByComponent($componentUuid: ID!, $cutOffDate: DateTime!) {
                        releaseAnalyticsByComponent(componentUuid: $componentUuid, cutOffDate: $cutOffDate) {
                            date
                            num
                        }
                    }
                `,
                variables: { 
                    componentUuid: props.componentUuid,
                    cutOffDate
                },
                fetchPolicy: 'no-cache'
            })
            
            resp.data.releaseAnalyticsByComponent.map((item: any) => {
                item.date = item.date.split('[')[0]
            })
            
            releaseVisData.value.data.values = resp.data.releaseAnalyticsByComponent
        } else if (props.type === 'BRANCH') {
            if (!props.branchUuid) return
            resp = await graphqlClient.query({
                query: gql`
                    query releaseAnalyticsByBranch($branchUuid: ID!, $cutOffDate: DateTime!) {
                        releaseAnalyticsByBranch(branchUuid: $branchUuid, cutOffDate: $cutOffDate) {
                            date
                            num
                        }
                    }
                `,
                variables: { 
                    branchUuid: props.branchUuid,
                    cutOffDate
                },
                fetchPolicy: 'no-cache'
            })
            
            resp.data.releaseAnalyticsByBranch.map((item: any) => {
                item.date = item.date.split('[')[0]
            })
            
            releaseVisData.value.data.values = resp.data.releaseAnalyticsByBranch
        } else if (props.type === 'PERSPECTIVE') {
            if (!props.perspectiveUuid) return
            resp = await graphqlClient.query({
                query: gql`
                    query releaseAnalyticsByPerspective($perspectiveUuid: ID!, $cutOffDate: DateTime!) {
                        releaseAnalyticsByPerspective(perspectiveUuid: $perspectiveUuid, cutOffDate: $cutOffDate) {
                            date
                            num
                        }
                    }
                `,
                variables: { 
                    perspectiveUuid: props.perspectiveUuid,
                    cutOffDate
                },
                fetchPolicy: 'no-cache'
            })
            
            resp.data.releaseAnalyticsByPerspective.map((item: any) => {
                item.date = item.date.split('[')[0]
            })
            
            releaseVisData.value.data.values = resp.data.releaseAnalyticsByPerspective
        }
        
        isLoading.value = false
        hasNoData.value = !releaseVisData.value.data.values || releaseVisData.value.data.values.length === 0
        await nextTick()
        if (!hasNoData.value) {
            renderChart()
        }
    } catch (error) {
        console.error('Error fetching release analytics:', error)
        isLoading.value = false
    }
}

function closeReleasesModal() {
    showReleasesModal.value = false
    releasesModalStartDate.value = undefined
    releasesModalEndDate.value = undefined
    // Clear query params silently without triggering watchers (only if there are params)
    if (window.location.search) {
        window.history.replaceState(window.history.state, '', window.location.pathname)
    }
}

const fullPageViewUrl = computed(() => `/releasesPerDay/${orgUuid.value}`)

function openReleasesModal(fromDate: string, toDate?: string) {
    // Parse the dates
    const fromDateObj = new Date(fromDate)
    const toDateObj = toDate ? new Date(toDate) : fromDateObj
    releasesModalStartDate.value = fromDateObj.getTime()
    releasesModalEndDate.value = toDateObj.getTime()
    showReleasesModal.value = true
    
    // Update URL without triggering watchers so page reload works
    updateUrlParams(fromDate, toDate || fromDate)
}

function updateUrlParams(fromDate: string, toDate: string) {
    const params = new URLSearchParams()
    params.set('display', 'releasesPerDay')
    params.set('fromDate', fromDate)
    params.set('toDate', toDate)
    window.history.replaceState({}, '', `${window.location.pathname}?${params.toString()}`)
}

function onDatesUpdate(dates: { fromDate: string, toDate: string }) {
    updateUrlParams(dates.fromDate, dates.toDate)
}

function renderChart() {
    if (!isMounted.value) {
        return
    }
    const element = document.querySelector('#releaseCreationVisHome')
    if (!element) {
        console.warn('Chart element #releaseCreationVisHome not found in DOM')
        return
    }
    
    vegaEmbed.default('#releaseCreationVisHome', 
        toRaw(releaseVisData.value),
        {
            actions: {
                editor: false
            },
            theme: 'powerbi'
        }
    ).then((result: any) => {
        result.view.addEventListener('click', (event: any, item: any) => {
            if (item && item.datum) {
                const datum = item.datum
                const dateObj = new Date(datum.utcyearmonthdate_date)
                // Adjust for timezone: if UTC-x (negative offset), pick next day; if UTC+x or UTC, use this day
                const timezoneOffset = dateObj.getTimezoneOffset() // positive for UTC-x, negative for UTC+x
                if (timezoneOffset > 0) {
                    dateObj.setUTCDate(dateObj.getUTCDate() + 1)
                }
                const date = dateObj.toISOString().split('T')[0]
                if (date) {
                    openReleasesModal(date)
                }
            }
        })
    }).catch((error: any) => {
        console.error('Error rendering Vega chart:', error)
    })
}

onMounted(() => {
    isMounted.value = true
    fetchReleaseAnalytics()
    // Check if we should open releases modal based on route query params
    if (showReleasesPerDay.value && releasesPerDayFromDate.value && releasesPerDayToDate.value) {
        openReleasesModal(releasesPerDayFromDate.value, releasesPerDayToDate.value)
    }
})

onBeforeUnmount(() => {
    isMounted.value = false
})

watch(() => [props.orgUuid, props.componentUuid, props.branchUuid, props.perspectiveUuid, props.daysBack], () => {
    if (props.type === 'BRANCH') return
    if (isMounted.value) {
        fetchReleaseAnalytics()
    }
}, { flush: 'post' })

// Watch for route changes to handle releases modal opening via URL
watch(showReleasesPerDay, (newVal) => {
    if (newVal && releasesPerDayFromDate.value && releasesPerDayToDate.value) {
        openReleasesModal(releasesPerDayFromDate.value, releasesPerDayToDate.value)
    }
})
</script>

<style scoped lang="scss">
.releasesPerDayChart {
    display: grid;
}

.charts {
    display: grid;
}
</style>
