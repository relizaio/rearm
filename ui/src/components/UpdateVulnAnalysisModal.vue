<template>
    <n-modal
        v-model:show="isVisible"
        :title="modalTitle"
        preset="dialog"
        :show-icon="false"
        style="width: 60%;"
    >
        <n-form ref="formRef" :model="formData" :rules="rules" label-placement="left" label-width="180px">
            <n-form-item>
                <template #label>
                    <field-label label="Finding Aliases" :tip="FIELD_HELP.findingAliases" />
                </template>
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

            <n-form-item path="state">
                <template #label>
                    <field-label label="Current State" :tip="FIELD_HELP.state" />
                </template>
                <n-select
                    v-model:value="formData.state"
                    :options="stateOptions"
                    placeholder="Select analysis state"
                />
            </n-form-item>

            <n-alert
                v-if="stateGuidance"
                type="info"
                :show-icon="false"
                style="margin: -8px 0 16px 180px;"
            >
                {{ stateGuidance }}
            </n-alert>

            <n-form-item v-if="showJustification" path="justification">
                <template #label>
                    <field-label label="Justification" :tip="FIELD_HELP.justification" />
                </template>
                <n-select
                    v-model:value="formData.justification"
                    :options="justificationOptions"
                    :placeholder="justificationPlaceholder"
                    :clearable="!justificationMandatory"
                />
            </n-form-item>

            <n-form-item path="severity">
                <template #label>
                    <field-label label="Severity" :tip="FIELD_HELP.severity" />
                </template>
                <n-select
                    v-model:value="formData.severity"
                    :options="severityOptions"
                    placeholder="Select severity (optional)"
                    clearable
                />
            </n-form-item>

            <n-form-item
                v-if="showResponses"
                path="responses"
            >
                <template #label>
                    <field-label label="Responses" :tip="FIELD_HELP.responses" />
                </template>
                <n-select
                    v-model:value="formData.responses"
                    :options="responseOptions"
                    multiple
                    :placeholder="formData.state === AnalysisState.EXPLOITABLE ? 'Select one or more responses (required unless recommendation provided)' : 'Select one or more responses (optional)'"
                    clearable
                />
            </n-form-item>

            <n-form-item
                v-if="showRecommendation"
                path="recommendation"
            >
                <template #label>
                    <field-label label="Recommendation" :tip="FIELD_HELP.recommendation" />
                </template>
                <n-input
                    v-model:value="formData.recommendation"
                    type="textarea"
                    placeholder="Recommended remediation (required unless responses provided)"
                    :rows="2"
                />
            </n-form-item>

            <n-form-item v-if="showWorkaround" path="workaround">
                <template #label>
                    <field-label label="Workaround" :tip="FIELD_HELP.workaround" />
                </template>
                <n-input
                    v-model:value="formData.workaround"
                    type="textarea"
                    placeholder="Workaround description (optional)"
                    :rows="2"
                />
            </n-form-item>

            <n-form-item path="details">
                <template #label>
                    <field-label label="Details (Impact Statement)" :tip="FIELD_HELP.details" />
                </template>
                <n-input
                    v-model:value="formData.details"
                    type="textarea"
                    :placeholder="(formData.state === AnalysisState.NOT_AFFECTED || formData.state === AnalysisState.FALSE_POSITIVE) ? 'Impact statement (required unless justification provided)' : 'Additional details (optional)'"
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
import { useStore } from 'vuex'
import { NModal, NForm, NFormItem, NSelect, NInput, NButton, NSpace, NDynamicInput, NAlert, useNotification, FormInst, FormItemRule, FormRules } from 'naive-ui'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import {
    ANALYSIS_STATE_OPTIONS,
    ANALYSIS_JUSTIFICATION_OPTIONS,
    ANALYSIS_RESPONSE_OPTIONS,
    AnalysisState
} from '@/constants/vulnAnalysis'
import { FIELD_HELP, STATE_GUIDANCE } from '@/constants/vulnAnalysisFieldHelp'
import FieldLabel from './FieldLabel.vue'

interface Props {
    show: boolean
    analysisRecord: any
}

const props = defineProps<Props>()

const emit = defineEmits<{
    'update:show': [value: boolean]
    'updated': [analysis: any]
}>()

const store = useStore()
const notification = useNotification()
const formRef = ref<FormInst | null>(null)
const submitting = ref(false)

// Org-level setting — only meaningful for NOT_AFFECTED (CISA VEX).
const justificationMandatoryOrg = computed(() => {
    const orgUuid = props.analysisRecord?.org
    if (!orgUuid) return false
    const org = store.getters.orgById(orgUuid)
    return org?.settings?.justificationMandatory === true
})
const justificationMandatory = computed(() =>
    justificationMandatoryOrg.value && formData.value.state === AnalysisState.NOT_AFFECTED
)

// Active VEX compliance framework for this org. CycloneDX is always the baseline data
// model; a framework (currently NONE or CISA) layers additional validation rules.
const vexFramework = computed<'NONE' | 'CISA'>(() => {
    const orgUuid = props.analysisRecord?.org
    if (!orgUuid) return 'NONE'
    const org = store.getters.orgById(orgUuid)
    return (org?.settings?.vexComplianceFramework === 'CISA') ? 'CISA' : 'NONE'
})
const cisaEnforced = computed(() => vexFramework.value === 'CISA')

const stateGuidance = computed(() =>
    cisaEnforced.value ? (STATE_GUIDANCE[formData.value.state] || '') : ''
)

