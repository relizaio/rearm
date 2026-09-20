<template>
    <div class="modelcat">
        <n-space align="center" justify="space-between" style="margin-bottom: 10px;">
            <div>
                <h3 style="margin: 0;">Model catalogue</h3>
                <n-text depth="3" style="font-size: 12px;">
                    What each model your agents run is, and what it costs. Rows are created
                    automatically the first time an agent declares a model; pricing is yours to set.
                </n-text>
            </div>
            <n-space :size="8">
                <n-button size="small" @click="load" :loading="loading">Refresh</n-button>
            </n-space>
        </n-space>

        <n-alert v-if="unresolvedCount" type="info" style="margin-bottom: 12px;" :bordered="false">
            {{ unresolvedCount }} {{ unresolvedCount === 1 ? 'model is' : 'models are' }} unresolved —
            auto-registered from an agent's declaration and not yet matched to a known model. Costs
            cannot be derived for them until they carry pricing, either by mapping them onto an
            existing row or by adding entries directly.
        </n-alert>

        <n-data-table
            :columns="columns"
            :data="models"
            :loading="loading"
            :pagination="{ pageSize: 20 }"
            :row-key="(r: any) => r.uuid"
            size="small"
        />

        <!-- ---------- Pricing drawer ---------- -->
        <n-drawer v-model:show="showPricing" :width="620" placement="right">
            <n-drawer-content :title="selected ? modelLabel(selected) : 'Pricing'" closable>
                <template v-if="selected">
                    <n-descriptions :column="2" size="small" bordered style="margin-bottom: 14px;">
                        <n-descriptions-item label="Canonical id">
                            <code>{{ selected.canonicalId || '—' }}</code>
                        </n-descriptions-item>
                        <n-descriptions-item label="Tier">{{ selected.tier || '—' }}</n-descriptions-item>
                        <n-descriptions-item label="Resolution">{{ selected.resolution || '—' }}</n-descriptions-item>
                        <n-descriptions-item label="Publisher">{{ selected.publisher || '—' }}</n-descriptions-item>
                        <n-descriptions-item label="Aliases" :span="2">
                            <n-space :size="4" v-if="selected.aliases?.length">
                                <n-tag v-for="a in selected.aliases" :key="a" size="tiny">{{ a }}</n-tag>
                            </n-space>
                            <span v-else>—</span>
                        </n-descriptions-item>
                        <n-descriptions-item label="Facts" :span="2">
                            <n-space :size="4" v-if="factPairs(selected).length">
                                <n-tag v-for="f in factPairs(selected)" :key="f" size="tiny" type="info">{{ f }}</n-tag>
                            </n-space>
                            <span v-else>—</span>
                        </n-descriptions-item>
                    </n-descriptions>

                    <div class="sec">Pricing entries</div>
                    <!-- History, not just what is live: an entry that has expired is why last
                         month's cost is what it is, so expiring never deletes. -->
                    <n-data-table
                        v-if="selected.pricing?.length"
                        size="small"
                        :columns="pricingColumns"
                        :data="sortedPricing"
                        :pagination="false"
                        :bordered="false"
                        style="margin-bottom: 14px;"
                    />
                    <n-text v-else depth="3" style="display: block; margin-bottom: 14px;">
                        No pricing entries. Usage on this model reports tokens but no cost.
                    </n-text>

                    <n-collapse>
                        <n-collapse-item title="Add a pricing entry" name="add">
                            <n-form label-placement="left" label-width="150" size="small">
                                <n-form-item label="Effective from">
                                    <n-date-picker v-model:value="draft.effectiveFrom" type="datetime" style="width: 100%;"/>
                                </n-form-item>
                                <n-form-item label="Input $/Mtok">
                                    <n-input-number v-model:value="draft.input" :min="0" :step="0.1" style="width: 100%;"/>
                                </n-form-item>
                                <n-form-item label="Output $/Mtok">
                                    <n-input-number v-model:value="draft.output" :min="0" :step="0.1" style="width: 100%;"/>
                                </n-form-item>
                                <n-form-item label="Cache read $/Mtok">
                                    <n-input-number v-model:value="draft.cacheRead" :min="0" :step="0.01" style="width: 100%;"/>
                                </n-form-item>
                                <n-form-item label="Cache write $/Mtok">
                                    <n-input-number v-model:value="draft.cacheWrite" :min="0" :step="0.1" style="width: 100%;"/>
                                </n-form-item>
                                <n-form-item label="Applies above">
                                    <n-input-number v-model:value="draft.contextAbove" :min="0" :step="1000"
                                                    placeholder="context tokens, e.g. 200000" style="width: 100%;"/>
                                </n-form-item>
                                <n-form-item label="Service tier">
                                    <n-select v-model:value="draft.serviceTier" clearable :options="tierOptions" style="width: 100%;"/>
                                </n-form-item>
                                <n-form-item label="Hosting">
                                    <n-select v-model:value="draft.hosting" clearable :options="hostingOptions" style="width: 100%;"/>
                                </n-form-item>
                                <n-form-item label="Source">
                                    <n-input v-model:value="draft.source" placeholder="where this price came from"/>
                                </n-form-item>
                                <n-alert type="warning" :bordered="false" style="margin-bottom: 10px;">
                                    Rates are per million tokens and stored exactly, as integer micros.
                                    A more specific entry wins over a general one; two entries equally
                                    specific over the same window are a conflict the server refuses to
                                    guess between, and neither will price.
                                </n-alert>
                                <n-button type="primary" size="small" :loading="saving"
                                          :disabled="!draft.effectiveFrom" @click="addEntry">
                                    Add entry
                                </n-button>
                            </n-form>
                        </n-collapse-item>
                    </n-collapse>
                </template>
            </n-drawer-content>
        </n-drawer>

        <!-- ---------- Map-to (merge) modal ---------- -->
        <n-modal v-model:show="showMerge" preset="card" style="width: 560px;" title="Map this model onto another">
            <template v-if="mergeSource">
                <n-text>
                    <code>{{ modelLabel(mergeSource) }}</code> will be merged into the row you pick.
                    Its names become aliases there, and every agent, session and usage row pointing at
                    it is re-pointed before it is removed. Usage history is preserved, not discarded.
                </n-text>
                <n-select
                    v-model:value="mergeTarget"
                    :options="mergeOptions"
                    placeholder="Merge into…"
                    style="margin-top: 14px;"
                />
                <n-alert v-if="!mergeOptions.length" type="warning" :bordered="false" style="margin-top: 12px;">
                    No candidates — the server ranks other rows of this org by similarity and found
                    none close enough to suggest.
                </n-alert>
                <n-space justify="end" style="margin-top: 16px;">
                    <n-button size="small" @click="showMerge = false">Cancel</n-button>
                    <n-button size="small" type="primary" :disabled="!mergeTarget" :loading="saving" @click="doMerge">
                        Merge
                    </n-button>
                </n-space>
            </template>
        </n-modal>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import {
    NAlert, NButton, NCollapse, NCollapseItem, NDataTable, NDatePicker, NDescriptions, NDescriptionsItem,
    NDrawer, NDrawerContent, NForm, NFormItem, NInput, NInputNumber, NModal, NSelect, NSpace, NTag, NText,
    DataTableColumns, useNotification,
} from 'naive-ui'

