import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// NodeTypes of @vue/compiler-core, which compiler-sfc does not re-export.
const ELEMENT = 1
const ATTRIBUTE = 6
const DIRECTIVE = 7

/**
 * TPF-78 test 5 (TPF-73 part 2, ARCHITECTURE round 2 3.3 and 3.4): the selection mode and
 * "Archive selected" on the component page's Branches / Feature Sets and Tags tables.
 * ComponentView is never mounted in tests, so the template is parsed and walked and the script
 * read for its wiring (the componentViewLicenseWiring.spec.ts idiom); the logic is run in
 * utils/branchBulkArchive.spec.ts and the store action in storeArchiveBranches.spec.ts.
 */
const source = readFileSync(fileURLToPath(new URL('./ComponentView.vue', import.meta.url)), 'utf8')
const code = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
const ast: any = parseSfc(source).descriptor.template?.ast

function find (pred: (n: any) => boolean, root: any = ast): any[][] {
    const out: any[][] = []
    const walk = (node: any, ancestors: any[]): void => {
        if (node.type === ELEMENT && pred(node)) out.push([...ancestors, node])
        for (const child of node.children || []) walk(child, node.type === ELEMENT ? [...ancestors, node] : ancestors)
    }
    walk(root, [])
    return out
}
const directive = (n: any, name: string, arg?: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === DIRECTIVE && p.name === name && (arg === undefined || p.arg?.content === arg))?.exp?.content
const attribute = (n: any, name: string): string | undefined => (n.props || []).find((p: any) =>
    p.type === ATTRIBUTE && p.name === name)?.value?.content
const elementChildren = (n: any): any[] => (n.children || []).filter((c: any) => c.type === ELEMENT)
const last = (path: any[]): any => path[path.length - 1]

/** A top-level script function or const, from its opening to the first line that closes it at column 0. */
function bodyOf (text: string, opening: string): string {
    const at = text.indexOf(opening)
    if (at < 0) return ''
    const end = text.slice(at).search(/\n\}\)?\n/)
    return end < 0 ? '' : text.slice(at, at + end + 3)
}
const body = (opening: string): string => bodyOf(code, opening)

const pane = (name: string): any => {
    const panes = find((n) => n.tag === 'n-tab-pane' && attribute(n, 'name') === name)
    expect(panes).toHaveLength(1)
    return last(panes[0])
}

const PANES: [string, string, string][] = [['branches', 'checkedBranchKeys', 'branchColumns'], ['tags', 'checkedTagKeys', 'tagFields']]

describe('ComponentView: the bulk archive selection mode (template)', () => {
    for (const [name, keys, columns] of PANES) {
        it(`binds the ${name} table's checked keys to ${keys} and keeps its row key`, () => {
            const tables = find((n) => n.tag === 'n-data-table', pane(name))
            expect(tables).toHaveLength(1)
            const table = last(tables[0])
            expect(directive(table, 'model', 'checked-row-keys')).toBe(keys)
            expect(directive(table, 'bind', 'row-key')).toBe('branchTableRowKey')
            expect(directive(table, 'bind', 'columns')).toBe(columns)
            expect(directive(table, 'bind', 'row-props')).toBe('rowProps')
        })

        it(`puts one bar before the ${name} table, only in the mode, with a guarded run button`, () => {
            const p = pane(name)
            const bars = find((n) => attribute(n, 'data-testid') === 'bulk-archive-bar', p)
            expect(bars).toHaveLength(1)
            const bar = last(bars[0])
            expect(directive(bar, 'if')).toBe('bulkArchiveMode')
            const children = elementChildren(p)
            const tableIndex = children.findIndex((c: any) => c.tag === 'n-data-table')
            expect(children.indexOf(bar)).toBeGreaterThanOrEqual(0)
            expect(children.indexOf(bar)).toBeLessThan(tableIndex)
            const runs = find((n) => attribute(n, 'data-testid') === 'bulk-archive-run', bar)
            expect(runs).toHaveLength(1)
            const run = last(runs[0])
            expect(run.tag).toBe('n-button')
            expect(attribute(run, 'type')).toBe('error')
            const disabled = directive(run, 'bind', 'disabled') || ''
            expect(disabled).toContain(`${keys}.length === 0`)
            expect(disabled).toContain('bulkArchiveRunning')
            expect(directive(run, 'on', 'click')).toBe(`runBulkArchive('${name}')`)
        })
    }

    it('has exactly two bars and two run buttons', () => {
        expect(find((n) => attribute(n, 'data-testid') === 'bulk-archive-bar')).toHaveLength(2)
        expect(find((n) => attribute(n, 'data-testid') === 'bulk-archive-run')).toHaveLength(2)
    })
})