// Per-state field visibility (CISA VEX).
const showJustification = computed(() =>
    formData.value.state === AnalysisState.NOT_AFFECTED
    || formData.value.state === AnalysisState.FALSE_POSITIVE)
const showResponses = computed(() =>
    formData.value.state === AnalysisState.EXPLOITABLE ||
    formData.value.state === AnalysisState.RESOLVED
)
const showRecommendation = computed(() => formData.value.state === AnalysisState.EXPLOITABLE)
const showWorkaround = computed(() => formData.value.state === AnalysisState.EXPLOITABLE)

const justificationPlaceholder = computed(() => {
    const s = formData.value.state
    if ((s === AnalysisState.NOT_AFFECTED || s === AnalysisState.FALSE_POSITIVE) && !formData.value.details.trim()) {
        return 'Select justification (required unless details provided)'
    }
    if (justificationMandatory.value) return 'Select justification (required by org settings)'
    return 'Select justification (optional)'
})

const isVisible = computed({
    get: () => props.show,
    set: (value: boolean) => emit('update:show', value)
})

const modalTitle = computed(() => {
    if (props.analysisRecord) {
        const findingId = props.analysisRecord.findingId || 'Unknown'
        const location = props.analysisRecord.location || 'Unknown'
        const scope = props.analysisRecord.scope || 'ORG'
        return `Update Finding Analysis [${scope}]: ${findingId} @ ${location}`
    }
    return 'Update Finding Analysis'
})

const formData = ref({
    findingAliases: [] as string[],
    state: AnalysisState.IN_TRIAGE as string,
    justification: null as string | null,
    severity: null as string | null,
    details: '',
    responses: [] as string[],
    recommendation: '',
    workaround: ''
})

// Clear fields that are hidden for the current state so stale values aren't submitted.
watch(() => formData.value.state, () => {
    if (!showJustification.value) formData.value.justification = null
    if (!showResponses.value) formData.value.responses = []
    if (!showRecommendation.value) formData.value.recommendation = ''
    if (!showWorkaround.value) formData.value.workaround = ''
})

const stateOptions = ANALYSIS_STATE_OPTIONS
const justificationOptions = ANALYSIS_JUSTIFICATION_OPTIONS
const responseOptions = ANALYSIS_RESPONSE_OPTIONS
const severityOptions = [
    { label: 'Critical', value: 'CRITICAL' },
    { label: 'High', value: 'HIGH' },
    { label: 'Medium', value: 'MEDIUM' },
    { label: 'Low', value: 'LOW' },
    { label: 'Unassigned', value: 'UNASSIGNED' }
]

const rules = computed<FormRules>(() => {
    const baseRules: FormRules = {
        state: [{ required: true, message: 'Analysis state is required', trigger: 'change' }],
        // Org-level setting: justification always required (independent of framework).
        justification: justificationMandatory.value
            ? [{ required: true, message: 'Justification is required by organization settings', trigger: 'change' }]
            : []
    }
    if (cisaEnforced.value) {
        // CISA VEX: NOT_AFFECTED and FALSE_POSITIVE each require justification OR details.
        baseRules.details = [
            {
                validator: (_rule: FormItemRule, value: string) => {
                    const s = formData.value.state
                    if (s !== AnalysisState.NOT_AFFECTED && s !== AnalysisState.FALSE_POSITIVE) return true
                    if (formData.value.justification) return true
                    if (value && value.trim().length > 0) return true
                    return new Error(`${s} requires either a justification or an impact statement in details`)
                },
                trigger: ['blur', 'change']
            }
        ]
        // CISA VEX: EXPLOITABLE requires responses OR recommendation.
        baseRules.recommendation = [
            {
                validator: (_rule: FormItemRule, value: string) => {
                    if (formData.value.state !== AnalysisState.EXPLOITABLE) return true
                    if (formData.value.responses && formData.value.responses.length > 0) return true
                    if (value && value.trim().length > 0) return true
                    return new Error('EXPLOITABLE requires an action statement: at least one response or a non-empty recommendation')
                },
                trigger: ['blur', 'change']
            }
        ]
    }
    return baseRules
})

// Watch for changes in the analysis record and populate form
watch(() => props.analysisRecord, (newRecord) => {
    if (newRecord) {
        formData.value.findingAliases = newRecord.findingAliases || []
        formData.value.state = newRecord.analysisState || AnalysisState.IN_TRIAGE
        formData.value.justification = newRecord.analysisJustification || null
        formData.value.severity = newRecord.severity || null
        const history = newRecord.analysisHistory || []
        const latestDetails = history.length > 0 ? (history[history.length - 1].details || '') : ''
        formData.value.details = latestDetails
        formData.value.responses = newRecord.responses || []
        formData.value.recommendation = newRecord.recommendation || ''
        formData.value.workaround = newRecord.workaround || ''
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
        
        // Always send these fields so clearing them in the form is persisted
        // (backend treats null as "preserve previous", so we must send explicit values).
        input.responses = formData.value.responses || []
        input.recommendation = (formData.value.recommendation || '').trim()
        input.workaround = (formData.value.workaround || '').trim()
        
        const response = await graphqlClient.mutate<{ updateVulnAnalysis: any }>({
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
                        responses
                        recommendation
                        workaround
                        analysisHistory {
                            state
                            justification
                            details
                            createdDate
                            responses
                            recommendation
                            workaround
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
        
        emit('updated', response.data?.updateVulnAnalysis)
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
