import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import {
    isProEdition,
    isChannelTypeAvailable,
    isIntegrationsSubTabAvailable,
    PRO_ONLY_CHANNEL_TYPES,
    INTEGRATIONS_SUBTABS,
} from './editionCapabilities'

describe('isProEdition', () => {
    it('treats only the literal OSS build as CE', () => {
        expect(isProEdition('OSS')).toBe(false)
        // The whole InstallationType enum, minus OSS.
        for (const t of ['SAAS', 'DEMO', 'MANAGED_SERVICE']) {
            expect(isProEdition(t)).toBe(true)
        }
    })

    it('treats an unloaded installationType as Pro (permissive default)', () => {
        // Documented as safe only because every caller mounts behind a loaded
        // user. If that ever stops being true this is the line to revisit.
        expect(isProEdition(undefined)).toBe(true)
        expect(isProEdition(null)).toBe(true)
    })
})

describe('isChannelTypeAvailable', () => {
    it('allows Slack / Teams / Webhook on CE -- the backend does', () => {
        // MS_TEAMS is the backend type name; the catalog card id is MSTEAMS.
        for (const t of ['SLACK', 'MS_TEAMS', 'WEBHOOK']) {
            expect(isChannelTypeAvailable(t, 'OSS')).toBe(true)
        }
    })

    it('blocks EMAIL and SENTINEL on CE -- the backend rejects them on save', () => {
        expect(isChannelTypeAvailable('EMAIL', 'OSS')).toBe(false)
        expect(isChannelTypeAvailable('SENTINEL', 'OSS')).toBe(false)
    })

    it('allows everything on a licensed edition', () => {
        for (const t of ['SLACK', 'MS_TEAMS', 'WEBHOOK', 'EMAIL', 'SENTINEL']) {
            expect(isChannelTypeAvailable(t, 'SAAS')).toBe(true)
        }
    })

    it('does not block an unknown type on CE (backend decides, UI does not invent gates)', () => {
        expect(isChannelTypeAvailable('SOMETHING_NEW', 'OSS')).toBe(true)
    })
})

// The point of PRO_ONLY_CHANNEL_TYPES is to agree with the backend, so assert
// against the BACKEND SOURCE rather than a literal in this file -- a literal
// would only ever detect someone editing the constant, never the drift the
// constant exists to prevent. Same in-repo-path + existsSync convention as
// notificationInboxSchemaDrift.spec.ts.
const GATE_SRC_PATH = fileURLToPath(new URL(
    '../../../backend/src/main/java/io/reliza/service/NotificationChannelService.java',
    import.meta.url))

describe('PRO_ONLY_CHANNEL_TYPES vs the CE backend gate', () => {
    it('has the backend source available', () => {
        expect(existsSync(GATE_SRC_PATH)).toBe(true)
    })

    it('lists exactly the types validateSeed rejects on the OSS edition', () => {
        if (!existsSync(GATE_SRC_PATH)) return
        const src = readFileSync(GATE_SRC_PATH, 'utf8')
        // Scope to validateSeed FIRST. isOssEdition() is used freely across this
        // codebase, so an unanchored search would silently repoint at whichever
        // gate happens to appear first if another is ever added above this one.
        const from = src.indexOf('validateSeed')
        expect(from, 'validateSeed not found -- method renamed?').toBeGreaterThan(-1)
        const body = src.slice(from)
        // The gate reads:
        //   if (LicensingConstants.isOssEdition()
        //           && (seed.getType() == IntegrationType.EMAIL
        //               || seed.getType() == IntegrationType.SENTINEL)) {
        const gate = body.match(/isOssEdition\(\)([\s\S]{0,400}?)\)\)\s*\{/)
        expect(gate, 'could not locate the isOssEdition gate in validateSeed').not.toBeNull()
        const gated = [...(gate![1].matchAll(/IntegrationType\.(\w+)/g))].map(m => m[1]).sort()
        expect(gated).toEqual([...PRO_ONLY_CHANNEL_TYPES].sort())
    })
})

describe('isIntegrationsSubTabAvailable', () => {
    it('offers catalog, subscriptions and channel groups on every edition', () => {
        for (const t of ['catalog', 'subscriptions', 'channel-groups']) {
            expect(isIntegrationsSubTabAvailable(t, 'OSS')).toBe(true)
            expect(isIntegrationsSubTabAvailable(t, 'SAAS')).toBe(true)
        }
    })

    it('keeps the CI sub-tabs on Pro only', () => {
        for (const t of ['webhooks', 'pr-validation']) {
            expect(isIntegrationsSubTabAvailable(t, 'OSS')).toBe(false)
            expect(isIntegrationsSubTabAvailable(t, 'SAAS')).toBe(true)
        }
    })

    it('rejects an unknown tab on every edition -- it would render a blank body', () => {
        for (const edition of ['OSS', 'SAAS']) {
            expect(isIntegrationsSubTabAvailable('bogus', edition)).toBe(false)
            expect(isIntegrationsSubTabAvailable('', edition)).toBe(false)
            expect(isIntegrationsSubTabAvailable(undefined, edition)).toBe(false)
        }
    })

    it('rejects the ARRAY vue-router yields for a repeated query param', () => {
        // ?integrationsTab=catalog&integrationsTab=webhooks
        expect(isIntegrationsSubTabAvailable(['catalog', 'webhooks'], 'SAAS')).toBe(false)
        expect(isIntegrationsSubTabAvailable(['catalog'], 'SAAS')).toBe(false)
    })

    it('every declared sub-tab is reachable on Pro', () => {
        // Guards a tab being added to the type but forgotten in the list.
        for (const t of INTEGRATIONS_SUBTABS) {
            expect(isIntegrationsSubTabAvailable(t, 'SAAS')).toBe(true)
        }
    })
})
