// @vitest-environment happy-dom
//
// T-25 (SCORE-35, design 3.2 to 3.4): the organization settings section "SBOM export signing":
// the current key, the JWKS URL and the other keys from a mocked list; each action calls its
// mutation, after a confirm where the design asks for one, and reloads the list; a schema-drift
// refusal of the list is reported to OrgSettings, any other failure is shown and the section stays.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { NTag } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
const fire = vi.fn()
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a) } }))
// commonFunctions pulls in keycloak; the panel uses only its message cleaner.
vi.mock('@/utils/commonFunctions', () => ({ default: { parseGraphQLError: (m: string) => m } }))

const { default: Panel } = await import('./SbomSigningKeysPanel.vue')
const { GENERATE_SBOM_SIGNING_KEY, IMPORT_SBOM_SIGNING_KEY, REVOKE_SBOM_SIGNING_KEY, SBOM_SIGNING_KEYS } = await import('@/utils/sbomSigningQueries')
const { PUBLIC_KEYS_NOTE, REVOKE_CONFIRM, ROTATE_CONFIRM, dateCell } = await import('@/utils/sbomSigningKeys')

const ORG = '11111111-2222-3333-4444-555555555555'
const JWK = '{"kty":"OKP","crv":"Ed25519","x":"11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo"}'
const PEM = '-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEA11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=\n-----END PUBLIC KEY-----\n'
const CURRENT = { uuid: 'aaaaaaaa-0000-4000-8000-000000000003', keyId: 'aaaaaaaa-0000-4000-8000-000000000003', algorithm: 'Ed25519', status: 'CURRENT',
    source: 'GENERATED', publicKeyJwk: JWK, publicKeyPem: PEM, createdDate: '2026-10-12T09:30:00Z', retiredAt: null, revokedAt: null }
const RETIRED = { uuid: 'aaaaaaaa-0000-4000-8000-000000000002', keyId: 'aaaaaaaa-0000-4000-8000-000000000002', algorithm: 'Ed25519', status: 'RETIRED',
    source: 'IMPORTED', publicKeyJwk: JWK, publicKeyPem: PEM, createdDate: '2026-10-05T08:00:00Z', retiredAt: '2026-10-12T09:30:00Z', revokedAt: null }
const REVOKED = { uuid: 'aaaaaaaa-0000-4000-8000-000000000001', keyId: 'aaaaaaaa-0000-4000-8000-000000000001', algorithm: 'Ed25519', status: 'REVOKED',
    source: 'GENERATED', publicKeyJwk: JWK, publicKeyPem: PEM, createdDate: '2026-10-01T07:00:00Z', retiredAt: '2026-10-05T08:00:00Z', revokedAt: '2026-10-06T12:00:00Z' }
const PKCS8_REFUSAL = 'SBOM signing key import expects an Ed25519 private key as PKCS#8 PEM (BEGIN PRIVATE KEY)'

// The import form sits in a modal, which teleports: render it in place.
const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' } } }

function listReply (keys: any[]) {
    return { data: { sbomSigningKeys: keys } }
}

// A schema-drift refusal as graphqlDriftFallback.spec.ts builds one (200 with a validation error).
function driftError (): any {
    const err: any = new Error('Validation error of type FieldUndefined: Field \'sbomSigningKeys\' is undefined')
    err.errors = [{ message: err.message, extensions: { classification: 'ValidationError' } }]
    return err
}

async function mountPanel (keys: any[] | Error, canManage = true) {
    if (keys instanceof Error) query.mockRejectedValueOnce(keys)
    else query.mockResolvedValueOnce(listReply(keys))
    const notify = vi.fn()
    const w = mount(Panel, { props: { orgUuid: ORG, canManage, notify }, global, attachTo: document.body })
    await flushPromises()
    return { w, notify }
}

const listCalls = () => query.mock.calls.filter(c => c[0].query === SBOM_SIGNING_KEYS)

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    fire.mockReset()
})

