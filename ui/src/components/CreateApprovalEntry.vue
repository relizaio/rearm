<template>
    <div class="createApprovalEntryGlobal">
        <div v-if="!isHideTitle">Create Approval Entry</div>
        <n-form
            ref="createApprovalEntryForm"
            :model="approvalEntryUIInput">
            <n-form-item path="approvalName" label="Approval Name">
                <n-input
                    v-model:value="approvalEntryUIInput.approvalName"
                    required
                    placeholder="Enter name for the approval entry" />
            </n-form-item>
            <n-form-item label="Requirements">
                <n-space vertical style="width: 100%;">
                    <div style="font-size: 12px; color: #888;">
                        The entry is approved once every requirement is met. A requirement is met when this many
                        different people approve with any of its roles, and fails once more people disapprove than
                        it tolerates.
                    </div>
                    <div
                        v-for="(req, i) in approvalEntryUIInput.requirements"
                        :key="i"
                        style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap;"
                        data-testid="approval-requirement"
                    >
                        <n-select
                            v-model:value="req.roles"
                            :options="roleOptions"
                            multiple
                            placeholder="Approval roles"
                            style="min-width: 240px; flex: 1;"
                        />
                        <span style="font-size: 13px;">approvals needed</span>
                        <n-input-number v-model:value="req.requiredNumberOfApprovals" :min="1" :precision="0" style="width: 100px;" />
                        <span style="font-size: 13px;">disapprovals tolerated</span>
                        <n-input-number v-model:value="req.permittedNumberOfDisapprovals" :min="0" :precision="0" style="width: 100px;" />
                        <n-button v-if="approvalEntryUIInput.requirements.length > 1" size="tiny" quaternary type="error"
                            @click="approvalEntryUIInput.requirements.splice(i, 1)">×</n-button>
                    </div>
                    <n-button size="tiny" dashed @click="approvalEntryUIInput.requirements.push(emptyRequirement())">
                        + Add requirement
                    </n-button>
                </n-space>
            </n-form-item>
            <div v-if="touched && issues.length" style="color: #d03050; font-size: 12px; margin-bottom: 8px;" data-testid="approval-entry-issues">
                <div v-for="(issue, i) in issues" :key="i">{{ issue }}</div>
            </div>
            <n-button type="success" :disabled="issues.length > 0" @click="onSubmit">Create Approval Entry</n-button>
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
import { Ref, ref, ComputedRef, computed, watch, nextTick } from 'vue'
import { useStore } from 'vuex'
import { FormInst, NForm, NFormItem, NInput, NInputNumber, NButton, NSelect, NSpace } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import gql from 'graphql-tag'
import {
    approvalEntryIssues, emptyRequirement, toApprovalEntryInput,
    type ApprovalEntryInput, type RequirementDraft
} from '@/utils/approvalEntryInput'

const props = defineProps<{
    orgProp: string,
    isHideTitle: boolean
}>()

const isHideTitle = ref(props.isHideTitle)

const emit = defineEmits(['approvalEntryCreated'])

const store = useStore()

const myorg: ComputedRef<any> = computed((): any => store.getters.orgById(props.orgProp))

const roleOptions = computed(() => (myorg.value?.approvalRoles || [])
    .map((ar: any) => ({ label: ar.displayView, value: ar.id })))

const createApprovalEntryForm = ref<FormInst | null>(null)

type ApprovalEntryUIInput = {
    approvalName: string;
    requirements: RequirementDraft[];
}

const approvalEntryUIInput : Ref<ApprovalEntryUIInput> = ref({
    approvalName: '',
    requirements: [emptyRequirement()]
})

const issues = computed(() => approvalEntryIssues(approvalEntryUIInput.value.approvalName,
    approvalEntryUIInput.value.requirements))
// Name what is missing only once the user has started; a fresh form is not a list of errors.
const touched = ref(false)
watch(approvalEntryUIInput, () => { touched.value = true }, { deep: true })

function onReset () {
    approvalEntryUIInput.value = {
        approvalName: '',
        requirements: [emptyRequirement()]
    }
    nextTick(() => { touched.value = false })
}

async function onSubmitSuccess () {
    const approvalEntry = toApprovalEntryInput(props.orgProp, approvalEntryUIInput.value.approvalName,
        approvalEntryUIInput.value.requirements)
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
    if (issues.value.length) return
    createApprovalEntryForm.value?.validate((errors) => {
        if (!errors) {
            onSubmitSuccess()
        }
    })
}

</script>

<style scoped lang="scss">
</style>