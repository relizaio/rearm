<template>
    <n-modal
        :show="show"
        preset="card"
        :title="isEdit ? 'Edit upstream source' : 'Add upstream source'"
        style="width: 720px; max-width: 95vw;"
        @update:show="onUpdateShow">
        <div data-testid="upstream-source-form">
            <n-form label-placement="top" @submit.prevent>
                <n-form-item label="Name" :feedback="shownProblem('name')" :validation-status="statusOf('name')">
                    <n-input v-model:value="model.name" type="text" placeholder="The supplier's name" data-testid="upstream-form-name" />
                </n-form-item>
                <n-form-item label="Kind">
                    <span data-testid="upstream-form-kind">{{ sourceKindLabel.TEA }}</span>
                </n-form-item>
                <n-form-item label="Server root" :feedback="shownProblem('root') || rootHint" :validation-status="statusOf('root')">
                    <n-input v-model:value="model.root" type="text" placeholder="https://tea.example.com/tea" data-testid="upstream-form-root" />
                </n-form-item>

                <n-form-item label="Credential" :feedback="credentialFeedback" :validation-status="credentialFeedback ? 'error' : undefined">
                    <div class="upstream-form-credential">
                        <template v-if="isEdit && source">
                            <div data-testid="upstream-form-stored-credential">{{ storedCredentialText }}</div>
                            <n-radio-group v-model:value="model.credentialChange" data-testid="upstream-form-credential-change">
                                <n-space vertical>
                                    <n-radio :value="CredentialChange.KEEP" data-testid="upstream-form-change-keep">{{ storedKind === 'NONE' ? 'Keep no credential' : 'Keep the stored credential' }}</n-radio>
                                    <n-radio :value="CredentialChange.REPLACE" data-testid="upstream-form-change-replace">{{ storedKind === 'NONE' ? 'Add one' : 'Replace it' }}</n-radio>
                                    <n-radio v-if="storedKind !== 'NONE'" :value="CredentialChange.REMOVE" data-testid="upstream-form-change-remove">Remove it</n-radio>
                                </n-space>
                            </n-radio-group>
                        </template>
                        <template v-if="showKindChoice">
                            <n-radio-group v-model:value="model.credentialKind" class="upstream-form-kind-choice" data-testid="upstream-form-credential-kind">
                                <n-space>
                                    <n-radio v-for="k in kindChoices" :key="k" :value="k" :data-testid="`upstream-form-kind-${k}`">{{ credentialKindLabel[k] }}</n-radio>
                                </n-space>
                            </n-radio-group>
                            <div v-if="model.credentialKind === 'BEARER_TOKEN'" class="upstream-form-secret">
                                <div>Token</div>
                                <n-input v-model:value="token" type="password" show-password-on="click"
                                    :input-props="{ autocomplete: 'new-password' }" data-testid="upstream-form-token" />
                                <div v-if="shownProblem('token')" class="upstream-form-problem">{{ shownProblem('token') }}</div>
                            </div>
                            <div v-if="model.credentialKind === 'API_KEY'" class="upstream-form-secret">
                                <div>Key id</div>
                                <n-input v-model:value="model.keyId" type="text" data-testid="upstream-form-key-id" />
                                <div v-if="shownProblem('keyId')" class="upstream-form-problem">{{ shownProblem('keyId') }}</div>
                                <div>Key secret</div>
                                <n-input v-model:value="keySecret" type="password" show-password-on="click"
                                    :input-props="{ autocomplete: 'new-password' }" data-testid="upstream-form-key-secret" />
                                <div v-if="shownProblem('keySecret')" class="upstream-form-problem">{{ shownProblem('keySecret') }}</div>
                            </div>
                        </template>
                    </div>
                </n-form-item>

                <n-form-item label="Documents to ingest" :feedback="shownProblem('documentClasses')" :validation-status="statusOf('documentClasses')">
                    <n-checkbox-group v-model:value="model.documentClasses" data-testid="upstream-form-document-classes">
                        <n-space>
                            <n-checkbox v-for="c in DOCUMENT_CLASSES" :key="c" :value="c" :label="documentClassLabel[c]" />
                        </n-space>
                    </n-checkbox-group>
                </n-form-item>
                <n-form-item label="Supplier VEX" :feedback="shownProblem('vexImportMode')" :validation-status="statusOf('vexImportMode')">
                    <n-radio-group v-model:value="model.vexImportMode" data-testid="upstream-form-vex-mode">
                        <n-space vertical>
                            <n-radio v-for="m in IMPORT_MODES" :key="m" :value="m">{{ vexModeSentence[m] }}</n-radio>
                        </n-space>
                    </n-radio-group>
                </n-form-item>
                <n-form-item label="Auto-integrate">
                    <n-space vertical>
                        <n-switch v-model:value="model.autoIntegrate" data-testid="upstream-form-auto-integrate" />
                        <span class="upstream-form-note">{{ autoIntegrateSentence }}</span>
                    </n-space>
                </n-form-item>
                <n-form-item label="Sync interval (minutes)" :feedback="shownProblem('syncIntervalMinutes')" :validation-status="statusOf('syncIntervalMinutes')">
                    <n-space align="center">
                        <n-input-number v-model:value="model.syncIntervalMinutes" :step="60" data-testid="upstream-form-interval" />
                        <span>{{ intervalWords(model.syncIntervalMinutes) }}</span>
                    </n-space>
                </n-form-item>
                <n-form-item label="Pass documents on">
                    <n-space vertical>
                        <n-space align="center">
                            <n-switch v-model:value="model.reServe" data-testid="upstream-form-re-serving" />
                            <span>{{ reServingFieldLabel }}</span>
                        </n-space>
                        <span class="upstream-form-note">{{ reServingSentence }}</span>
                    </n-space>
                </n-form-item>
            </n-form>

            <n-alert v-if="saveError" type="error" class="upstream-form-error" data-testid="upstream-form-error">{{ saveError }}</n-alert>
            <n-space justify="end" class="upstream-form-buttons">
                <span v-if="nothingChanged" data-testid="upstream-form-nothing">{{ nothingToChange }}</span>
                <n-button :disabled="saving" data-testid="upstream-form-cancel" @click="close">Cancel</n-button>
                <n-button type="primary" :disabled="saving || nothingChanged" :loading="saving" data-testid="upstream-form-save" @click="save">Save</n-button>
            </n-space>
        </div>
    </n-modal>
