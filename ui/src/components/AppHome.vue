<template>
    <div class="home">
        <div class="dashboardBlock">
            <n-grid x-gap="24" cols="2">
                <n-gi>
                    <div class="charts">
                        <div id="releaseCreationVisHome"></div>
                    </div>
                </n-gi>
                <n-gi>
                    <div>
                        <n-input-number style="display:inline-block; width:80px;" 
                            v-model:value="activeComponentsInput.maxComponents"
                            :on-update:value="(value: number|null) => {activeComponentsInput.maxComponents = value ? value : 3; fetchActiveComponentsBranchesAnalytics();}" />
                        <span>Most Active </span>
                        <n-dropdown title="Select Type" trigger="hover"
                            :options="[{label: 'Components', key: 'COMPONENT'}, {label: 'Products', key: 'PRODUCT'}, {label: 'Branches', key: 'BRANCH'}, {label: 'Feature Sets', key: 'FEATURE_SET'}]"
                            @select="$key => {activeComponentsInput.componentType = $key ? $key: 'COMPONENT'; fetchActiveComponentsBranchesAnalytics();}">
                            <span>
                                <span>{{ displayActiveComponentType() }}</span>
                                <Icon><CaretDownFilled/></Icon>
                            </span>
                        </n-dropdown>
                        <span>since </span>
                        <n-date-picker style="display:inline-block; width:130px;"
                            v-model:value="activeComponentsInput.cutOffDate"
                            :on-update:show="fetchActiveComponentsBranchesAnalytics"/>
                    </div>
                    <div class="charts">
                        <div id="mostActiveVisHome"></div>
                    </div>
                </n-gi>
                <n-gi span="2"><n-divider /></n-gi>
                <n-gi>
                    <div class="charts">
                        <div id="analyticsMetricsVisHome"></div>
                    </div>
                </n-gi>
                <n-gi>
                    <div class="searchBlock">
                        <div class="searchUnit artifactSearch">
                            <n-tabs
                            class="card-tabs"
                            default-value="searchreleasesbytext"
                            size="large"
                            animated
                            style="margin: 0 -4px"
                            pane-style="padding-left: 4px; padding-right: 4px; box-sizing: border-box;"
                            >
                                <n-tab-pane name="searchreleasesbytext" tab="Search Releases by Version, Digest, Commit">
                                    <h5>Search For Releases By Digest, Version, Commit, Git Tag</h5>
                                    <n-form
                                        inline
                                        @submit="searchHashVersion">
                                        <n-input-group>
                                            <n-input
                                                placeholder="Search by digest, version, commit, tag, build ID"
                                                v-model:value="hashSearchQuery"
                                            />
                                            <n-button
                                                variant="contained-text"
                                                attr-type="submit">
                                                Find
                                            </n-button>
                                        </n-input-group>
                                    </n-form>
                                </n-tab-pane>
                                <n-tab-pane name="searchreleasesbytags" tab="Search Releases by Tags">
                                    <h5>Search For Releases By Reliza Tags</h5>
                                    <n-form
                                        inline
                                        @submit="searchReleasesByTags">
                                        <n-input-group>
                                            <n-select :options="releaseTagKeys" v-model:value="releaseKeySearchObj.key">
                                            </n-select>
                                            <n-input
                                                placeholder="Release Tag Value (Optional)"
                                                v-model:value="releaseKeySearchObj.value"
                                            />
                                            <n-button
                                                variant="contained-text"
                                                attr-type="submit">
                                                Search
                                            </n-button>
                                        </n-input-group>
                                    </n-form>
                                </n-tab-pane>
                                <n-tab-pane v-if="false && installationType !== 'OSS'" name="searchinstancesbytags" tab="Search Instances by Tags">
                                    <h5 class="mt-4">Search For Instances By Properties</h5>
                                    <n-form
                                        inline
                                        @submit="searchInstProp"
                                        label-placement="left"
                                    >
                                        <n-input-group>
                                            <n-select :options="properties" v-model:value="instancePropsSearchObj.key">
                                            </n-select>
                                            <n-input
                                                placeholder="Search For Property Value"
                                                v-model:value="releaseKeySearchObj.value"
                                            />
                                            <n-button
                                                variant="contained-text"
                                                @click="searchInstProp">
                                                Search
                                            </n-button>
                                        </n-input-group>
                                    </n-form>
                                </n-tab-pane>
                                <n-tab-pane v-if="false && installationType !== 'OSS'" name="searchinstancechangesbydate" tab="Search Instance Changes by Date">
                                    <h5 class="mt-4">Search For Instance Changes By Date</h5>
                                    <n-form label-placement="left" inline>
                                        <n-input-group>
                                            <n-form-item label="From">
                                                <n-date-picker v-model:value="instanceChangeSearchObj.dateFrom" type="datetime" />
                                                
                                            </n-form-item>
                                        </n-input-group>
                                        <n-input-group>
                                            <n-form-item label="To">
                                                <n-date-picker v-model:value="instanceChangeSearchObj.dateTo" type="datetime" />
                                            
                                            </n-form-item>
                                        </n-input-group>
                                        <n-button
                                                variant="contained-text"
                                                @click="searchInstanceChanges">
                                                Search
                                            </n-button>
                                    </n-form>
                                </n-tab-pane>
                            </n-tabs>
                        </div>

                        <n-modal
                            v-model:show="showSearchResultsModal"
                            preset="dialog"
                            :show-icon="false"
                            style="width: 90%"
                        >
                            <div class="searchResults" v-if="hashSearchResults.commitReleases && hashSearchResults.commitReleases.length">
                                <h4>Releases:</h4>
                                <n-data-table
                                    :data="hashSearchResults.commitReleases"
                                    :columns="releaseSearchResultRows"
                                    :pagination="pagination"
                                />

                                <div v-if="releaseInstances && releaseInstances.length">
                                    <h4>Deployed on Instances:</h4>
                                    <ul>
                                        <li v-for="id in releaseInstances" :key="id.uuid">
                                            <router-link :to="{ name: 'Instance', params: {orguuid: myorg.uuid, instuuid: id.uuid }}">{{ id.uri }}</router-link>
                                        </li>
                                    </ul>
                                </div>
                            </div>
                            <div v-else>No results.</div>
                        </n-modal>
                        <n-modal
                                v-model:show="showInstPropSearchResultsModal"
                                preset="dialog"
                                :show-icon="false"
                                style="width: 90%"
                                title="Search for Instances By Properties"
                                :hide-footer="true"
                                >
                                <n-form
                                    inline
                                    @submit="searchInstProp"
                                    label-placement="left"
                                >
                                    <n-input-group>
                                        <n-select :options="properties" v-model:value="instancePropsSearchObj.key">
                                        </n-select>
                                        <n-input
                                            placeholder="Search For Property Value"
                                            v-model:value="releaseKeySearchObj.value"
                                        />
                                        <n-button
                                            variant="contained-text"
                                            @click="searchInstProp">
                                            Search
                                        </n-button>
                                    </n-input-group>
                                </n-form>
                                <div class="searchResults">
                                    <h5>Search Results:</h5>
                                    <div v-if="instPropsSearchResults.length">
                                        <ul>
                                            <li v-for="inst in instPropsSearchResults" :key="inst.uuid">
                                                <router-link :to="{ name: 'Instance', params: {orguuid: myorg.uuid, instuuid: inst.uuid }}">{{ inst.uri }}</router-link> - {{ inst.environment }}
                                            </li>
                                        </ul>
                                    </div>
                                    <div v-else>No results.</div>
                                </div>
                        </n-modal>
                        <n-modal
                            v-model:show="showInstChangeSearchResultsModal"
                            preset="dialog"
                            :show-icon="false"
                            style="width: 90%"
                            title="Search For Instance Changes By Date"
                            :hide-footer="true"
                            >
                            <n-form label-placement="left" inline>
                                <n-input-group>
                                    <n-form-item label="From">
                                        <n-date-picker v-model:value="instanceChangeSearchObj.dateFrom" type="datetime" />
                                        
                                    </n-form-item>
                                </n-input-group>
                                <n-input-group>
                                    <n-form-item label="To">
                                        <n-date-picker v-model:value="instanceChangeSearchObj.dateTo" type="datetime" />
                                        
                                    </n-form-item>
                                </n-input-group>
                                <n-button
                                        variant="contained-text"
                                        @click="searchInstanceChanges">
                                        Search
                                    </n-button>
                            </n-form>
                            <div v-if="instChangeSearchResults.length" class="instanceChangeSearchResults">
                                <h5>Search Results:</h5>
                                <div v-for="ir in instChangeSearchResults" :key="ir.instanceData.uuid">
                                    <h5><router-link :to="{ name: 'Instance', params: {orguuid: myorg.uuid, instuuid: ir.instanceData.uuid }}">{{ ir.instanceData.uri }}:</router-link></h5>
                                    <instance-history
                                        :instanceUuid="ir.instanceData.uuid"
                                        :history="ir.instanceHistory"
                                        :orgProp="myorg.uuid"
                                    />
                                </div>
                            </div>
                            <div v-else>No results.</div>
                        </n-modal>
                    </div>
                </n-gi>
                <n-gi>
                </n-gi>
            </n-grid>

        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'AppHome'
}
</script>
<script lang="ts" setup>
import { NTabs, NTabPane, NInputGroup, NInput, NInputNumber, NButton, NDropdown, NForm, NModal, NDataTable, NSelect, NFormItem, NDatePicker, NTooltip, NIcon, NGrid, NGi, NDivider } from 'naive-ui'
import { useStore } from 'vuex'
import { ComputedRef, h, computed, ref, Ref, onMounted, watch, toRaw } from 'vue'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import GqlQueries from '../utils/graphqlQueries'
import InstanceHistory from './InstanceHistory.vue'
import { RouterLink } from 'vue-router'
import axios from '../utils/axios'
import { Commit } from '@vicons/carbon'
import { AspectRatio, Box, Eye} from '@vicons/tabler'
import { CaretDownFilled } from '@vicons/antd'
import { Icon } from '@vicons/utils'
import { useRouter } from 'vue-router'
import * as vegaEmbed from 'vega-embed'