const props = defineProps<{ orgUuid: string }>()

const store = useStore()
const notification = useNotification()

const models = ref<any[]>([])
const loading = ref(false)
const saving = ref(false)
const showPricing = ref(false)
const selected = ref<any>(null)
const showMerge = ref(false)
const mergeSource = ref<any>(null)
const mergeTarget = ref<string | null>(null)

const tierOptions = ['STANDARD', 'BATCH', 'PRIORITY'].map(v => ({ label: v, value: v }))
const hostingOptions = ['DIRECT', 'BEDROCK', 'VERTEX', 'AZURE'].map(v => ({ label: v, value: v }))

const draft = ref<any>({
    effectiveFrom: Date.now(),
    input: null, output: null, cacheRead: null, cacheWrite: null,
    contextAbove: null, serviceTier: null, hosting: null, source: '',
})

function modelLabel (m: any): string {
    const v = m.version && m.version !== 'unknown' ? ' ' + m.version : ''
    return (m.name ?? '(unnamed)') + v
}

/**
 * Declared rather than derived from the object's keys. facts stopped being a free-form
 * map and became a typed record, so every model now carries all seven keys plus the
 * __typename Apollo adds -- iterating the keys would print six "null" rows and a type
 * name for a model that only knows its context window.
 */
const FACT_LABELS: [string, string][] = [
    ['contextWindow', 'context window'],
    ['maxOutputTokens', 'max output'],
    ['modalities', 'modalities'],
    ['hostingKind', 'hosting'],
    ['releaseDate', 'released'],
    ['deprecatedAt', 'deprecated'],
    ['knowledgeCutoff', 'knowledge cutoff'],
]

function factValue (key: string, value: any): string {
    if (Array.isArray(value)) return value.join(', ')
    if (key === 'contextWindow' || key === 'maxOutputTokens') {
        const n = Number(value)
        return Number.isFinite(n) ? n.toLocaleString() : String(value)
    }
    return String(value)
}

