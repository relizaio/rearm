<template>
    <div class="createApprovalEntryGlobal">
        <div v-if="!isHideTitle">Create Approval Entry</div>
        <n-form
            ref="createApprovalEntryForm"
            :model="approvalEntryUIInput">
            <n-form-item path="approvalRoles" label="Required Approval Roles">
                <n-checkbox-group v-model:value="approvalEntryUIInput.approvalRoles">
                    <n-space item-style="display: flex;">
                        <n-checkbox v-for="ar in myorg.approvalRoles" :key="ar.id" :value="ar.id" :label="ar.displayView" />
                    </n-space>
                </n-checkbox-group>
            </n-form-item>
            <n-form-item path="approvalName" label="Approval Name">
                <n-input
                    v-model:value="approvalEntryUIInput.approvalName"
                    required
                    placeholder="Enter name for the approval entry" />
            </n-form-item>
            <n-button type="success" @click="onSubmit">Create Approval Entry</n-button>
            <n-button type="warning" @click="onReset">Reset Approval Entry</n-button>
        </n-form>
    </div>
</template>

<script lang="ts">
export default {
    name: 'CreateApprovalEntry'
}
</script>

<script lang="ts" setup>
import { Ref, ref, ComputedRef, computed } from 'vue'
import { useStore } from 'vuex'
import { FormInst, NCard, NForm, NFormItem, NInput, NButton, NSelect, NSwitch, NSpace, NCheckbox, NCheckboxGroup, NDataTable, DataTableColumns } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import gql from 'graphql-tag'
import {ApprovalEntry, ApprovalRole, ApprovalRequirement} from '@/utils/commonTypes'

const props = defineProps<{
    orgProp: string,
    isHideTitle: boolean
}>()

const isHideTitle = ref(props.isHideTitle)

const emit = defineEmits(['approvalEntryCreated'])

const store = useStore()

const myorg: ComputedRef<any> = computed((): any => store.getters.orgById(props.orgProp))

const createApprovalEntryForm = ref<FormInst | null>(null)

function onReset () {
    approvalEntryUIInput.value = {
        org: props.orgProp,
        approvalRoles: [],
        approvalName: ''
    }
}

async function onSubmitSuccess () {
    const approvalEntry : ApprovalEntryInput = {
        org: props.orgProp,
        approvalRequirements: [],
        approvalName: approvalEntryUIInput.value.approvalName
    }

    approvalEntryUIInput.value.approvalRoles.forEach((aRole: string) => {
        const aRequirement: ApprovalRequirementInput = {
            allowedApprovalRoleIds: [aRole],
            requiredNumberOfApprovals: 1,
            permittedNumberOfDisapprovals: 0
        }
        approvalEntry.approvalRequirements.push(aRequirement)
    })

    const createdUuid = await gqlCreateApprovalEntry(approvalEntry)
    emit('approvalEntryCreated', createdUuid)
    onReset()
}

async function gqlCreateApprovalEntry (approvalEntryGqlInput: ApprovalEntryInput) {
    const response = await graphqlClient.mutate({
        mutation: gql`
            mutation createApprovalEntry($approvalEntry: ApprovalEntryInput!) {
                createApprovalEntry(approvalEntry: $approvalEntry) {
                    uuid
                }
            }`,
        variables: {
            'approvalEntry': approvalEntryGqlInput
        },
        fetchPolicy: 'no-cache'
    })
    return response.data.createApprovalEntry.uuid
}

async function onSubmit () {
    createApprovalEntryForm.value?.validate((errors) => {
        if (!errors) {
            onSubmitSuccess()
        }
    })
}


type ApprovalRequirementInput = {
    allowedApprovalRoleIds: string[];
    requiredNumberOfApprovals: number;
    permittedNumberOfDisapprovals: number;
}

type ApprovalEntryUIInput = {
    org: string;
    approvalRoles: string[];
    approvalName: string;
}

type ApprovalEntryInput = {
    org: string;
    approvalRequirements: ApprovalRequirementInput[];
    approvalName: string;
}

const approvalEntryUIInput : Ref<ApprovalEntryUIInput> = ref({
    org: props.orgProp ? props.orgProp : '',
    approvalRoles: [],
    approvalName: ''
})

const rules = {}

</script>

<style scoped lang="scss">
</style>