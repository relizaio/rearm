<template>
    <div>
        <div class="instanceDisplayWrapper">
            <div class="instanceListColumn">
                <h4 v-if="organization">Instances of {{organization.name}}</h4>
                <n-icon v-if="isWritableGeneral || isWritableSpawn" @click="showCreateInstanceModal = true" class="icons clickable" title="Add Instance" size="24"><CirclePlus /></n-icon>
                <n-popover trigger="hover" style="width: 300px;">
                    <template #trigger>
                        <n-icon :style="isFilterActivated ? 'color: red;' : ''" size="20" class="icons" title="Filter"><Filter /></n-icon>
                    </template>
                    <div>Environment: 
                        <n-select
                                v-model:value="filterValue.environment"
                                v-on:update:value="onFilterChange"
                                :options="environmentTypes" />
                    </div>
                    <div>Type:
                        <n-select
                                v-model:value="filterValue.type"
                                v-on:update:value="onFilterChange"
                                :options="instanceFilterTypes" />
                    </div>
                    <div>Created By:
                        <n-select
                                v-model:value="filterValue.createdBy"
                                v-on:update:value="onFilterChange"
                                :options="createdByTypes" />
                    </div>
                    <n-button type="warning" @click="resetFilter">Reset</n-button>
                </n-popover>
                <n-data-table
                    :columns="instanceFields"
                    :data="instances"
                    :pagination="instancePagination"
                    :row-class-name="rowClassName"
                    :row-props="rowProps"
                    size="small" />

                <h4 v-if="organization">Clusters of {{organization.name}}</h4>
                <n-icon v-if="isWritableGeneral || isWritableSpawn" @click="showCreateClusterModal = true" class="icons clickable" title="Add Cluster" size="24"><CirclePlus /></n-icon>
                <n-data-table
                    :columns="clusetrFields"
                    :data="clusters"
                    :row-props="rowProps"
                    :row-class-name="rowClassName"
                    size="small" />
            </div>
            <div class="instanceDetails">
                <instance-view v-if="selectedInstUuid && selectedInstance"
                    :childInstances="selectedInstanceChildren"
                    :instanceType="selectedInstance.instanceType"
                />
            </div>
        </div>
        <n-modal
                v-model:show="showCreateInstanceModal"
                preset="dialog"
                :show-icon="false"
                style="width: 90%;"
                title="Add New Instance"
            >
            <create-instance
                        class="addInstance"
                        v-if="orguuid"
                        :orgProp="orguuid"
                        :instanceType="InstanceType.STANDALONE_INSTANCE"
                        @instanceCreated="instCreated" />
        </n-modal>
        <n-modal
                v-model:show="showCreateClusterModal"
                preset="dialog"
                :show-icon="false"
                style="width: 90%;"
                title="Add New Cluster"
            >
            <create-instance
                        class="addInstance"
                        v-if="orguuid"
                        :orgProp="orguuid"
                        :instanceType="InstanceType.CLUSTER"
                        @instanceCreated="instCreated" />
        </n-modal>
    </div>
</template>

<script lang="ts">
export default {
    name: 'InstancesOfOrg'
}
</script>
<script lang="ts" setup>
import { ComputedRef, ref, Ref, computed, h, reactive } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NDataTable, NModal, NPopover, NTooltip, NSelect, NIcon, DataTableBaseColumn, DataTableColumns, useNotification, NotificationType } from 'naive-ui'
import { CirclePlus, Filter } from '@vicons/tabler'
import InstanceView from '@/components/InstanceView.vue'
import CreateInstance from '@/components/CreateInstance.vue'
import commonFunctions from '@/utils/commonFunctions'
import constants from '@/utils/constants'
import graphqlQueries from '../utils/graphqlQueries'
import graphqlClient from '../utils/graphql'

async function loadEnvironmentTypes (org: string) {
    const etypesResponse: any = await graphqlClient.query({
        query: graphqlQueries.EnvironmentTypesGql,
        variables: { orgUuid: org }
    })
    const anyArrEntry = [{label: 'Any', value: ''}]
    const nonAnyETypes = etypesResponse.data.environmentTypes
        .map((x: string) => {return {label: x, value: x}})
    return anyArrEntry.concat(nonAnyETypes)
}

const route = useRoute()
const router = useRouter()
const store = useStore()
const notification = useNotification()
const InstanceType = constants.InstanceType

const selectedInstUuid = ref(route.params.instuuid ? route.params.instuuid.toString() : '')