function factPairs (m: any): string[] {
    const f = m.facts ?? {}
    return FACT_LABELS
        .filter(([k]) => f[k] !== null && f[k] !== undefined && f[k] !== ''
            && !(Array.isArray(f[k]) && f[k].length === 0))
        .map(([k, label]) => `${label}: ${factValue(k, f[k])}`)
}

const unresolvedCount = computed(() => models.value.filter(m => m.resolution === 'UNRESOLVED').length)

/**
 * Newest first. Pricing is a dated history and the current rate is the one an
 * operator looks for, so it goes at the top rather than at the bottom of however
 * many superseded entries have accumulated.
 */
const sortedPricing = computed(() => {
    const rows = [...(selected.value?.pricing ?? [])]
    return rows.sort((a, b) => String(b.effectiveFrom ?? '').localeCompare(String(a.effectiveFrom ?? '')))
})

const mergeOptions = computed(() =>
    (mergeSource.value?.mergeCandidates ?? []).map((c: any) => ({
        label: modelLabel(c) + (c.canonicalId ? ` — ${c.canonicalId}` : ''),
        value: c.uuid,
    })))

async function load () {
    loading.value = true
    try {
        models.value = await store.dispatch('fetchModelOntologiesOfOrg', props.orgUuid) ?? []
    } catch (e: any) {
        notification.error({ title: 'Could not load the model catalogue', content: e?.message, duration: 5000 })
    } finally {
        loading.value = false
    }
}

onMounted(load)

/**
 * Rates are entered per million tokens and stored as integer micros per million.
 * The conversion happens once, here, because a float anywhere in the pricing
 * path eventually shows up as a cost that does not reconcile.
 */
function toMicros (perMillion: number | null): number | null {
    if (perMillion === null || perMillion === undefined) return null
    return Math.round(perMillion * 1_000_000)
}

async function addEntry () {
    if (!selected.value || !draft.value.effectiveFrom) return
    const appliesTo: any = {}
    if (draft.value.contextAbove) appliesTo.contextAboveTokens = draft.value.contextAbove
    if (draft.value.serviceTier) appliesTo.serviceTier = draft.value.serviceTier
    if (draft.value.hosting) appliesTo.hosting = draft.value.hosting

    const entry: any = {
        effectiveFrom: new Date(draft.value.effectiveFrom).toISOString(),
        currency: 'USD',
        unit: 'PER_MILLION_TOKENS',
        inputMicros: toMicros(draft.value.input),
        outputMicros: toMicros(draft.value.output),
        cacheReadMicros: toMicros(draft.value.cacheRead),
        cacheWriteMicros: toMicros(draft.value.cacheWrite),
    }
    if (Object.keys(appliesTo).length) entry.appliesTo = appliesTo
    if (draft.value.source) entry.source = draft.value.source

    saving.value = true
    try {
        await store.dispatch('addModelPricing', { modelOntologyUuid: selected.value.uuid, entry })
        notification.success({ title: 'Pricing entry added', duration: 3000 })
        await load()
        selected.value = models.value.find(m => m.uuid === selected.value.uuid) ?? selected.value
    } catch (e: any) {
        notification.error({ title: 'Could not add the entry', content: e?.message, duration: 6000 })
    } finally {
        saving.value = false
    }
}

async function expireEntry (row: any) {
    if (!selected.value) return
    saving.value = true
    try {
        await store.dispatch('expireModelPricing', {
            modelOntologyUuid: selected.value.uuid,
            entryUuid: row.uuid,
            effectiveTo: new Date().toISOString(),
        })
        notification.success({ title: 'Entry expired', duration: 3000 })
        await load()
        selected.value = models.value.find(m => m.uuid === selected.value.uuid) ?? selected.value
    } catch (e: any) {
        notification.error({ title: 'Could not expire the entry', content: e?.message, duration: 6000 })
    } finally {
        saving.value = false
    }
}

async function applyPreset (row: any) {
    if (!row.canonicalId) return
    saving.value = true
    try {
        await store.dispatch('applyModelCataloguePreset', { orgUuid: props.orgUuid, canonicalId: row.canonicalId })
        notification.success({ title: 'Catalogue preset applied', duration: 3000 })
        await load()
    } catch (e: any) {
        notification.error({ title: 'Could not apply the preset', content: e?.message, duration: 6000 })
    } finally {
        saving.value = false
    }
}

function openMerge (row: any) {
    mergeSource.value = row
    mergeTarget.value = null
    showMerge.value = true
}

