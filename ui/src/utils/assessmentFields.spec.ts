import { describe, it, expect } from 'vitest'
import {
    DISCOVERY_METHOD_OPTIONS,
    MAX_CONTROLS,
    MAX_RESIDUAL_RISK_RATIONALE_LENGTH,
    MAX_RISK_FILE_REFERENCE_LENGTH,
    RESIDUAL_RISK_OPTIONS,
    RISK_CONTROL_TYPE_OPTIONS,
    RISK_LEVEL_OPTIONS
} from '@/constants/vulnAnalysis'
import {
    assessmentFieldsAvailable,
    assessmentFormOf,
    assessmentInputOf,
    assessmentSummaryLines,
    canWriteFindingAnalysis,
    cleanControls,
    emptyAssessmentForm,
    RESIDUAL_RISK_MISSING_PREFIX,
    residualRiskMissing,
    shownAssessmentForm,
    validateControls,
    visibleAssessmentFields,
    type AssessmentForm
} from './assessmentFields'

/** FDAW-17c (FDAW-73 row 15): the triage editor's typed assessment fields, pure. */

const ORG = 'org-1'
const form = (over: Partial<AssessmentForm> = {}): AssessmentForm => ({ ...emptyAssessmentForm(), ...over })
const row = (type: string | null, description: string) => ({ type, description })

describe('option lists', () => {
    it('discovery methods: values and labels in enum order', () => {
        expect(DISCOVERY_METHOD_OPTIONS).toEqual([
            { label: 'SBOM analysis', value: 'SBOM_ANALYSIS' },
            { label: 'Security testing', value: 'SECURITY_TESTING' },
            { label: 'Supplier notification', value: 'SUPPLIER_NOTIFICATION' },
            { label: 'External report', value: 'EXTERNAL_REPORT' },
            { label: 'Internal review', value: 'INTERNAL_REVIEW' }
        ])
    })

    it('risk levels: values and labels in enum order', () => {
        expect(RISK_LEVEL_OPTIONS).toEqual([
            { label: 'High', value: 'HIGH' },
            { label: 'Medium', value: 'MEDIUM' },
            { label: 'Low', value: 'LOW' },
            { label: 'Negligible', value: 'NEGLIGIBLE' }
        ])
    })

    it('risk control types: values and labels in enum order', () => {
        expect(RISK_CONTROL_TYPE_OPTIONS).toEqual([
            { label: 'Design', value: 'DESIGN' },
            { label: 'Compensating', value: 'COMPENSATING' },
            { label: 'Labeling', value: 'LABELING' },
            { label: 'Planned update', value: 'PLANNED_UPDATE' }
        ])
        expect(MAX_CONTROLS).toBe(10)
    })
})

describe('visibleAssessmentFields', () => {
    const none = { discoveryMethod: false, riskLevel: false, controls: false, residualRisk: false }

    it.each(['', 'IN_TRIAGE'])('%j shows none', (state) => {
        expect(visibleAssessmentFields(state)).toEqual(none)
    })

    it('null shows none', () => {
        expect(visibleAssessmentFields(null)).toEqual(none)
    })

    it('EXPLOITABLE shows all three and the residual risk conclusion (FDAW-162)', () => {
        expect(visibleAssessmentFields('EXPLOITABLE'))
            .toEqual({ discoveryMethod: true, riskLevel: true, controls: true, residualRisk: true })
    })

    it('RESOLVED shows all three, not the residual risk conclusion', () => {
        expect(visibleAssessmentFields('RESOLVED'))
            .toEqual({ discoveryMethod: true, riskLevel: true, controls: true, residualRisk: false })
    })

    it.each(['NOT_AFFECTED', 'FALSE_POSITIVE'])('%s shows the discovery method only', (state) => {
        expect(visibleAssessmentFields(state)).toEqual({ ...none, discoveryMethod: true })
    })
})

