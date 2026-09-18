<template>
    <div class="action-guards">
        <div class="header">
            <h4>{{ headings.title }}</h4>
            <n-tooltip trigger="hover" style="max-width: 420px;">
                <template #trigger>
                    <n-icon size="16" style="cursor: help;"><QuestionMark/></n-icon>
                </template>
                A guard is the mirror of a rule. A rule says "when this CEL is true, do something";
                a guard says "do this only while the CEL is true, otherwise refuse". Guards written
                here accumulate with the guards the other scopes declare — every one that applies is
                evaluated, and any one of them can refuse. A component can add to what it is held
                to, never subtract from it.
            </n-tooltip>
        </div>

        <p class="text-muted intro">{{ headings.intro }}</p>

        <p class="text-muted intro">
            A guard withholds an action until its condition holds. For release promotion that means
            <strong>every forward lifecycle move up to Shipped</strong> is checked — Draft to Assembled,
            Assembled to Ready to Ship, Ready to Ship to Shipped. Rejecting or cancelling a release, and
            retiring one past Shipped, are never withheld.
        </p>

        <n-alert v-if="!isWritable" type="default" style="margin-bottom: 0.75rem; font-size: 13px;">
            Read-only: writing guards needs admin rights here.
        </n-alert>

        <div class="actions">
            <n-button v-if="isWritable" type="primary" @click="openAdd">
                <template #icon><n-icon><CirclePlus/></n-icon></template>
                Add guard
            </n-button>
        </div>

        <n-data-table :columns="columns" :data="guards" :pagination="false" :bordered="false"/>

        <n-modal
            preset="dialog"
            :show-icon="false"
            style="width: 820px;"
            v-model:show="editorOpen"
            :title="editorTitle">
            <n-form :model="draft" label-placement="top" class="mt-3">
                <n-form-item label="Name" required>
                    <n-input v-model:value="draft.name" placeholder="e.g. Inputs must be Ready to Ship"/>
                </n-form-item>
                <n-form-item label="Guarded action" required>
                    <n-select v-model:value="draft.action" :options="actionOptions"/>
                    <template #feedback>
                        <strong>Every</strong> forward lifecycle move up to Shipped is checked — Draft to
                        Assembled, Assembled to Ready to Ship, Ready to Ship to Shipped — not just the
                        last one. In Block mode any of those moves is refused while the condition is
                        false. Moves into Rejected or Cancelled, and into the retirement lifecycles
                        (End of Marketing and beyond), are never withheld. To govern one transition
                        only, test <code>action.targetLifecycle</code> — see the samples below.
                    </template>
                </n-form-item>
                <n-form-item v-if="scope === 'ORG'" label="Component name regex">
                    <n-input v-model:value="draft.namePattern" placeholder="Leave empty for every component, e.g. product-.*"/>
                    <template #feedback>
                        Matched in full, not searched: <code>product</code> governs the component named
                        exactly that, not <code>product-api</code>. Write <code>product.*</code> for a prefix.
                    </template>
                </n-form-item>
                <n-form-item label="Mode" required>
                    <n-radio-group v-model:value="draft.mode">
                        <n-radio value="BLOCK">Block — refuse the action, naming this guard</n-radio>
                        <n-radio value="WARN">Warn — let it through and record the failure</n-radio>
                        <n-radio value="OFF">Off — keep the guard without evaluating it</n-radio>
                    </n-radio-group>
                </n-form-item>
                <n-form-item label="Condition" required>
                    <CelExpressionBuilder
                        v-model="draft.cel"
                        :approval-entry-options="[]"
                        :cel-only="true"
                        :extra-variable-docs="guardVariableDocs"
                        :extra-example-docs="guardExampleDocs"
                        placeholder="The action proceeds only while this is true, e.g. release.dependencies.all(d, d.maturity >= 3)"
                    />
                </n-form-item>
                <div class="samples">
                    <div class="samples-title">
                        <span>Start from a sample:</span>
                        <n-tooltip trigger="hover" style="max-width: 460px;">
                            <template #trigger>
                                <n-icon size="14" style="cursor: help; margin-left: 6px; vertical-align: middle;"><QuestionMark/></n-icon>
                            </template>
                            <div><strong>maturity</strong> is a rank over the release lifecycle, so a rule can
                            say "at least this far" without listing lifecycles by hand:</div>
                            <div style="margin-top: 6px;">
                                <div v-for="rank in maturityRanks" :key="rank.value">
                                    {{ rank.value }} — {{ rank.lifecycles }}
                                </div>
                            </div>
                            <div style="margin-top: 6px;">
                                Everything from Shipped on shares the top rank: those lifecycles describe a
                                market position, not more maturity, so a dependency at End of Life still
                                counts as having shipped. Whether it is still usable is the separate
                                <code>supported</code> flag.
                            </div>
                        </n-tooltip>
                    </div>
                    <div v-for="s in samples" :key="s.cel" class="sample">
                        <n-button size="tiny" dashed @click="applySample(s)">Use</n-button>
                        <div>
                            <div class="sample-label">
                                {{ s.label }}
                                <n-tooltip trigger="hover" style="max-width: 460px;">
                                    <template #trigger>
                                        <n-icon size="14" style="cursor: help; margin-left: 4px; vertical-align: middle;"><QuestionMark/></n-icon>
                                    </template>
                                    {{ s.help }}
                                </n-tooltip>
                            </div>
                            <code>{{ s.cel }}</code>
                        </div>
                    </div>
                </div>
                <n-space>
                    <n-button type="primary" :disabled="!canSave" @click="saveDraft">Save</n-button>
                    <n-button @click="editorOpen = false">Cancel</n-button>
                </n-space>
            </n-form>
        </n-modal>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useStore } from 'vuex'
