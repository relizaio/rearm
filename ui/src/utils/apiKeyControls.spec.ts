// The key id a table shows, the EXTERNAL key grants summary and token URL, and the labels of the two
// TEA permission functions (task TEA-3, design 4.10).
import { describe, expect, it, vi } from 'vitest'

// The graphql client connects on import; nothing here calls it.
vi.mock('@/utils/graphql', () => ({ default: {} }))
const { apiKeyIdOf, externalGrantsSummary, EXTERNAL_GRANTS_NONE, teaTokenUrl } = await import('./apiKeyControls')
const { default: commonFunctions } = await import('./commonFunctions')
const { default: constants } = await import('./constants')

describe('apiKeyIdOf', () => {
    it('prefers the printed keyId of the server', () => {
        expect(apiKeyIdOf({ type: 'EXTERNAL', object: 'org-uuid', keyOrder: 'ord1', keyId: 'EXTERNAL__tea-uuid__ord__ord1' }))
            .toBe('EXTERNAL__tea-uuid__ord__ord1')
    })

    it('falls back to the derivation where the query did not ask for keyId', () => {
        expect(apiKeyIdOf({ type: 'FREEFORM', object: 'org-uuid', keyOrder: 'ord1' })).toBe('FREEFORM__org-uuid__ord__ord1')
        expect(apiKeyIdOf({ type: 'COMPONENT', object: 'comp-uuid' })).toBe('COMPONENT__comp-uuid')
        expect(apiKeyIdOf({ type: 'ORGANIZATION', object: 'org-uuid', keyOrder: null, keyId: null })).toBe('ORGANIZATION__org-uuid')
    })
})

describe('externalGrantsSummary', () => {
    const g = (scope: string, object: string, type = 'READ_ONLY', org = 'o1') => ({ org, scope, object, type, functions: ['TEA_READ'] })
    const products = new Set(['prod1', 'prod2'])

    it('says organization-wide for an organization grant, whatever else is there', () => {
        expect(externalGrantsSummary([g('COMPONENT', 'c1'), g('ORGANIZATION', 'o1')], 'o1', products)).toBe('organization-wide')
    })

    it('counts perspectives, products and components, products told apart by the product uuids', () => {
        expect(externalGrantsSummary([g('PERSPECTIVE', 'p1'), g('PERSPECTIVE', 'p2'), g('COMPONENT', 'prod1'),
            g('COMPONENT', 'c1'), g('COMPONENT', 'c2'), g('COMPONENT', 'c3')], 'o1', products))
            .toBe('2 perspectives, 1 product, 3 components')
        expect(externalGrantsSummary([g('COMPONENT', 'c1')], 'o1', products)).toBe('1 component')
        expect(externalGrantsSummary([g('COMPONENT', 'prod1'), g('COMPONENT', 'prod2')], 'o1', products)).toBe('2 products')
    })

    it('reads nothing with no grant, NONE grants only, or grants of another organization', () => {
        expect(EXTERNAL_GRANTS_NONE).toBe('none: reads nothing yet')
        expect(externalGrantsSummary([], 'o1', products)).toBe(EXTERNAL_GRANTS_NONE)
        expect(externalGrantsSummary(null, 'o1', products)).toBe(EXTERNAL_GRANTS_NONE)
        expect(externalGrantsSummary([g('ORGANIZATION', 'o1', 'NONE')], 'o1', products)).toBe(EXTERNAL_GRANTS_NONE)
        expect(externalGrantsSummary([g('ORGANIZATION', 'o2', 'READ_ONLY', 'o2')], 'o1', products)).toBe(EXTERNAL_GRANTS_NONE)
    })
})

describe('teaTokenUrl', () => {
    it('is the token endpoint under the API base, or null while the org has no TEA id', () => {
        expect(teaTokenUrl('https://r.example.com/tea/abc')).toBe('https://r.example.com/tea/abc/v1.0.0/token')
        expect(teaTokenUrl('https://r.example.com/tea/abc/')).toBe('https://r.example.com/tea/abc/v1.0.0/token')
        expect(teaTokenUrl(null)).toBeNull()
        expect(teaTokenUrl('')).toBeNull()
    })
})

describe('the TEA permission functions', () => {
    it('labels both functions', () => {
        expect(commonFunctions.translateFunctionName('PUBLISH_EXTERNALLY')).toBe('Publish Externally (TEA)')
        expect(commonFunctions.translateFunctionName('TEA_READ')).toBe('TEA Read')
    })

    it('describes PUBLISH_EXTERNALLY, which the editors offer; TEA_READ is never offered', () => {
        expect(commonFunctions.translateFunctionDescription('PUBLISH_EXTERNALLY')).toBe('Publish, re-publish and hide releases on the '
            + 'Transparency Exchange API. Granted organization-wide or on a perspective, product or component.')
        const fns: string[] = constants.PermissionFunctions
        expect(fns.indexOf('PUBLISH_EXTERNALLY')).toBe(fns.indexOf('VERSION_FEATURESET') + 1)
        expect(fns).not.toContain('TEA_READ')
        expect(constants.ExternalKeyOrgPermissionTypes).toEqual(['NONE', 'READ_ONLY'])
    })
})
