<template>
    <div class="mostActiveChart">
        <div class="charts">
            <div id="mostActiveVisHome"></div>
        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'MostActiveChart'
}
</script>

<script lang="ts" setup>
import { ref, Ref, computed, onMounted, watch, toRaw } from 'vue'
import { useStore } from 'vuex'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import * as vegaEmbed from 'vega-embed'

const props = withDefaults(defineProps<{
    componentType: 'COMPONENT' | 'PRODUCT' | 'BRANCH' | 'FEATURE_SET'
    maxComponents: number
    cutOffDate: number
    orgUuid?: string
    perspectiveUuid?: string
    featureSetLabel?: string
}>(), {
    maxComponents: 3,
    featureSetLabel: 'Feature Set'
})

const store = useStore()
const myorg = computed(() => store.getters.myorg)
const myperspective = computed(() => store.getters.myperspective)

const orgUuid = computed(() => props.orgUuid || myorg.value?.uuid || '')
const featureSetLabelPlural = computed(() => props.featureSetLabel + 's')

const mostActiveOverTime: Ref<any> = ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v6.json',
    background: 'white',
    width: 'container',
    height: 220,
    data: {
        values: []
    },
    mark: {
        type: "arc",
        innerRadius: 50
    },
    transform: [{
        calculate: "'/componentsOfOrg/" + orgUuid.value + "/' + datum.componentuuid", "as": "url"
    }],
    encoding: {
        theta: {field: "rlzcount", type: "quantitative"},
        color: {
            field: "componentname",
            type: "nominal", 
            title: "Name",
            legend: {
                direction: 'horizontal',
                orient: 'bottom'
            }
        },
        href: {field: "url", type: "nominal"},
        tooltip: [
            {field: "componentname", type: "nominal", title: "Name"},
            {field: "rlzcount", type: "quantitative", title: "Releases"}
        ]
    }
})

function parseActiveComponentsInput() {
    const parsedActiveComponentsInput = {
        organization: orgUuid.value,
        cutOffDate: new Date(props.cutOffDate),
        componentType: props.componentType,
        maxComponents: props.maxComponents
    }
    if (props.componentType === 'COMPONENT' || props.componentType === 'BRANCH') {
        parsedActiveComponentsInput.componentType = 'COMPONENT'
    } else {
        parsedActiveComponentsInput.componentType = 'PRODUCT'
    }
    return parsedActiveComponentsInput
}

function embedActiveComponentsVega() {
    const mostActiveToEmbed = toRaw(mostActiveOverTime.value)
    vegaEmbed.default('#mostActiveVisHome', mostActiveToEmbed,
        {
            actions: {
                editor: false
            },
            theme: 'powerbi'
        }
    )
}

function transformMostActiveDataBasedOnType() {
    if (props.componentType === 'COMPONENT') {
        mostActiveOverTime.value.transform = [{
            calculate: "'/componentsOfOrg/" + orgUuid.value + "/' + datum.componentuuid", "as": "url"
        }]
        mostActiveOverTime.value.encoding.color.title = "Component"
        mostActiveOverTime.value.encoding.tooltip[0].title = "Component"
    } else if (props.componentType === 'PRODUCT') {
        mostActiveOverTime.value.transform = [{
            calculate: "'/productsOfOrg/" + orgUuid.value + "/' + datum.componentuuid", "as": "url"
        }]
        mostActiveOverTime.value.encoding.color.title = "Product"
        mostActiveOverTime.value.encoding.tooltip[0].title = "Product"
    } else if (props.componentType === 'BRANCH') {
        mostActiveOverTime.value.transform = [{
            calculate: "'/componentsOfOrg/" + orgUuid.value + "/' + datum.componentuuid + '/' + datum.branchuuid", "as": "url"
        }]
        mostActiveOverTime.value.encoding.color.title = "Branch"
        mostActiveOverTime.value.encoding.tooltip[0].title = "Branch"
    } else if (props.componentType === 'FEATURE_SET') {
        mostActiveOverTime.value.transform = [{
            calculate: "'/productsOfOrg/" + orgUuid.value + "/' + datum.componentuuid + '/' + datum.branchuuid", "as": "url"
        }]
        mostActiveOverTime.value.encoding.color.title = props.featureSetLabel
        mostActiveOverTime.value.encoding.tooltip[0].title = props.featureSetLabel
    }
}

