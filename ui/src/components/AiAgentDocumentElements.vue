<template>
    <!-- The element list under one document (elements.md §8): what the document names, how it is
         linked, and -- opened -- the findings about an element, what depends on it and which rounds
         changed it. -->
    <div class="els">
        <template v-for="e in elements" :key="e.id ?? ''">
            <div class="erow" :class="{ 'erow--open': open === e.id }" @click="toggle(e.id ?? '')">
                <code class="erow__id">{{ e.id }}</code>
                <n-tag v-if="e.family" size="tiny" :bordered="false">{{ e.family }}</n-tag>
                <span class="erow__title">{{ e.title }}</span>
                <span v-if="e.parent" class="erow__meta">↑ {{ e.parent }}</span>
                <span class="erow__meta">{{ counts.get(e.id ?? '')?.in ?? 0 }} in · {{ counts.get(e.id ?? '')?.out ?? 0 }} out</span>
                <n-tag v-if="warningsOf(release, e.id ?? '').length" size="tiny" :bordered="false" type="warning">
                    {{ warningsOf(release, e.id ?? '').length }} warning(s)
                </n-tag>
                <n-tag v-if="findings.get(e.id ?? '')?.length" size="tiny" :bordered="false" type="error">
                    {{ findings.get(e.id ?? '')?.length }} open
                </n-tag>
            </div>
            <div v-if="open === e.id" class="epanel">
                <div v-if="e.level != null || e.speculative?.length" class="epanel__line">
                    <template v-if="e.level != null">level {{ e.level }}</template>
                    <template v-if="e.speculative?.length"> · speculative: {{ e.speculative.join(', ') }}</template>
                </div>
                <div v-for="(w, i) in warningsOf(release, e.id ?? '')" :key="'w' + i" class="epanel__warn">{{ w.message }}</div>

                <div class="epanel__h">Links out</div>
                <div v-if="!linksOut(e).length" class="epanel__empty">none</div>
                <div v-for="(l, i) in linksOut(e)" :key="'o' + i" class="epanel__line">
                    <span class="epanel__kind">{{ l.kind }}</span> <code>{{ l.to }}</code>
                </div>

                <div class="epanel__h">Links in</div>
                <div v-if="!linksIn(taskElements, e.id ?? '').length" class="epanel__empty">none in this task's documents</div>
                <div v-for="(l, i) in linksIn(taskElements, e.id ?? '')" :key="'i' + i" class="epanel__line">
                    <code>{{ l.from }}</code> <span class="epanel__kind">{{ l.kind }}</span>
                </div>

                <template v-if="findings.get(e.id ?? '')?.length">
                    <div class="epanel__h">Open findings</div>
                    <div v-for="f in findings.get(e.id ?? '')" :key="f.id ?? ''" class="epanel__line">
                        <code>{{ f.id }}</code> <n-tag size="tiny" :bordered="false"
                            :type="f.priority === 1 ? 'error' : 'warning'">P{{ f.priority ?? '?' }}</n-tag> {{ f.title }}
                    </div>
                </template>

                <div class="epanel__h">Dependents</div>
                <div v-if="loading" class="epanel__empty">loading…</div>
                <div v-else-if="error" class="epanel__warn">{{ error }}</div>
                <template v-else-if="view?.dependentsOf">
                    <div class="epanel__line">
                        {{ view.dependentsOf.count }} across the board<template v-if="view.dependentsOf.truncated">, stopped at the depth bound</template>
                        <n-button v-if="view.dependentsOf.count" size="tiny" quaternary @click.stop="showDependents = !showDependents">
                            {{ showDependents ? 'hide' : 'show' }}
                        </n-button>
                    </div>
                    <template v-if="showDependents">
                        <div v-for="d in view.dependentsOf.dependents" :key="d.element?.id ?? ''" class="epanel__line epanel__dep">
                            <span class="epanel__kind">{{ d.distance }}</span>
                            <code>{{ d.element?.id }}</code> {{ d.element?.title }}
                            <span class="erow__meta">{{ (d.via ?? []).map((v: any) => v.kind + ' ' + v.to).join(', ') }}</span>
                        </div>
                    </template>
                </template>

                <div class="epanel__h">History</div>
                <div v-if="!loading && !history.length" class="epanel__empty">first seen in this round</div>
                <div v-for="(h, i) in history" :key="'h' + i" class="epanel__line">
                    round {{ h.round ?? '?' }} ·
                    <n-tag size="tiny" :bordered="false" :type="h.mark === 'changed' ? 'warning' : 'default'">{{ h.mark }}</n-tag>
                    <a v-if="h.url" :href="h.url" target="_blank" rel="noopener" class="epanel__link">file at that round</a>
                </div>
            </div>
        </template>
    </div>
</template>

<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import { NButton, NTag } from 'naive-ui'
import { useStore } from 'vuex'
import type { DocumentRelease } from '@/utils/agentDocuments'
import { elementsOf, findingsByElement, historyRows, linkCounts, linksIn, linksOut, taskElementsOf, warningsOf } from '@/utils/agentElements'

const props = defineProps<{
    /** The document whose elements are listed. */
    release: DocumentRelease
    /** The task's documents, newest first: links in, findings and history resolve against them. */
    documents: DocumentRelease[]
    boardUuid?: string | null
    taskUuid?: string | null
    taskStatus?: string | null
    /** An element to open, e.g. from a finding's element chip. */
    focus?: string | null
}>()

const store = useStore()
const elements = computed(() => elementsOf(props.release))
const taskElements = computed(() => taskElementsOf(props.documents))
const counts = computed(() => linkCounts(taskElements.value))
const findings = computed(() => findingsByElement(props.documents))

const open = ref<string | null>(null)
const view = ref<any>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const showDependents = ref(false)
const history = computed(() => historyRows(view.value?.elementHistory, props.documents))

async function load (id: string) {
    view.value = null
    error.value = null
    showDependents.value = false
    if (!props.boardUuid || !props.taskUuid) return
    loading.value = true
    try {
        view.value = await store.dispatch('fetchAgentTaskElementView', {
            boardUuid: props.boardUuid, taskUuid: props.taskUuid, status: props.taskStatus ?? undefined, element: id,
        })
    } catch (e: any) {
        error.value = 'Could not read what depends on ' + id + ': ' + (e?.message ?? e)
    } finally {
        loading.value = false
    }
}

function toggle (id: string) {
    if (open.value === id) {
        open.value = null
        return
    }
    open.value = id
    load(id)
}

watch(() => props.focus, (id) => {
    if (id && id !== open.value && elements.value.some(e => e.id === id)) toggle(id)
}, { immediate: true })
</script>

<style scoped lang="scss">
.els { margin: 4px 0 6px 12px; }
.erow {
    display: flex; align-items: center; gap: 6px; flex-wrap: wrap; cursor: pointer; padding: 2px 0;
    &--open { font-weight: 600; }
    &__id { font-size: 12px; }
    &__title { flex: 1 1 auto; min-width: 0; }
    &__meta { color: #888; font-size: 12px; }
}
.epanel {
    margin: 2px 0 8px 16px; padding-left: 8px; border-left: 2px solid #ddd; font-size: 12px;
    &__h { margin-top: 6px; font-weight: 600; }
    &__line { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
    &__kind { color: #888; }
    &__empty { color: #aaa; }
    &__warn { color: #b58105; }
    &__link { margin-left: 4px; }
    &__dep { padding-left: 4px; }
}
</style>