const orguuid : Ref<string> = ref('')
if (route.params.orguuid) {
    orguuid.value = route.params.orguuid.toString()
} else {
    const storemyorgid = store.getters.myorg
    orguuid.value = storemyorgid.value
}

const myUser = store.getters.myuser

const users: ComputedRef<any[]> = computed((): any => store.getters.allUsers)

const showCreateInstanceModal = ref(false)
const showCreateClusterModal = ref(false)
const isFilterActivated = ref(false)

const isWritableGeneral : boolean = commonFunctions.isWritable(orguuid.value, myUser, 'ORGANIZATION')
const isWritableSpawn : boolean = commonFunctions.isWritable(orguuid.value, myUser, 'INSTANCE', constants.SpawnInstancePermissionId)

const organization: ComputedRef<any> = computed((): any => store.getters.orgById(orguuid.value))
const environmentTypes: Ref<any[]> = ref([])

const instanceFilterTypes = [
    {label: 'Any', value: ''},
    {label: 'Persistent', value: 'MANUAL'},
    {label: 'Ephemeral', value: 'RELIZA_AUTO'}
]

const createdByTypes = [
    {label: 'Anyone', value: ''},
    {label: 'Me', value: myUser.uuid}
]

const filterValue: Ref<any> = ref({
    environment: '',
    type: '',
    createdBy: ''
})

const uriField = reactive<DataTableBaseColumn<any>>({
    key: 'uri',
    title: 'URI',
    render (row: any) {
        // console.log(row)
        return h(
            NTooltip,
            {
                trigger: 'hover'
            }, 
            {
                trigger: () => row.uri,
                default: () => 'Environment: ' + row.environment + ', type: ' + ((row.spawnType !== 'MANUAL') ? 'Ephemeral' : 'Persistent')
            }
        )
    },
    filter (value: any, row: any) {
        let retVal = true
        if (value.environment) {
            retVal = row.environment === value.environment
        }
        if (retVal && value.type) {
            retVal = row.spawnType === value.type
        }
        return retVal
    }
})

const rowClassName = (row: any) => {
    if(selectedInstUuid.value === row.uuid)
        return 'selectedRow'
    else
        return ''
}

const instanceFields: DataTableColumns<any> = [
    uriField,
    {
        key: 'cluster',
        title: 'Cluster',
        render: (row: any) => {
            let clustername = ""
            if(row.instanceType === InstanceType.CLUSTER_INSTANCE){
                let cluster = findInstanceCluster(row.uuid)
                if(cluster && cluster.uuid && cluster.uuid.length){
                    clustername = cluster.name
                }
            }
            return clustername
        }
    },
    {
        key: 'namespace',
        title: 'ns',
    },
]

const clusetrFields = [
    {
        key: 'name',
        title: 'Name',
    }
]


const rowProps = (row: any) => {
    return {
        style: 'cursor: pointer;',
        onClick: () => {
            selectInstance(row.uuid)
        }
    }
}

const findInstanceCluster = (instanceId: string) => {
    let storeInstances = store.getters.instancesOfOrg(orguuid.value)
    if (storeInstances && storeInstances.length) {
        return storeInstances.find((x: any) => x.revision === -1 && x.instanceType === InstanceType.CLUSTER && x.instances.includes(instanceId))
    }
    return null
}

const instancePagination = store.getters.instancePagination

const instances: ComputedRef<any> = computed((): any => {
    let storeInstances = store.getters.instancesOfOrg(orguuid.value)
    if (storeInstances && storeInstances.length) {
        storeInstances = storeInstances.filter((x: any) => x.revision === -1 && (x.instanceType === InstanceType.STANDALONE_INSTANCE || x.instanceType === InstanceType.CLUSTER_INSTANCE))
        // sort - TODO make sort configurable
        storeInstances.sort((a: any, b: any) => {
            if (a.uri.toLowerCase() < b.uri.toLowerCase()) {
                return -1
            } else if (a.uri.toLowerCase() > b.uri.toLowerCase()) {
                return 1
            } else {
                return 0
            }
        })
    }
    return storeInstances
})

const clusters: ComputedRef<any> = computed((): any => {
    let storeInstances = store.getters.instancesOfOrg(orguuid.value)
    if (storeInstances && storeInstances.length) {
        storeInstances = storeInstances.filter((x: any) => x.revision === -1 && x.instanceType === InstanceType.CLUSTER)
        // sort - TODO make sort configurable
        storeInstances.sort((a: any, b: any) => {
            if (a.name.toLowerCase() < b.name.toLowerCase()) {
                return -1
            } else if (a.name.toLowerCase() > b.name.toLowerCase()) {
                return 1
            } else {
                return 0
            }
        })
    }
    return storeInstances
})

