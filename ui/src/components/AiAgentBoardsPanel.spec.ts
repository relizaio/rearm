import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { BOARD_SETTING_KEYS, settingsDraftOf, settingsPatch } from '@/utils/agentBudget'

// humanQueueAgeMinutes in the board settings form (task 28dc4afb). The panel is not mounted here
// (it loads a board, its roles and its tasks); the form's field is read from the template the build
// ships, and what it saves from settingsDraftOf, which the panel's save sends through settingsPatch.
const source = readFileSync(fileURLToPath(new URL('./AiAgentBoardsPanel.vue', import.meta.url)), 'utf8')
const template = parseSfc(source).descriptor.template?.content ?? ''

describe('the board settings form: notify a person after', () => {
    it('binds the field to the board\'s humanQueueAgeMinutes, between the stops and the event retention', () => {
        const field = template.indexOf('data-testid="board-human-queue-age"')
        expect(field).toBeGreaterThan(-1)
        const tag = template.lastIndexOf('<n-input-number', field)
        expect(template.slice(tag, field)).toContain('v-model:value="editingBoard.humanQueueAgeMinutes"')
        expect(template.indexOf('editingBoard.noProgressRepeatsToStop')).toBeLessThan(field)
        expect(template.indexOf('editingBoard.eventRetentionDays')).toBeGreaterThan(field)
    })

    it('is a board setting the form saves', () => {
        expect(BOARD_SETTING_KEYS).toContain('humanQueueAgeMinutes')
        expect(source).toContain('settingsPatch(original, settingsDraftOf(editingBoard.value))')
    })

    it('loads the board\'s value, sends an edit, and sends null for a blank', () => {
        const board = { cycleCap: 3, humanQueueAgeMinutes: 60 }
        const form = { ...board }
        expect(settingsDraftOf(form).humanQueueAgeMinutes).toBe(60)
        expect(settingsPatch(board, settingsDraftOf(form))).toBeNull()
        expect(settingsPatch(board, settingsDraftOf({ ...form, humanQueueAgeMinutes: 30 }))).toEqual({ humanQueueAgeMinutes: 30 })
        expect(settingsPatch(board, settingsDraftOf({ ...form, humanQueueAgeMinutes: null }))).toEqual({ humanQueueAgeMinutes: null })
        expect(settingsPatch(board, settingsDraftOf({ ...form, humanQueueAgeMinutes: undefined }))).toEqual({ humanQueueAgeMinutes: null })
    })

    it('reads every setting key off the form, the budget in dollars', () => {
        const draft = settingsDraftOf({ budgetDollars: 1.5, softAlertPercent: 70, cycleCap: 4, noProgressRepeatsToStop: 2,
            blockingPriority: 1, completionPriority: 2, humanQueueAgeMinutes: 45, eventRetentionDays: 0,
            coordinatorStopRelease: false })
        expect(Object.keys(draft).sort()).toEqual([...BOARD_SETTING_KEYS].sort())
        expect(draft).toEqual({ budgetMicros: 1_500_000, softAlertPercent: 70, cycleCap: 4, noProgressRepeatsToStop: 2,
            blockingPriority: 1, completionPriority: 2, humanQueueAgeMinutes: 45, eventRetentionDays: 0,
            coordinatorStopRelease: false })
        expect(Object.values(settingsDraftOf({})).every(v => v === null)).toBe(true)
    })
})

// The naming fields (task fceb1e57): the prefix and the documents block are in the form, saved
// through the utils the spec above pins, and a refusal about either is shown beside it.
describe('the board form: task-key prefix and documents', () => {
    it('binds the four fields', () => {
        expect(template).toContain('v-model:value="editingBoard.taskPrefix"')
        expect(template).toContain('v-model:value="editingBoard.documentsDraft.prefix"')
        expect(template).toContain('v-model:checked="editingBoard.documentsDraft.shared"')
        expect(template).toContain('v-model:checked="editingBoard.documentsDraft.rootSet"')
        expect(template).toContain('v-model:value="editingBoard.documentsDraft.root"')
        expect(template).toContain(':placeholder="editingBoardIsNew ? taskPrefixPlaceholder(editingBoard.name) : \'unchanged\'"')
    })

    it('saves both through the board input, create and update alike', () => {
        expect(source).toContain('const taskPrefix = taskPrefixPatch(original, editingBoard.value.taskPrefix)')
        expect(source).toContain('if (taskPrefix !== undefined) input.taskPrefix = taskPrefix')
        expect(source).toContain('const documents = documentsPatch(original, editingBoard.value.documentsDraft)')
        expect(source).toContain('if (documents !== undefined) input.documents = documents')
        const save = source.slice(source.indexOf('async function saveBoard'))
        expect(save.indexOf('input.documents = documents')).toBeLessThan(save.indexOf("store.dispatch('createAgentBoard'"))
        expect(source).toContain('taskPrefix: b.taskPrefix ?? \'\', heldTaskPrefix: b.taskPrefix ?? \'\', documentsDraft: documentsDraftOf(b)')
    })

    it('shows a refusal beside its field, verbatim', () => {
        expect(source).toContain('const field = boardFieldOfError(message)')
        expect(source).toContain('if (field) boardFieldErrors.value = { [field]: message }')
        const prefixError = template.slice(template.indexOf('data-testid="board-task-prefix-error"'))
        expect(prefixError.slice(0, prefixError.indexOf('</n-text>'))).toContain('boardFieldErrors.taskPrefix')
        const docsError = template.slice(template.indexOf('data-testid="board-documents-error"'))
        expect(docsError.slice(0, docsError.indexOf('</n-text>'))).toContain('{{ boardFieldErrors.documents }}')
    })
})
