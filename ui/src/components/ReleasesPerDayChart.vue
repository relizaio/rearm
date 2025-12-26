<template>
    <div class="releasesPerDayChart">
        <h3 class="chart-title">Releases Per Day</h3>
        <div class="charts">
            <n-skeleton v-if="isLoading" height="220px" :sharp="false" />
            <n-empty v-else-if="hasNoData" description="Not enough analytics data" size="large" />
            <div v-else id="releaseCreationVisHome"></div>
        </div>
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
import { NSkeleton, NEmpty } from 'naive-ui'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import * as vegaEmbed from 'vega-embed'

const props = withDefaults(defineProps<{
    type: 'ORGANIZATION' | 'BRANCH' | 'COMPONENT' | 'PERSPECTIVE'
    orgUuid?: string
    branchUuid?: string
    componentUuid?: string
    perspectiveUuid?: string
    daysBack?: number
}>(), {
    daysBack: 60
})

const store = useStore()
const myorg = computed(() => store.getters.myorg)

const orgUuid = computed(() => props.orgUuid || myorg.value?.uuid || '')
const isLoading = ref(true)
const isMounted = ref(true)
const hasNoData = ref(false)

const releaseVisData: Ref<any> = ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v6.json',
    background: 'white',
    title: 'Releases Per Day',
    height: 220,
    width: 'container',
    data: {
        values: []
    },
    mark: {
        type: 'line',
        point: {
            "filled": false,
            "fill": "white"
        },
        tooltip: true
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
    )
}

onMounted(() => {
    isMounted.value = true
    fetchReleaseAnalytics()
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
</script>

<style scoped lang="scss">
.releasesPerDayChart {
    display: grid;
}

.charts {
    display: grid;
}
</style>