import {
    NAlert, NButton, NDataTable, NForm, NFormItem, NIcon, NInput, NModal, NPopconfirm, NRadio,
    NRadioGroup, NSelect, NSpace, NTooltip, useNotification
} from 'naive-ui'
import { CirclePlus, QuestionMark, Edit as EditIcon, Trash } from '@vicons/tabler'
import CelExpressionBuilder from './CelExpressionBuilder.vue'

const props = withDefaults(defineProps<{
    scope: 'ORG' | 'COMPONENT' | 'PERSPECTIVE'
    uuid: string
    // Admin on the object. The panel is shown either way -- knowing what a release is held to
    // matters to everyone who ships one -- but editing needs the rights the backend asks for.
    isWritable: boolean
    // The org's own word for a component, so the copy matches the terminology
    // setting the rest of the page honours.
    componentWord?: string
    // Required for PERSPECTIVE scope: perspectives are read through their org's list, which is
    // the only query that returns them.
    orgUuid?: string
}>(), { componentWord: 'component', orgUuid: '' })

const store = useStore()
const notification = useNotification()

const guards = ref<any[]>([])
const editorOpen = ref(false)
const editingIndex = ref<number | null>(null)
const draft = reactive({
    name: '',
    action: 'RELEASE_PROMOTION',
    cel: '',
    mode: 'BLOCK' as 'OFF' | 'WARN' | 'BLOCK',
    namePattern: ''
})

const headings = computed(() => {
    if (props.scope === 'ORG') {
        return {
            title: 'Organization Action Guards',
            intro: 'Each guard picks the components it governs by a regex over the component name.'
                + ' Leave the pattern empty to govern every component in the organization.'
        }
    }
    if (props.scope === 'PERSPECTIVE') {
        return {
            title: 'Guards',
            intro: 'These guards govern every component of this perspective, on top of anything the'
                + ' organization or the component itself declares.'
        }
    }
    return {
        title: 'Guards',
        intro: `These guards govern this ${props.componentWord} only, on top of anything its`
            + ' perspectives and its organization declare for it.'
    }
})

const actionOptions = [
    { label: 'Release promotion — moving a release forward through its lifecycle', value: 'RELEASE_PROMOTION' }
]

// action.* exists only where a guard is being written, so it is passed in rather
// than living in the shared variable list the release rules also use.
const guardVariableDocs = [
    {
        name: 'action.targetLifecycle',
        snippet: 'action.targetLifecycle == "READY_TO_SHIP"',
        display: 'action.targetLifecycle',
        desc: 'string — the lifecycle being moved to. release.lifecycle is still the one being left, which is what lets a guard govern one transition and leave the rest alone.'
    }
]

// Ranks shown in the help tooltip, using the lifecycle names the rest of the UI uses. Kept next
// to the samples because every sample that says ">= n" is really saying "at least this lifecycle".
const maturityRanks = [
    { value: 4, lifecycles: 'Shipped, End of Marketing, End of Distribution, End of Support, End of Life' },
    { value: 3, lifecycles: 'Ready to Ship' },
    { value: 2, lifecycles: 'Assembled' },
    { value: 1, lifecycles: 'Draft' },
    { value: 0, lifecycles: 'Pending' },
    { value: -1, lifecycles: 'Rejected, Cancelled' }
]

