<template>
    <n-modal
        v-model:show="isVisible"
        title="Create Vulnerability Analysis"
        preset="dialog"
        :show-icon="false"
        style="width: 70%;"
    >
        <n-form ref="formRef" :model="formData" :rules="rules">
            <n-form-item label="Finding ID" path="findingId">
                <n-tag type="info" size="medium">
                    {{ formData.findingId }}
                </n-tag>
            </n-form-item>

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

            <n-form-item label="Finding Type" path="findingType">
                <n-tag type="warning" size="medium">
                    {{ formData.findingType }}
                </n-tag>
            </n-form-item>

            <n-form-item label="Location" path="location">
                <n-tag type="default" size="medium" style="max-width: 100%; word-break: break-all;">
                    {{ formData.location }}
                </n-tag>
            </n-form-item>

            <n-form-item label="Location Type" path="locationType">
                <n-tag type="success" size="medium">
                    {{ formData.locationType }}
                </n-tag>
            </n-form-item>

            <n-form-item label="Scope" path="scope">
                <n-select
                    v-model:value="formData.scope"
                    :options="scopeOptions"
                    placeholder="Select analysis scope"
                    @update:value="onScopeChange"
                />
            </n-form-item>

            <n-form-item label="Analysis State" path="state">
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
                    Create Analysis
                </n-button>
            </n-space>
        </template>
    </n-modal>
</template>

<script lang="ts">
export default {
    name: 'CreateVulnAnalysisModal'
}
</script>

<script lang="ts" setup>
import { ref, computed, watch } from 'vue'
import { NModal, NForm, NFormItem, NInput, NSelect, NButton, NSpace, NTag, NDynamicInput, useNotification, FormInst, FormRules } from 'naive-ui'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import { ANALYSIS_STATE_OPTIONS, ANALYSIS_JUSTIFICATION_OPTIONS } from '@/constants/vulnAnalysis'

interface Props {
    show: boolean
    findingRow: any
    orgUuid: string
    // Context for scope selection
    releaseUuid?: string
    branchUuid?: string
    componentUuid?: string
    // If true, only ORG scope is allowed (artifact view)
    artifactViewOnly?: boolean
    // If provided, only these scopes will be available for selection
    availableScopesOnly?: string[]
}

const props = withDefaults(defineProps<Props>(), {
    releaseUuid: '',
    branchUuid: '',
    componentUuid: '',
    artifactViewOnly: false,
    availableScopesOnly: () => []
})

const emit = defineEmits<{
    'update:show': [value: boolean]
    'created': [analysis: any]
}>()

const notification = useNotification()
const formRef = ref<FormInst | null>(null)
const submitting = ref(false)

const isVisible = computed({
    get: () => props.show,
    set: (value: boolean) => emit('update:show', value)
})

const formData = ref({
    findingId: '',
    findingAliases: [] as string[],
    findingType: 'VULNERABILITY',
    location: '',
    locationType: 'PURL',
    scope: 'ORG',
    scopeUuid: '',
    state: 'IN_TRIAGE',
    justification: null as string | null,
    details: ''
})

const scopeOptions = computed(() => {
    // If availableScopesOnly is provided, filter to only those scopes
    if (props.availableScopesOnly && props.availableScopesOnly.length > 0) {
        const allOptions = [
            { label: 'Organization', value: 'ORG' },
            { label: 'Component', value: 'COMPONENT' },
            { label: 'Branch', value: 'BRANCH' },
            { label: 'Release', value: 'RELEASE' }
        ]
        return allOptions.filter(opt => props.availableScopesOnly!.includes(opt.value))
    }
    
    if (props.artifactViewOnly) {
        return [{ label: 'Organization', value: 'ORG' }]
    }
    
    const options = [{ label: 'Organization', value: 'ORG' }]
    
    if (props.componentUuid) {
        options.push({ label: 'Component', value: 'COMPONENT' })
    }
    if (props.branchUuid) {
        options.push({ label: 'Branch', value: 'BRANCH' })
    }
    if (props.releaseUuid) {
        options.push({ label: 'Release', value: 'RELEASE' })
    }
    
    return options
})