describe('assessmentInputOf', () => {
    const stored = form({
        discoveryMethod: 'SBOM_ANALYSIS',
        riskLevel: 'MEDIUM',
        controls: [row('DESIGN', 'isolated'), row('LABELING', 'warned')]
    })
    const copy = (f: AssessmentForm): AssessmentForm => ({ ...f, controls: f.controls.map(c => ({ ...c })) })

    it('nothing changed sends nothing', () => {
        expect(Object.keys(assessmentInputOf(stored, copy(stored)))).toHaveLength(0)
    })

    it('a risk level changed sends exactly that', () => {
        expect(assessmentInputOf(stored, { ...copy(stored), riskLevel: 'HIGH' })).toEqual({ riskLevel: 'HIGH' })
    })

    it('a discovery method changed sends exactly that', () => {
        expect(assessmentInputOf(stored, { ...copy(stored), discoveryMethod: 'SECURITY_TESTING' }))
            .toEqual({ discoveryMethod: 'SECURITY_TESTING' })
    })

    it('a cleared select sends nothing: null keeps the stored value on the server', () => {
        expect(assessmentInputOf(stored, { ...copy(stored), discoveryMethod: null, riskLevel: null })).toEqual({})
    })

    it('one control removed to none sends exactly an empty list', () => {
        const one = form({ controls: [row('DESIGN', 'isolated')] })
        expect(assessmentInputOf(one, form())).toEqual({ controls: [] })
    })

    it('the same controls in new array objects send no controls key', () => {
        const input = assessmentInputOf(stored, copy(stored))
        expect('controls' in input).toBe(false)
    })

    it('two controls swapped send the list in the new order', () => {
        const swapped = { ...copy(stored), controls: [row('LABELING', 'warned'), row('DESIGN', 'isolated')] }
        expect(assessmentInputOf(stored, swapped)).toEqual({
            controls: [{ type: 'LABELING', description: 'warned' }, { type: 'DESIGN', description: 'isolated' }]
        })
    })

    it('a row with a type and a blank description is kept for validation; a fully empty row is dropped', () => {
        const edited = form({ controls: [row('DESIGN', '   '), row(null, '  ')] })
        expect(assessmentInputOf(form(), edited)).toEqual({ controls: [{ type: 'DESIGN', description: '' }] })
    })

    it('descriptions are trimmed', () => {
        expect(assessmentInputOf(form(), form({ controls: [row('DESIGN', '  x ')] })))
            .toEqual({ controls: [{ type: 'DESIGN', description: 'x' }] })
    })

    it('a trimmed description equal to the stored one is no change', () => {
        expect(assessmentInputOf(stored, { ...copy(stored), controls: [row('DESIGN', ' isolated '), row('LABELING', 'warned')] }))
            .toEqual({})
    })

    it('a create with a discovery method only sends exactly that', () => {
        expect(assessmentInputOf(emptyAssessmentForm(), form({ discoveryMethod: 'SBOM_ANALYSIS' })))
            .toEqual({ discoveryMethod: 'SBOM_ANALYSIS' })
    })

    it('a create with nothing set sends nothing', () => {
        expect(assessmentInputOf(emptyAssessmentForm(), emptyAssessmentForm())).toEqual({})
    })
})

describe('shownAssessmentForm', () => {
    const initial = form({ discoveryMethod: 'SBOM_ANALYSIS', riskLevel: 'LOW', controls: [row('DESIGN', 'a')] })
    const edited = form({ discoveryMethod: 'EXTERNAL_REPORT', riskLevel: 'HIGH', controls: [] })

    it('a state that hides risk level and controls keeps their initial values, so they are not sent', () => {
        const shown = shownAssessmentForm(initial, edited, 'NOT_AFFECTED')
        expect(assessmentInputOf(initial, shown)).toEqual({ discoveryMethod: 'EXTERNAL_REPORT' })
    })

    it('in triage sends none of the three', () => {
        expect(assessmentInputOf(initial, shownAssessmentForm(initial, edited, 'IN_TRIAGE'))).toEqual({})
    })

    it('exploitable sends all three changes', () => {
        expect(assessmentInputOf(initial, shownAssessmentForm(initial, edited, 'EXPLOITABLE')))
            .toEqual({ discoveryMethod: 'EXTERNAL_REPORT', riskLevel: 'HIGH', controls: [] })
    })
})

