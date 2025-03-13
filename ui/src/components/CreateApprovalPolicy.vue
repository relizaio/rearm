<template>
    <div class="createApprovalPolicyGlobal">
        <div v-if="!isHideTitle">Create Approval Policy</div>
        <n-data-table :data="approvalEntityTableData" :columns="approvalEntityFields" :row-key="aeRowKey" v-model:checked-row-keys="approvalPolicy.approvalEntries"/>
        <n-form
            ref="createApprovalPolicyForm"
            :model="approvalPolicy"
            :rules="rules">
            <n-form-item    path="policyName"
                            label="Policy Name">
                <n-input
                    v-model:value="approvalPolicy.policyName"
                    required
                    placeholder="Enter name for the approval policy" />
            </n-form-item>
            <n-button type="success" @click="onSubmit">Submit</n-button>
            <n-button type="warning" @click="onReset">Reset</n-button>
        </n-form>
    </div>
</template>

<script lang="ts">
export default {
    name: 'CreateApprovalPolicy'
}
</script>

<script lang="ts" setup>
import { Ref, ref, ComputedRef, computed } from 'vue'
import { useStore } from 'vuex'
import { FormInst, NCard, NForm, NFormItem, NInput, NButton, NSelect, NSwitch, NSpace, NCheckbox, NCheckboxGroup, NDataTable, DataTableColumns } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import gql from 'graphql-tag'
import {ApprovalEntry} from '@/utils/commonTypes'

const props = defineProps<{
    orgProp: string,
    isHideTitle: boolean
}>()

const isHideTitle = ref(props.isHideTitle)

const emit = defineEmits(['approvalPolicyCreated'])

const store = useStore()

const createApprovalPolicyForm = ref<FormInst | null>(null)

const orgApprovalEntries: Ref<ApprovalEntry[]> = ref([])

const approvalEntityFields: DataTableColumns<any> = [
    {
        type: 'selection'
    },
    {
        key: 'approvalName',
        title: 'Approval Name'
    },
    {
        key: 'approvalRoles',
        title: 'Required Approvals'
    }
]

const approvalEntityTableData: ComputedRef<any[]> = computed((): any => {
    const data = orgApprovalEntries.value.map(oae => {
        const approvalRoles = oae.approvalRequirements.map(oaear => oaear.allowedApprovalRoleIdExpanded[0].displayView)
        return {
            uuid: oae.uuid,
            approvalName: oae.approvalName,
            approvalRoles: approvalRoles.toString()
        }
    })
    return data
})

async function fetchApprovalEntries () {
    const response = await graphqlClient.query({
        query: gql`
            query approvalEntriesOfOrg($orgUuid: ID!) {
                approvalEntriesOfOrg(orgUuid: $orgUuid) {
                    uuid
                    approvalName
                    approvalRequirements {
                        allowedApprovalRoleIdExpanded {
                            id
                            displayView
                        }
                    }
                }
            }`,
        variables: {
            'orgUuid': props.orgProp
        },
        fetchPolicy: 'no-cache'
    })

    orgApprovalEntries.value = response.data.approvalEntriesOfOrg
}

async function gqlCreateApprovalPolicy () {
    const response = await graphqlClient.mutate({
        mutation: gql`
            mutation createApprovalPolicy($approvalPolicy: ApprovalPolicyInput!) {
                createApprovalPolicy(approvalPolicy: $approvalPolicy) {
                    uuid
                }
            }`,
        variables: {
            'approvalPolicy': approvalPolicy.value
        },
        fetchPolicy: 'no-cache'
    })
    emit('approvalPolicyCreated', response.data.createApprovalPolicy.uuid)
}

async function onSubmit () {
    createApprovalPolicyForm.value?.validate((errors) => {
        if (!errors) {
            onSubmitSuccess()
        }
    })
}

async function onSubmitSuccess () {
    await gqlCreateApprovalPolicy()
    onReset()
}

const onReset = function () {
    approvalPolicy.value = {
        org: props.orgProp ? props.orgProp : '',
        policyName: '',
        resourceGroup: '',
        approvalMappings: [],
        approvalEntries: []            
    }
}

enum ApprovalMappingClass {'RELEASE_LIFECYCLE', 'MARKETING_RELEASE_LIFECYCLE', 'ENVIRONMENT_TYPE'}

type ApprovalMapping = {
    mappingClass: ApprovalMappingClass;
    mapTo: string;
    approvalEntries: string[];
}

type ApprovalPolicy = {
    org: string;
    policyName: string;
    resourceGroup: string;
    approvalMappings: ApprovalMapping[];
    approvalEntries: string[];
}

const approvalPolicy : Ref<ApprovalPolicy> = ref({
    org: props.orgProp ? props.orgProp : '',
    resourceGroup: '',
    approvalMappings: [],
    approvalEntries: [],
    policyName: ''         
})

const rules = {}

fetchApprovalEntries()

const aeRowKey = (row: any) => row.uuid

</script>

<style scoped lang="scss">
</style>