<template>
    <n-collapse class="revhist" @item-header-click="onHeader">
        <n-collapse-item title="History" name="history">
            <n-spin :show="loading">
                <div v-if="error" class="revhist__note revhist__note--error">{{ error }}</div>
                <div v-else-if="loaded && !revisions.length" class="revhist__note">No earlier revisions.</div>
                <div v-for="(e, i) in shown" :key="e.revision ?? 'current'" class="revhist__row">
                    <div class="revhist__line">
                        <span class="revhist__rev">{{ e.revision === null ? 'current' : `rev ${e.revision}` }}</span>
                        <span v-if="e.at" class="revhist__at">{{ when(e.at) }}</span>
                        <span class="revhist__facts">{{ revisionSummary(kind, e.snapshot).join(' · ') }}</span>
                        <n-button v-if="i + 1 < shown.length" size="tiny" quaternary class="revhist__compare"
                                  @click="flip('compare', i)">
                            {{ compareOpen.has(i) ? 'hide changes' : 'changes from previous' }}
                        </n-button>
                        <n-button v-if="e.snapshot" size="tiny" quaternary @click="flip('full', i)">
                            {{ fullOpen.has(i) ? 'hide snapshot' : 'snapshot' }}
                        </n-button>
                    </div>
                    <div v-if="compareOpen.has(i)" class="revhist__diff">
                        <div v-if="!changesAt(i).length" class="revhist__note">No field shown here changed.</div>
                        <template v-for="c in changesAt(i)" :key="c.key">
                            <div v-if="c.long" class="revhist__long">
                                <div class="revhist__key">{{ c.key }}</div>
                                <div class="revhist__sides">
                                    <pre class="revhist__text revhist__text--before">{{ c.beforeValue ?? '' }}</pre>
                                    <pre class="revhist__text revhist__text--after">{{ c.afterValue ?? '' }}</pre>
                                </div>
                            </div>
                            <div v-else class="revhist__change">
                                <span class="revhist__key">{{ c.key }}</span>
                                <span class="revhist__before">{{ c.before }}</span>
                                <span class="revhist__arrow">→</span>
                                <span>{{ c.after }}</span>
                            </div>
                        </template>
                    </div>
                    <pre v-if="fullOpen.has(i)" class="revhist__full">{{ JSON.stringify(e.snapshot, null, 2) }}</pre>
                </div>
                <n-button v-if="more" size="tiny" class="revhist__more" @click="load(false)">Load more</n-button>
            </n-spin>
        </n-collapse-item>
    </n-collapse>
</template>

<script lang="ts" setup>
// A task's, board's or role's earlier revisions (22ddc644), loaded when the section is opened.
// Each row can show what changed from the revision before it; a prompt or other long text
// shows the two versions side by side, since that is what people look for after a bad round.
import { computed, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { NButton, NCollapse, NCollapseItem, NSpin } from 'naive-ui'
import { diffSnapshots, historyEntries, revisionSummary } from '@/utils/agentRevisions'
import type { Revision, RevisionKind } from '@/utils/agentRevisions'

const props = defineProps<{
    kind: RevisionKind
    uuid: string
    /** The live object as the host has it: the newest revision is the state before the last save. */
    current?: any
}>()

const PAGE = 20
const store = useStore()
const revisions = ref<Revision[]>([])
const loaded = ref(false)
const loading = ref(false)
const more = ref(false)
const error = ref<string | null>(null)
const compareOpen = ref(new Set<number>())
const fullOpen = ref(new Set<number>())

const shown = computed(() => loaded.value ? historyEntries(props.kind, revisions.value, props.current) : [])

/** What changed from the entry below (older) to this one. */
function changesAt (i: number) {
    const list = shown.value
    return i + 1 < list.length ? diffSnapshots(list[i + 1].snapshot, list[i].snapshot) : []
}

// Takes a name, not the ref: the template unwraps refs, so a ref passed from it arrives as the Set.
function flip (which: 'compare' | 'full', i: number) {
    const set = which === 'compare' ? compareOpen : fullOpen
    const next = new Set(set.value)
    if (next.has(i)) next.delete(i)
    else next.add(i)
    set.value = next
}

function when (at: string) {
    const d = new Date(at)
    return isNaN(d.getTime()) ? at : d.toLocaleString()
}

async function load (reset: boolean) {
    loading.value = true
    error.value = null
    try {
        const page: Revision[] = await store.dispatch('fetchAgentRevisions',
            { kind: props.kind, uuid: props.uuid, limit: PAGE, offset: reset ? 0 : revisions.value.length })
        revisions.value = reset ? page : [...revisions.value, ...page]
        more.value = page.length === PAGE
        loaded.value = true
    } catch (e: any) {
        error.value = e?.message ?? String(e)
    } finally {
        loading.value = false
    }
}

function onHeader ({ expanded }: { expanded: boolean }) {
    if (expanded && !loaded.value && !loading.value) load(true)
}

watch(() => props.uuid, () => {
    revisions.value = []
    loaded.value = false
    more.value = false
    error.value = null
    compareOpen.value = new Set()
    fullOpen.value = new Set()
})

defineExpose({ load })
</script>

<style scoped lang="scss">
.revhist {
    margin-top: 8px;
}
.revhist__row {
    border-bottom: 1px solid rgba(128, 128, 128, 0.15);
    padding: 4px 0;
}
.revhist__line {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    font-size: 12px;
}
.revhist__rev {
    font-weight: 600;
    min-width: 52px;
}
.revhist__at,
.revhist__note {
    opacity: 0.7;
    font-size: 12px;
}
.revhist__note--error {
    color: #d03050;
    opacity: 1;
}
.revhist__facts {
    flex: 1 1 auto;
}
.revhist__diff {
    margin: 4px 0 4px 12px;
    font-size: 12px;
}
.revhist__change {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    overflow-wrap: anywhere;
}
.revhist__key {
    font-family: monospace;
    min-width: 120px;
}
.revhist__before {
    text-decoration: line-through;
    opacity: 0.7;
}
.revhist__sides {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
}
.revhist__text,
.revhist__full {
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    font-size: 11px;
    margin: 2px 0;
    padding: 6px;
    border-radius: 4px;
    background: rgba(128, 128, 128, 0.08);
    max-height: 320px;
    overflow: auto;
}
.revhist__text--before {
    background: rgba(208, 48, 80, 0.08);
}
.revhist__text--after {
    background: rgba(24, 160, 88, 0.08);
}
.revhist__more {
    margin-top: 6px;
}
</style>
