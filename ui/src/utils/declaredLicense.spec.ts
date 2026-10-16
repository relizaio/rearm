import { describe, expect, it, vi } from 'vitest'
import {
    COMPONENT_DECLARED_LICENSE_QUERY, EFFECTIVE_NONE, ORG_DECLARED_LICENSE_QUERY, ORG_NOT_LISTED, RELEASE_DECLARED_LICENSE_QUERY,
    SUPPLIER_HINT, SUPPLIER_SENTENCE,
    componentDeclaredLicenseVariables, declaredLicenseDiffers, effectiveLicenseLine, licenseUrlHref,
    loadComponentDeclaredLicense, loadOrgDeclaredLicense, loadReleaseDeclaredLicense, orgDeclaredLicenseVariables,
    releaseDeclaredLicenseVariables,
} from './declaredLicense'
import * as copy from './declaredLicense'

// T-18 (SCORE-42, design ADR-1 and ADR-5): the variable builders, the effective line, the link
// rule and the loaders of the declared licence, run.

const ORG = 'org-1'
const COMP = 'comp-1'
const REL = 'rel-1'

// The sentence of design 3.1, the supplierName schema comment's last three sentences.
const CARRIER = 'Applies to the nodes ReARM makes at the next export and to SBOMs uploaded after it is set. ' +
    'A root uploaded earlier keeps the supplier recorded at its upload; a newly generated SBOM (a new serialNumber) ' +
    'uploaded to a release made after the change carries the new value in that release\'s export. ' +
    'An identical file is answered with the stored row, unchanged.'

// A schema-drift refusal as graphqlDriftFallback.spec.ts builds one (200 with a validation error).
function driftError (): any {
    const err: any = new Error('Validation error (FieldUndefined@[component/licenseExpression]) : Field \'licenseExpression\' in type \'Component\' is undefined')
    err.errors = [{ message: err.message, extensions: { classification: 'ValidationError' } }]
    return err
}
const clientAnswering = (data: any) => ({ query: vi.fn().mockResolvedValue({ data }) })
const clientFailing = (err: any) => ({ query: vi.fn().mockRejectedValue(err) })

describe('orgDeclaredLicenseVariables', () => {
    const base = { expression: 'Apache-2.0', url: 'https://x.example/l' }

    it('gives null when nothing changed', () => {
        expect(orgDeclaredLicenseVariables(ORG, { ...base }, base)).toBeNull()
    })

    it('sends only a changed expression, trimmed', () => {
        expect(orgDeclaredLicenseVariables(ORG, { ...base, expression: '  MIT ' }, base))
            .toEqual({ orgUuid: ORG, settings: { defaultLicenseExpression: 'MIT' } })
    })

    it('sends an emptied expression as the empty string, the clear', () => {
        expect(orgDeclaredLicenseVariables(ORG, { ...base, expression: '' }, base))
            .toEqual({ orgUuid: ORG, settings: { defaultLicenseExpression: '' } })
    })

    it('sends only the URL when it is added beside an unchanged expression', () => {
        const was = { expression: 'LicenseRef-Acme', url: '' }
        expect(orgDeclaredLicenseVariables(ORG, { expression: 'LicenseRef-Acme', url: 'https://x.example/l' }, was))
            .toEqual({ orgUuid: ORG, settings: { defaultLicenseUrl: 'https://x.example/l' } })
    })

    it('reads whitespace-only input as empty, both ways', () => {
        expect(orgDeclaredLicenseVariables(ORG, { expression: '   ', url: '' }, { expression: '', url: '' })).toBeNull()
        expect(orgDeclaredLicenseVariables(ORG, { expression: '', url: '' }, { expression: '  ', url: ' ' })).toBeNull()
        expect(orgDeclaredLicenseVariables(ORG, { expression: '  ', url: '' }, { expression: 'MIT', url: '' }))
            .toEqual({ orgUuid: ORG, settings: { defaultLicenseExpression: '' } })
    })
})