const store = useStore()
const router = useRouter()

const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)
const installationType: ComputedRef<any> = computed((): any => store.getters.myuser.installationType)

const hashSearchQuery = ref('')

onMounted(() => {
    if (myorg.value) 
        initLoad()
})
watch(myorg, (currentValue, oldValue) => {
    activeComponentsInput.value.organization = myorg.value.uuid
    initLoad()
});

const releaseTagKeys: Ref<any[]> = ref([])
const releaseKeySearchObj: Ref<any> = ref({
    value: '',
    key: ''
})

const fetchReleaseKeys = async function (orgUuid: string) {
    const response = await graphqlClient.query({
        query: gql`
            query releaseTagKeys($orgId: ID!) {
                releaseTagKeys(orgUuid: $orgId)
            }`,
        variables: { orgId: orgUuid }
    })
    releaseTagKeys.value = [{value: '', label: 'Select Tag Key', disabled: true}, ...(response.data.releaseTagKeys.map((tag: string) => {return {'label': tag, 'value': tag}}))]
}
const instances: Ref<any[]> = ref([])

const initLoad = async function () {
    instances.value = []
    activeComponentsInputDate.value = new Date()
    activeComponentsInputDate.value.setDate(activeComponentsInputDate.value.getDate() - 30)
    activeComponentsInput.value.cutOffDate = activeComponentsInputDate.value.getTime()
    fetchReleaseKeys(myorg.value.uuid)
    fetchActiveComponentsBranchesAnalytics()
    fetchReleaseAnalytics()
    fetchVulnerabilityViolationAnalytics()
}