const selectedInstance: ComputedRef<any> = computed((): any => { 
    const storeInstances = store.getters.instancesOfOrg(orguuid.value)
    return storeInstances.find((x: any) => x.uuid === selectedInstUuid.value)
})

const selectedInstanceChildren: ComputedRef<any[]> = computed((): any => {
    const storeInstances = store.getters.instancesOfOrg(orguuid.value)
    let instanceChildren: any[] = []
    
    if(selectedInstance.value && selectedInstance.value.instanceType === InstanceType.CLUSTER && selectedInstance.value.instances.length){
        instanceChildren = storeInstances
            .filter((x: any) => selectedInstance.value.instances.includes(x.uuid))
            .sort((a: { uuid: any }, b: { uuid: any }) => selectedInstance.value.instances.indexOf(a.uuid) - selectedInstance.value.instances.indexOf(b.uuid));
    }
    return instanceChildren
})

const resetFilter = function () {
    filterValue.value = {
        environment: '',
        type: '',
        createdBy: ''
    }
    onFilterChange ()
}

const notify = async function (type: NotificationType, title: string, content: string) {
    notification[type]({
        content: content,
        meta: title,
        duration: 3500,
        keepAliveOnHover: true
    })
}

const displayUser = function (userId: string) {
    let displayRes = userId
    let user = users.value.find(u => (u.uuid === userId))
    if (user) {
        displayRes = user.name
    }
    return displayRes
}

const onFilterChange = function () {
    uriField.filterOptionValue = filterValue.value
    window.localStorage.setItem('relizaInstancesOfOrgFilter' + orguuid.value, JSON.stringify(filterValue.value))
    isFilterActivated.value = (filterValue.value.environment || filterValue.value.type || filterValue.value.createdBy)
}

const selectInstance = function (uuid: string) {
    router.push({
        name: 'Instance',
        params: {
            orguuid: orguuid.value,
            instuuid: uuid
        }
    })
}

const instCreated = async function (inst: any, instanceType: String) {
    showCreateInstanceModal.value = false
    showCreateClusterModal.value = false
    if(instanceType === InstanceType.CLUSTER)
        notify('info', 'Created', `Cluster ${inst.name} created`)
    else
        notify('info', 'Created', `Instance ${inst.uri} created`)

    await onCreate()
}

const onCreate = async function () {
    
    let storeInst = await store.dispatch('fetchInstances', orguuid.value)
    if (storeInst.length) {
        storeInst = storeInst.sort((a: any, b: any) => {
            if (a.uri.toLowerCase() < b.uri.toLowerCase()) {
                return -1
            } else if (a.uri.toLowerCase() > b.uri.toLowerCase()) {
                return 1
            } else {
                return 0
            }
        })
        if (typeof (route.params.instuuid) === 'undefined') {
            selectInstance(storeInst[0].uuid)
        }
    }
    
    if (!environmentTypes.value || environmentTypes.value.length < 1) {
        environmentTypes.value = await loadEnvironmentTypes (orguuid.value)
    }

    if (!users.value || users.value.length < 1) {
        store.dispatch('fetchUsers', orguuid.value)
    }

    const storedFilterVal = window.localStorage.getItem('relizaInstancesOfOrgFilter' + orguuid.value)
    if (storedFilterVal) {
        filterValue.value = JSON.parse(storedFilterVal)
        onFilterChange()
    }
}
await onCreate()

</script>

<style scoped lang="scss">
.instanceDisplayWrapper {
    display: grid;
    grid-template-columns: 270px 1fr;
    grid-gap: 3px;
}

.instanceList {
    display: grid;
    grid-template-columns: 1fr 123px;
    border-radius: 9px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
}

.instanceListComparison {
    display: grid;
    grid-template-columns: 24px 1fr 123px;
    border-radius: 9px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
}

.instanceList:hover, .instanceListComparison:hover {
    background-color: #d9eef3;
}
.instanceHeader {
    background-color: #f9dddd;
    font-weight: bold;
}
.instanceSideBySide {
    display: grid;
    grid-template-columns: 1fr 1fr;
}
.selectedInstance {
    background-color: #c4c4c4;
}
:deep(.selectedRow td){
    background-color: #f1f1f1 !important;
}
</style>
