import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { FREEFORM_CREATE_CONFIRM, FREEFORM_CREATED_NOTICE, keyCreatedFollowUp } from './apiKeyCreated'

const org = readFileSync(fileURLToPath(new URL('../components/OrgSettings.vue', import.meta.url)), 'utf8')

describe('after a key id is created', () => {
    it('a Free Form key gets a notice to set its permissions, and no prompt for a secret', () => {
        expect(keyCreatedFollowUp('FREEFORM', 'Free Form API Key')).toEqual({ kind: 'notice', title: 'Created',
            content: 'Free Form API Key created. Set its permissions, then generate a secret from the Secrets column when needed.' })
        expect(FREEFORM_CREATED_NOTICE).toContain('Set its permissions')
    })

    it('every other type is still offered its first secret', () => {
        for (const type of ['COMPONENT', 'PRODUCT', 'ORGANIZATION_RW', 'USER']) {
            expect(keyCreatedFollowUp(type, 'API Key')).toEqual({ kind: 'offerSecret', titleText: 'API Key created',
                text: 'The key id exists but has no secret yet. Generate its first secret now? You can also do it later from the Secrets column.',
                confirmButtonText: 'Generate secret', cancelButtonText: 'Later' })
        }
    })

    it('the Free Form confirm no longer promises the secret step', () => {
        expect(FREEFORM_CREATE_CONFIRM).not.toMatch(/separate step/)
    })

    it('OrgSettings follows it: a notice for Free Form, the Swal prompt for the rest, the confirm from the util', () => {
        const fn = org.slice(org.indexOf('async function createOrgKey'), org.indexOf('const programmaticAccessFields'))
        expect(fn).toContain('const next = keyCreatedFollowUp(apiType, label)')
        expect(fn).toContain("if (next.kind === 'notice') {")
        expect(fn).toContain("notify('success', next.title, next.content)")
        expect(fn.indexOf("notify('success', next.title, next.content)")).toBeLessThan(fn.indexOf('Swal.fire'))
        expect(fn).toContain('const r = await Swal.fire({ titleText: next.titleText, text: next.text, icon: \'success\', showCancelButton: true,')
        expect(fn).toContain('confirmButtonText: next.confirmButtonText, cancelButtonText: next.cancelButtonText })')
        expect(fn).toContain("if (r.value) await apiKeyControls.mintSecret(created.uuid, 'Secret generated')")
        expect(fn).not.toContain('Generate its first secret now?')
        const gen = org.slice(org.indexOf('async function genFreeFormApiKey'), org.indexOf('function extractOrgWidePermission'))
        expect(gen).toContain('text: FREEFORM_CREATE_CONFIRM,')
        expect(gen).toContain("await createOrgKey('FREEFORM', null, 'Free Form API Key')")
    })
})
