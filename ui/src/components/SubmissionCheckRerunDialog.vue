<template>
    <n-modal :show="show" preset="card" :title="title" style="max-width: 1200px" data-testid="check-rerun-dialog"
        @update:show="(v: boolean) => emit('update:show', v)">
        <div v-if="show" class="check-rerun-columns">
            <div class="check-rerun-column" data-testid="check-rerun-stored">
                <div class="check-rerun-heading" data-testid="check-rerun-stored-heading">{{ storedHeading(loader.checkers.stored, loader.reports.stored) }}</div>
                <sbom-score-panel :load="loader.loadStored" file-kind="stored" :file-id="submission" />
            </div>
            <div class="check-rerun-column" data-testid="check-rerun-fresh">
                <div class="check-rerun-heading" data-testid="check-rerun-fresh-heading">{{ freshHeading(loader.checkers.fresh, loader.reports.fresh) }}</div>
                <sbom-score-panel :load="loader.loadFresh" file-kind="now" :file-id="submission" />
            </div>
        </div>
    </n-modal>
</template>

<script setup lang="ts">
// The re-run of a Final version's check (FDAW-16 3.9a): mounted from a version row, it asks the server once
// for both reports and shows them side by side, the stored one and the engine's answer now, each in the
// panel the export check uses (its errors, its raw-report actions). Nothing is stored; closing drops both.
import { computed } from 'vue'
import { NModal } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import submissionQueries from '@/utils/submissionQueries'
import { checkRerunLoader, freshHeading, storedHeading, type CheckRerun } from '@/utils/submissionCheckRerun'
import SbomScorePanel from './SbomScorePanel.vue'

const props = defineProps<{
    show: boolean
    submission: string
    version: number
}>()
const emit = defineEmits<{ (e: 'update:show', value: boolean): void }>()

const title = computed(() => `Re-run check: v${props.version}`)

async function fetchRerun (signal: AbortSignal): Promise<CheckRerun | null> {
    const resp: any = await graphqlClient.query({
        query: submissionQueries.SubmissionVersionCheckRerunGql,
        variables: { submission: props.submission, version: props.version },
        fetchPolicy: 'no-cache',
        context: { fetchOptions: { signal } },
    })
    return resp?.data?.submissionVersionCheckRerun ?? null
}

// One loader per mount: the panel mounts the dialog when a row's button is pressed and unmounts it on close.
const loader = checkRerunLoader(fetchRerun)
</script>

<style scoped>
.check-rerun-columns {
    display: flex;
    gap: 16px;
    flex-wrap: wrap;
}
.check-rerun-column {
    flex: 1 1 420px;
    min-width: 0;
}
.check-rerun-heading {
    font-weight: 600;
    margin-bottom: 6px;
}
</style>
