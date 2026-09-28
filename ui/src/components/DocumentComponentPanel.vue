<template>
    <div class="documentComponent">
        <n-alert type="info" class="documentComponent__banner" :show-icon="false">
            <router-link v-if="banner.board && orgUuid" class="documentComponent__board"
                         :to="{ name: 'AiAgentsOfOrg', params: { orguuid: orgUuid }, query: { tab: 'boards', board: banner.board } }">
                {{ banner.text }}
            </router-link>
            <span v-else class="documentComponent__board">{{ banner.text }}</span>
        </n-alert>
        <div class="dsec__h documentComponent__title" data-testid="rounds-heading">Rounds</div>
        <p v-if="!rows.length" class="documentComponent__empty">No rounds published yet.</p>
        <table v-else class="documentComponent__rounds">
            <thead>
                <tr><th>Round</th><th>Version</th><th>State</th><th>Path</th><th>Task</th><th>Published</th></tr>
            </thead>
            <tbody>
                <tr v-for="r in rows" :key="r.uuid" class="documentComponent__round clickable" @click="openRelease(r.uuid)">
                    <td>{{ r.round ?? '—' }}</td>
                    <td>{{ r.version }}</td>
                    <td>{{ lifecycleWord(r.lifecycle) }}</td>
                    <td><code>{{ r.path }}</code></td>
                    <td>
                        <router-link v-if="r.taskUuid" :to="taskPagePath(r.taskUuid)" data-testid="round-task" @click.stop>{{ r.task }}</router-link>
                    </td>
                    <td><agent-time :at="r.publishedAt"/></td>
                </tr>
            </tbody>
        </table>
    </div>
</template>

<script lang="ts" setup>
// A DOCUMENT component's page (board-documents.md §5, task 36d0549e): the board it belongs to, and its
// releases as the rounds they are. The software panels are not shown for it; ComponentView decides.
import AgentTime from './AgentTime.vue'
import { computed, ref, watch } from 'vue'
import { NAlert } from 'naive-ui'
import { RouterLink, useRouter } from 'vue-router'
import { useStore } from 'vuex'
import { documentBoardBanner, documentRoundRows } from '@/utils/agentDocumentsView'
import { lifecycleWord } from '@/utils/agentWords'
import { taskPagePath } from '@/utils/agentTaskFormat'

const props = defineProps<{
    /** The component, with agentBoard { uuid name taskPrefix }. */
    component: any
    orgUuid?: string
    /** Its base branch, where the board publishes the rounds. */
    baseBranchUuid?: string | null
}>()

const store = useStore()
const router = useRouter()
const releases = ref<any[]>([])
const taskKeys = ref<Record<string, string>>({})

const banner = computed(() => documentBoardBanner(props.component?.agentBoard))
const rows = computed(() => documentRoundRows(releases.value, taskKeys.value))

async function load () {
    releases.value = []
    taskKeys.value = {}
    if (!props.baseBranchUuid) return
    const r = await store.dispatch('fetchDocumentRounds', props.baseBranchUuid).catch(() => null)
    releases.value = r?.releases ?? []
    taskKeys.value = r?.taskKeys ?? {}
}

function openRelease (uuid: string) {
    router.push({ name: 'ReleaseView', params: { uuid } })
}

watch(() => props.baseBranchUuid, load, { immediate: true })
</script>

<style scoped lang="scss">
.documentComponent__rounds { width: 100%; border-collapse: collapse; font-size: 13px; }
.documentComponent__rounds th, .documentComponent__rounds td { text-align: left; padding: 4px 8px; border-bottom: 1px solid #eee; }
.documentComponent__banner { margin-bottom: 10px; }
.documentComponent__title { font-size: 14px; font-weight: 600; margin: 4px 0 8px; }
.documentComponent__empty { color: #888; }
.clickable { cursor: pointer; }
</style>
