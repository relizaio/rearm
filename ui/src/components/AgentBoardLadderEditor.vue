<template>
    <!-- The board's level ladder (task RD3-6), in the board form: the rungs in order, each numbered by its
         place from 0, and the prompt that overrides the served ladder section. Empty removes the ladder. -->
    <div class="ladder" data-testid="board-ladder">
        <div class="flabel">level ladder</div>
        <n-text depth="3" class="ladder__help">{{ LADDER_HELP }}</n-text>
        <div v-if="!modelValue.levels.length" class="ladder__empty" data-testid="ladder-empty">
            No ladder: tasks on this board carry no level.
        </div>
        <div v-for="(l, i) in modelValue.levels" :key="i" class="ladder__row" data-testid="ladder-level">
            <span class="ladder__num" data-testid="ladder-number">{{ i }}</span>
            <n-input :value="l.name" size="small" placeholder="Name, e.g. requirements" data-testid="ladder-name"
                     style="width: 200px" @update:value="(v: string) => update(setLadderLevel(modelValue, i, 'name', v))"/>
            <n-input :value="l.description" size="small" placeholder="Description (optional)" data-testid="ladder-description"
                     @update:value="(v: string) => update(setLadderLevel(modelValue, i, 'description', v))"/>
            <n-button size="tiny" quaternary :disabled="i === 0" data-testid="ladder-up" title="Move up"
                      @click="update(moveLadderLevel(modelValue, i, -1))">↑</n-button>
            <n-button size="tiny" quaternary :disabled="i === modelValue.levels.length - 1" data-testid="ladder-down"
                      title="Move down" @click="update(moveLadderLevel(modelValue, i, 1))">↓</n-button>
            <n-button size="tiny" quaternary data-testid="ladder-remove" @click="update(removeLadderLevel(modelValue, i))">
                Remove
            </n-button>
        </div>
        <n-button size="small" dashed :disabled="modelValue.levels.length >= MAX_LADDER_LEVELS" data-testid="ladder-add"
                  @click="update(addLadderLevel(modelValue))">+ Add level</n-button>
        <n-input v-if="modelValue.levels.length" :value="modelValue.prompt" type="textarea" :autosize="{ minRows: 2, maxRows: 8 }"
                 :placeholder="LADDER_PROMPT_HINT" data-testid="ladder-prompt"
                 @update:value="(v: string) => update({ ...modelValue, prompt: v ?? '' })"/>
        <n-text v-if="problem" type="error" class="ladder__err" data-testid="ladder-problem">{{ problem }}</n-text>
        <n-text v-else-if="error" type="error" class="ladder__err" data-testid="board-ladder-error">{{ error }}</n-text>
    </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { NButton, NInput, NText } from 'naive-ui'
import { addLadderLevel, LADDER_HELP, LADDER_PROMPT_HINT, LadderDraft, ladderProblem, moveLadderLevel, removeLadderLevel,
    setLadderLevel } from '@/utils/agentLadder'
import { MAX_LADDER_LEVELS } from '@/utils/agentTaskLevel'

const props = defineProps<{
    modelValue: LadderDraft
    /** A save refusal about the ladder, shown beside it. */
    error?: string | null
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: LadderDraft): void }>()

const problem = computed(() => ladderProblem(props.modelValue))

function update (v: LadderDraft) {
    emit('update:modelValue', v)
}
</script>

<style scoped lang="scss">
.ladder {
    display: flex;
    flex-direction: column;
    gap: 6px;
    &__help { font-size: 11.5px; }
    &__empty { font-size: 12px; color: #888; }
    &__row { display: flex; align-items: center; gap: 6px; }
    &__num { min-width: 18px; text-align: right; font-weight: 600; font-variant-numeric: tabular-nums; }
    &__err { font-size: 12px; }
}
.flabel { font-size: 12px; color: #666; }
</style>
