<template>
    <div class="home">
        <div style="text-align: center;">
            <h5 v-if="componentTypeProp === 'COMPONENT'" >Component Analytics</h5>
            <h5 v-else>Product Analytics</h5>
        </div>
        <!-- Container for the visualization -->
        <div class="charts">
            <div v-if="componentTypeProp === 'COMPONENT'" id="buildDurationsVis"></div>
            <div id="deployDurationsVis"></div>
            <div id="releasePercentVis"></div>
        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'ComponentAnalytics'
}
</script>
<script lang="ts" setup>
import * as vegaEmbed from 'vega-embed'
import axios from '../utils/axios'
import graphqlClient from '../utils/graphql'
import gql from 'graphql-tag'

async function fetchComponentArtifacts (componentUuid : string, buildDurationVisData : any) {
    const response = await graphqlClient.query({
        query: gql`
            query listArtifactsByComponent($componentUuid: ID!) {
                listArtifactsByComponent(componentUuid: $componentUuid) {
                    duration
                    dateFrom
                }
            }`,
        variables: {
            componentUuid
        },
        fetchPolicy: 'no-cache'
    })
    buildDurationVisData.data.values = response.data.listArtifactsByComponent
    vegaEmbed.default('#buildDurationsVis', buildDurationVisData)
}

async function fetchComponentReleases (componentUuid : string, deployDurationVisData : any) {
    let axiosResp = await axios.get('/api/manual/v1/release/listSoftLeadTimeByComponent/' + componentUuid)
    deployDurationVisData.data.values = axiosResp.data
    vegaEmbed.default('#deployDurationsVis', deployDurationVisData)
}

async function fetchReleaseEnvPercentages (componentUuid : string, releasePercentVisData : any) {
    let axiosResp = await axios.get('/api/manual/v1/release/percentagesByComponent/' + componentUuid)
    releasePercentVisData.data.values = Object.keys(axiosResp.data).map((k, i) => {
        return { 'environment': k, 'percentage': parseFloat(axiosResp.data[k]).toFixed(2) }
    })
    vegaEmbed.default('#releasePercentVis', releasePercentVisData)
}

const buildDurationVisData = {
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    background: '#f7f7f7',
    title: 'Average Duration of Builds',
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
        { calculate: 'datum.duration/60', as: 'duration_minutes' }
    ],
    encoding: {
        y: {
            field: 'duration_minutes',
            type: 'quantitative',
            aggregate: 'average',
            axis: {
                title: 'Duration of builds, mins'
            }
        },
        x: {
            field: 'dateFrom',
            type: 'temporal',
            timeUnit: 'yearmonthdate',
            axis: {
                title: 'Date'
            }
        }
    }
}

const deployDurationVisData = {
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
}

const releasePercentVisData = {
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
}

const props = defineProps<{
    componentUuidProp: String,
    componentTypeProp: String
}>()


if (props.componentTypeProp === 'COMPONENT') {
    fetchComponentArtifacts(props.componentUuidProp, buildDurationVisData)
}
fetchComponentReleases(props.componentUuidProp, deployDurationVisData)
fetchReleaseEnvPercentages(props.componentUuidProp, releasePercentVisData)




</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
.vega-actions a {
    margin-right: 5px;
}
.charts {
    display: grid;
    grid-template-columns: 0.95fr 0.95fr;
}
.searchBlock {
    display: grid;
    grid-template-columns: 0.95fr 0.95fr;
    grid-gap: 20px;
    div.searchUnit {
        margin: 0 40px;
    }
}
.numericAnalyticsBlock {
    margin-left: 20px;
}
.numericAnalytics {
    display: grid;
    grid-template-columns: 300px 300px;
}
.releaseSearchResults {
    min-width: 600px;
}
.releaseElInList {
    display: inline-block;
}
.searchResults {
    margin-top: 30px;
}
.instanceChangeSearchGroup {
    max-width: 640px;
    margin-bottom: 7px;
    label {
        width: 50px;
    }
    .timeInput {
        max-width: 150px;
    }
}
</style>