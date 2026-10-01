<template>
    <!-- The operator's pause (RD2-17): a reason asked in the page, not a browser prompt, and a resume that
         names what it lifts before it does. Shown only to who may operate the board. -->
    <n-popover v-if="action === 'pause'" v-model:show="open" trigger="click" placement="bottom-end" style="width: 300px">
        <template #trigger>
            <n-button size="tiny" quaternary data-testid="pause-open">Operator pause</n-button>
        </template>
        <div class="pauseform" data-testid="pause-form">
            <n-input v-model:value="reason" size="small" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }"
                     placeholder="Why pause it (required)" data-testid="pause-reason"/>
            <div class="pauseform__note">{{ PAUSE_NOTE }}</div>
            <n-space justify="end" :size="6">
                <n-button size="tiny" data-testid="pause-cancel" @click="cancel">Cancel</n-button>
                <n-button size="tiny" type="error" :disabled="!pauseReasonToSend(reason)" data-testid="pause-submit" @click="submit">
                    Pause
                </n-button>
            </n-space>
        </div>
    </n-popover>
    <n-popconfirm v-else positive-text="Resume" negative-text="Cancel" @positive-click="emit('resume')">
        <template #trigger>
            <n-button size="tiny" style="margin-left: 10px" data-testid="resume-open">Operator resume</n-button>
        </template>
        <span data-testid="resume-confirm">{{ resumeConfirmText(board, when) }}</span>
    </n-popconfirm>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import { NButton, NInput, NPopconfirm, NPopover, NSpace } from 'naive-ui'
import { PAUSE_NOTE, pauseReasonToSend, resumeConfirmText } from '@/utils/agentBoardPause'

defineProps<{
    action: 'pause' | 'resume'
    board: any
    /** How the pause's time reads, as the board page formats its times. */
    when: (at: string) => string
}>()
const emit = defineEmits<{
    (e: 'pause', p: { reason: string }): void
    (e: 'resume'): void
}>()

const open = ref(false)
const reason = ref('')

function cancel () {
    open.value = false
    reason.value = ''
}

function submit () {
    const r = pauseReasonToSend(reason.value)
    if (!r) return
    emit('pause', { reason: r })
    cancel()
}
</script>

<style scoped>
.pauseform { display: flex; flex-direction: column; gap: 6px; }
.pauseform__note { font-size: 12px; color: #888; }
</style>