const samples = [
    {
        label: 'Every dependency has reached Ready to Ship',
        cel: 'release.dependencies.all(d, d.maturity >= 3)',
        help: 'Refuses every forward move — including Draft to Assembled — while any dependency is'
            + ' still at Pending, Draft or Assembled (or Rejected/Cancelled). A dependency that has'
            + ' moved on to Shipped still satisfies it, because the rank asks for "at least Ready to'
            + ' Ship". A release with no dependencies passes: all() over an empty list is true.'
    },
    {
        label: 'Documents held to a higher standard than code',
        cel: 'release.dependencies.all(d, d.specification == "TEST_PLAN" ? d.maturity >= 3 : d.maturity >= 2)',
        help: 'Dependencies whose component carries the TEST_PLAN specification identifier must have'
            + ' reached Ready to Ship; everything else only has to be Assembled. The rule a single'
            + ' setting cannot express.'
    },
    {
        label: 'Only the move to Shipped is governed',
        cel: 'action.targetLifecycle != "GENERAL_AVAILABILITY" || release.dependencies.all(d, d.maturity >= 3)',
        help: 'Moves to Assembled and to Ready to Ship are allowed whatever the dependencies are'
            + ' doing; the condition only applies when the promotion target is Shipped. This is how'
            + ' you narrow a guard to one transition instead of all of them.'
    },
    {
        label: 'Every dependency has Shipped and is still supported',
        cel: 'release.dependencies.all(d, d.maturity >= 4 && d.supported)',
        help: 'Rank 4 is Shipped or later. supported is false at End of Support, End of Life,'
            + ' Rejected and Cancelled, so a dependency that shipped and was later withdrawn fails'
            + ' this — which the rank alone cannot express, since End of Life still ranks 4.'
    },
    {
        label: 'Every dependency is at least Assembled',
        cel: 'release.dependencies.all(d, d.maturity >= 2)',
        help: 'Nothing still at Pending or Draft underneath this release. The loosest useful bar,'
            + ' and a reasonable first rule to adopt in Warn mode.'
    },
    {
        label: 'Third-party dependencies are exempt',
        cel: 'release.dependencies.all(d, d.external || d.maturity >= 3)',
        help: 'external is true for releases held against the external-components org. They are not'
            + ' yours to promote, so holding your release to their lifecycle would be a rule nobody'
            + ' in your organization can satisfy.'
    },
    {
        label: 'A software requirements specification exists and has reached Ready to Ship',
        cel: 'release.dependencies.exists(d, d.specification == "SRS" && d.maturity >= 3)',
        help: 'exists(), not all(): this one demands that such a dependency is actually there, so a'
            + ' release with no dependencies at all is refused rather than passing vacuously.'
    },
    {
        label: 'A test plan is at exactly Ready to Ship — not still in Draft, not already Shipped',
        cel: 'release.dependencies.all(d, d.specification != "TEST_PLAN" || d.lifecycle == "READY_TO_SHIP")',
        help: 'Lifecycle equality rather than a rank, for the case where one exact state is the rule.'
            + ' A test plan that has moved on to Shipped fails this, which is the point — but it is'
            + ' also why a rank is the better default for most rules.'
    },
    {
        label: 'Nothing promotes with an open critical or known-exploited finding',
        cel: 'release.criticalVulns == 0 && release.kevCount == 0',
        help: 'About this release rather than its dependencies. Findings come from scans, so at'
            + ' creation time — before anything has been scanned — the counts are 0 and this passes'
            + ' vacuously; it bites on the promotions that follow.'
    }
]

const guardExampleDocs = samples.map(s => s.cel)

const editorTitle = computed(() => editingIndex.value === null ? 'Add guard' : 'Edit guard')
const canSave = computed(() => !!(draft.name && draft.action && (draft.cel || draft.mode === 'OFF')))

const modeLabel = (m: string) => {
    if (m === 'BLOCK') return 'Block'
    if (m === 'WARN') return 'Warn'
    return 'Off'
}

const applySample = (s: { cel: string }) => { draft.cel = s.cel }

const resetDraft = () => {
    draft.name = ''
    draft.action = 'RELEASE_PROMOTION'
    draft.cel = ''
    draft.mode = 'BLOCK'
    draft.namePattern = ''
}

const openAdd = () => {
    resetDraft()
    editingIndex.value = null
    editorOpen.value = true
}

const openEdit = (idx: number) => {
    const g = guards.value[idx]
    draft.name = g.name
    draft.action = g.action || 'RELEASE_PROMOTION'
    draft.cel = g.cel || ''
    draft.mode = g.mode || 'BLOCK'
    draft.namePattern = g.namePattern || ''
    editingIndex.value = idx
    editorOpen.value = true
}

const saveDraft = async () => {
    if (!canSave.value) return
    const guard = {
        name: draft.name,
        action: draft.action,
        cel: draft.cel,
        mode: draft.mode,
        namePattern: props.scope === 'ORG' ? (draft.namePattern || null) : null
    }
    const next = guards.value.slice()
    if (editingIndex.value === null) next.push(guard)
    else next.splice(editingIndex.value, 1, guard)
    const saved = await persist(next, 'Guard saved.')
    if (saved) editorOpen.value = false
}