const hashSearchResults : Ref<any> = ref({})
const releaseInstances : Ref<any[]> = ref([])
const showSearchResultsModal : Ref<boolean> = ref(false)

const executeGqlSearchHashVersion = async function (params : any) {
    const response = await graphqlClient.query({
        query: gql`
            query searchDigestVersion($orgUuid: ID!, $query: String!) {
                searchDigestVersion(orgUuid: $orgUuid, query: $query) {
                    commitReleases {
                        ${GqlQueries.MultiReleaseGqlData}
                    }
                }
            }`,
        variables: { orgUuid: params.org, query: params.query },
        fetchPolicy: 'no-cache'
    })
    return response.data.searchDigestVersion
}

async function searchHashVersion (e: Event) {
    e.preventDefault()
    const params = {
        org: myorg.value.uuid,
        query: hashSearchQuery.value
    }
    hashSearchResults.value = await executeGqlSearchHashVersion(params)
    showSearchResultsModal.value = true
}

const instancePropsSearchObj: Ref<any> = ref({
    value: '',
    key: ''
})
const properties: ComputedRef<any> = computed((): any => {
    let props = []
    if (myorg.value && myorg.value.uuid) {
        let storeProps = store.getters.propertiesOfOrg(myorg.value.uuid)
        props = storeProps.map((prop: any) => {
            let propObj = {
                label: prop.name,
                value: prop.uuid
            }
            return propObj
        })
        props.push({
            label: 'Select Property Key (Optional)',
            value: ''
        })
    }
    return props
})

