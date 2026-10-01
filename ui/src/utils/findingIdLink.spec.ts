import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
    cweUrlFor,
    findingTypeOf,
    findingTypeOfSearchedId,
    findingIdLink,
    followFindingIdLink,
    renderFindingId,
    osvUrlFor
} from './findingUtils'
import { FindingType } from '@/constants/findingType'

/**
 * The one finding-id link rule, which replaced four copies (the findings table,
 * the analysis page, releases-by-finding and the changelog each built their own
 * osv.dev / MITRE link with its own consent dialog).
 */

// A stand-in for Vue's h(): records what would be rendered.
const h = (tag: string, props: any, children: any) => ({ tag, props, children })

function clickEvent () {
    return { preventDefault: vi.fn() } as unknown as Event & { preventDefault: ReturnType<typeof vi.fn> }
}

describe('finding id links', () => {
    it('links every vulnerability id family to the details panel, with the osv.dev page as href', () => {
        for (const id of ['CVE-2023-45853', 'GHSA-67hx-6x53-jw92', 'PYSEC-2024-1', 'RUSTSEC-2023-0001',
            'DEBIAN-CVE-2023-45853', 'ALPINE-CVE-2023-1', 'GO-2024-2687']) {
            expect(findingIdLink(id, FindingType.VULNERABILITY)).toEqual({ action: 'details', href: osvUrlFor(id) })
        }
        // The id is a path segment, never raw.
        expect(osvUrlFor('A/B C')).toBe('https://osv.dev/vulnerability/A%2FB%20C')
    })

    it('links a weakness to MITRE and leaves violations, unknown kinds and blanks as text', () => {
        expect(findingIdLink('CWE-79', FindingType.WEAKNESS))
            .toEqual({ action: 'external', href: 'https://cwe.mitre.org/data/definitions/79.html' })
        expect(findingIdLink('CWE-unknown', FindingType.WEAKNESS)).toEqual({ action: 'none' })
        expect(findingIdLink('GPL-3.0-only', FindingType.VIOLATION)).toEqual({ action: 'none' })
        expect(findingIdLink('CVE-2023-45853', null)).toEqual({ action: 'none' })
        expect(findingIdLink('', FindingType.VULNERABILITY)).toEqual({ action: 'none' })
    })

    it('builds MITRE urls from the CWE number only', () => {
        expect(cweUrlFor('CWE-0079')).toBe('https://cwe.mitre.org/data/definitions/79.html')
        expect(cweUrlFor('CWE-NVD-noinfo')).toBeNull()
        expect(cweUrlFor('79')).toBeNull()
        expect(cweUrlFor('')).toBeNull()
    })

    it('normalizes the three finding-type spellings the UI receives', () => {
        expect(['Vulnerability', 'VULNERABILITY', 'VULN'].map(findingTypeOf))
            .toEqual([FindingType.VULNERABILITY, FindingType.VULNERABILITY, FindingType.VULNERABILITY])
        expect(['Weakness', 'WEAKNESS'].map(findingTypeOf)).toEqual([FindingType.WEAKNESS, FindingType.WEAKNESS])
        expect(['Violation', 'VIOLATION'].map(findingTypeOf)).toEqual([FindingType.VIOLATION, FindingType.VIOLATION])
        expect(findingTypeOf('SOMETHING_NEW')).toBeNull()
        expect(findingTypeOf(undefined)).toBeNull()
    })

    it('types a searched id: CWE ids in any case are weaknesses, blanks are nothing', () => {
        expect(findingTypeOfSearchedId('CVE-2021-44228')).toBe(FindingType.VULNERABILITY)
        expect(findingTypeOfSearchedId('  GHSA-67hx-6x53-jw92 ')).toBe(FindingType.VULNERABILITY)
        expect(findingTypeOfSearchedId('CWE-79')).toBe(FindingType.WEAKNESS)
        expect(findingTypeOfSearchedId('cwe-79')).toBe(FindingType.WEAKNESS)
        // A lower-case CWE has no MITRE number to link, so it stays plain text.
        expect(findingIdLink('cwe-79', findingTypeOfSearchedId('cwe-79'))).toEqual({ action: 'none' })
        // The home search input yields null once cleared.
        expect(findingTypeOfSearchedId(null)).toBeNull()
        expect(findingTypeOfSearchedId('   ')).toBeNull()
    })
})

describe('following a finding id link', () => {
    const opened: string[] = []

    beforeEach(() => {
        opened.length = 0
        // Consent already given, so openExternalLink opens straight away
        // instead of raising the confirmation dialog.
        vi.stubGlobal('localStorage', { getItem: () => String(Date.now() + 60_000), setItem: () => {} })
        vi.stubGlobal('window', { open: (href: string) => { opened.push(href) } })
    })

    afterEach(() => {
        vi.unstubAllGlobals()
    })

    it('opens the details panel, not the external page, when the host has one', () => {
        const onVulnClick = vi.fn()
        const e = clickEvent()
        followFindingIdLink(e, 'CVE-2023-45853', findingIdLink('CVE-2023-45853', FindingType.VULNERABILITY), onVulnClick)
        expect(e.preventDefault).toHaveBeenCalled()
        expect(onVulnClick).toHaveBeenCalledWith('CVE-2023-45853')
        expect(opened).toEqual([])
    })

    it('falls back to the osv.dev page when the host has no panel', async () => {
        followFindingIdLink(clickEvent(), 'PYSEC-2024-1', findingIdLink('PYSEC-2024-1', FindingType.VULNERABILITY))
        await Promise.resolve()
        expect(opened).toEqual([osvUrlFor('PYSEC-2024-1')])
    })

    it('opens a weakness on MITRE even when the host has a panel', async () => {
        const onVulnClick = vi.fn()
        followFindingIdLink(clickEvent(), 'CWE-79', findingIdLink('CWE-79', FindingType.WEAKNESS), onVulnClick)
        await Promise.resolve()
        expect(onVulnClick).not.toHaveBeenCalled()
        expect(opened).toEqual(['https://cwe.mitre.org/data/definitions/79.html'])
    })

    it('renders a clickable anchor for linked ids and plain text otherwise', () => {
        const onVulnClick = vi.fn()
        const vnode = renderFindingId(h, 'GHSA-67hx-6x53-jw92', FindingType.VULNERABILITY, onVulnClick)
        expect(vnode.tag).toBe('a')
        expect(vnode.props.href).toBe(osvUrlFor('GHSA-67hx-6x53-jw92'))
        expect(vnode.props.title).toBe('Show vulnerability details')
        vnode.props.onClick(clickEvent())
        expect(onVulnClick).toHaveBeenCalledWith('GHSA-67hx-6x53-jw92')

        expect(renderFindingId(h, 'CVE-2023-45853', FindingType.VULNERABILITY).props.title).toBeUndefined()
        expect(renderFindingId(h, 'GPL-3.0-only', FindingType.VIOLATION, onVulnClick)).toBe('GPL-3.0-only')
    })
})
