// @vitest-environment happy-dom
//
// The board functions and scope BOARD in the permission editors (task 428b4a71): the lists pinned
// (a select list that lint, build and validate all pass can still be wrong), the loaders that keep
// BOARD grants, and the labels.
import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { mount } from '@vue/test-utils'
import constants from './constants'
import { boardNameOf, boardScopeFunctions, editorKeepsScope, EDITOR_SCOPES, isBoardFunction } from './boardPermissions'

vi.mock('@/utils/graphql', () => ({ default: {} }))

const read = (f: string) => readFileSync(fileURLToPath(new URL(f, import.meta.url)), 'utf8')

describe('the board functions', () => {
    it('are listed, with what a board card offers, and are not offered at Essential Read', () => {
        expect(constants.PermissionFunctions).toEqual(expect.arrayContaining(['BOARD_READ', 'BOARD_AGENT', 'BOARD_WRITE']))
        expect(constants.BoardFunctions).toEqual(['BOARD_READ', 'BOARD_AGENT', 'BOARD_WRITE'])
        expect(constants.BoardScopeFunctions).toEqual(['BOARD_READ', 'BOARD_AGENT', 'BOARD_WRITE', 'CONFIGURATION_READ', 'CONFIGURATION_WRITE'])
        // The brief's floor for all three is READ_ONLY (board-permissions.md §2.2).
        expect(constants.EssentialReadPermissionFunctions).toEqual(['AGENT'])
    })

    it('narrows to the allowed functions and knows a board function', () => {
        expect(boardScopeFunctions(null)).toEqual(constants.BoardScopeFunctions)
        expect(boardScopeFunctions(['BOARD_AGENT', 'DEVOPS_READ'])).toEqual(['BOARD_AGENT'])
        expect(isBoardFunction('BOARD_WRITE')).toBe(true)
        expect(isBoardFunction('CONFIGURATION_WRITE')).toBe(false)
    })

    it('names a board, or says it cannot', () => {
        expect(boardNameOf([{ uuid: 'b1', name: 'One' }], 'b1')).toBe('One')
        expect(boardNameOf([{ uuid: 'b1', name: 'One' }], 'b2')).toBeNull()
        expect(boardNameOf(null, 'b1')).toBeNull()
    })

    it('have labels and descriptions', async () => {
        const { default: Label } = await import('@/components/PermissionFunctionLabel.vue')
        expect(mount(Label, { props: { f: 'BOARD_READ' } }).text()).toBe('Board Read')
        expect(mount(Label, { props: { f: 'BOARD_AGENT' } }).text()).toBe('Board Agent')
        expect(mount(Label, { props: { f: 'BOARD_WRITE' } }).text()).toBe('Board Write')
        const { default: common } = await import('./commonFunctions')
        for (const f of constants.BoardFunctions) expect(common.translateFunctionDescription(f), f).toBeTruthy()
    })
})

// A save replaces the whole set, so an editor that loaded a key, user or team without its BOARD
// grants would drop them on the next save.
describe('the editors keep BOARD grants', () => {
    it('keeps every object scope the editor shows', () => {
        expect(EDITOR_SCOPES).toEqual(['PERSPECTIVE', 'COMPONENT', 'INSTANCE', 'BOARD'])
        expect(editorKeepsScope('BOARD')).toBe(true)
        expect(editorKeepsScope('ORGANIZATION')).toBe(false)
        expect(editorKeepsScope(null)).toBe(false)
    })

    it('in the key modal, the user editor and the team editor, each naming the board', () => {
        const key = read('../components/ApiKeyPermissionsModal.vue')
        expect(key).toContain('} else if (editorKeepsScope(up.scope) && up.org === props.orgUuid) {')
        expect(key).toContain("if (scope === 'BOARD') return boardNameOf(orgBoards.value, objectId) ?? objectId")
        expect(key.match(/:boards="orgBoards"/g)).toHaveLength(2)

        const org = read('../components/OrgSettings.vue')
        expect(org).toContain("} else if ((up.scope === 'PERSPECTIVE' || up.scope === 'COMPONENT' || up.scope === 'BOARD') && up.org === orgResolved.value) {")
        expect(org).toContain('} else if (editorKeepsScope(p.scope) && p.org === orgResolved.value) {')
        expect(org).toContain('return boardNameOf(orgBoards.value, objectId) ?? objectId')
        expect(org.match(/:boards="orgBoards"/g)).toHaveLength(2)
        // the users table's permission summary names a BOARD grant's board
        expect(org).toContain("} else if (p.scope === 'BOARD' && p.object) {")
    })
})