describe('the residual risk conclusion (FDAW-162 T-12)', () => {
    const stored = form({
        residualRiskAcceptability: 'ACCEPTABLE',
        residualRiskRationale: 'Port closed in the field.',
        riskFileReference: 'SRMR-014 section 4.2'
    })
    const MISSING = 'Needed before a regulatory package can be confirmed or finalized: '

    it('the residual risk options: values and labels', () => {
        expect(RESIDUAL_RISK_OPTIONS).toEqual([
            { label: 'Acceptable', value: 'ACCEPTABLE' },
            { label: 'Not acceptable', value: 'NOT_ACCEPTABLE' }
        ])
        expect(MAX_RESIDUAL_RISK_RATIONALE_LENGTH).toBe(2048)
        expect(MAX_RISK_FILE_REFERENCE_LENGTH).toBe(512)
    })

    it('the acceptability is sent only when changed', () => {
        expect(assessmentInputOf(stored, { ...stored })).toEqual({})
        expect(assessmentInputOf(stored, { ...stored, residualRiskAcceptability: 'NOT_ACCEPTABLE' }))
            .toEqual({ residualRiskAcceptability: 'NOT_ACCEPTABLE' })
        expect(assessmentInputOf(stored, { ...stored, residualRiskAcceptability: null })).toEqual({})
    })

    it('a changed rationale or reference is sent trimmed; whitespace around the stored text is no change', () => {
        expect(assessmentInputOf(stored, { ...stored, residualRiskRationale: '  Fixed in 2.5.  ' }))
            .toEqual({ residualRiskRationale: 'Fixed in 2.5.' })
        expect(assessmentInputOf(stored, { ...stored, riskFileReference: ' SRMR-015 ' }))
            .toEqual({ riskFileReference: 'SRMR-015' })
        expect(assessmentInputOf(stored, { ...stored, riskFileReference: ' SRMR-014 section 4.2 ' })).toEqual({})
    })

    it('an erased stored text is sent as an empty string, which clears it', () => {
        expect(assessmentInputOf(stored, { ...stored, residualRiskRationale: '   ', riskFileReference: '' }))
            .toEqual({ residualRiskRationale: '', riskFileReference: '' })
    })

    it('a create sends what was typed and nothing for the empty texts', () => {
        expect(assessmentInputOf(emptyAssessmentForm(), form({ residualRiskAcceptability: 'ACCEPTABLE',
            residualRiskRationale: 'why' }))).toEqual({ residualRiskAcceptability: 'ACCEPTABLE', residualRiskRationale: 'why' })
    })

    it('a NOT_AFFECTED state keeps the three initial values, so nothing is sent', () => {
        const edited = form({ residualRiskAcceptability: 'NOT_ACCEPTABLE', residualRiskRationale: '', riskFileReference: 'x' })
        const shown = shownAssessmentForm(stored, edited, 'NOT_AFFECTED')
        expect(shown.residualRiskAcceptability).toBe('ACCEPTABLE')
        expect(shown.residualRiskRationale).toBe('Port closed in the field.')
        expect(shown.riskFileReference).toBe('SRMR-014 section 4.2')
        expect(assessmentInputOf(stored, shown)).toEqual({})
        expect(assessmentInputOf(stored, shownAssessmentForm(stored, edited, 'EXPLOITABLE')))
            .toEqual({ residualRiskAcceptability: 'NOT_ACCEPTABLE', residualRiskRationale: '', riskFileReference: 'x' })
    })

    it('residualRiskMissing names what the gate would call missing, for an exploitable finding only', () => {
        expect(residualRiskMissing('NOT_AFFECTED', emptyAssessmentForm())).toBeNull()
        expect(residualRiskMissing(null, emptyAssessmentForm())).toBeNull()
        expect(residualRiskMissing('EXPLOITABLE', stored)).toBeNull()
        expect(residualRiskMissing('EXPLOITABLE', emptyAssessmentForm())).toBe(
            'Needed before a regulatory package can be confirmed or finalized: residual risk acceptability, residual risk rationale')
        expect(residualRiskMissing('EXPLOITABLE', { ...stored, residualRiskAcceptability: null }))
            .toBe(MISSING + 'residual risk acceptability')
        expect(residualRiskMissing('EXPLOITABLE', { ...stored, residualRiskRationale: '  ' }))
            .toBe(MISSING + 'residual risk rationale')
        expect(RESIDUAL_RISK_MISSING_PREFIX).toBe(MISSING)
    })

    it('a record fills the three; a history entry names them by their labels', () => {
        expect(assessmentFormOf({ residualRiskAcceptability: 'NOT_ACCEPTABLE', residualRiskRationale: 'r', riskFileReference: 'f' }))
            .toEqual(form({ residualRiskAcceptability: 'NOT_ACCEPTABLE', residualRiskRationale: 'r', riskFileReference: 'f' }))
        expect(assessmentSummaryLines({
            controls: [{ type: 'PLANNED_UPDATE', description: 'next release' }],
            residualRiskAcceptability: 'NOT_ACCEPTABLE',
            residualRiskRationale: 'Fixed in 2.5.',
            riskFileReference: 'SRMR-014'
        })).toEqual([
            'Risk control: Planned update: next release',
            'Residual risk: Not acceptable',
            'Residual risk rationale: Fixed in 2.5.',
            'Risk file reference: SRMR-014'
        ])
    })
})

