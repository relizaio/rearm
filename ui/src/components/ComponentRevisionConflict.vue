<template>
    <!-- TPF-62: shown when the server refused a settings save because the component changed
         after the page read it. Every text below is interpolated, never v-html: the who-label
         is a name another member chose. -->
    <n-alert type="warning" data-testid="revision-conflict" style="margin: 8px 0 12px;"
        :title="`This ${componentWord} was changed after you opened it`">
        <div data-testid="revision-conflict-who">{{ whoLine }}</div>
        <div style="margin-top: 4px;">
            Reload shows the current settings and discards your unsaved changes here.{{ canForce ? ' Force saves your values over that change.' : '' }}
        </div>
        <div style="margin-top: 4px;">Locks, archive status and perspective membership are not part of this save.</div>
        <n-space style="margin-top: 10px;">
            <n-button size="small" data-testid="revision-conflict-reload" @click="emit('reload')">Reload</n-button>
            <n-button v-if="canForce" size="small" type="warning" data-testid="revision-conflict-force"
                @click="emit('force')">Force save</n-button>
        </n-space>
    </n-alert>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { NAlert, NButton, NSpace } from 'naive-ui'
import { describeLastUpdater, type RevisionConflict, type UserOption } from '@/utils/componentRevision'

const props = withDefaults(defineProps<{
    conflict: RevisionConflict
    users?: UserOption[]
    canForce?: boolean
    when?: (iso: string) => string
    componentWord?: string
}>(), { users: () => [], canForce: false, when: (iso: string) => iso, componentWord: 'component' })

const emit = defineEmits<{ (e: 'reload'): void, (e: 'force'): void }>()

const whoLine = computed((): string => {
    const who = describeLastUpdater(props.conflict, props.users)
    const date = props.conflict.lastUpdatedDate
    return date ? `Changed by ${who} at ${props.when(date)}.` : `Changed by ${who}.`
})
</script>