const stateOptions = ANALYSIS_STATE_OPTIONS
const justificationOptions = ANALYSIS_JUSTIFICATION_OPTIONS

const rules: FormRules = {
    findingId: [{ required: true, message: 'Finding ID is required', trigger: 'blur' }],
    findingType: [{ required: true, message: 'Finding type is required', trigger: 'change' }],
    location: [{ required: true, message: 'Location is required', trigger: 'blur' }],
    locationType: [{ required: true, message: 'Location type is required', trigger: 'change' }],
    scope: [{ required: true, message: 'Scope is required', trigger: 'change' }],
    state: [{ required: true, message: 'Analysis state is required', trigger: 'change' }]
}

// Helper function to set default scope based on available options
const setDefaultScope = () => {
    const availableOptions = scopeOptions.value
    if (availableOptions.length > 0) {
        const defaultScope = availableOptions[0].value
        formData.value.scope = defaultScope
        
        // Set the corresponding UUID
        switch (defaultScope) {
            case 'RELEASE':
                formData.value.scopeUuid = props.releaseUuid
                break
            case 'BRANCH':
                formData.value.scopeUuid = props.branchUuid
                break
            case 'COMPONENT':
                formData.value.scopeUuid = props.componentUuid
                break
            case 'ORG':
            default:
                formData.value.scopeUuid = props.orgUuid
                break
        }
    }
}

// Watch for changes in the finding row and populate form
watch(() => props.findingRow, (newRow) => {
    if (newRow) {
        formData.value.findingId = newRow.id || ''
        formData.value.findingAliases = newRow.aliases?.map((a: any) => a.aliasId) || []
        formData.value.findingType = newRow.type?.toUpperCase() || 'VULNERABILITY'
        formData.value.location = newRow.purl || newRow.location || ''
        formData.value.locationType = newRow.purl ? 'PURL' : 'CODE_POINT'
        
        setDefaultScope()
    }
}, { immediate: true })

// Watch for changes in availableScopesOnly to update default scope
watch(() => props.availableScopesOnly, () => {
    setDefaultScope()
}, { immediate: true })

const onScopeChange = (value: string) => {
    // Update scopeUuid based on selected scope
    switch (value) {
        case 'RELEASE':
            formData.value.scopeUuid = props.releaseUuid
            break
        case 'BRANCH':
            formData.value.scopeUuid = props.branchUuid
            break
        case 'COMPONENT':
            formData.value.scopeUuid = props.componentUuid
            break
        case 'ORG':
        default:
            formData.value.scopeUuid = props.orgUuid
            break
    }
}

const handleCancel = () => {
    isVisible.value = false
}

const handleSubmit = async () => {
    if (!formRef.value) return
    
    try {
        await formRef.value.validate()
        submitting.value = true
        
        const input: any = {
            org: props.orgUuid,
            location: formData.value.location,
            locationType: formData.value.locationType,
            findingId: formData.value.findingId,
            findingAliases: formData.value.findingAliases.length > 0 ? formData.value.findingAliases : null,
            findingType: formData.value.findingType,
            scope: formData.value.scope,
            scopeUuid: formData.value.scopeUuid,
            state: formData.value.state,
            details: formData.value.details || null
        }

        if (formData.value.justification) {
            input.justification = formData.value.justification
        }
        
        const response = await graphqlClient.mutate({
            mutation: gql`
                mutation createVulnAnalysis($analysis: CreateVulnAnalysisInput!) {
                    createVulnAnalysis(analysis: $analysis) {
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
                    }
                }
            `,
            variables: { analysis: input }
        })
        
        notification.success({
            content: 'Vulnerability analysis created successfully',
            duration: 3000
        })
        
        emit('created', response.data.createVulnAnalysis)
        isVisible.value = false
    } catch (error: any) {
        console.error('Error creating vulnerability analysis:', error)
        notification.error({
            content: 'Failed to create vulnerability analysis',
            meta: error.message || 'Unknown error',
            duration: 5000
        })
    } finally {
        submitting.value = false
    }
}
</script>
