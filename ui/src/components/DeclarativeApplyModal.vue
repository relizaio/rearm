<template>
    <n-modal :show="show" preset="card" :title="title" style="max-width: 980px"
             @update:show="(v: boolean) => { if (!v) close() }">
        <n-space vertical :size="12">
            <n-text depth="3" style="font-size: 12px">
                Pick a {{ kinds.join(' or ') }} file, YAML or JSON. It is checked with a dry run first; nothing
                changes until you apply. A field set to null clears it, a field left out is untouched<template
                    v-if="kinds.includes('BOARD')">, and a role the file does not list is deactivated</template>.
                Files that point at other files (<code>file:</code>, <code>promptFile:</code>) need the CLI.
            </n-text>
            <input ref="picker" type="file" accept=".yaml,.yml,.json,application/json,text/yaml"
                   @change="onPick"/>
            <n-alert v-if="problem" type="error">{{ problem }}</n-alert>
            <n-spin :show="busy">
                <div v-if="result">
                    <div class="summary">
                        <n-tag size="small" :bordered="false" :type="result.errors ? 'error' : 'info'">
                            {{ result.dryRun ? 'dry run' : 'applied' }}
                        </n-tag>
                        {{ result.created }} to create · {{ result.updated }} to update ·
                        {{ result.unchanged }} unchanged · {{ result.archived }} to deactivate ·
                        {{ result.errors }} error{{ result.errors === 1 ? '' : 's' }}
                    </div>
                    <n-data-table :columns="columns" :data="result.changes" size="small" :bordered="false"
                                  :row-key="(r: any, i: number) => `${r.name}-${r.action}-${i}`"/>
                </div>
            </n-spin>
            <n-space justify="end">
                <n-button size="small" @click="close">Close</n-button>
                <n-button size="small" type="primary"
                          :disabled="!parsed || !result || !result.dryRun || result.errors > 0 || busy"
                          @click="run(false)">
                    Apply
                </n-button>
            </n-space>
        </n-space>
    </n-modal>
</template>

<script lang="ts" setup>
import { computed, h, ref } from 'vue'
import { useStore } from 'vuex'
import { NAlert, NButton, NDataTable, NModal, NSpace, NSpin, NTag, NText, DataTableColumns } from 'naive-ui'
import { ParsedSpec, SpecKind, changeEntity, changeMessage, parseSpecFile, specReferences } from '@/utils/declarativeSpec'

/**
 * Apply a board or presets file from the browser (declarative-boards D8): parse, dry run, show the
 * change set, apply. The dry run is the server's apply rolled back, so what it shows is what Apply
 * does, and any error blocks it.
 */
const props = defineProps<{
    show: boolean
    orgUuid: string
    kinds: SpecKind[]
}>()
const emit = defineEmits<{
    (e: 'close'): void
    (e: 'applied', p: { kind: SpecKind, name?: string }): void
}>()

const store = useStore()
const picker = ref<HTMLInputElement | null>(null)
const parsed = ref<ParsedSpec | null>(null)
const result = ref<any>(null)
const problem = ref('')
const busy = ref(false)

const title = computed(() => props.kinds.includes('BOARD') ? 'Apply a board file' : 'Apply a presets file')

const columns: DataTableColumns<any> = [
    { title: 'Entity', key: 'entity', width: 80, render: (r: any) => changeEntity(r.kind, r.action, r.message) },
    { title: 'Name', key: 'name', width: 180 },
    {
        title: 'Action', key: 'action', width: 100,
        render: (r: any) => h(NTag, {
            size: 'tiny', bordered: false,
            type: r.action === 'ERROR' ? 'error' : r.action === 'ARCHIVE' ? 'warning'
                : r.action === 'UNCHANGED' ? 'default' : 'success',
        }, { default: () => r.action }),
    },
    { title: 'Fields', key: 'fields', render: (r: any) => (r.fields ?? []).join(', ') },
    {
        title: 'Message', key: 'message',
        render: (r: any) => h('div', [
            h('div', changeMessage(r.kind, r.message)),
            ...(r.warnings ?? []).map((w: string) => h('div', { class: 'warn' }, `⚠ ${w}`)),
        ]),
    },
]

async function onPick (ev: Event) {
    const file = (ev.target as HTMLInputElement).files?.[0]
    parsed.value = null
    result.value = null
    problem.value = ''
    if (!file) return
    try {
        const p = parseSpecFile(await file.text(), props.kinds)
        const refs = specReferences(p)
        if (refs.length) {
            problem.value = 'This file points at other files, which only the CLI or Terraform can read. '
                + `Inline them, or apply it with rearm agent ${p.kind === 'BOARD' ? 'board' : 'presets'} apply: `
                + refs.join('; ')
            return
        }
        parsed.value = p
        await run(true)
    } catch (e: any) {
        problem.value = e?.message ?? String(e)
    }
}

async function run (dryRun: boolean) {
    if (!parsed.value) return
    busy.value = true
    problem.value = ''
    try {
        const action = parsed.value.kind === 'BOARD' ? 'agentBoardApplySpec' : 'agentRolePresetsApplySpec'
        result.value = await store.dispatch(action, { orgUuid: props.orgUuid, spec: parsed.value.spec, dryRun })
        if (!dryRun && result.value && !result.value.errors) {
            emit('applied', { kind: parsed.value.kind, name: parsed.value.spec.name })
        }
    } catch (e: any) {
        problem.value = e?.message ?? String(e)
    } finally {
        busy.value = false
    }
}

function close () {
    parsed.value = null
    result.value = null
    problem.value = ''
    if (picker.value) picker.value.value = ''
    emit('close')
}
</script>

<style scoped>
.summary { font-size: 12.5px; margin-bottom: 8px; display: flex; gap: 8px; align-items: center; }
:deep(.warn) { color: #b0854a; font-size: 11.5px; margin-top: 2px; }
</style>
