import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { buildSchema, validate, type GraphQLSchema } from 'graphql'
import { ADDENDUM_RELEASE_QUERY_CORE, ADDENDUM_RELEASE_QUERY_FULL } from './addendumData'
import { COMPONENT_DEVICE_WINDOW_QUERY } from './componentDeviceWindow'

/**
 * Every document that gained a device-window selection, validated against BOTH schemas (D7).
 *
 * CE declares `Component.medicalProfile` but NOT `deviceSupportWindow` inside it. That makes
 * the subfield a WHOLE-DOCUMENT failure on CE rather than a null field: graphql rejects the
 * query at validation and the caller renders nothing. It is the #339 defect exactly -- a UI
 * build meeting a backend one field behind and blanking.
 *
 * So this asserts the split is real in both directions: CORE must validate against CE, FULL
 * must NOT (if it did, the split would be unnecessary and someone would rightly delete it),
 * and both must validate against Pro.
 */
const CE_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../backend/src/main/resources/schema/schema.graphqls', import.meta.url))
const PRO_SCHEMA_PATH = fileURLToPath(new URL(
    '../../../../rearm-core/backend/src/main/resources/schema/schema.graphqls', import.meta.url))

const ceSchema = buildSchema(readFileSync(CE_SCHEMA_PATH, 'utf8'))
const proSchema: GraphQLSchema | null = existsSync(PRO_SCHEMA_PATH)
    ? buildSchema(readFileSync(PRO_SCHEMA_PATH, 'utf8'))
    : null

const errorsAgainst = (schema: GraphQLSchema, doc: any) => validate(schema, doc).map(e => e.message)

describe('the addendum release query', () => {
    /**
     * CORE's ONLY CE failure is the pre-existing `Release.fdaAssessmentNarrative` gap, which
     * predates D7 and is already on the CE sync list (board t20260904-041253-3993). What this
     * asserts is the property the split actually guarantees: CORE does not fail on the DEVICE
     * WINDOW. Asserting `toEqual([])` here would be asserting something false about this repo
     * today, and would go green for the wrong reason the moment CE syncs.
     */
    it('CORE does not fail on the device window against the CE mirror', () => {
        const errs = errorsAgainst(ceSchema, ADDENDUM_RELEASE_QUERY_CORE)
        expect(errs.join(' ')).not.toMatch(/deviceSupportWindow/)
        expect(errs.filter(e => !e.includes('fdaAssessmentNarrative')),
            'CORE gained a CE-invalid field other than the known fdaAssessmentNarrative gap')
            .toEqual([])
    })

    /**
     * The load-bearing half. If FULL ever validates on CE the split has become pointless and
     * should be removed -- but far more likely is that someone moved the subfield into CORE,
     * which this catches from the other side.
     */
    it('FULL does NOT validate against the CE mirror', () => {
        const errs = errorsAgainst(ceSchema, ADDENDUM_RELEASE_QUERY_FULL)
        expect(errs.length, 'FULL validated on CE -- either CE gained the field (delete the'
            + ' split) or the split stopped covering it').toBeGreaterThan(0)
        expect(errs.join(' ')).toMatch(/deviceSupportWindow/)
    })

    it('both validate against the Pro schema', () => {
        if (!proSchema) return
        expect(errorsAgainst(proSchema, ADDENDUM_RELEASE_QUERY_CORE)).toEqual([])
        expect(errorsAgainst(proSchema, ADDENDUM_RELEASE_QUERY_FULL)).toEqual([])
    })
})

describe('the component device-window query (the panel and the release read-only view)', () => {
    /**
     * One document serves both surfaces: the editable panel on the component page and the
     * read-only inherited line on the release page. It is FULL-only by nature -- there is no
     * CORE shape of "read the window", because the whole document exists to read it. The panel
     * and the line handle a CE backend by HIDING, via loadComponentDeviceWindow's drift branch,
     * not by falling back to a narrower query.
     */
    it('does NOT validate against the CE mirror', () => {
        const errs = errorsAgainst(ceSchema, COMPONENT_DEVICE_WINDOW_QUERY)
        expect(errs.length, 'CE gained deviceSupportWindow -- the panel can stop hiding')
            .toBeGreaterThan(0)
        expect(errs.join(' ')).toMatch(/deviceSupportWindow/)
    })

    it('validates against the Pro schema', () => {
        if (!proSchema) return
        expect(errorsAgainst(proSchema, COMPONENT_DEVICE_WINDOW_QUERY)).toEqual([])
    })
})

describe('what CE actually declares', () => {
    /**
     * Pinned as a FACT rather than inferred from a validation failure. If CE gains the field,
     * this is the test that says so in one line, and the three "does not validate" assertions
     * above stop being mysterious.
     */
    it('has medicalProfile but not deviceSupportWindow inside it', () => {
        const ce = readFileSync(CE_SCHEMA_PATH, 'utf8')
        const medicalProfile = ce.slice(ce.indexOf('type MedicalProfile {'))
        expect(medicalProfile.slice(0, medicalProfile.indexOf('}'))).not.toMatch(/deviceSupportWindow/)
    })
})