async function fetchActiveBranchesAnalytics() {
    const parsedActiveComponentsInput = parseActiveComponentsInput()

    const response = await graphqlClient.query({
        query: gql`
            query mostActiveBranchesOverTime($activeComponentsInput: ActiveComponentsInput!) {
                mostActiveBranchesOverTime(activeComponentsInput: $activeComponentsInput) {
                    componentuuid
                    componentname
                    branchuuid
                    branchname
                    rlzcount
                }
            }`,
        variables: { 
            activeComponentsInput: parsedActiveComponentsInput
        }
    })
    if (response && response.data) {
        mostActiveOverTime.value.data.values = []
        response.data.mostActiveBranchesOverTime.forEach((e: any) => {
            const analyticsEl = {
                componentname: e.componentname + " - " + e.branchname,
                componentuuid: e.componentuuid,
                branchuuid: e.branchuuid,
                rlzcount: e.rlzcount,
                componenttype: e.componenttype
            }
            mostActiveOverTime.value.data.values.push(analyticsEl)
        })
        transformMostActiveDataBasedOnType()
        embedActiveComponentsVega()
    }
}

async function fetchActiveBranchesAnalyticsByPerspective() {
    const parsedActiveComponentsInput = parseActiveComponentsInput()

    const response = await graphqlClient.query({
        query: gql`
            query mostActiveBranchesOverTimeByPerspective($activeComponentsInput: ActiveComponentsInput!, $perspectiveUuid: ID!) {
                mostActiveBranchesOverTimeByPerspective(activeComponentsInput: $activeComponentsInput, perspectiveUuid: $perspectiveUuid) {
                    componentuuid
                    componentname
                    branchuuid
                    branchname
                    rlzcount
                }
            }`,
        variables: { 
            activeComponentsInput: parsedActiveComponentsInput,
            perspectiveUuid: props.perspectiveUuid
        }
    })
    if (response && response.data) {
        mostActiveOverTime.value.data.values = []
        response.data.mostActiveBranchesOverTimeByPerspective.forEach((e: any) => {
            const analyticsEl = {
                componentname: e.componentname + " - " + e.branchname,
                componentuuid: e.componentuuid,
                branchuuid: e.branchuuid,
                rlzcount: e.rlzcount,
                componenttype: e.componenttype
            }
            mostActiveOverTime.value.data.values.push(analyticsEl)
        })
        transformMostActiveDataBasedOnType()
        embedActiveComponentsVega()
    }
}

async function fetchActiveComponentsAnalytics() {
    const parsedActiveComponentsInput = parseActiveComponentsInput()

    const response = await graphqlClient.query({
        query: gql`
            query mostActiveComponentsOverTime($activeComponentsInput: ActiveComponentsInput!) {
                mostActiveComponentsOverTime(activeComponentsInput: $activeComponentsInput) {
                    componentuuid
                    componentname
                    rlzcount
                }
            }`,
        variables: { 
            activeComponentsInput: parsedActiveComponentsInput
        }
    })
    if (response && response.data) {
        mostActiveOverTime.value.data.values = []
        response.data.mostActiveComponentsOverTime.forEach((e: any) => mostActiveOverTime.value.data.values.push(Object.assign({}, e)))
        transformMostActiveDataBasedOnType()
        embedActiveComponentsVega()
    }
}

async function fetchActiveComponentsAnalyticsByPerspective() {
    const parsedActiveComponentsInput = parseActiveComponentsInput()

    const response = await graphqlClient.query({
        query: gql`
            query mostActiveComponentsOverTimeByPerspective($activeComponentsInput: ActiveComponentsInput!, $perspectiveUuid: ID!) {
                mostActiveComponentsOverTimeByPerspective(activeComponentsInput: $activeComponentsInput, perspectiveUuid: $perspectiveUuid) {
                    componentuuid
                    componentname
                    rlzcount
                }
            }`,
        variables: { 
            activeComponentsInput: parsedActiveComponentsInput,
            perspectiveUuid: props.perspectiveUuid
        }
    })
    if (response && response.data) {
        mostActiveOverTime.value.data.values = []
        response.data.mostActiveComponentsOverTimeByPerspective.forEach((e: any) => mostActiveOverTime.value.data.values.push(Object.assign({}, e)))
        transformMostActiveDataBasedOnType()
        embedActiveComponentsVega()
    }
}

async function fetchActiveComponentsBranchesAnalytics() {
    const usePerspective = props.perspectiveUuid && props.perspectiveUuid !== 'default'
    
    if (props.componentType === 'COMPONENT' || props.componentType === 'PRODUCT') {
        if (usePerspective) {
            await fetchActiveComponentsAnalyticsByPerspective()
        } else {
            await fetchActiveComponentsAnalytics()
        }
    } else {
        if (usePerspective) {
            await fetchActiveBranchesAnalyticsByPerspective()
        } else {
            await fetchActiveBranchesAnalytics()
        }
    }
}

onMounted(() => {
    fetchActiveComponentsBranchesAnalytics()
})

watch(() => [props.componentType, props.maxComponents, props.cutOffDate, props.perspectiveUuid, props.orgUuid], () => {
    fetchActiveComponentsBranchesAnalytics()
})
</script>

<style scoped lang="scss">
.mostActiveChart {
    display: grid;
}

.charts {
    display: grid;
}
</style>
