import { describe, expect, it } from 'vitest'
import { API_KEY_TYPE_WORDS, apiKeyLabel, apiKeyName, apiKeyNotesForLabel, apiKeyNotesUncut, apiKeyShortId, apiKeyTypeWord,
    cleanLabelText, MAX_NOTES_IN_LABEL, splitDeniedNotes } from './apiKeyLabel'

describe('apiKeyLabel', () => {
    it('names every key type in words, and falls back to the raw type', () => {
        expect(Object.keys(API_KEY_TYPE_WORDS).sort()).toEqual(['APPROVAL', 'CLUSTER', 'COMPONENT', 'FEDERATED', 'FREEFORM', 'INSTANCE', 'ORGANIZATION', 'ORGANIZATION_RW', 'USER'])
        expect(apiKeyTypeWord('FREEFORM')).toBe('Free Form')
        expect(apiKeyTypeWord('REGISTRY_USER')).toBe('REGISTRY_USER')
        expect(apiKeyTypeWord(null)).toBe('Key')
    })

    it('cleans user text of the separator, control and zero-width characters', () => {
        expect(cleanLabelText(' CI · pipeline\u200B\n')).toBe('CI pipeline')
        expect(cleanLabelText('a\u0000b\u007Fc\u2060d\uFEFFe\u0085f')).toBe('a b c d e f')
        // a bidi override in a note must not reverse the short id that follows it
        expect(cleanLabelText('deploy\u202Eabc\u2066x\u2069\u200Fy')).toBe('deploy abc x y')
        // joiners stay: Persian and emoji sequences need them
        expect(cleanLabelText('\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645')).toBe('\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645')
        expect(cleanLabelText(null)).toBe('')
        expect(cleanLabelText('   ')).toBe('')
    })

    it('names a key by its declared name, else its notes, else its type word', () => {
        expect(apiKeyName({ type: 'FREEFORM', notes: 'CI pipeline' })).toEqual({ text: 'CI pipeline', kind: 'notes' })
        expect(apiKeyName({ type: 'FREEFORM', notes: 'CI pipeline', declaredName: 'release-pipeline' })).toEqual({ text: 'release-pipeline', kind: 'declared' })
        expect(apiKeyName({ type: 'USER' })).toEqual({ text: 'Personal', kind: 'type' })
    })

    it('drops the denial the server appends to a denied request, and only there', () => {
        expect(apiKeyName({ type: 'FREEFORM', status: 'DENIED', notes: 'Release automation | Denied: no budget' })).toEqual({ text: 'Release automation', kind: 'notes' })
        expect(apiKeyName({ type: 'FREEFORM', status: 'DENIED', notes: 'Denied' })).toEqual({ text: 'Free Form', kind: 'type' })
        expect(apiKeyName({ type: 'FREEFORM', status: 'ACTIVE', notes: 'Access Denied' })).toEqual({ text: 'Access Denied', kind: 'notes' })
        // the split is at the server's " | Denied" separator, so a note that says "Denied:" itself stays whole
        expect(apiKeyName({ type: 'FREEFORM', status: 'DENIED', notes: 'Fix Denied: errors in CI | Denied: no budget' })).toEqual({ text: 'Fix Denied: errors in CI', kind: 'notes' })
        expect(apiKeyName({ type: 'FREEFORM', status: 'DENIED', notes: 'Denied: build logs | Denied' })).toEqual({ text: 'Denied: build logs', kind: 'notes' })
    })

    it('splits the denial off the notes, reason and all', () => {
        expect(splitDeniedNotes('Release automation | Denied: no budget')).toEqual({ note: 'Release automation', reason: 'no budget' })
        expect(splitDeniedNotes('Release automation | Denied')).toEqual({ note: 'Release automation', reason: '' })
        expect(splitDeniedNotes('Denied: no budget')).toEqual({ note: '', reason: 'no budget' })
        expect(splitDeniedNotes('Fix Denied: errors | Denied: x')).toEqual({ note: 'Fix Denied: errors', reason: 'x' })
        expect(splitDeniedNotes('CI pipeline')).toBeNull()
        expect(splitDeniedNotes(null)).toBeNull()
    })

    it('cuts long notes, and keeps them whole uncut', () => {
        expect(MAX_NOTES_IN_LABEL).toBe(48)
        expect(apiKeyNotesForLabel({ notes: 'x'.repeat(60) })).toBe('x'.repeat(47) + '…')
        expect(apiKeyNotesForLabel({ notes: 'x'.repeat(48) })).toBe('x'.repeat(48))
        expect(apiKeyNotesUncut({ notes: 'x'.repeat(60) })).toBe('x'.repeat(60))
    })

    it('shortens the uuid to 8 characters, longer only where siblings would clash', () => {
        expect(apiKeyShortId({ uuid: '77781336-922a-4286-9a7e-65c69e77dae9' })).toBe('77781336')
        const a = { uuid: '77781336-922a-4286-9a7e-65c69e77dae9' }
        // a and b share their first 9 characters
        const b = { uuid: '77781336-822a-4286-9a7e-65c69e77dae9' }
        expect(apiKeyShortId(a, [a, b])).toBe('77781336-9')
        expect(apiKeyShortId(a, [a, { uuid: '1c069715-0000-0000-0000-000000000000' }])).toBe('77781336')
        expect(apiKeyShortId({})).toBe('')
    })

    it('joins type word, name and short id, never the type word twice', () => {
        const key = { type: 'FREEFORM', notes: 'CI pipeline', uuid: '77781336-922a-4286-9a7e-65c69e77dae9' }
        expect(apiKeyLabel(key)).toBe('CI pipeline · 77781336')
        expect(apiKeyLabel(key, { withType: true })).toBe('Free Form · CI pipeline · 77781336')
        expect(apiKeyLabel({ type: 'USER', uuid: '86d93bbc-0000-0000-0000-000000000000' }, { withType: true })).toBe('Personal · 86d93bbc')
        expect(apiKeyLabel({ type: 'FREEFORM', notes: 'Personal · 86d93bbc', uuid: '1c069715-0000-0000-0000-000000000000' })).toBe('Personal 86d93bbc · 1c069715')
    })
})
