<template>
    <div v-if="investigation || returned.length" class="dsec" data-testid="task-investigation">
        <template v-if="investigation">
            <div class="dsec__h">Investigation</div>
            <div v-for="l in lines" :key="l.label" class="deprow invrow">
                <span class="deplab">{{ l.label }}</span>
                <span data-testid="investigation-line">{{ l.value }}</span>
            </div>
            <div class="deprow invrow" data-testid="investigation-report">
                <span class="deplab">report</span>
                <span v-if="report">
                    <code>{{ report.document?.path }}</code>
                    round {{ report.document?.round ?? '?' }} · {{ report.lifecycle }}
                    <span v-if="task.investigation.report === report.uuid"> · delivered</span>
                </span>
                <span v-else class="holdmeta" style="margin-top: 0">not published yet</span>
            </div>
        </template>
        <template v-if="returned.length">
            <div class="dsec__h">Reports returned</div>
            <div v-for="r in returned" :key="r.investigation" class="deprow invrow" data-testid="report-returned">
                <span class="deplab">{{ r.investigationKey ?? r.investigation }}</span>
                <span v-if="r.cancelled" data-testid="report-returned-cancelled">
                    cancelled: no report, this task no longer waits on it<span v-if="r.reoffered">; offered back to {{ r.role }}</span>
                    <span v-if="r.note"> · {{ r.note }}</span>
                    · <agent-time :at="r.at"/>
                </span>
                <span v-else>
                    report pinned as an input<span v-if="r.reoffered">, offered back to {{ r.role }}</span>
                    · <agent-time :at="r.at"/>
                </span>
            </div>
        </template>
    </div>
</template>

<script lang="ts" setup>
// An investigation's block and its report (task RD4-12), and on a task that commissioned one, the reports that
// came back to it -- a cancelled investigation among them, with its note (design round 2 §2).
import { computed } from 'vue'
import AgentTime from '../AgentTime.vue'
import { investigationLines, isInvestigation, reportOf } from '@/utils/agentInvestigation'
import { ts } from '@/utils/agentTaskFormat'

const props = defineProps<{ task: any, tasks?: any[] }>()
const investigation = computed(() => isInvestigation(props.task) ? props.task.investigation : null)
const lines = computed(() => investigationLines(props.task,
    (uuid: string) => (props.tasks ?? []).find((t: any) => t.uuid === uuid)?.key ?? null, ts))
const report = computed(() => reportOf(props.task))
const returned = computed(() => props.task?.reportsReturned ?? [])
</script>

<style scoped lang="scss">
@use './taskSections';
.invrow { align-items: baseline; }
</style>
