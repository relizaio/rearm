<template>
    <div class="org-declared-license-panel">
        <h5>Default declared licence</h5>
        <p class="text-muted">{{ ORG_INTRO }}</p>
        <div v-if="loadError" class="org-declared-license-error" data-testid="org-default-license-load-error">
            {{ loadError }}
            <a href="#" class="ml-2" data-testid="org-default-license-retry" @click.prevent="load">Retry</a>
        </div>
        <n-spin :show="loading">
            <n-form v-if="loaded">
                <n-form-item label="Default licence expression">
                    <div class="org-declared-license-field">
                        <n-input v-model:value="form.expression" :readonly="!canManage" :placeholder="EXPRESSION_PLACEHOLDER"
                            data-testid="org-default-license" @update:value="saveError = ''" />
                        <span class="text-muted">{{ EXPRESSION_HINT }}</span>
                    </div>
                </n-form-item>
                <n-form-item label="Licence text URL">
                    <div class="org-declared-license-field">
                        <n-input v-model:value="form.url" :readonly="!canManage" :placeholder="URL_PLACEHOLDER"
                            data-testid="org-default-license-url" @update:value="saveError = ''" />
                        <span class="text-muted">{{ URL_HINT }}</span>
                    </div>
                </n-form-item>
                <n-alert v-if="saveError" type="error" :show-icon="true" class="mb-2" data-testid="org-default-license-error">
                    {{ saveError }}
                </n-alert>
                <n-space v-if="canManage">
                    <n-button type="primary" :disabled="!dirty || saving" :loading="saving" data-testid="org-default-license-save" @click="save">
                        Save Default Licence
                    </n-button>
                </n-space>
            </n-form>
        </n-spin>
    </div>
</template>

<script lang="ts" setup>
// Organization settings, "Default declared licence" (SCORE-42, design ADR-2): the organization
// default licence expression and its URL, written on the SBOM nodes ReARM makes. Its own
// component, read and saved through its own documents, never through the settings form's
// mutation: the CE mirror lacks the two fields, and the form's response selection must stay
// valid there. OrgSettings mounts it for admins and latches the drift flag from `unsupported`.
import { computed, reactive, ref, watch } from 'vue'
import { NAlert, NButton, NForm, NFormItem, NInput, NSpace, NSpin } from 'naive-ui'
import graphqlClient from '../utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import {
    EXPRESSION_HINT, EXPRESSION_PLACEHOLDER, ORG_CLEARED, ORG_INTRO, ORG_SAVED, UPDATE_ORG_DECLARED_LICENSE, URL_HINT, URL_PLACEHOLDER,
    declaredLicenseDiffers, loadOrgDeclaredLicense, orgDeclaredLicenseVariables,
} from '@/utils/declaredLicense'

const props = defineProps<{
    orgUuid: string
    canManage: boolean
    notify: (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void
}>()
const emit = defineEmits<{ (e: 'unsupported'): void }>()

const form = reactive({ expression: '', url: '' })
const baseline = reactive({ expression: '', url: '' })
const loaded = ref(false)
const loading = ref(false)
const loadError = ref('')
const saving = ref(false)
const saveError = ref('')

const dirty = computed((): boolean => declaredLicenseDiffers(form, baseline))

function take (expression: string, url: string) {
    form.expression = expression
    form.url = url
    baseline.expression = expression
    baseline.url = url
}

async function load () {
    if (!props.orgUuid) return
    loading.value = true
    try {
        const r = await loadOrgDeclaredLicense(graphqlClient as any, props.orgUuid)
        if (!r.supported) {
            // A backend without the declared licence: OrgSettings replaces the block with its note.
            loaded.value = false
            emit('unsupported')
            return
        }
        take(r.expression, r.url)
        loaded.value = true
        loadError.value = ''
    } catch (e: any) {
        // A failed read, not an unanswerable server: say so here and offer Retry; nothing of
        // OrgSettings waits on this panel.
        loaded.value = false
        loadError.value = commonFunctions.extractGraphQLErrorMessage(e)
        props.notify('error', 'Error', loadError.value)
    } finally {
        loading.value = false
    }
}
watch(() => props.orgUuid, () => load(), { immediate: true })

async function save () {
    const variables = orgDeclaredLicenseVariables(props.orgUuid, form, baseline)
    if (!variables) return
    saving.value = true
    saveError.value = ''
    try {
        const resp: any = await graphqlClient.mutate({ mutation: UPDATE_ORG_DECLARED_LICENSE, variables, fetchPolicy: 'no-cache' })
        // The baseline from the mutation's own response: the canonical form, and the URL the
        // server cleared with the expression.
        const s = resp?.data?.updateOrganizationSettings?.settings
        take(s?.defaultLicenseExpression || '', s?.defaultLicenseUrl || '')
        props.notify('success', 'Saved', baseline.expression ? ORG_SAVED : ORG_CLEARED)
    } catch (e: any) {
        saveError.value = commonFunctions.extractGraphQLErrorMessage(e)
    } finally {
        saving.value = false
    }
}

defineExpose({ reload: load })
</script>

<style scoped>
.org-declared-license-field {
    display: flex;
    flex-direction: column;
    width: 100%;
    max-width: 600px;
    gap: 4px;
}
.org-declared-license-error {
    color: #d03050;
}
</style>