const remove = async (idx: number) => {
    const next = guards.value.slice()
    next.splice(idx, 1)
    await persist(next, 'Guard deleted.')
}

const dispatchFetch = () => {
    if (props.scope === 'ORG') return store.dispatch('fetchOrgActionGuards', props.uuid)
    if (props.scope === 'PERSPECTIVE') {
        return store.dispatch('fetchPerspectiveActionGuards',
            { orgUuid: props.orgUuid, perspectiveUuid: props.uuid })
    }
    return store.dispatch('fetchComponentActionGuards', props.uuid)
}

const dispatchSave = (payload: any[]) => {
    if (props.scope === 'ORG') return store.dispatch('setOrgActionGuards', { orgUuid: props.uuid, guards: payload })
    if (props.scope === 'PERSPECTIVE') {
        return store.dispatch('setPerspectiveActionGuards', { perspectiveUuid: props.uuid, guards: payload })
    }
    return store.dispatch('setComponentActionGuards', { componentUuid: props.uuid, guards: payload })
}

const persist = async (next: any[], successMsg: string) => {
    const payload = next.map((g: any) => ({
        name: g.name,
        action: g.action,
        cel: g.cel,
        mode: g.mode,
        namePattern: props.scope === 'ORG' ? (g.namePattern || null) : null
    }))
    try {
        guards.value = await dispatchSave(payload)
        notification.success({ title: 'Saved', content: successMsg, duration: 3500 })
        return true
    } catch (e: any) {
        // The backend validates the expression, the name uniqueness and the regex before
        // writing, so its message is the useful one — surface it rather than a generic failure.
        notification.error({ title: 'Save failed', content: e?.message || 'Unknown error', duration: 8000 })
        return false
    }
}

const columns = computed(() => {
    const cols: any[] = [
        { title: 'Name', key: 'name' },
        { title: 'Action', key: 'action', width: 180, render: (row: any) => row.action === 'RELEASE_PROMOTION' ? 'Release promotion' : row.action },
        {
            title: 'Mode',
            key: 'mode',
            width: 90,
            render: (row: any) => h(NTooltip, { trigger: 'hover', style: 'max-width: 380px;' }, {
                trigger: () => h('span', { style: 'cursor: help; border-bottom: 1px dotted #aaa;' }, modeLabel(row.mode)),
                default: () => row.mode === 'BLOCK'
                    ? 'Refuses any forward lifecycle move up to Shipped while the condition is false, naming this guard.'
                    : row.mode === 'WARN'
                        ? 'Lets the move through and records the unsatisfied condition on the release.'
                        : 'Kept but not evaluated.'
            })
        }
    ]
    if (props.scope === 'ORG') {
        cols.push({
            title: 'Component name regex',
            key: 'namePattern',
            width: 200,
            render: (row: any) => row.namePattern || 'every component'
        })
    }
    cols.push({
        title: 'Condition',
        key: 'cel',
        render: (row: any) => h('code', { style: 'font-size: 11px; white-space: pre-wrap; word-break: break-all;' }, row.cel)
    })
    cols.push({
        title: 'Actions',
        key: 'actions',
        width: 110,
        render: (row: any, idx: number) => h('div', { style: 'display: flex; gap: 6px;' },
            props.isWritable ? [
                h(NIcon, { size: 22, class: 'clickable', title: 'Edit', onClick: () => openEdit(idx) },
                    { default: () => h(EditIcon) }),
                h(NPopconfirm, { onPositiveClick: () => remove(idx) }, {
                    trigger: () => h(NIcon, { size: 22, class: 'clickable', style: 'color: #d03050;',
                        title: 'Delete' }, { default: () => h(Trash) }),
                    default: () => `Delete guard "${row.name}"? Releases this guard was withholding become promotable straight away.`
                })
            ] : [])
    })
    return cols
})

onMounted(async () => {
    guards.value = (await dispatchFetch()) || []
})
</script>

<style scoped lang="scss">
.action-guards {
    padding: 0.5rem 0;
}
.header {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    margin-bottom: 0.25rem;
}
.intro {
    margin: 0 0 0.75rem 0;
    font-size: 13px;
}
.actions {
    margin-bottom: 0.75rem;
}
.samples {
    margin: 0.25rem 0 1rem 0;
    font-size: 12px;
}
.samples-title {
    font-weight: 600;
    margin-bottom: 4px;
}
.sample {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    margin-bottom: 6px;
}
.sample-label {
    color: #666;
}
.mt-3 { margin-top: 0.75rem; }
// The explanations under the action and pattern fields are a couple of lines each; without this
// they butt straight up against the next label.
:deep(.n-form-item-feedback-wrapper) {
    min-height: auto;
    padding: 2px 0 10px 0;
    line-height: 1.4;
}
.clickable {
    cursor: pointer;
}
</style>