async function searchReleasesByTags (e: Event) {
    e.preventDefault()
    const cleanedSearchObj = {
        org: myorg.value.uuid,
        tagKey: releaseKeySearchObj.value.key,
        tagValue: releaseKeySearchObj.value.value
    }
    const releases = await store.dispatch('searchReleasesByTags', cleanedSearchObj)
    hashSearchResults.value = {commitReleases: releases, releaseInstances: []}
    showSearchResultsModal.value = true
}
const instPropsSearchResults: Ref<any[]> = ref([])
const showInstPropSearchResultsModal: Ref<boolean> = ref(false)
const searchInstProp = async function (e: Event) {
    e.preventDefault()
    let cleanedSearchObj = {
        org: myorg.value.uuid,
        query: instancePropsSearchObj.value.value,
        property: instancePropsSearchObj.value.key
    }
    axios.post('/api/manual/v1/instance/searchInstancesByPropVal', cleanedSearchObj).then(response => {
        instPropsSearchResults.value = response.data
        showInstPropSearchResultsModal.value = true
    })
}
const instanceChangeSearchObj: Ref<any> = ref({
    dateFrom: (new Date()).getTime(),
    dateTo: (new Date()).getTime(),
})
const instChangeSearchResults: Ref<any[]> = ref([])
const showInstChangeSearchResultsModal: Ref<boolean> = ref(false)
const searchInstanceChanges = function(e: Event) {
    e.preventDefault()
    let cleanedSearchObj = {
        dateFrom: new Date(instanceChangeSearchObj.value.dateFrom).toISOString(),
        dateTo: new Date(instanceChangeSearchObj.value.dateTo).toISOString(),
        org: myorg.value.uuid
    }

    axios.post('/api/manual/v1/instance/searchChangesOverTime', cleanedSearchObj).then(response => {
        instChangeSearchResults.value = response.data
        showInstChangeSearchResultsModal.value=true
    })
}
const openRelease = function (uuid: string) {
    const routeData = router.resolve({ name: 'ReleaseView', params: { uuid: uuid } })
    window.open(routeData.href, '_blank')
}
const releaseSearchResultRows = [
    {
        key: 'component',
        title: 'Component / Product',
        render(row: any) {
            const routeName = row.componentDetails.type === 'COMPONENT' ? 'ComponentsOfOrg' : 'ProductsOfOrg'
            return h(RouterLink, 
                {to: {name: routeName,
                params: {orguuid: myorg.value.uuid, compuuid: row.componentDetails.uuid}},
                style: "text-decoration: none;"},
                () => row.componentDetails.name )
        }
    },
    {
        title: 'Type',
        key: 'type',
        render: (row: any) => row.componentDetails.type === 'PRODUCT' ? 'PRODUCT' : row.componentDetails.type 

    },
    {
        key: 'branch',
        title: 'Branch / Feature Set',
        render(row: any) {
            const routeName = row.componentDetails.type === 'COMPONENT' ? 'ComponentsOfOrg' : 'ProductsOfOrg'
            return h(RouterLink, 
                {to: {name: routeName,
                params: {orguuid: myorg.value.uuid, compuuid: row.componentDetails.uuid,
                    branchuuid: row.branchDetails.uuid
                }},
                style: "text-decoration: none;"},
                () => row.branchDetails.name )
        }
    },
    {
        key: 'version',
        title: 'Version',
        render(row: any) {
            return h(RouterLink, 
                {to: {name: 'ReleaseView', params: {uuid: row.uuid}},
                style: "text-decoration: none;"},
                () => row.version )
        }
    },
    {
        key: 'lifecycle',
        title: 'Lifecycle'
    },
    {
        title: 'Marketing Version',
        key: 'marketingVersion'
    }
]
const pagination = { pageSize: 10 }

const activeComponentsInputDate = ref(new Date())
const activeComponentsInput = ref({
    organization: myorg.value.uuid,
    cutOffDate: activeComponentsInputDate.value.getTime(),
    componentType: 'COMPONENT',
    maxComponents: 3
})

function parseActiveComponentsInput () {
    const parsedActiveComponentsInput = {
        organization: activeComponentsInput.value.organization,
        cutOffDate: new Date(activeComponentsInput.value.cutOffDate),
        componentType: activeComponentsInput.value.componentType,
        maxComponents: activeComponentsInput.value.maxComponents
    }
    if (activeComponentsInput.value.componentType === 'COMPONENT' || activeComponentsInput.value.componentType === 'BRANCH') {
        parsedActiveComponentsInput.componentType = 'COMPONENT'
    } else parsedActiveComponentsInput.componentType = 'PRODUCT'
    return parsedActiveComponentsInput
}

