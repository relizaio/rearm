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

// The New task form carries a description and refuses a title the server would (T-3 of
// tests/fceb1e57/run-1.md).
describe('the board page: New task', () => {
    it('has a description beside the title, and sends both through taskRegisterInput', () => {
        const modal = template.slice(template.indexOf('title="New task"'))
        const form = modal.slice(0, modal.indexOf('</n-modal>'))
        expect(form).toContain('v-model:value="registering.title"')
        expect(form).toContain('v-model:value="registering.description" type="textarea"')
        expect(form).toContain('data-testid="new-task-title-error"')
        expect(source).toContain('input: taskRegisterInput(registering.value),')
        expect(source).toContain("registering = { title: '', description: '', externalRef: '', sourceUrl: '' }")
    })

    it('cannot register a title or description the server refuses', () => {
        const can = source.slice(source.indexOf('const canRegister'), source.indexOf('async function registerTask'))
        expect(can).toContain('!taskTitleProblem(registering.value.title)')
        expect(can).toContain('!taskDescriptionProblem(registering.value.description)')
    })
})

// Both lists in one warning, the coverage read for the selected board only (task 5c70990d).
describe('the board warning: capabilities and coverage', () => {
    it('shows the capability line, then each coverage line, in one alert', () => {
        const alert = template.slice(template.indexOf('data-testid="board-warning"'))
        const body = alert.slice(0, alert.indexOf('</n-alert>'))
        expect(template).toContain('v-if="boardWarningShown(currentBoard.missingCapabilities, missingCoverage)"')
        expect(body).toContain('currentBoard.missingCapabilities.join')
        expect(body).toContain('v-for="line in coverageLines(missingCoverage)"')
        expect(body.indexOf('missingCapabilities.join')).toBeLessThan(body.indexOf('coverageLines(missingCoverage)'))
    })

    it('reads the coverage for the selected board, not with the board list', () => {
        expect(source).toContain("await store.dispatch('fetchAgentBoardCoverage', selectedBoard.value)")
        expect(source).toContain('    await loadCoverage()\n')
    })
})

// The target picker (task RD2-4): the choices, the patch and the chip are pinned in
// utils/agentBoardTarget.spec.ts; here, that the form and the header use them.
describe('the board form: target component', () => {
    const field = template.indexOf('data-testid="board-target"')

    it('sits between the description and the sources, bound to the board\'s target', () => {
        expect(field).toBeGreaterThan(-1)
        const tag = template.lastIndexOf('<n-select', field)
        expect(template.slice(tag, field)).toContain('v-model:value="editingBoard.target" filterable :options="targetOptions"')
        expect(template.indexOf('v-model:value="editingBoard.description"')).toBeLessThan(field)
        expect(template.indexOf('v-model:value="editingBoard.sources"')).toBeGreaterThan(field)
        expect(template.indexOf('v-model:value="editingBoard.perspectives"')).toBeGreaterThan(field)
    })

    it('keeps Save disabled on a new board without a target, and says why', () => {
        const save = template.indexOf('data-testid="board-save"')
        expect(template.slice(save, template.indexOf('</n-button>', save)))
            .toContain(':disabled="targetMissing(editingBoardIsNew, editingBoard.target)"')
        expect(template).toContain('v-else-if="targetMissing(editingBoardIsNew, editingBoard.target)"')
        expect(template).toContain('Target component: {{ TARGET_HINT }}')
        expect(source).toContain("taskPrefix: '', documentsDraft: documentsDraftOf(null), target: null }")
    })

    it('sends the target on create and on a changed update, before either mutation', () => {
        const save = source.slice(source.indexOf('async function saveBoard'))
        expect(save).toContain('const target = targetPatch(original, editingBoard.value.target)')
        expect(save).toContain('if (target !== undefined) input.target = target')
        expect(save.indexOf('input.target = target')).toBeLessThan(save.indexOf("store.dispatch('createAgentBoard'"))
        expect(save.indexOf('input.target = target')).toBeLessThan(save.indexOf("store.dispatch('updateAgentBoard'"))
    })

    it('offers the fetched software components, keeping the current target on Edit', () => {
        expect(source).toContain("targetComponents.value = await store.dispatch('fetchComponents', props.orgUuid) ?? []")
        expect(source).toContain(': targetOf(boards.value.find(x => x.uuid === editingBoard.value?.uuid), targetComponents.value)))')
        expect(source).toContain('void loadTargetComponents()')
    })

    it('shows a refusal about the target beside the field', () => {
        expect(template).toContain(':status="boardFieldErrors.target ? \'error\' : undefined"')
        const err = template.indexOf('data-testid="board-target-error"')
        expect(template.slice(err, template.indexOf('</n-text>', err))).toContain('{{ boardFieldErrors.target }}')
    })

    it('shows the target as a chip in the header, linking to the component', () => {
        const chip = template.indexOf('data-testid="target-chip"')
        expect(chip).toBeGreaterThan(-1)
        expect(template.slice(chip, template.indexOf('</n-tag>', chip)))
            .toContain('<RouterLink :to="boardTargetChip.to">{{ boardTargetChip.label }}</RouterLink>')
        expect(source).toContain('const boardTargetChip = computed(() => targetChip(currentBoard.value, props.orgUuid, targetComponents.value))')
        // T-1: the list is read quietly with the panel too, so the chip can name the target on a
        // server that does not resolve targetDetails.
        expect(source).toContain('onMounted(() => loadTargetComponents(true))')
        expect(source).toContain('if (!quiet) notification.error(')
    })
})
