<template>
    <div class="role-strength">
        <div class="flabel" style="margin-bottom: 4px">model strength</div>
        <n-space :size="8" align="center" wrap>
            <n-input-number v-model:value="strength.requiredStrength" :min="0" :precision="2" :step="0.25"
                            clearable placeholder="no floor" style="width: 160px">
                <template #prefix><span class="flabel">floor</span></template>
            </n-input-number>
            <n-input-number v-model:value="strength.strengthHeadroom" :min="0" :precision="2" :step="0.25"
                            :disabled="strength.requiredStrength == null" style="width: 170px">
                <template #prefix><span class="flabel">headroom</span></template>
            </n-input-number>
            <n-select v-model:value="strength.strengthCategory" :options="categoryOptions" clearable
                      placeholder="reads the model's base strength" style="width: 250px"/>
        </n-space>
        <n-text depth="3" style="font-size: 11.5px; display: block; margin-top: 4px;">
            A model may take this role when its strength is at least the floor and at most floor + headroom
            (two decimals; 0.001 either side counts as a match). The category picks which of a model's
            per-category strengths applies — a PLANNER can read ARCHITECT.
        </n-text>

        <div class="flabel" style="margin: 10px 0 4px">per-model overrides for this role</div>
        <n-space vertical :size="6">
            <n-space v-for="(o, i) in strength.modelStrengths" :key="i" :size="8" align="center">
                <n-select v-model:value="o.model" :options="modelOptions" filterable placeholder="model"
                          style="width: 300px"/>
                <n-input-number v-model:value="o.strength" :min="0" :precision="2" :step="0.25" style="width: 130px"/>
                <n-button size="tiny" quaternary @click="strength.modelStrengths.splice(i, 1)">remove</n-button>
            </n-space>
            <n-button size="tiny" dashed @click="strength.modelStrengths.push({ model: null, strength: null })">
                add override
            </n-button>
        </n-space>
        <n-text depth="3" style="font-size: 11.5px; display: block; margin-top: 4px;">
            An override wins over everything the catalogue says about that model, for this role only.
        </n-text>
    </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { NSpace, NInputNumber, NSelect, NText, NButton } from 'naive-ui'
import type { StrengthDraft } from '@/utils/roleStrength'

/**
 * The strength part of a role or preset, bound with v-model:strength to a draft made by
 * strengthDraft(); strengthInput() turns the draft into what the API takes.
 */
const strength = defineModel<StrengthDraft>('strength', { required: true })
const props = defineProps<{
    models: { uuid: string, name: string, version?: string }[]
}>()

const categoryOptions = ['ARCHITECT', 'CODER', 'QA', 'REVIEWER'].map(c => ({ label: c, value: c }))

const modelOptions = computed(() => props.models.map(m => ({
    label: m.version && m.version !== 'unknown' ? `${m.name} ${m.version}` : m.name,
    value: m.uuid,
})))
</script>

<style scoped>
.flabel { font-size: 12px; color: var(--n-text-color-3, #888); }
</style>