</template>

<script lang="ts" setup>
// Create and edit an upstream source (TPF-185 design 3.6). The credential is the part the threat
// model leans on: the two secret values live in the refs token and keySecret below and nowhere
// else, are never prefilled (the backend serves no credential), are cleared whenever the modal
// opens or closes and on unmount, and are sent once, inside the mutation's variables. The modal
// emits the saved source's uuid and nothing else. Mounted for administrators only.
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { NAlert, NButton, NCheckbox, NCheckboxGroup, NForm, NFormItem, NInput, NInputNumber, NModal, NRadio, NRadioGroup, NSpace, NSwitch } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { refusalOf } from '@/utils/upstream'
import { CREATE_UPSTREAM_SOURCE, UPDATE_UPSTREAM_SOURCE } from '@/utils/upstreamSourceQueries'
import {
    CredentialChange, DOCUMENT_CLASSES, IMPORT_MODES, buildCreateInput, buildUpdateInput, createModel, createProblems, editModel,
    intervalWords, nothingToChange, reServingFieldLabel, rootHint, updateProblems,
} from '@/utils/upstreamSourceForm'
import type { FormField, FormProblems, LoadedUpstreamSource, UpstreamCredentialKindValue, UpstreamSourceFormModel, UpstreamSourceSecrets } from '@/utils/upstreamSourceForm'
import { autoIntegrateSentence, credentialKindLabel, documentClassLabel, reServingSentence, sourceKindLabel, vexModeSentence } from '@/utils/upstreamLabels'

const props = defineProps<{
    show: boolean
    orgUuid: string
    /** The source to edit; null or absent to create one. */
    source?: LoadedUpstreamSource | null
    /** Create only: the root to start with, e.g. a root a TEI led to. */
    initialRoot?: string | null
}>()

const emit = defineEmits<{
    (e: 'update:show', value: boolean): void
    (e: 'saved', uuid: string): void
}>()

const isEdit = computed((): boolean => !!props.source)
const model = ref<UpstreamSourceFormModel>(createModel())
// The two secrets. Nothing else holds them.
const token = ref('')
const keySecret = ref('')
const saving = ref(false)
const saveError = ref('')
const attempted = ref(false)