describe('cleanControls', () => {
    it('trims and drops only rows with neither a type nor a description', () => {
        expect(cleanControls([row(null, ''), row(null, ' d '), row('DESIGN', '')]))
            .toEqual([row(null, 'd'), row('DESIGN', '')])
        expect(cleanControls(null)).toEqual([])
    })
})

describe('validateControls', () => {
    const valid = (n: number) => Array.from({ length: n }, (_, i) => row('DESIGN', `control ${i}`))

    it('11 rows: the count text', () => {
        expect(validateControls(valid(11))).toBe('controls: at most 10 controls per assessment')
    })

    it('a row without a type', () => {
        expect(validateControls([row(null, 'described')])).toBe('control type is required')
    })

    it('a row with a type and a blank description', () => {
        expect(validateControls([row('DESIGN', '  ')])).toBe('control description is required')
    })

    it('10 valid rows: null', () => {
        expect(validateControls(valid(10))).toBeNull()
    })

    it('fully empty rows do not count', () => {
        expect(validateControls([...valid(10), row(null, ' ')])).toBeNull()
    })
})

describe('canWriteFindingAnalysis', () => {
    const orgRow = (over: Record<string, unknown>) => ({ scope: 'ORGANIZATION', org: ORG, object: ORG, ...over })

    it('an ORGANIZATION row of the org with type ADMIN', () => {
        expect(canWriteFindingAnalysis([orgRow({ type: 'ADMIN', functions: [] })], ORG)).toBe(true)
    })

    it('READ_ONLY with the FINDING_ANALYSIS_WRITE function', () => {
        expect(canWriteFindingAnalysis([orgRow({ type: 'READ_ONLY', functions: ['FINDING_ANALYSIS_WRITE'] })], ORG)).toBe(true)
    })

    it('READ_WRITE without the function', () => {
        expect(canWriteFindingAnalysis([orgRow({ type: 'READ_WRITE', functions: ['RESOURCE'] })], ORG)).toBe(false)
    })

    it('the function on a row of another org', () => {
        const other = { scope: 'ORGANIZATION', org: 'org-2', object: 'org-2', type: 'READ_ONLY', functions: ['FINDING_ANALYSIS_WRITE'] }
        expect(canWriteFindingAnalysis([other], ORG)).toBe(false)
    })

    it('the function on a COMPONENT row', () => {
        const component = { scope: 'COMPONENT', org: ORG, object: 'comp-1', type: 'ADMIN', functions: ['FINDING_ANALYSIS_WRITE'] }
        expect(canWriteFindingAnalysis([component], ORG)).toBe(false)
    })

    it('null permissions', () => {
        expect(canWriteFindingAnalysis(null, ORG)).toBe(false)
    })
})

describe('edition, record fill and history lines', () => {
    it('the fields are offered on Pro only; an unknown edition is treated as CE', () => {
        expect(assessmentFieldsAvailable('SAAS')).toBe(true)
        expect(assessmentFieldsAvailable('OSS')).toBe(false)
        expect(assessmentFieldsAvailable(undefined)).toBe(false)
        expect(assessmentFieldsAvailable('')).toBe(false)
    })

    it('a record fills the form; a record without the fields reads as empty', () => {
        expect(assessmentFormOf({ discoveryMethod: 'SBOM_ANALYSIS', riskLevel: 'LOW', controls: [{ type: 'DESIGN', description: 'd' }] }))
            .toEqual(form({ discoveryMethod: 'SBOM_ANALYSIS', riskLevel: 'LOW', controls: [row('DESIGN', 'd')] }))
        expect(assessmentFormOf({ analysisState: 'NOT_AFFECTED' })).toEqual(emptyAssessmentForm())
    })

    it('a history entry names the fields by their labels', () => {
        expect(assessmentSummaryLines({
            discoveryMethod: 'SUPPLIER_NOTIFICATION',
            riskLevel: 'NEGLIGIBLE',
            controls: [{ type: 'PLANNED_UPDATE', description: 'next release' }]
        })).toEqual([
            'Discovery method: Supplier notification',
            'Risk level: Negligible',
            'Risk control: Planned update: next release'
        ])
        expect(assessmentSummaryLines({ state: 'IN_TRIAGE', controls: [] })).toEqual([])
    })
})
