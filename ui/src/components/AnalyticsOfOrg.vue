<template>
    <div>
        <h4>Statistics</h4>
        <!-- Container for the visualization -->
        <div class="charts">
            <div id="releaseCreationVis"></div>
            <div id="instanceChangesVis"></div>
            <div id="leadTimeVis"></div>
            <div id="releasePercentVis"></div>
        </div>
        <div class="numericAnalyticsBlock" v-if="Object.entries(totalsAnalytics).length">
            <div class="numericAnalytics">
                <div>Components: {{ totalsAnalytics.components }}</div>
                <div>Products: {{ totalsAnalytics.products }}</div>
                <div>Branches and Feature Sets: {{ totalsAnalytics.branches }}</div>
                <div>Releases: {{ totalsAnalytics.releases }}</div>
                <!-- div>Instances: {{ totalsAnalytics.instances }}</div -->
                <div>VCS Repositories: {{ totalsAnalytics.vcs }}</div>
                <div>Recorded Commits: {{ totalsAnalytics.commits }}</div>
                <div>Recorded Artifacts: {{ totalsAnalytics.artifacts }}</div>
            </div>
        </div>
    </div>
</template>
  
<script lang="ts" setup>
import * as vegaEmbed from 'vega-embed'
import axios from '../utils/axios'
import { ComputedRef, h, ref, Ref, computed, onMounted } from 'vue'
import { useStore } from 'vuex'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'

const store = useStore()


const instVisData : Ref<any> = ref({ 
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    background: '#f7f7f7',
    title: 'Number of Instance Changes Per Day',
    width: 'container',
    data: {
        values: []
    },
    mark: {
        type: 'point',
        tooltip: true
    },
    encoding: {
        y: {
            field: 'num',
            type: 'quantitative',
            aggregate: 'sum',
            axis: {
                title: '# of changes'
            }
        },
        x: {
            field: 'date',
            type: 'temporal',
            timeUnit: 'utcyearmonthdate',
            axis: {
                title: 'Date'
            }
        }
    }
})
const leadTimeVisData: Ref<any> = ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    background: '#f7f7f7',
    title: 'Lead Time Per Environment',
    width: 'container',
    data: {
        values: []
    },
    mark: {
        type: 'line',
        tooltip: true
    },
    transform: [
        { filter: 'datum.duration > 0' },
        { calculate: 'datum.duration/3600', as: 'duration_hours' }
    ],
    encoding: {
        y: {
            field: 'duration_hours',
            type: 'quantitative',
            aggregate: 'average',
            axis: {
                title: 'Lead Time, hours'
            }
        },
        x: {
            field: 'dateFrom',
            type: 'temporal',
            timeUnit: {
                unit: 'yearmonthdate',
                step: 7
            },
            axis: {
                title: 'Date'
            }
        },
        color: {
            field: 'environment',
            type: 'nominal'
        }
    }
})
const releasePercentVisData: Ref<any> = ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    background: '#f7f7f7',
    title: 'Percent Releases Deployed Per Environment',
    width: 'container',
    data: {
        values: []
    },
    layer: [
        { mark: 'bar' },
        {
            mark: {
                type: 'text',
                align: 'left',
                baseline: 'middle',
                dx: 3
            },
            encoding: {
                text: { field: 'percentage', type: 'quantitative' }
            }
        }
    ],
    encoding: {
        y: {
            field: 'environment',
            type: 'nominal',
            axis: {
                title: 'Environments'
            }
        },
        x: {
            field: 'percentage',
            type: 'quantitative',
            axis: {
                title: 'Percent of Releases'
            },
            scale: { domain: [0, 100] }
        }
    }
})
const totalsAnalytics: Ref<any> =  ref({})
       
const props = defineProps<{
    orguuid?: string
}>()
const isLoading: Ref<boolean> = ref(true)
function fetchInstanceAnalytics(orgUuid: string) {
    axios.get('/v1/home/instanceAnalytics/' + orgUuid).then(response => {
        response.data.map((item: any) => {
            item.date = item.date.split('[')[0]
        })
        instVisData.value.data.values = response.data
        vegaEmbed.default('#instanceChangesVis', instVisData.value).then(() => {
            isLoading.value = false
        })
    })
}
function fetchOrgReleasesLeadTimes(orgUuid: string) {
    axios.get('/api/manual/v1/release/listSoftLeadTimeByOrg/' + orgUuid).then(response => {
        leadTimeVisData.value.data.values = response.data
        vegaEmbed.default('#leadTimeVis', leadTimeVisData.value)
    })
}
function fetchReleaseEnvPercentages(orgUuid: string) {
    axios.get('/api/manual/v1/release/percentagesByOrg/' + orgUuid).then(response => {
        releasePercentVisData.value.data.values = Object.keys(response.data).map((k, i) => {
            return { 'environment': k, 'percentage': parseFloat(response.data[k]).toFixed(2) }
        })
        vegaEmbed.default('#releasePercentVis', releasePercentVisData.value)
    })
}
async function fetchTotalsAnalytics(orgUuid: string) {
    const resp = await graphqlClient.query({
        query: gql`
            query totalsAnalytics($orgUuid: ID!) {
                totalsAnalytics(orgUuid: $orgUuid)
            }
            `,
        variables: { orgUuid },
        fetchPolicy: 'no-cache'
    })
    totalsAnalytics.value = resp.data.totalsAnalytics
}
const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)
const myuser = store.getters.myuser

onMounted(() => {
    if (false && myuser.installationType !== 'OSS') fetchInstanceAnalytics(myorg.value.uuid)
    if (false && myuser.installationType !== 'OSS') fetchOrgReleasesLeadTimes(myorg.value.uuid)
    if (false && myuser.installationType !== 'OSS') fetchReleaseEnvPercentages(myorg.value.uuid)
    fetchTotalsAnalytics(myorg.value.uuid)
    if (false && myuser.installationType !== 'OSS') store.dispatch('fetchProperties', myorg.value.uuid)
})
</script>
  
<style scoped lang="scss">
.vega-actions a {
    margin-right: 5px;
}

.charts {
    display: grid;
    grid-template-columns: 0.95fr 0.95fr;
}

.numericAnalyticsBlock {
    margin-left: 20px;
}

</style>
  