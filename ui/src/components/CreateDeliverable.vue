<template>
    <div class="createDeliverable">
        <div>Create Deliverable</div>
        <n-form ref="createDeliverableForm" :model="deliverable" :rules="rules">
            <n-form-item
                        path="displayIdentifier"
                        label="Display Identifier">
                <n-input
                        v-model:value="deliverable.displayIdentifier"
                        required
                        placeholder="Enter deliverable identifier, i.e. this can be its URI" />
            </n-form-item>
            <n-form-item
                        path="publisher"
                        label="Publisher">
                <n-input
                        v-model:value="deliverable.publisher"
                        placeholder="Enter publisher, i.e. organization name" />
            </n-form-item>
            <n-form-item
                        path="group"
                        label="Group">
                <n-input
                        v-model:value="deliverable.group"
                        placeholder="Enter deliverable group if exists, i.e. Maven Group ID" />
            </n-form-item>
            <n-form-item 
                        path="type"
                        label="Deliverable CycloneDX Type">
                <n-select
                        v-model:value="deliverable.type"
                        :options="constants.CdxTypes" />
            </n-form-item>
            <n-form-item 
                        path="packageType"
                        label="Deliverable Package Type">
                <n-select
                        v-model:value="deliverable.softwareMetadata.packageType"
                        :options="constants.PackageTypes" />
            </n-form-item>
            <n-form-item 
                        path="supportedOs"
                        label="Supported Operating Systems">
                <n-select
                        v-model:value="deliverable.supportedOs"
                        multiple
                        :options="constants.OperatingSystems" />
            </n-form-item>
            <n-form-item 
                        path="supportedCpuArchitectures"
                        label="Supported CPU Architectures">
                <n-select
                        v-model:value="deliverable.supportedCpuArchitectures"
                        multiple
                        :options="constants.CpuArchitectures" />
            </n-form-item>
            <n-form-item
                        label="Deliverable Tags">
                <n-dynamic-input
                    preset="pair"
                    v-model:value="deliverable.tags"
                    key-placeholder="Enter tag key, i.e. 'deploymentType'"
                    value-placeholder="Enter tag value, i.e. 'primary'" />
            </n-form-item>
            <n-form-item
                        label="Deliverable Identities">
                <n-dynamic-input
                    preset="pair"
                    v-model:value="deliverable.identities"
                    key-placeholder="Enter Identity Type"
                    value-placeholder="Enter Identity" />
            </n-form-item>
            <n-form-item
                        label="Deliverable Download Links">
                <n-dynamic-input
                    v-model:value="deliverable.softwareMetadata.downloadLinks"
                    :on-create="onCreateDownloadLink">
                    <template #default="{ value }">
                        <n-input v-model:value="value.uri" type="text" />
                        <n-input v-model:value="value.content" type="text" />
                    </template>
                </n-dynamic-input>
            </n-form-item>
            <n-space>
                <n-button type="success" @click="onSubmit">Add Deliverable</n-button>
                <n-button type="warning" @click="onReset">Reset Deliverable Input</n-button>
            </n-space>
        </n-form>

    </div>
</template>
<script lang="ts">
export default {
    name: 'CreateArtifact'
}
</script>
<script lang="ts" setup>
import graphqlClient from '@/utils/graphql'
import gql from 'graphql-tag'
import { FormInst, NButton, NDynamicInput, NForm, NFormItem, NInput, NRadioButton, NRadioGroup, NSelect, NTooltip, NUpload, NSpace } from 'naive-ui'
import { computed, ComputedRef, ref, Ref } from 'vue'
import { useStore } from 'vuex'
import { Tag, DownloadLink} from '@/utils/commonTypes'
import constants from '@/utils/constants' 

const props = defineProps<{
    inputBranch: string,
    inputOrgUuid: string,
    inputRelease: string
}>()

const emit = defineEmits(['addDeliverable'])

const store = useStore()

const org = store.getters.orgById(props.inputOrgUuid)

const createDeliverableForm = ref<FormInst | null>(null)

interface Deliverable {
    displayIdentifier: string,
    publisher: string,
    group: string,
    tags: Tag[],
    branch: string,
    type: string,
    identities: [],
    supportedOs: string[],
    supportedCpuArchitectures: string[],
    softwareMetadata: SoftwareMetadata
}

interface SoftwareMetadata {
    packageType: string,
    downloadLinks: DownloadLink[],
    digests: string[]
}

const deliverable: Ref<Deliverable>= ref({
    displayIdentifier: '',
    publisher: org.name,
    group: '',
    branch: props.inputBranch,
    type: '',
    identities: [],
    supportedOs: [],
    tags: [],
    supportedCpuArchitectures: [],
    softwareMetadata: {
        packageType: '',
        digests: [],
        downloadLinks: [],
    }
})

function onReset () {
    deliverable.value = {
        displayIdentifier: '',
        publisher: org.name,
        group: '',
        branch: props.inputBranch,
        type: '',
        identities: [],
        supportedOs: [],
        tags: [],
        supportedCpuArchitectures: [],
        softwareMetadata: {
            packageType: '',
            digests: [],
            downloadLinks: [],
        }
    }
}

const rules = {
    displayIdentifier: {
        required: true,
        message: 'Identifier is required'
    },
    type: {
        required: true,
        message: 'Type is required'
    }
}


const onSubmit = async () => {
    const response = await graphqlClient.mutate({
        mutation: gql`
            mutation addOutboundDeliverablesManual($deliverables: AddDeliverableInput!) {
                addOutboundDeliverablesManual(deliverables: $deliverables)
            }`,
        variables: {
            deliverables: {
                release: props.inputRelease,
                deliverables: [deliverable.value]
            }
        },
        fetchPolicy: 'no-cache'
    })
    console.log(response)
    emit('addDeliverable')
}

const onCreateDownloadLink = () => {
    return {
        uri: '',
        content: ''
    }
}


</script>

<style scoped lang="scss">
.createArtifact {
    width: 95%;
    margin-left: 20px;
}
Input.digestInput {
    display: inline;
    width: 90%;
}
.digestEntry {
    display:inline;

}
.removeDigest {
    display: inline;
    margin-left:2px;
    cursor: pointer;
}
</style>