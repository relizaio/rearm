import { describe, it, expect } from 'vitest'
import { changeEntity, changeMessage, parseSpecFile, specReferences } from './declarativeSpec'

describe('parseSpecFile', () => {
    it('keeps a declared null and leaves an absent field absent', () => {
        const p = parseSpecFile('kind: BOARD\nname: platform\nsettings:\n  budgetMicros: null\n', ['BOARD'])
        expect(p.kind).toBe('BOARD')
        expect(p.spec.version).toBe(1)
        expect('budgetMicros' in p.spec.settings).toBe(true)
        expect(p.spec.settings.budgetMicros).toBeNull()
        expect('cycleCap' in p.spec.settings).toBe(false)
    })

    it('reads JSON as well', () => {
        const p = parseSpecFile('{"kind": "ROLE_PRESETS", "presets": [{"name": "coder"}]}', ['ROLE_PRESETS'])
        expect(p.spec.presets[0].name).toBe('coder')
    })

    it('refuses a kind this upload does not take, and a file that is not a spec', () => {
        expect(() => parseSpecFile('kind: CATALOG\n', ['BOARD'])).toThrow(/takes BOARD files, not CATALOG/)
        expect(() => parseSpecFile('name: x\n', ['BOARD'])).toThrow(/no kind/)
        expect(() => parseSpecFile('- a\n- b\n', ['BOARD'])).toThrow(/not a spec/)
        expect(() => parseSpecFile('kind: [unclosed\n', ['BOARD'])).toThrow(/Not valid YAML/)
    })
})

describe('specReferences', () => {
    it('names every reference only a client with the files can inline', () => {
        const p = parseSpecFile(`kind: BOARD
name: platform
coordinatorPromptFile: prompts/coordinator.md
roles:
  - name: designer
    promptFile: prompts/designer.md
  - file: roles/coder.yaml
  - name: reviewer
    prompt: inline
`, ['BOARD'])
        expect(specReferences(p)).toEqual([
            'coordinatorPromptFile: prompts/coordinator.md',
            'roles[0].promptFile: prompts/designer.md',
            'roles[1].file: roles/coder.yaml',
        ])
    })

    it('reads presets from their own list', () => {
        const p = parseSpecFile('kind: ROLE_PRESETS\npresets:\n  - file: presets/coder.yaml\n', ['ROLE_PRESETS'])
        expect(specReferences(p)).toEqual(['presets[0].file: presets/coder.yaml'])
    })
})

describe('changeEntity and changeMessage', () => {
    it('tells the board from its roles', () => {
        expect(changeEntity('BOARD', 'UPDATE', 'board')).toBe('board')
        expect(changeEntity('BOARD', 'CREATE', 'role')).toBe('role')
        expect(changeEntity('BOARD', 'ARCHIVE', "absent from the board's file")).toBe('role')
        expect(changeEntity('ROLE_PRESETS', 'CREATE', null)).toBe('preset')
        expect(changeMessage('BOARD', 'role')).toBe('')
        expect(changeMessage('BOARD', "absent from the board's file")).toBe("absent from the board's file")
    })
})