describe('SbomSigningKeysPanel', () => {
    it('rendersTheCurrentKeyAndTheJwksUrl', async () => {
        const { w } = await mountPanel([CURRENT, RETIRED, REVOKED])
        expect(query).toHaveBeenCalledWith({ query: SBOM_SIGNING_KEYS, variables: { orgUuid: ORG }, fetchPolicy: 'network-only' })

        const current = w.find('[data-testid="sbom-signing-current"]')
        expect(current.text()).toContain(CURRENT.keyId)
        expect(current.text()).toContain('Ed25519')
        expect(w.find('[data-testid="sbom-signing-current-created"]').text()).toBe(dateCell(CURRENT.createdDate))
        expect(w.find('[data-testid="sbom-signing-current-jwk"]').text()).toBe(JWK)
        expect((w.find('[data-testid="sbom-signing-current-pem"] textarea').element as HTMLTextAreaElement).value).toBe(PEM)
        expect(w.find('[data-testid="sbom-signing-no-key"]').exists()).toBe(false)

        // the public JWKS is withdrawn for now: no URL is shown, a note says it is upcoming experimental functionality
        expect(w.find('[data-testid="sbom-signing-jwks-url"]').exists()).toBe(false)
        expect(w.find('[data-testid="sbom-signing-public-keys-note"]').text()).toContain(PUBLIC_KEYS_NOTE)

        const rows = w.find('[data-testid="sbom-signing-others"]').findAll('tbody tr')
        expect(rows).toHaveLength(2)
        expect(rows[0].text()).toContain(RETIRED.keyId)
        expect(rows[0].text()).toContain(dateCell(RETIRED.retiredAt))
        expect(rows[1].text()).toContain(REVOKED.keyId)
        expect(rows[1].text()).toContain(dateCell(REVOKED.revokedAt))
        const tags = w.findAllComponents(NTag).map(t => [t.text(), t.props('type')])
        expect(tags).toEqual([['RETIRED', 'default'], ['REVOKED', 'error']])
        // Revoke on the RETIRED row only.
        expect(rows[0].find('[data-testid="sbom-signing-revoke-row"]').exists()).toBe(true)
        expect(rows[1].find('[data-testid="sbom-signing-revoke-row"]').exists()).toBe(false)

        expect(w.find('[data-testid="sbom-signing-generate"]').text()).toBe('Rotate key')
        w.unmount()
    })

    it('rendersNoKeyState', async () => {
        const { w } = await mountPanel([])
        expect(w.find('[data-testid="sbom-signing-no-key"]').text()).toBe('No current key: exports are not signed.')
        expect(w.find('[data-testid="sbom-signing-current"]').exists()).toBe(false)
        expect(w.find('[data-testid="sbom-signing-others"]').exists()).toBe(false)
        expect(w.find('[data-testid="sbom-signing-jwks-url"]').exists()).toBe(false)
        expect(w.find('[data-testid="sbom-signing-public-keys-note"]').exists()).toBe(true)
        expect(w.find('[data-testid="sbom-signing-generate"]').text()).toBe('Generate key')
        w.unmount()
    })

    it('generateWithoutAKeyCallsTheMutationWithoutAConfirm', async () => {
        const { w, notify } = await mountPanel([])
        mutate.mockResolvedValueOnce({ data: { generateSbomSigningKey: CURRENT } })
        query.mockResolvedValueOnce(listReply([CURRENT]))
        await w.find('[data-testid="sbom-signing-generate"]').trigger('click')
        await flushPromises()
        expect(fire).not.toHaveBeenCalled()
        expect(mutate).toHaveBeenCalledOnce()
        expect(mutate.mock.calls[0][0]).toMatchObject({ mutation: GENERATE_SBOM_SIGNING_KEY, variables: { orgUuid: ORG } })
        expect(listCalls()).toHaveLength(2)
        expect(notify).toHaveBeenCalledWith('success', 'SBOM export signing', 'Key generated')
        // The reloaded list, not the mutation's reply, is what shows.
        expect(w.find('[data-testid="sbom-signing-current"]').text()).toContain(CURRENT.keyId)
        expect(w.find('[data-testid="sbom-signing-generate"]').text()).toBe('Rotate key')
        w.unmount()
    })

    it('rotateConfirmsThenGenerates', async () => {
        const { w } = await mountPanel([CURRENT])
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await w.find('[data-testid="sbom-signing-generate"]').trigger('click')
        await flushPromises()
        expect(fire).toHaveBeenCalledWith(expect.objectContaining({ ...ROTATE_CONFIRM, showCancelButton: true }))
        expect(mutate).not.toHaveBeenCalled()

        fire.mockResolvedValueOnce({ isConfirmed: true })
        mutate.mockResolvedValueOnce({ data: {} })
        query.mockResolvedValueOnce(listReply([CURRENT]))
        await w.find('[data-testid="sbom-signing-generate"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledOnce()
        expect(mutate.mock.calls[0][0]).toMatchObject({ mutation: GENERATE_SBOM_SIGNING_KEY, variables: { orgUuid: ORG } })
        expect(listCalls()).toHaveLength(2)
        w.unmount()
    })

    it('importSendsThePemVerbatim', async () => {
        const { w } = await mountPanel([CURRENT])
        await w.find('[data-testid="sbom-signing-import"]').trigger('click')
        const modal = w.find('[data-testid="sbom-signing-import-modal"]')
        expect(modal.text()).toContain('PKCS#8 PEM (BEGIN PRIVATE KEY)')
        // With a CURRENT key the import rotates, and the modal says so.
        expect(modal.text()).toContain(ROTATE_CONFIRM.text)
        const pem = '  -----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEIA==\n-----END PRIVATE KEY-----\n'
        await w.find('[data-testid="sbom-signing-import-pem"] textarea').setValue(pem)
        mutate.mockResolvedValueOnce({ data: {} })
        query.mockResolvedValueOnce(listReply([CURRENT]))
        await w.find('[data-testid="sbom-signing-import-submit"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledOnce()
        expect(mutate.mock.calls[0][0]).toMatchObject({ mutation: IMPORT_SBOM_SIGNING_KEY, variables: { orgUuid: ORG, privateKeyPem: pem } })
        expect(listCalls()).toHaveLength(2)
        expect(w.find('[data-testid="sbom-signing-import-modal"]').exists()).toBe(false)
        w.unmount()
    })

    it('importShowsTheServerRefusalAndStaysOpen', async () => {
        const { w } = await mountPanel([])
        await w.find('[data-testid="sbom-signing-import"]').trigger('click')
        expect(w.find('[data-testid="sbom-signing-import-modal"]').text()).not.toContain(ROTATE_CONFIRM.text)
        await w.find('[data-testid="sbom-signing-import-pem"] textarea').setValue('-----BEGIN RSA PRIVATE KEY-----\n')
        mutate.mockRejectedValueOnce(new Error(PKCS8_REFUSAL))
        await w.find('[data-testid="sbom-signing-import-submit"]').trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="sbom-signing-import-error"]').text()).toBe(PKCS8_REFUSAL)
        expect(w.find('[data-testid="sbom-signing-import-modal"]').exists()).toBe(true)
        expect(listCalls()).toHaveLength(1)
        w.unmount()
    })

    it('revokeConfirmsThenRevokesByUuid', async () => {
        const { w } = await mountPanel([CURRENT, RETIRED, REVOKED])
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await w.find('[data-testid="sbom-signing-revoke-row"]').trigger('click')
        await flushPromises()
        expect(fire).toHaveBeenCalledWith(expect.objectContaining({ ...REVOKE_CONFIRM, showCancelButton: true }))
        expect(mutate).not.toHaveBeenCalled()

        fire.mockResolvedValue({ isConfirmed: true })
        mutate.mockResolvedValue({ data: {} })
        query.mockResolvedValue(listReply([CURRENT, RETIRED, REVOKED]))
        await w.find('[data-testid="sbom-signing-revoke-row"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledOnce()
        expect(mutate.mock.calls[0][0]).toMatchObject({ mutation: REVOKE_SBOM_SIGNING_KEY, variables: { uuid: RETIRED.uuid } })

        await w.find('[data-testid="sbom-signing-revoke-current"]').trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(2)
        expect(mutate.mock.calls[1][0]).toMatchObject({ mutation: REVOKE_SBOM_SIGNING_KEY, variables: { uuid: CURRENT.uuid } })
        expect(listCalls()).toHaveLength(3)
        w.unmount()
    })

    it('hidesActionsWhenCanManageIsFalse', async () => {
        const { w } = await mountPanel([CURRENT, RETIRED, REVOKED], false)
        expect(w.find('[data-testid="sbom-signing-current"]').exists()).toBe(true)
        expect(w.find('[data-testid="sbom-signing-actions"]').exists()).toBe(false)
        expect(w.find('[data-testid="sbom-signing-generate"]').exists()).toBe(false)
        expect(w.find('[data-testid="sbom-signing-import"]').exists()).toBe(false)
        expect(w.find('[data-testid="sbom-signing-revoke-row"]').exists()).toBe(false)
        expect(w.find('[data-testid="sbom-signing-others"]').findAll('tbody tr')).toHaveLength(2)
        w.unmount()
    })

    it('emitsUnsupportedOnSchemaDrift', async () => {
        const { w, notify } = await mountPanel(driftError())
        expect(w.emitted('unsupported')).toHaveLength(1)
        expect(notify).not.toHaveBeenCalled()
        w.unmount()
    })

    it('reportsOtherErrorsAndKeepsTheSection', async () => {
        const err: any = Object.assign(new Error('Response not successful: Received status code 500'), { statusCode: 500 })
        const { w, notify } = await mountPanel(err)
        expect(notify).toHaveBeenCalledOnce()
        expect(notify).toHaveBeenCalledWith('error', 'Error', err.message)
        expect(w.emitted('unsupported')).toBeUndefined()
        expect(w.text()).toContain('SBOM export signing')
        expect(w.find('[data-testid="sbom-signing-load-error"]').text()).toContain(err.message)

        // Retry loads again, and the section fills.
        query.mockResolvedValueOnce(listReply([CURRENT]))
        await w.find('[data-testid="sbom-signing-retry"]').trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="sbom-signing-load-error"]').exists()).toBe(false)
        expect(w.find('[data-testid="sbom-signing-current"]').exists()).toBe(true)
        w.unmount()
    })
})
