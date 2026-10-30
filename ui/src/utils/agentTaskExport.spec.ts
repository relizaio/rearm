import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { TASK_EXPORT_FORMAT, taskExportDocument, taskExportFileName } from './agentTaskExport'

const read = (rel: string) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')

describe('the task JSON export', () => {
    it('wraps the task object as returned, with the format and the export time', () => {
        const task = { uuid: 'u1', key: 'TPF-7', statusHistory: [{ from: 'QUEUED', to: 'ASSIGNED' }] }
        const doc = taskExportDocument(task, new Date('2026-10-28T12:00:00Z'))
        expect(doc).toEqual({ format: TASK_EXPORT_FORMAT, formatVersion: 1, exportedAt: '2026-10-28T12:00:00.000Z', task })
    })

    it('drops the GraphQL client\'s __typename at every depth and keeps everything else', () => {
        const task = { __typename: 'AgentTask', uuid: 'u1', cycles: null, tags: [],
            signOffs: [{ __typename: 'AgentTaskSignOff', role: 'coder', reviewedBy: { __typename: 'Actor', kind: 'USER' } }] }
        expect(taskExportDocument(task).task).toEqual({ uuid: 'u1', cycles: null, tags: [],
            signOffs: [{ role: 'coder', reviewedBy: { kind: 'USER' } }] })
        expect(task.__typename).toBe('AgentTask')
    })

    it('names the file by the task key, else its uuid, safe for a file system', () => {
        expect(taskExportFileName({ key: 'TPF-7', uuid: 'u1' })).toBe('TPF-7-task.json')
        expect(taskExportFileName({ uuid: '0a1b-2c' })).toBe('0a1b-2c-task.json')
        expect(taskExportFileName({ key: 'a/b c:d' })).toBe('a-b-c-d-task.json')
        expect(taskExportFileName(null)).toBe('task-task.json')
    })

    it('reads the page selection plus every other task field, without the element-graph lookups', () => {
        const store = read('../store.ts')
        const start = store.indexOf('async exportAgentTask (')
        const action = store.slice(start, store.indexOf('\n        },', start))
        expect(action).toContain('agentTask(uuid: $uuid) {')
        expect(action).toContain('${AGENT_TASK_SELECTION}')
        expect(action).toContain('${AGENT_TASK_EXPORT_EXTRA}')
        const es = store.indexOf('const AGENT_TASK_EXPORT_EXTRA = `')
        const extra = store.slice(es, store.indexOf('\n`', es))
        for (const f of ['\n    updatedAt\n', 'unassignments {', 'unlinkedPrs {', 'testedHeads { pr head }', 'requiredInputs {',
            '\n    regressedInputs\n', '\n    releases\n', '\n    cycles\n', '\n    roleUuid\n', '\n    producesComponent\n',
            'producesComponentDetails { uuid name }', 'elements {', 'elementChecks {']) {
            expect(extra, f).toContain(f)
        }
        expect(extra).not.toContain('dependentsOf')
        expect(extra).not.toContain('elementHistory')
        // the timeline and the rounds come with the page selection
        const ss = store.indexOf('const AGENT_TASK_SELECTION = `')
        const sel = store.slice(ss, store.indexOf('\n`', ss))
        for (const f of ['statusHistory {', 'signOffs {', 'returns {', 'reopens {']) expect(sel, f).toContain(f)
    })

    it('the task page offers the export as the download icon, reading the task afresh and saving the file', () => {
        const page = read('../components/AiAgentTaskPage.vue')
        const at = page.indexOf('data-testid="task-export"')
        const tip = page.slice(page.lastIndexOf('<n-tooltip>', at), page.indexOf('</n-tooltip>', at))
        expect(tip).toContain('<n-button size="tiny" quaternary circle data-testid="task-export" aria-label="Export task as JSON"')
        expect(tip).toContain(':loading="exporting" :disabled="exporting" @click="exportTask">')
        expect(tip).toContain('<template #icon><n-icon><Download/></n-icon></template>')
        expect(tip).toMatch(/<\/template>\s+Export task as JSON\s*$/)
        expect(page).not.toContain('Export JSON</n-button>')
        // the icon the release view downloads an SBOM with
        expect(page).toContain("import { Download } from '@vicons/tabler'")
        expect(read('../components/ReleaseView.vue')).toContain("title: 'Download Artifact', class: 'icons clickable', size: 25, onClick: () => openDownloadArtifactModal(row) }, () => h(Download))")
        expect(page).toContain("const full = await store.dispatch('exportAgentTask', task.value.uuid)")
        expect(page).toContain('downloadJson(taskExportFileName(full), taskExportDocument(full))')
        expect(page).toContain("exportError.value = 'Export failed: ' + (e?.message ?? String(e))")
    })
})
