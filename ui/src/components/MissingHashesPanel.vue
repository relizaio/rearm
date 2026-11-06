<template>
    <div data-testid="missing-hashes-panel">
        <n-spin v-if="loadingHeads" size="small" />
        <n-alert v-else-if="loadError" type="error" :show-icon="false">{{ loadError }}</n-alert>
        <!-- No identity: the sentence and nothing else (3.9). -->
        <div v-else-if="!heads.length" data-testid="missing-hashes-no-identity" style="font-size: 13px;">
            {{ NO_IDENTITY_SENTENCE }}
        </div>
        <div v-else>
            <n-form label-placement="top" :disabled="saving">
                <n-form-item label="Release SBOM">
                    <n-select v-model:value="headUuid" :options="headOptions" data-testid="missing-hashes-head"
                        @update:value="loadComponents" />
                </n-form-item>
                <n-form-item label="Components">
                    <n-radio-group v-model:value="choice.form" size="small" data-testid="missing-hashes-selector">
                        <n-radio-button v-for="o in SELECTOR_FORM_OPTIONS" :key="o.value" :value="o.value"
                            :label="o.value === 'EXPLICIT' ? 'Shown' : o.label" />
                    </n-radio-group>
                </n-form-item>
                <n-space v-if="choice.form !== 'EXPLICIT'" style="margin-bottom: 12px;">
                    <n-input v-model:value="choice.type" placeholder="Ecosystem (purl type), e.g. deb"
                        size="small" style="width: 220px;" />
                    <n-input v-if="choice.form === 'NAMESPACE'" v-model:value="choice.namespace"
                        placeholder="Namespace, e.g. debian" size="small" style="width: 220px;" />
                </n-space>
                <div v-if="choice.form === 'EXPLICIT'" style="max-height: 240px; overflow-y: auto; margin-bottom: 12px;">
                    <n-button size="small" text type="primary" data-testid="missing-hashes-select-all"
                        @click="selectAllShown">Select all shown</n-button>
                    <div v-for="c in components" :key="c.key" style="font-size: 12px; margin: 2px 0;">
                        <n-checkbox :checked="checked.includes(c.key)" @update:checked="v => toggle(c.key, v)">
                            {{ c.name || c.key }} {{ c.version || '' }}
                        </n-checkbox>
                        <!-- A justified row shows what an explicit set would replace (3.2a). -->
                        <span v-if="c.justification" data-testid="missing-hashes-justified">
                            &mdash; {{ justifiedUnknownLabel(c.justification.code) }}
                            <n-tag v-if="c.justification.bulkAct" size="tiny">bulk</n-tag>
                            <n-button size="tiny" text type="error" style="margin-left: 6px;"
                                data-testid="missing-hashes-clear" @click="clearOne(c.key)">Clear</n-button>
                        </span>
                    </div>
                </div>
                <n-form-item label="Why the hash cannot be given">
                    <n-select v-model:value="input.code" :options="justifiedUnknownOptions(HASH_CODES)"
                        placeholder="Choose a reason" />
                </n-form-item>
                <n-form-item label="Explanation (required for Other)">
                    <n-input v-model:value="input.text" type="textarea" :rows="2" />
                </n-form-item>
                <n-form-item label="Reason (audit only, never exported; required to clear)">
                    <n-input v-model:value="input.reason" />
                </n-form-item>
            </n-form>
            <n-alert v-for="e in errors" :key="e" type="error" :show-icon="false"
                style="margin-top: 4px; font-size: 12px;">{{ e }}</n-alert>
            <div v-if="choice.form !== 'EXPLICIT' && previewCount !== null" data-testid="missing-hashes-count"
                style="font-size: 12px; margin-top: 6px;">
                Matches {{ previewCount }} {{ previewCount === 1 ? 'component' : 'components' }} without a hash
                in this release SBOM.
            </div>
            <n-alert v-if="writeError" type="error" :show-icon="false" style="margin-top: 8px;">{{ writeError }}</n-alert>
            <div v-if="result" data-testid="missing-hashes-result" style="font-size: 12px; margin-top: 8px;">
                Revision {{ result.revision }}: {{ result.hashes.length }} justified.
                <ul v-if="result.skippedKeys.length" style="margin: 4px 0 0 18px;">
                    <li v-for="line in skippedKeysText(result)" :key="line">{{ line }}</li>
                </ul>
            </div>
            <n-space justify="end" style="margin-top: 12px;">
                <n-button v-if="choice.form !== 'EXPLICIT'" size="small" :disabled="errors.length > 0 || saving"
                    data-testid="missing-hashes-preview" @click="preview">Count matches</n-button>
                <n-button size="small" type="primary" data-testid="missing-hashes-confirm"
                    :disabled="errors.length > 0 || saving || (choice.form !== 'EXPLICIT' && previewCount === null)"
                    :loading="saving" @click="submit">Justify</n-button>
            </n-space>
        </div>
    </div>
