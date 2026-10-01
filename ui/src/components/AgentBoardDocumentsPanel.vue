<template>
    <div class="boardDocuments">
        <p v-if="!rows.length" class="boardDocuments__empty">No documents yet.</p>
        <table v-else class="boardDocuments__table">
            <thead>
                <tr>
                    <th>Document</th><th>Component</th><th>Latest</th><th>State</th><th>Rounds</th>
                    <th>Open review items</th><th>Element checks</th>
                </tr>
            </thead>
            <tbody>
                <tr v-for="r in rows" :key="r.specification" class="boardDocuments__row">
                    <td>{{ r.label }}</td>
                    <td>
                        <router-link v-if="r.component && orgUuid"
                                     :to="{ name: 'ComponentsOfOrg', params: { orguuid: orgUuid, compuuid: r.component } }">
                            {{ r.componentName }}
                        </router-link>
                        <span v-else>{{ r.componentName }}</span>
                    </td>
                    <td>{{ r.latest }}</td>
                    <td>{{ r.lifecycle }}</td>
                    <td>{{ r.roundsCount }}</td>
                    <td>{{ r.openReviewItems ?? '—' }}</td>
                    <!-- The task page's summary, the verdict by the same rule beside it (RD2-24). -->
                    <td class="boardDocuments__checks" :data-verdict="r.elementCheckVerdict ?? undefined">
                        {{ r.elementChecks }}<span v-if="r.elementCheckVerdict && r.elementChecks !== r.elementCheckVerdict" class="boardDocuments__verdict"> · {{ r.elementCheckVerdict }}</span>
                    </td>
                </tr>
            </tbody>
        </table>
    </div>
</template>

<script lang="ts" setup>
// What a board has produced, one row per document series (board-documents.md §5, task 36d0549e): its
// document components live here rather than in the org's Components list.
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { documentSeriesRows } from '@/utils/agentDocumentsView'

const props = defineProps<{
    /** AgentBoard.documentSeries */
    series: any[] | null | undefined
    orgUuid?: string
}>()

const rows = computed(() => documentSeriesRows(props.series))
</script>

<style scoped lang="scss">
.boardDocuments__table { width: 100%; border-collapse: collapse; font-size: 13px; }
.boardDocuments__table th, .boardDocuments__table td { text-align: left; padding: 4px 8px; border-bottom: 1px solid #eee; }
.boardDocuments__empty { color: #888; }
</style>