describe('ComponentView: the bulk archive selection mode (script)', () => {
    const toggleGate = /\(isWritable\.value && myUser\.installationType !== 'OSS'\) \? h\(\s*Icon,[\s\S]*?'data-testid': 'bulk-archive-toggle'/

    it('renders the toggle in both titles behind the writer and Pro gate', () => {
        const branchFields = body('const branchFields: any[] = [')
        expect(branchFields).not.toBe('')
        expect(branchFields).toMatch(toggleGate)
        const tagBase = body('const tagBaseFields: any[] = [')
        expect(tagBase).not.toBe('')
        expect(tagBase).toMatch(toggleGate)
        expect(code.match(/'data-testid': 'bulk-archive-toggle'/g)).toHaveLength(2)
    })

    it('defines the selection column once, with the base row disabled', () => {
        const column = body('const selectionColumn = {')
        expect(column).not.toBe('')
        expect(column).toContain("type: 'selection'")
        expect(column).toContain('disabled: (row: any) => !selectableBranch(row)')
        expect(code.match(/type: 'selection'/g)).toHaveLength(1)
    })

    it('adds the selection column to both computeds only in the mode', () => {
        for (const opening of ['const branchColumns = computed(', 'const tagFields = computed(']) {
            const computedBody = body(opening)
            expect(computedBody).not.toBe('')
            expect(computedBody).toContain('selectionColumn')
            expect(computedBody).toContain('bulkArchiveMode.value')
        }
    })

    describe('runBulkArchive', () => {
        const run = body('async function runBulkArchive')
        const catchAt = run.indexOf('} catch')
        const finallyAt = run.indexOf('} finally')
        const catchBlock = run.slice(catchAt, finallyAt)

        it('confirms first, in the object form with text only', () => {
            expect(run).not.toBe('')
            expect(run).toContain('Swal.fire({')
            expect(run).toContain('text: confirmText(names, words.value)')
            expect(run).not.toContain('html:')
            expect(run).not.toContain("Swal.fire('")
            const confirmedAt = run.indexOf('if (!answer.isConfirmed)')
            expect(confirmedAt).toBeGreaterThan(0)
            expect(confirmedAt).toBeLessThan(run.indexOf("store.dispatch('archiveBranches'"))
        })

        it('sends the chunks in order and counts the returned calls', () => {
            expect(run).toContain('chunk(')
            expect(run).toMatch(/store\.dispatch\('archiveBranches', \{ branchUuids: batch, componentUuid/)
            const pushAt = run.indexOf('results.push(')
            const sentAt = run.indexOf('sent += 1')
            expect(pushAt).toBeGreaterThan(0)
            expect(sentAt).toBeGreaterThan(pushAt)
            expect(sentAt).toBeLessThan(catchAt)
        })

        it('shows a thrown error as text and keeps the failing and later batches', () => {
            expect(catchAt).toBeGreaterThan(0)
            expect(finallyAt).toBeGreaterThan(catchAt)
            expect(catchBlock).toContain('Swal.fire({')
            expect(catchBlock).toContain('commonFunctions.parseGraphQLError(')
            expect(catchBlock).toContain('unsent = unsentAfter(batches, sent)')
            expect(run.slice(finallyAt)).toMatch(/^\} finally \{\s*bulkArchiveRunning\.value = false/)
        })

        it('summarises once, keeps the not-archived rows selected and moves off an archived current branch', () => {
            const summaryAt = run.indexOf('summarizeOutcomes(')
            const notifyAt = run.indexOf('notify(s.type, s.title, s.text)')
            expect(summaryAt).toBeGreaterThan(finallyAt)
            expect(notifyAt).toBeGreaterThan(summaryAt)
            expect(run).toMatch(/= \[\.\.\.keepFailedSelected\([^\n]*\.\.\.unsent\]/)
            expect(run.indexOf('selectBranch(mainBranch.value)')).toBeGreaterThan(notifyAt)
        })
    })

    it('leaves the per-row archive untouched', () => {
        expect(bodyOf(source, 'async function archiveBranchFromList')).toBe(`async function archiveBranchFromList (row: any) {
    if (!row || !row.uuid || row.type === 'BASE') return
    const onSwalConfirm = async function () {
        try {
            await store.dispatch('archiveBranch', {
                branchUuid: row.uuid,
                componentUuid: componentUuid,
            })
        } catch (err: any) {
            Swal.fire('Error!', commonFunctions.parseGraphQLError(err.message), 'error')
        }
    }
    const swalData: SwalData = {
        questionText: \`Are you sure you want to archive \${words.value.branch} "\${row.name}"?\`,
        successTitle: 'Archived!',
        successText: \`\${words.value.branchFirstUpper} "\${row.name}" has been archived.\`,
        dismissText: \`Archive cancelled.\`,
    }
    await commonFunctions.swalWrapper(onSwalConfirm, swalData, notify)
}
`)
    })
})

// TPF-78 ARCHITECTURE round 3 3.7 (tester run 1 T-1): in the selection mode a row click toggles the
// row and never navigates; outside the mode the click and double click behave as before.
describe('ComponentView: rowProps in the selection mode', () => {
    const rp = body('const rowProps = (row: any) => {')
    const onClickOpening = 'onClick: (e: MouseEvent) => {'
    const dblAt = rp.indexOf('onDblclick: () => {')
    const clickAt = rp.indexOf(onClickOpening)
    const dbl = rp.slice(dblAt, clickAt)
    const click = rp.slice(clickAt)
    const count = (text: string, needle: string): number => text.split(needle).length - 1

    it('is one top-level arrow and imports rowClickAction from the util', () => {
        expect(rp).not.toBe('')
        expect(dblAt).toBeGreaterThan(0)
        expect(clickAt).toBeGreaterThan(dblAt)
        const imports = code.split('\n').filter((l) => l.includes("from '@/utils/branchBulkArchive'"))
        expect(imports).toHaveLength(1)
        expect(imports[0]).toMatch(/[{,]\s*rowClickAction\s*[,}]/)
    })

    it('returns from the double click in the mode before it navigates', () => {
        const guardAt = dbl.indexOf('if (bulkArchiveMode.value) return')
        expect(guardAt).toBeGreaterThan(0)
        expect(dbl.indexOf('router.push(')).toBeGreaterThan(guardAt)
    })

    it('decides the click with rowClickAction and only selects the branch on select', () => {
        const steps = [
            "const keysRef = row.type === 'TAG' ? checkedTagKeys : checkedBranchKeys",
            'rowClickAction(bulkArchiveMode.value, row, e.target, keysRef.value)',
            "if (r.action !== 'select') {",
            'clearTimeout(clickTimer)',
            "if (r.action === 'toggle') keysRef.value = r.keys",
            'return',
            'clickTimer = setTimeout(',
            'selectBranch(row.uuid)',
        ]
        let from = 0
        for (const step of steps) {
            const at = click.indexOf(step, from)
            expect(at, step).toBeGreaterThanOrEqual(from)
            from = at + step.length
        }
    })

    it('navigates only from the timer outside the mode and from the guarded double click', () => {
        expect(count(rp, 'selectBranch(')).toBe(1)
        expect(rp.indexOf('selectBranch(')).toBeGreaterThan(rp.indexOf('clickTimer = setTimeout('))
        expect(count(rp, 'router.push(')).toBe(1)
        expect(rp.indexOf('router.push(')).toBeLessThan(clickAt)
        const branchAt = click.indexOf("if (r.action !== 'select') {")
        const nonSelect = click.slice(branchAt, click.indexOf('return', branchAt))
        expect(nonSelect).not.toContain('selectBranch(')
        expect(nonSelect).not.toContain('router.push(')
    })

    it('leaves the row class and the row key untouched', () => {
        expect(bodyOf(source, 'const branchRowClassName')).toBe(`const branchRowClassName = (row: any) => {
    return selectedBranchUuid.value === row.uuid ? 'selectedRow' : ''
}
`)
        expect(source.split('\n').filter((l) => l.startsWith('const branchTableRowKey'))).toEqual(['const branchTableRowKey = (row: any) => row.uuid'])
    })
})
