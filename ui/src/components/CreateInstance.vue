<template>
    <div class="createInstanceGlobal">
        <n-tabs
            class="card-tabs"
            :default-value="isWritableGeneral ? 'createmanual' : 'createauto'"
            size="large"
            animated
            style="margin: 0 -4px"
            pane-style="padding-left: 4px; padding-right: 4px; box-sizing: border-box;"
        >
            <n-tab-pane name="createmanual" tab="Manual">
                <n-form
                    ref="createInstanceForm"
                    :model="manualInstance"
                    :rules="manualInstanceRules">
                    <n-form-item   v-if="instanceTypeProp != InstanceType.CLUSTER"  
                                    path="uri"
                                    label="URI">
                        <n-input
                                    v-model:value="manualInstance.uri"
                                    required
                                    placeholder="Enter URI without http or https" />
                    </n-form-item>
                    <n-form-item  v-if="instanceTypeProp != InstanceType.CLUSTER"  
                                    path="environment"
                                    label="Select Environment or Type to Add New Environment">
                        <n-select
                                    v-model:value="manualInstance.environment"
                                    filterable
                                    tag
                                    :options="envValues" />
                    </n-form-item>
                    <n-form-item   v-if="instanceTypeProp === InstanceType.CLUSTER"  
                                    path="name"
                                    label="Name">
                        <n-input
                                    v-model:value="manualInstance.name"
                                    required
                                    placeholder="Enter a name for the cluster" />
                    </n-form-item>
                    <n-form-item   v-if="instanceTypeProp === InstanceType.CLUSTER_INSTANCE"  
                                    path="namespace"
                                    label="Namespace">
                        <n-input
                                    v-model:value="manualInstance.namespace"
                                    required
                                    placeholder="Reserve a namespace for the instacnce" />
                    </n-form-item>
                    <n-button type="success" @click="onSubmitManual">Submit</n-button>
                    <n-button type="warning" @click="onResetManual">Reset</n-button>
                </n-form>
            </n-tab-pane>
            <n-tab-pane name="createauto" tab="Auto Ephemeral" v-if="instanceTypeProp === InstanceType.STANDALONE_INSTANCE">
                <create-release
                                v-if="!autoInstance.productReleaseUuid"     
                                :orgProp="props.orgProp"
                                inputType="PRODUCT"
                                :attemptPickRelease="true"
                                :disallowPlaceholder="true"
                                :disallowCreateRelease="true"
                                createButtonText="Pick Release for Ephemeral Instance"
                                @createdRelease="onReleaseSetAuto" />
                <div v-if="autoInstance.productReleaseUuid">
                    <h5>Your Ephemeral will be created using the following product:</h5>
                    <release-el
                                style="margin-bottom: 20px;"
                                :uuid="autoInstance.productReleaseUuid"
                                :org="props.orgProp"
                                :updatable="false"
                                :deleteable="false" />
                </div>


                <n-form 
                    v-if="autoInstance.productReleaseUuid"
                    ref="createInstanceAutoForm"
                    :model="autoInstance"
                    :rules="autoInstanceRules"
                >
                    <n-form-item
                            path="valuesFile"
                            label="Name of the values yaml file to use">
                        <n-input
                            v-model:value="autoInstance.valuesFile"
                            required
                            placeholder="Values yaml file to use"
                            />
                    </n-form-item>
                    <n-form-item
                            path="timeBeforeAutoDestroy"
                            label="Number of hours after which the ephemeral will be auto-destroyed">
                        <n-slider
                            v-model:value="autoInstance.timeBeforeAutoDestroy"
                            :min="1"
                            :max="168"
                            :default-value="24"
                             />
                        <n-input-number
                            v-model:value="autoInstance.timeBeforeAutoDestroy"
                            size="small"
                            :min="1"
                            :max="168"
                            :default-value="24"
                            />
                    </n-form-item>
                    <n-button type="success" @click="onSubmitAuto">Submit</n-button>
                    <n-button type="warning" @click="onResetAuto">Reset</n-button>
                </n-form>
            </n-tab-pane>
        </n-tabs>
    </div>
</template>

<script lang="ts">
export default {
    name: 'CreateInstance'
}
</script>
<script lang="ts" setup>
import { ref, Ref } from 'vue'
import { useStore } from 'vuex'
import { FormInst, NForm, NFormItem, NInput, NInputNumber, NButton, NSelect, NTabs, NTabPane, NSlider } from 'naive-ui'
import CreateRelease from '@/components/CreateRelease.vue'
import ReleaseEl from '@/components/ReleaseEl.vue'
import commonFunctions from '@/utils/commonFunctions'
import axios from '../utils/axios'
import Swal from 'sweetalert2'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import constants from '../utils/constants'

const props = defineProps<{
    orgProp: String,
    instanceType: String,
    clusterId: String
}>()

const emit = defineEmits(['instanceCreated','instanceFailed'])


const store = useStore()

const createInstanceForm = ref<FormInst | null>(null)
const createInstanceAutoForm = ref<FormInst | null>(null)