function clearSecrets () {
    token.value = ''
    keySecret.value = ''
}

function reset () {
    clearSecrets()
    model.value = props.source ? editModel(props.source) : createModel(props.initialRoot)
    saveError.value = ''
    attempted.value = false
    saving.value = false
}

watch(() => props.show, (open) => {
    if (open) reset()
    else clearSecrets()
}, { immediate: true })

onBeforeUnmount(clearSecrets)

const secrets = (): UpstreamSourceSecrets => ({ token: token.value, keySecret: keySecret.value })

const storedKind = computed((): UpstreamCredentialKindValue => props.source?.credential?.kind ?? 'NONE')

const storedCredentialText = computed((): string => {
    const c = props.source?.credential
    if (!c || c.kind === 'NONE') return 'Stored: no credential'
    const kind = credentialKindLabel[c.kind] || ''
    return c.setDate ? `Stored: ${kind}, set on ${commonFunctions.dateDisplay(c.setDate)}` : `Stored: ${kind}`
})

const showKindChoice = computed((): boolean => !isEdit.value || model.value.credentialChange === CredentialChange.REPLACE)

const kindChoices = computed((): UpstreamCredentialKindValue[] => isEdit.value
    ? ['BEARER_TOKEN', 'API_KEY']
    : ['NONE', 'BEARER_TOKEN', 'API_KEY'])

watch(() => model.value.credentialChange, (change) => {
    // A new choice starts from empty fields: nothing typed for one choice is kept for another.
    clearSecrets()
    model.value.keyId = ''
    if (change === CredentialChange.REPLACE && model.value.credentialKind === 'NONE') model.value.credentialKind = 'BEARER_TOKEN'
})

watch(() => model.value.credentialKind, () => clearSecrets())

const problems = computed((): FormProblems => props.source
    ? updateProblems(props.source, model.value, secrets())
    : createProblems(model.value, secrets()))

const updateInput = computed((): Record<string, any> | null => props.source
    ? buildUpdateInput(props.source, model.value, secrets())
    : null)

const nothingChanged = computed((): boolean => !!updateInput.value && Object.keys(updateInput.value).length === 0)

function shownProblem (field: FormField): string {
    return attempted.value ? (problems.value[field] ?? '') : ''
}

function statusOf (field: FormField): 'error' | undefined {
    return shownProblem(field) ? 'error' : undefined
}

// The root rule shows at once: it explains why the save is blocked, not what was mistyped.
const credentialFeedback = computed((): string => {
    const p = problems.value.credential ?? ''
    if (p && (attempted.value || model.value.credentialChange === CredentialChange.KEEP)) return p
    return ''
})

function close () {
    clearSecrets()
    emit('update:show', false)
}

function onUpdateShow (value: boolean) {
    if (!value) close()
}

async function save () {
    attempted.value = true
    saveError.value = ''
    if (Object.keys(problems.value).length || nothingChanged.value || saving.value) return
    saving.value = true
    try {
        const resp: any = props.source
            ? await graphqlClient.mutate({ mutation: UPDATE_UPSTREAM_SOURCE, variables: { uuid: props.source.uuid, source: updateInput.value }, fetchPolicy: 'no-cache' })
            : await graphqlClient.mutate({ mutation: CREATE_UPSTREAM_SOURCE, variables: { org: props.orgUuid, source: buildCreateInput(model.value, secrets()) }, fetchPolicy: 'no-cache' })
        const saved = props.source ? resp?.data?.updateUpstreamSource : resp?.data?.createUpstreamSource
        const uuid: string = saved?.uuid ?? props.source?.uuid ?? ''
        saving.value = false
        close()
        emit('saved', uuid)
    } catch (err) {
        // Only the backend's sentence reaches the alert; the form keeps what was typed.
        saveError.value = refusalOf(err).text
        saving.value = false
    }
}
</script>

<style scoped>
.upstream-form-credential {
    width: 100%;
}
.upstream-form-kind-choice {
    margin-top: 8px;
}
.upstream-form-secret {
    margin-top: 8px;
}
.upstream-form-problem {
    color: #d03050;
    font-size: 0.9em;
}
.upstream-form-note {
    font-size: 0.9em;
    color: #666;
}
.upstream-form-error {
    margin-bottom: 12px;
}
</style>
