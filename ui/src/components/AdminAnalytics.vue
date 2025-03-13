<template>
    <div class="home">
        <h5>Analytics</h5>
        <div class="charts">
            <div id="orgsCreatedVis"></div>
            <div id="userRegsVis"></div>
        </div>
        <n-input-group>
            <label for="datepicker-admin-analytics">Org Created From: </label>
            <n-date-picker id="datepicker-admin-analytics" @update:value="updateAnalytics" v-model:value="dateFrom" />
            <label for="num-releases-cut-off">Min Num Releases: </label>
            <n-input id="num-releases-cut-off" @update:value="updateAnalytics" v-model:value="numReleases"/>
            <n-button type="success" @click="updateAnalytics">Update</n-button>
        </n-input-group>
        <n-data-table ref="analyticsTable" :columns="orgWithReleasesFields" :data="orgsWithReleases" />
        <div>
            <h3>Register OSS Helm Chart</h3>
            <n-form>
                <n-input
                        v-model:value="hpi.uri"
                        required
                        placeholder="Enter Helm uri" />
                <n-input
                        v-model:value="hpi.version"
                        required
                        placeholder="Enter Helm version" />
                <n-button type="success" @click="onSubmitHelm">Submit</n-button>
                <n-button type="warning" @reset="onResetHelm">Reset</n-button>
            </n-form>
        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'AdminAnalytics'
}
</script>
<script lang="ts" setup>
import { Ref, ref, h, reactive } from 'vue'
import { useStore } from 'vuex'
import { NDataTable, NDatePicker, NForm, NButton, NInput, NInputGroup, useNotification, NotificationType, DataTableColumns, DataTableBaseColumn } from 'naive-ui'
import axios from '../utils/axios'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import * as vegaEmbed from 'vega-embed'

// const props = defineProps<{
//     orgProp: String,
//     instanceUuid: String,
//     productUuid: String,
//     namespace: String,
// }>()
// const emit = defineEmits(['addedComponentBranch'])

const store = useStore()
const notification = useNotification()

const notify = async function (type: NotificationType, title: string, content: string) {
    notification[type]({
        content: content,
        meta: title,
        duration: 3500,
        keepAliveOnHover: true
    })
}

const dateFrom = ref((new Date()).getTime())

const numReleases = ref('20')

const oldNumReleases = ref('-1')

const hpi = ref({
    uri: '',
    version: ''
})

const orgsWithReleases: Ref<any[]> = ref([])

const dateField = reactive<DataTableBaseColumn<any>>({
    key: 'date',
    title: 'Created',
    render: (row: any) => {
        return h('div', {}, getDateForOrg(row.org))
    },
    defaultFilterOptionValues: [dateFrom.value],
    filter (value: any, row: any) {
        let retVal = false
        if (row.org) {
            retVal = new Date(getDateForOrg(row.org)) > new Date(value)
        }
        return retVal
    }
})

const orgWithReleasesFields: DataTableColumns<any> = [
    {
        key: 'name',
        title: 'Name',
        render: (row: any) => {
            return h('div', {}, getNameForOrg(row.org))
        }
    },
    {
        key: 'org',
        title: 'UUID'
    },
    dateField,
    {
        key: 'email',
        title: 'Email'
    },
    {
        key: 'marketing',
        title: 'Marketing'
    },
    {
        key: 'num_releases',
        title: 'Number of Releases',
        defaultSortOrder: 'descend',
        sorter: 'default'
    }
]

const orgVisData = ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    background: '#f7f7f7',
    title: 'Number of Orgs Created Per Day',
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
                title: '# of orgs created'
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

const userVisData = ref({
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    background: '#f7f7f7',
    title: 'Number of Users Registered Per Day',
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
                title: '# of registrations'
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

const getNameForOrg = function (uuid: string) {
    let orgName = 'not found'
    const org = store.getters.orgById(uuid)
    if (org) {
        orgName = org.name
    } else {
        console.error("Not found org for uuid = " + uuid)
    }
    return orgName
}

const getDateForOrg = function (uuid: string) {
    let orgDate = ''
    const org = store.getters.orgById(uuid)
    if (org) {
        orgDate = org.createdDate
    } else {
        console.error('Not found date for org = ' + uuid)
    }
    return orgDate
}

const onResetHelm = function () {
    hpi.value = {
        uri: '',
        version: ''
    }
}

const onSubmitHelm = async function () {
    await graphqlClient.query({
        query: gql`
            mutation registerHelmProduct($uri: String!, $version: String!) {
                registerHelmProduct(uri: $uri, version: $version)
            }`,
        variables: hpi.value,
        fetchPolicy: 'no-cache'
    })
    notify('info', 'Submitted', 'Helm Chart Sent for processing')
}

const fetchOrgsCreatedAnalytics = async function () {
    const axiosResp = await axios.get('/api/manual/v1/admin/orgsCreatedOverTime')
    axiosResp.data.map((item: any) => {
        item.date = item.date.split('[')[0]
    })
    orgVisData.value.data.values = axiosResp.data
    const orgVisEmbedData: any = orgVisData.value
    vegaEmbed.default('#orgsCreatedVis', orgVisEmbedData)
}

const fetchUserRegsAnalytics = async function () {
    const axiosResp = await axios.get('/api/manual/v1/admin/registrationsOverTime')
    axiosResp.data.map((item: any) => {
        item.date = item.date.split('[')[0]
    })
    userVisData.value.data.values = axiosResp.data
    const userVisEmbedData: any = userVisData.value
    vegaEmbed.default('#userRegsVis', userVisEmbedData)
}

const fetchOrgsByNumReleases = async function (numReleases: string) {
    if (numReleases !== oldNumReleases.value) {
        const axiosResp = await axios.get('/api/manual/v1/admin/orgsByNumReleases/' + numReleases)
        orgsWithReleases.value = axiosResp.data
        oldNumReleases.value = numReleases
    }
}

const updateAnalytics = async function () {
    await fetchOrgsByNumReleases(numReleases.value)
    dateField.filterOptionValue = dateFrom.value
}

const onCreated = async function () {
    await store.dispatch('fetchOrganizations')
    fetchOrgsCreatedAnalytics()
    fetchUserRegsAnalytics()
    fetchOrgsByNumReleases(numReleases.value)
}

onCreated()


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