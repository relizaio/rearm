<template>
    <n-modal
        v-model:show="isVisible"
        title="Update Vulnerability Analysis"
        preset="dialog"
        :show-icon="false"
        style="width: 60%;"
    >
        <n-form ref="formRef" :model="formData" :rules="rules" label-placement="left" label-width="140px">
            <n-form-item label="Finding Aliases">
                <n-dynamic-input
                    v-model:value="formData.findingAliases"
                    placeholder="Add alias"
                    :on-create="() => ''"
                >
                    <template #default="{ value, index }">
                        <n-input
                            v-model:value="formData.findingAliases[index]"
                            placeholder="Enter alias"
                        />
                    </template>
                </n-dynamic-input>
            </n-form-item>

            <n-form-item label="Current State" path="state">
                <n-select
                    v-model:value="formData.state"
                    :options="stateOptions"
                    placeholder="Select analysis state"
                />
            </n-form-item>

            <n-form-item label="Justification" path="justification">
                <n-select
                    v-model:value="formData.justification"
                    :options="justificationOptions"
                    placeholder="Select justification (optional)"
                    clearable
                />
            </n-form-item>

            <n-form-item label="Severity" path="severity">
                <n-select
                    v-model:value="formData.severity"
                    :options="severityOptions"
                    placeholder="Select severity (optional)"
                    clearable
                />
            </n-form-item>

            <n-form-item label="Details" path="details">
                <n-input
                    v-model:value="formData.details"
                    type="textarea"
                    placeholder="Additional details (optional)"
                    :rows="3"
                />
            </n-form-item>
        </n-form>

        <template #action>
            <n-space>
                <n-button @click="handleCancel">Cancel</n-button>
                <n-button type="primary" @click="handleSubmit" :loading="submitting">
                    Update Analysis
                </n-button>
            </n-space>
        </template>
    </n-modal>
</template>

<script lang="ts">
export default {
    name: 'UpdateVulnAnalysisModal'
}
</script>

<script lang="ts" setup>
import { ref, computed, watch } from 'vue'
import { NModal, NForm, NFormItem, NSelect, NInput, NButton, NSpace, NDynamicInput, useNotification, FormInst, FormRules } from 'naive-ui'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import { ANALYSIS_STATE_OPTIONS, ANALYSIS_JUSTIFICATION_OPTIONS } from '@/constants/vulnAnalysis'

interface Props {
    show: boolean
    analysisRecord: any
}

const props = defineProps<Props>()

const emit = defineEmits<{
    'update:show': [value: boolean]
    'updated': [analysis: any]
}>()

const notification = useNotification()
const formRef = ref<FormInst | null>(null)
const submitting = ref(false)

const isVisible = computed({
    get: () => props.show,
    set: (value: boolean) => emit('update:show', value)
})

const formData = ref({
    findingAliases: [] as string[],
    state: 'IN_TRIAGE',
    justification: null as string | null,
    severity: null as string | null,
    details: ''
})

const stateOptions = ANALYSIS_STATE_OPTIONS
const justificationOptions = ANALYSIS_JUSTIFICATION_OPTIONS
const severityOptions = [
    { label: 'Critical', value: 'CRITICAL' },
    { label: 'High', value: 'HIGH' },
    { label: 'Medium', value: 'MEDIUM' },
    { label: 'Low', value: 'LOW' },
    { label: 'Info', value: 'INFO' }
]

const rules: FormRules = {
    state: [{ required: true, message: 'Analysis state is required', trigger: 'change' }]
}

// Watch for changes in the analysis record and populate form
watch(() => props.analysisRecord, (newRecord) => {
    if (newRecord) {
        formData.value.findingAliases = newRecord.findingAliases || []
        formData.value.state = newRecord.analysisState || 'IN_TRIAGE'
        formData.value.justification = newRecord.analysisJustification || null
        formData.value.severity = newRecord.severity || null
        formData.value.details = ''
    }
}, { immediate: true })

const handleCancel = () => {
    isVisible.value = false
}

const handleSubmit = async () => {
    if (!formRef.value) return
    
    try {
        await formRef.value.validate()
        submitting.value = true
        
        const input: any = {
            analysisUuid: props.analysisRecord.uuid,
            findingAliases: formData.value.findingAliases.length > 0 ? formData.value.findingAliases : null,
            state: formData.value.state,
            details: formData.value.details || null
        }

        if (formData.value.justification) {
            input.justification = formData.value.justification
        }
        
        if (formData.value.severity) {
            input.severity = formData.value.severity
        }
        
        const response = await graphqlClient.mutate({
            mutation: gql`
                mutation updateVulnAnalysis($analysis: UpdateVulnAnalysisInput!) {
                    updateVulnAnalysis(analysis: $analysis) {
                        uuid
                        org
                        location
                        locationType
                        findingId
                        findingAliases
                        findingType
                        scope
                        scopeUuid
                        analysisState
                        analysisJustification
                        severity
                        analysisHistory {
                            state
                            justification
                            details
                            createdDate
                        }
                    }
                }
            `,
            variables: { analysis: input }
        })
        
        notification.success({
            content: 'Vulnerability analysis updated successfully',
            duration: 3000
        })
        
        emit('updated', response.data.updateVulnAnalysis)
        isVisible.value = false
    } catch (error: any) {
        console.error('Error updating vulnerability analysis:', error)
        notification.error({
            content: 'Failed to update vulnerability analysis',
            meta: error.message || 'Unknown error',
            duration: 5000
        })
    } finally {
        submitting.value = false
    }
}
</script>
