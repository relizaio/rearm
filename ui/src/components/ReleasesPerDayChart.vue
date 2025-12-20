<template>
    <div class="releasesPerDayChart">
        <div class="charts">
            <div id="releaseCreationVisHome"></div>
        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'ReleasesPerDayChart'
}
</script>

<script lang="ts" setup>
import { ref, Ref, computed, onMounted, watch, toRaw } from 'vue'
import { useStore } from 'vuex'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import * as vegaEmbed from 'vega-embed'

const props = withDefaults(defineProps<{
    orgUuid?: string
    daysBack?: number
}>(), {
    daysBack: 60
})

const store = useStore()
const myorg = computed(() => store.getters.myorg)

const orgUuid = computed(() => props.orgUuid || myorg.value?.uuid || '')

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
    if (!orgUuid.value) return
    
    const cutOffDate = new Date()
    cutOffDate.setDate(cutOffDate.getDate() - props.daysBack)
    
    try {
        const resp = await graphqlClient.query({
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
        renderChart()
    } catch (error) {
        console.error('Error fetching release analytics:', error)
    }
}

function renderChart() {
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
    fetchReleaseAnalytics()
})

watch(() => [props.orgUuid, props.daysBack], () => {
    fetchReleaseAnalytics()
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