describe('componentDeclaredLicenseVariables', () => {
    const base = { expression: 'MIT', url: '', supplier: 'Acme Corp' }

    it('always carries uuid and name, and only the changed fields', () => {
        expect(componentDeclaredLicenseVariables(COMP, 'web', { ...base, url: 'https://x.example/l' }, base))
            .toEqual({ component: { uuid: COMP, name: 'web', licenseUrl: 'https://x.example/l' } })
        expect(componentDeclaredLicenseVariables(COMP, 'web', { expression: 'Apache-2.0', url: '', supplier: ' Other ' }, base))
            .toEqual({ component: { uuid: COMP, name: 'web', licenseExpression: 'Apache-2.0', supplierName: 'Other' } })
    })

    it('sends an emptied supplier as the empty string', () => {
        expect(componentDeclaredLicenseVariables(COMP, 'web', { ...base, supplier: '' }, base))
            .toEqual({ component: { uuid: COMP, name: 'web', supplierName: '' } })
    })

    it('gives null when nothing changed', () => {
        expect(componentDeclaredLicenseVariables(COMP, 'web', { ...base, supplier: 'Acme Corp  ' }, base)).toBeNull()
    })
})

describe('releaseDeclaredLicenseVariables', () => {
    it('sends uuid, org and licenseExpression and nothing else', () => {
        expect(releaseDeclaredLicenseVariables(REL, ORG, { expression: ' mit ' }, { expression: '' }))
            .toEqual({ release: { uuid: REL, org: ORG, licenseExpression: 'mit' } })
    })

    it('sends the empty string on a clear', () => {
        expect(releaseDeclaredLicenseVariables(REL, ORG, { expression: '' }, { expression: 'MIT' }))
            .toEqual({ release: { uuid: REL, org: ORG, licenseExpression: '' } })
    })

    it('gives null when unchanged', () => {
        expect(releaseDeclaredLicenseVariables(REL, ORG, { expression: 'MIT ' }, { expression: 'MIT' })).toBeNull()
    })
})

describe('declaredLicenseDiffers', () => {
    it('is false for equal values and whitespace-only differences', () => {
        expect(declaredLicenseDiffers({ expression: 'MIT', url: '' }, { expression: 'MIT', url: '' })).toBe(false)
        expect(declaredLicenseDiffers({ expression: ' MIT', url: '  ' }, { expression: 'MIT', url: '' })).toBe(false)
    })

    it('is true for an edit and for a clear', () => {
        expect(declaredLicenseDiffers({ expression: 'MIT', supplier: 'B' }, { expression: 'MIT', supplier: 'A' })).toBe(true)
        expect(declaredLicenseDiffers({ expression: '' }, { expression: 'MIT' })).toBe(true)
    })
})

describe('effectiveLicenseLine', () => {
    it.each([['ORGANIZATION', 'organization'], ['COMPONENT', 'component'], ['RELEASE', 'release']])(
        'names the level %s', (level, word) => {
            expect(effectiveLicenseLine({ expression: 'MIT', url: null, level })).toBe(`Effective: MIT (from the ${word})`)
        })

    it('says no licence is declared for null', () => {
        expect(effectiveLicenseLine(null)).toBe('No licence declared at any level.')
        expect(EFFECTIVE_NONE).toBe('No licence declared at any level.')
    })

    it('prints an unknown level as served, lower-cased', () => {
        expect(effectiveLicenseLine({ expression: 'MIT', url: null, level: 'PRODUCT' })).toBe('Effective: MIT (from the product)')
    })
})

describe('licenseUrlHref', () => {
    it('returns http and https URLs as given', () => {
        expect(licenseUrlHref('https://x.example/l')).toBe('https://x.example/l')
        expect(licenseUrlHref('http://x.example/l')).toBe('http://x.example/l')
    })

    it.each([['javascript:alert(1)'], ['file:///etc/passwd'], ['data:text/html,x'], ['ftp://x'], ['not a url'], [''], [null],
        [' javascript:alert(1)'], ['java\tscript:alert(1)']])('refuses %j', (url) => {
        expect(licenseUrlHref(url)).toBeNull()
    })
})

