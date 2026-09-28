<template>
    <!-- The operator's lock (RD2-17): a reason asked in the page, not a browser prompt, and an unlock that
         names what it lifts before it does. Shown only to who may operate the board. -->
    <n-popover v-if="action === 'lock'" v-model:show="open" trigger="click" placement="bottom-end" style="width: 300px">
        <template #trigger>
            <n-button size="tiny" quaternary data-testid="lock-open">Operator lock</n-button>
        </template>
        <div class="lockform" data-testid="lock-form">
            <n-input v-model:value="reason" size="small" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }"
                     placeholder="Why lock it (required)" data-testid="lock-reason"/>
            <div class="lockform__note">{{ LOCK_NOTE }}</div>
            <n-space justify="end" :size="6">
                <n-button size="tiny" data-testid="lock-cancel" @click="cancel">Cancel</n-button>
                <n-button size="tiny" type="error" :disabled="!lockReasonToSend(reason)" data-testid="lock-submit" @click="submit">
                    Lock
                </n-button>
            </n-space>
        </div>
    </n-popover>
    <n-popconfirm v-else positive-text="Unlock" negative-text="Cancel" @positive-click="emit('unlock')">
        <template #trigger>
            <n-button size="tiny" style="margin-left: 10px" data-testid="unlock-open">Operator unlock</n-button>
        </template>
        <span data-testid="unlock-confirm">{{ unlockConfirmText(board, when) }}</span>
    </n-popconfirm>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import { NButton, NInput, NPopconfirm, NPopover, NSpace } from 'naive-ui'
import { LOCK_NOTE, lockReasonToSend, unlockConfirmText } from '@/utils/agentBoardLock'

defineProps<{
    action: 'lock' | 'unlock'
    board: any
    /** How the lock's time reads, as the board page formats its times. */
    when: (at: string) => string
}>()
const emit = defineEmits<{
    (e: 'lock', p: { reason: string }): void
    (e: 'unlock'): void
}>()

const open = ref(false)
const reason = ref('')

function cancel () {
    open.value = false
    reason.value = ''
}

function submit () {
    const r = lockReasonToSend(reason.value)
    if (!r) return
    emit('lock', { reason: r })
    cancel()
}
</script>

<style scoped>
.lockform { display: flex; flex-direction: column; gap: 6px; }
.lockform__note { font-size: 12px; color: #888; }
</style>
