<template>
    <div class="createDeliverable">
        <h2>Create Deliverable</h2>
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
                        label="Deliverable Identifiers">
                <n-dynamic-input
                    v-model:value="deliverable.identifiers" :on-create="onCreateIdentifier">
                    <template #create-button-default>
                        Add Identifier
                    </template>
                    <template #default="{ value }">
                        <n-select style="width: 200px;" v-model:value="value.idType"
                            :options="[{label: 'PURL', value: 'PURL'}, {label: 'TEI', value: 'TEI'}, {label: 'CPE', value: 'CPE'}]" />
                        <n-input type="text" minlength="100" v-model:value="value.idValue" />
                                        </template>
                </n-dynamic-input>
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
            <n-form-item
                        path ="softwareMetadata.digestRecords"
                        label="Deliverable Digests">
                <n-dynamic-input
                    v-model:value="deliverable.softwareMetadata.digestRecords"
                    :on-create="onCreateDigestRecords">
                    <template #default="{ value }">
                        <n-select style="width: 200px;" :options="constants.TeaArtifactChecksumTypes" v-model:value="value.algo"  placeholder='Select Algo'/>
                        <n-input v-model:value="value.digest" type="text" placeholder='digest'/>
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
    identifiers: any[],
    supportedOs: string[],
    supportedCpuArchitectures: string[],
    softwareMetadata: SoftwareMetadata
}

interface DigestRecord {
    algo: string,
    digest: string
}

interface SoftwareMetadata {
    packageType: null | string,
    downloadLinks: DownloadLink[],
    digestRecords: DigestRecord[]
}

const deliverable: Ref<Deliverable>= ref({
    displayIdentifier: '',
    publisher: org.name,
    group: '',
    branch: props.inputBranch,
    type: '',
    identifiers: [],
    supportedOs: [],
    tags: [],
    supportedCpuArchitectures: [],
    softwareMetadata: {
        packageType: null,
        digestRecords: [],
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
        identifiers: [],
        supportedOs: [],
        tags: [],
        supportedCpuArchitectures: [],
        softwareMetadata: {
            packageType: null,
            digestRecords: [],
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


async function onSubmit () {
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
    emit('addDeliverable')
}

function onCreateDownloadLink () {
    return {
        uri: '',
        content: ''
    }
}

function onCreateIdentifier () {
    return {
        idType: '',
        idValue: ''
    }
}

function onCreateDigestRecords () {
    return {
        algo: '',
        digest: ''
    } as DigestRecord
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