const myUser = store.getters.myuser
let isWritableGeneral : boolean = commonFunctions.isWritable(props.orgProp, myUser, 'ORGANIZATION')
if(!isWritableGeneral && constants.InstanceType.CLUSTER_INSTANCE === props.instanceType && props.clusterId && props.clusterId !== ''){
    isWritableGeneral = commonFunctions.isWritable(props.orgProp, myUser, 'INSTANCE', props.clusterId)
}
const instanceTypeProp = ref(props.instanceType)
const manualInstance = ref({
    org: props.orgProp,
    uri: '',
    environment: '',
    name: null,
    namespace: null,
    instanceType: instanceTypeProp.value,
    clusterId: props.clusterId
})
const autoInstance = ref({
    org: props.orgProp,
    productReleaseUuid: '',
    valuesFile: '',
    namespace: 'rhapp',
    timeBeforeAutoDestroy: 24
})

const envValues: Ref<any[]> = ref([])
let manualInstanceRules = ref({})
let createTitle = ""
switch (instanceTypeProp.value) {
case constants.InstanceType.STANDALONE_INSTANCE:
    manualInstanceRules = ref({
        uri: {
            required: true,
            message: 'URI is required'
        },
        environment: {
            required: true,
            message: 'Environment is required'
        }
    })
    createTitle = "Create Instance"
    break;
case constants.InstanceType.CLUSTER:
    manualInstanceRules = ref({
        name: {
            required: true,
            message: 'Name is required'
        }
    })
    createTitle = "Create Cluster"
    break;
case constants.InstanceType.CLUSTER_INSTANCE:
    manualInstanceRules = ref({
        uri: {
            required: true,
            message: 'URI is required'
        },
        namespace: {
            required: true,
            message: 'Namespace is required'
        },
        environment: {
            required: true,
            message: 'Environment is required'
        }
    })
    createTitle = "Create a new instance in the cluster"
    break;
default:
    console.error("Instance Type must be specified!")
}


const autoInstanceRules = {
    valuesFile: {
        required: true,
        message: 'Values file is required'
    }
}

const onReleaseSetAuto = async function (rlz: any) {
    // resolve default config values from component
    const gqlConfigResp = await graphqlClient.query({
        query: gql`
                query defaultConfigurationForEphemeral {
                    defaultConfigurationForEphemeral(releaseUuid: "${rlz.uuid}")
                }`
    })

    if (gqlConfigResp && gqlConfigResp.data) {
        autoInstance.value.valuesFile = gqlConfigResp.data.defaultConfigurationForEphemeral
    }
    autoInstance.value.productReleaseUuid = rlz.uuid
}

const onResetManual = function () {
    manualInstance.value = {
        org: props.orgProp,
        uri: '',
        environment: '',
        name: null,
        namespace: null,
        instanceType: props.instanceType,
        clusterId: props.clusterId
    }
}

const onSubmitManual = async function () {
    createInstanceForm.value?.validate(async (errors) => {
        if (!errors) {
            const inst = await createInstanceFromDto(manualInstance.value)
            emit('instanceCreated', inst, instanceTypeProp.value)
            onResetManual()
        }
    })
}

const createInstanceFromDto =  async function (instanceProps: any) {
    try{
        const data = await graphqlClient.mutate({
            mutation: gql`
                mutation createInstanceFromDto($inst: CreateInstanceInput) {
                    createInstanceFromDto(instance: $inst) {
                        uuid
                        uri
                        name
                    }
                }`,
            variables: {
                inst: instanceProps   
            }
        })
        return data.data.createInstanceFromDto
    }   catch (err: any) {
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    }
}

const onResetAuto = function () {
    autoInstance.value = {
        org: props.orgProp,
        productReleaseUuid: '',
        valuesFile: '',
        namespace: 'rhapp',
        timeBeforeAutoDestroy: 24
    }
}

const onSubmitAuto = async function () {
    createInstanceAutoForm.value?.validate(async (errors) => {
        if (!errors) {
            try {
                const autoInstanceSubmission = commonFunctions.deepCopy(autoInstance.value)
                autoInstanceSubmission.timeBeforeAutoDestroy = autoInstanceSubmission.timeBeforeAutoDestroy * 60 * 60 * 1000
                const inst = await store.dispatch('spawnInstance', autoInstanceSubmission)
                emit('instanceCreated', inst, instanceTypeProp.value)
                onResetAuto()
            } catch (error: any) {
                Swal.fire(
                    'Error!',
                    commonFunctions.parseGraphQLError(error.message),
                    'error'
                )
            }
        }
    })
}

const onCreate = async function () {
    const axiosResp = await axios.get('/api/manual/v1/instance/environmentTypes/' + props.orgProp)
    axiosResp.data.forEach((el: string) => {
        let retObj = {
            label: el,
            value: el
        }
        envValues.value.push(retObj)
    })
}

const InstanceType = constants.InstanceType

onCreate()
</script>

<style scoped lang="scss">
.createInstanceGlobal {
    margin-left: 20px;
}
</style>