describe('the loaders', () => {
    // Round 3 (T-1): the organization is read from the organizations list and matched by uuid; the
    // single-organization root field is never resolved by rearm-saas and answers null.
    const OTHER = { uuid: 'org-2', settings: { defaultLicenseExpression: 'GPL-3.0-only', defaultLicenseUrl: 'https://other.example/l' } }

    it('read the organization default from the organizations list, matched by uuid, without the cache', async () => {
        const mine = { uuid: ORG, settings: { defaultLicenseExpression: 'AGPL-3.0-only', defaultLicenseUrl: 'https://x.example/l' } }
        const c = clientAnswering({ organizations: [OTHER, mine] })
        expect(await loadOrgDeclaredLicense(c, ORG)).toEqual({ supported: true, expression: 'AGPL-3.0-only', url: 'https://x.example/l' })
        expect(c.query).toHaveBeenCalledWith({ query: ORG_DECLARED_LICENSE_QUERY, variables: {}, fetchPolicy: 'no-cache' })
    })

    it('read the matched organization\'s null settings fields as empty', async () => {
        const c = clientAnswering({ organizations: [OTHER, { uuid: ORG, settings: { defaultLicenseExpression: null, defaultLicenseUrl: null } }] })
        expect(await loadOrgDeclaredLicense(c, ORG)).toEqual({ supported: true, expression: '', url: '' })
    })

    it.each([
        ['a list without the organization', { organizations: [OTHER] }],
        ['an empty list', { organizations: [] }],
        ['a null list', { organizations: null }],
        ['the unresolved single-organization root', { organization: null }],
    ])('refuse %s as a failed read, never an empty declaration', async (_name, data) => {
        await expect(loadOrgDeclaredLicense(clientAnswering(data), ORG)).rejects.toThrow(new Error(ORG_NOT_LISTED))
    })

    it('read the component fields, nulls as empty, without the cache', async () => {
        const c = clientAnswering({ component: { uuid: COMP, licenseExpression: 'LicenseRef-Acme', licenseUrl: 'https://x.example/l', supplierName: null } })
        expect(await loadComponentDeclaredLicense(c, COMP))
            .toEqual({ supported: true, expression: 'LicenseRef-Acme', url: 'https://x.example/l', supplier: '' })
        expect(c.query).toHaveBeenCalledWith({ query: COMPONENT_DECLARED_LICENSE_QUERY, variables: { componentUuid: COMP }, fetchPolicy: 'no-cache' })
    })

    it('read the release override and the effective licence, without the cache', async () => {
        const effective = { expression: 'MIT', url: null, level: 'COMPONENT' }
        const c = clientAnswering({ release: { uuid: REL, licenseExpression: null, effectiveLicense: effective } })
        expect(await loadReleaseDeclaredLicense(c, REL, ORG)).toEqual({ supported: true, override: '', effective })
        expect(c.query).toHaveBeenCalledWith({ query: RELEASE_DECLARED_LICENSE_QUERY, variables: { releaseID: REL, orgID: ORG }, fetchPolicy: 'no-cache' })
    })

    it('report a schema-drift refusal as unsupported', async () => {
        expect((await loadOrgDeclaredLicense(clientFailing(driftError()), ORG)).supported).toBe(false)
        expect((await loadComponentDeclaredLicense(clientFailing(driftError()), COMP)).supported).toBe(false)
        const r = await loadReleaseDeclaredLicense(clientFailing(driftError()), REL, ORG)
        expect(r).toEqual({ supported: false, override: '', effective: null })
    })

    it('rethrow any other error', async () => {
        const boom = new Error('502 Bad Gateway')
        await expect(loadOrgDeclaredLicense(clientFailing(boom), ORG)).rejects.toBe(boom)
        await expect(loadComponentDeclaredLicense(clientFailing(boom), COMP)).rejects.toBe(boom)
        await expect(loadReleaseDeclaredLicense(clientFailing(boom), REL, ORG)).rejects.toBe(boom)
    })
})

describe('the copy', () => {
    it('carries the supplier sentence of the schema comment word for word', () => {
        expect(SUPPLIER_SENTENCE).toBe(CARRIER)
        expect(SUPPLIER_HINT.endsWith(CARRIER)).toBe(true)
    })

    it('words the missing-organization read as design 3.7 does', () => {
        expect(ORG_NOT_LISTED).toBe('The server\'s list of your organizations does not include this one, so its default licence could not be read.')
    })

    it('never uses the struck remedies', () => {
        const strings = Object.values(copy).filter((v): v is string => typeof v === 'string')
        expect(strings.length).toBeGreaterThan(10)
        for (const s of strings) {
            expect(s).not.toContain('higher version')
            expect(s).not.toContain('uploaded again')
            expect(s).not.toContain('uploaded after the change carries')
        }
    })
})