function embedActiveComponentsVega () {
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

async function fetchActiveComponentsBranchesAnalytics() {
    if (activeComponentsInput.value.componentType === 'COMPONENT' || activeComponentsInput.value.componentType === 'PRODUCT') {
        await fetchActiveComponentsAnalytics()
    } else {
        await fetchActiveBranchesAnalytics()
    }
}

function transformMostActiveDataBasedOnType () {
    if (activeComponentsInput.value.componentType === 'COMPONENT') {
        mostActiveOverTime.value.transform = [{
            calculate: "'/componentsOfOrg/" + myorg.value.uuid + "/' + datum.componentuuid", "as": "url"
        }]
        mostActiveOverTime.value.encoding.color.title = "Component"
        mostActiveOverTime.value.encoding.tooltip[0].title = "Component"
    } else if (activeComponentsInput.value.componentType === 'PRODUCT') {
        mostActiveOverTime.value.transform = [{
            calculate: "'/productsOfOrg/" + myorg.value.uuid + "/' + datum.componentuuid", "as": "url"
        }]
        mostActiveOverTime.value.encoding.color.title = "Product"
        mostActiveOverTime.value.encoding.tooltip[0].title = "Product"
    } else if (activeComponentsInput.value.componentType === 'BRANCH') {
        mostActiveOverTime.value.transform = [{
            calculate: "'/componentsOfOrg/" + myorg.value.uuid + "/' + datum.componentuuid + '/' + datum.branchuuid", "as": "url"
        }]
        mostActiveOverTime.value.encoding.color.title = "Branch"
        mostActiveOverTime.value.encoding.tooltip[0].title = "Branch"
    } else if (activeComponentsInput.value.componentType === 'FEATURE_SET') {
        mostActiveOverTime.value.transform = [{
            calculate: "'/productsOfOrg/" + myorg.value.uuid + "/' + datum.componentuuid + '/' + datum.branchuuid", "as": "url"
        }]
        mostActiveOverTime.value.encoding.color.title = "Feature Set"
        mostActiveOverTime.value.encoding.tooltip[0].title = "Feature Set"
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
        transformMostActiveDataBasedOnType ()
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
        transformMostActiveDataBasedOnType ()
        embedActiveComponentsVega()
    }
}

const releaseVisData: Ref<any> = ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    background: 'white',
    title: 'Number of Releases Created Per Day',
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

const analyticsMetrics: Ref<any> = ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    background: 'white',
    title: 'Vulnerabilities and Policy Violations Over Time',
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
        }
    }
})

const mostActiveOverTime: Ref<any> = ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    background: 'white',
    width: 'container',
    data: {
        values: []
    },
    mark: {
        type: "arc",
        innerRadius: 50
    },
    transform: [{
        calculate: "'/componentsOfOrg/" + myorg.value.uuid + "/' + datum.componentuuid", "as": "url"
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

async function fetchReleaseAnalytics() {
    const cutOffDate = new Date()
    cutOffDate.setDate(cutOffDate.getDate() - 60)
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
            orgUuid: myorg.value.uuid,
            cutOffDate
        },
        fetchPolicy: 'no-cache'
    })
    resp.data.releaseAnalytics.map((item: any) => {
        item.date = item.date.split('[')[0]
    })
    releaseVisData.value.data.values = resp.data.releaseAnalytics
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

async function fetchVulnerabilityViolationAnalytics() {
    const dateFrom = new Date()
    dateFrom.setDate(dateFrom.getDate() - 60)
    const dateTo = new Date()
    const resp = await graphqlClient.query({
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
            orgUuid: myorg.value.uuid,
            dateFrom,
            dateTo
        },
        fetchPolicy: 'no-cache'
    })
    resp.data.vulnerabilitiesViolationsOverTime.map((item: any) => {
        item.createdDate = item.createdDate.split('[')[0]
    })
    analyticsMetrics.value.data.values = resp.data.vulnerabilitiesViolationsOverTime
    vegaEmbed.default('#analyticsMetricsVisHome', toRaw(analyticsMetrics.value),
        {
            actions: {
                editor: false
            },
            theme: 'powerbi'
        }
    )
}

function displayActiveComponentType () {
    let displayComp
    switch (activeComponentsInput.value.componentType) {
        case 'BRANCH':
            displayComp = 'Branches'
            break
        case 'FEATURE_SET':
            displayComp = 'Feature Sets'
            break
        case 'PRODUCT':
            displayComp = 'Products'
            break
        default:
            displayComp = 'Components'
            break
    }
    return displayComp
}

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
.charts {
    display: grid;
}
</style>