</template>

<script lang="ts" setup>
// The Missing hashes tab of the bulk dialog (FDAW-14 3.9). COMPONENT WRITE: offered under
// isWritable, and canWriteOrg is not read here. The rules live in releaseSbomHashJustification
// and bulkSelectorForm, which have specs; this is selection and display.
import { computed, onMounted, reactive, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { NAlert, NButton, NCheckbox, NForm, NFormItem, NInput, NRadioButton, NRadioGroup, NSelect, NSpace, NSpin,
    NTag } from 'naive-ui'
import graphqlClient from '../utils/graphql'
import { SELECTOR_FORM_OPTIONS, emptySelectorChoice } from '@/utils/bulkSelectorForm'
import type { BulkSelectorChoice } from '@/utils/bulkSelectorForm'
import { HASH_CODES, justifiedUnknownLabel, justifiedUnknownOptions } from '@/utils/justifiedUnknownLabels'
import { NO_IDENTITY_SENTENCE, clearHashJustification, countHashlessBySelector, headLabel, loadHashlessComponents,
    loadReleaseSbomHeads, skippedKeysText, submitHashJustification, validateHashJustification }
    from '@/utils/releaseSbomHashJustification'
import type { HashJustificationInput, HashJustificationResult, HashlessComponent, ReleaseSbomHead }
    from '@/utils/releaseSbomHashJustification'

const props = defineProps<{ releaseUuid: string }>()

const heads: Ref<ReleaseSbomHead[]> = ref([])
const headUuid: Ref<string | null> = ref(null)
const components: Ref<HashlessComponent[]> = ref([])
const checked: Ref<string[]> = ref([])
const loadingHeads = ref(true)
const loadError: Ref<string | null> = ref(null)
const saving = ref(false)
const writeError: Ref<string | null> = ref(null)
const result: Ref<HashJustificationResult | null> = ref(null)
const previewCount: Ref<number | null> = ref(null)
const choice: BulkSelectorChoice = reactive(emptySelectorChoice())
const input: HashJustificationInput = reactive({ code: null, text: '', reason: '' })

const headOptions = computed(() => heads.value.map(h => ({ label: headLabel(h), value: h.uuid })))
const errors = computed(() => validateHashJustification(choice, checked.value, input))

// A preview counted for another selector is not this selector's count.
watch(() => [choice.form, choice.type, choice.namespace, headUuid.value], () => { previewCount.value = null })

onMounted(async () => {
    try {
        heads.value = await loadReleaseSbomHeads(graphqlClient as any, props.releaseUuid)
        if (heads.value.length) {
            headUuid.value = heads.value[0].uuid
            await loadComponents()
        }
    } catch (err: any) {
        loadError.value = err?.message || String(err)
    } finally {
        loadingHeads.value = false
    }
})

async function loadComponents () {
    checked.value = []
    if (!headUuid.value) return
    try {
        components.value = await loadHashlessComponents(graphqlClient as any, headUuid.value)
    } catch (err: any) {
        components.value = []
        writeError.value = err?.message || String(err)
    }
}

function toggle (key: string, on: boolean) {
    checked.value = on ? [...new Set([...checked.value, key])] : checked.value.filter(k => k !== key)
}

function selectAllShown () { checked.value = components.value.map(c => c.key) }

async function preview () {
    if (!headUuid.value) return
    writeError.value = null
    try {
        previewCount.value = await countHashlessBySelector(graphqlClient as any, headUuid.value, choice)
    } catch (err: any) {
        writeError.value = err?.message || String(err)
    }
}

async function submit () {
    if (!headUuid.value || errors.value.length) return
    saving.value = true
    writeError.value = null
    try {
        result.value = await submitHashJustification(graphqlClient as any, headUuid.value, choice, checked.value, input)
        await loadComponents()
    } catch (err: any) {
        writeError.value = err?.message || String(err)
    } finally {
        saving.value = false
    }
}

async function clearOne (key: string) {
    if (!headUuid.value) return
    writeError.value = null
    try {
        result.value = await clearHashJustification(graphqlClient as any, headUuid.value, [key], input.reason)
        await loadComponents()
    } catch (err: any) {
        writeError.value = err?.message || String(err)
    }
}
</script>