async function doMerge () {
    if (!mergeSource.value || !mergeTarget.value) return
    saving.value = true
    try {
        await store.dispatch('mergeModelOntology', { from: mergeSource.value.uuid, into: mergeTarget.value })
        notification.success({ title: 'Models merged', duration: 3000 })
        showMerge.value = false
        await load()
    } catch (e: any) {
        notification.error({ title: 'Could not merge', content: e?.message, duration: 6000 })
    } finally {
        saving.value = false
    }
}

function openPricing (row: any) {
    selected.value = row
    draft.value = {
        effectiveFrom: Date.now(),
        input: null, output: null, cacheRead: null, cacheWrite: null,
        contextAbove: null, serviceTier: null, hosting: null, source: '',
    }
    showPricing.value = true
}

const columns = computed<DataTableColumns<any>>(() => [
    { title: 'Model', key: 'name', render: (r: any) => modelLabel(r) },
    { title: 'Canonical id', key: 'canonicalId', render: (r: any) => h('code', { style: 'font-size: 11px;' }, r.canonicalId ?? '—') },
    { title: 'Tier', key: 'tier', render: (r: any) => r.tier ?? '—' },
    {
        title: 'Resolution',
        key: 'resolution',
        render: (r: any) => h(NTag, { size: 'small', type: r.resolution === 'RESOLVED' ? 'success' : 'warning' },
            { default: () => r.resolution ?? 'UNKNOWN' }),
    },
    {
        title: 'Pricing',
        key: 'pricing',
        render: (r: any) => {
            const live = (r.pricing ?? []).filter((p: any) => !p.effectiveTo || new Date(p.effectiveTo) > new Date())
            if (!live.length) {
                // Said plainly, because this is the single reason a cost reads
                // "no price" anywhere else in the product.
                return h(NTag, { size: 'small', type: 'default' }, { default: () => 'none' })
            }
            return h('span', `${live.length} live`)
        },
    },
    {
        title: '',
        key: 'actions',
        render: (r: any) => h(NSpace, { size: 6 }, {
            default: () => [
                h(NButton, { size: 'tiny', onClick: () => openPricing(r) }, { default: () => 'Pricing' }),
                r.resolution === 'UNRESOLVED' && (r.mergeCandidates ?? []).length
                    ? h(NButton, { size: 'tiny', onClick: () => openMerge(r) }, { default: () => 'Map to…' })
                    : null,
                r.canonicalId
                    ? h(NButton, { size: 'tiny', onClick: () => applyPreset(r) }, { default: () => 'Apply preset' })
                    : null,
            ].filter(Boolean),
        }),
    },
])

const perMillion = (micros: number | null | undefined) =>
    micros === null || micros === undefined ? '—' : '$' + (micros / 1_000_000).toFixed(2)

const pricingColumns = computed<DataTableColumns<any>>(() => [
    { title: 'From', key: 'effectiveFrom', render: (r: any) => r.effectiveFrom ? new Date(r.effectiveFrom).toLocaleDateString() : '—' },
    {
        title: 'To',
        key: 'effectiveTo',
        // "open" rather than a dash: an entry with no end is live indefinitely,
        // which is a fact about it, not a missing value.
        render: (r: any) => r.effectiveTo ? new Date(r.effectiveTo).toLocaleDateString() : 'open',
    },
    { title: 'In', key: 'inputMicros', render: (r: any) => perMillion(r.inputMicros) },
    { title: 'Out', key: 'outputMicros', render: (r: any) => perMillion(r.outputMicros) },
    { title: 'C.read', key: 'cacheReadMicros', render: (r: any) => perMillion(r.cacheReadMicros) },
    { title: 'C.write', key: 'cacheWriteMicros', render: (r: any) => perMillion(r.cacheWriteMicros) },
    {
        title: 'Applies to',
        key: 'appliesTo',
        render: (r: any) => {
            const a = r.appliesTo
            if (!a) return 'any'
            const bits: string[] = []
            if (a.contextAboveTokens) bits.push(`> ${a.contextAboveTokens} ctx`)
            if (a.serviceTier) bits.push(a.serviceTier)
            if (a.hosting) bits.push(a.hosting)
            if (a.contextVariant) bits.push(a.contextVariant)
            if (a.reasoning) bits.push(a.reasoning)
            return bits.length ? bits.join(', ') : 'any'
        },
    },
    {
        title: '',
        key: 'actions',
        render: (r: any) => r.effectiveTo
            ? null
            : h(NButton, { size: 'tiny', onClick: () => expireEntry(r) }, { default: () => 'Expire' }),
    },
])
</script>

<style scoped>
.sec {
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: #999;
    margin-bottom: 6px;
}
</style>
