import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { withoutComments } from '@/utils/specText'

/**
 * FDAW-17c (FDAW-73 row 17): wiring guard for the typed assessment fields of the triage
 * dialogs and for the shared selection and permission check of the two finding analysis views.
 * Scope is imports and bindings only: the behaviour runs in assessmentFields.spec.ts,
 * assessmentInput.schema.spec.ts and vulnAnalysisQueries.spec.ts. What only a scan can check:
 * the helpers are imported and used (no vue-tsc, so a missing import is a runtime
 * ReferenceError), the fields are gated on the edition, and the inline permission check is gone.
 */
function source (file: string): string {
    return withoutComments(readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8'))
}

const importBlockOf = (code: string) => (code.match(/^import\s[^;]*?\sfrom\s+'[^']+'/gms) || []).join('\n')
const withoutImports = (code: string) => code.replace(/^import\s[^;]*?\sfrom\s+'[^']+'/gms, '')

function expectImportedAndUsed (code: string, name: string) {
    expect(importBlockOf(code), `${name} imported`).toMatch(new RegExp(`\\b${name}\\b`))
    expect(withoutImports(code), `${name} used`).toMatch(new RegExp(`\\b${name}\\b`))
}

describe.each(['./CreateVulnAnalysisModal.vue', './UpdateVulnAnalysisModal.vue'])('%s assessment fields', (file) => {
    const code = source(file)

    it.each([
        'assessmentInputOf', 'visibleAssessmentFields', 'validateControls', 'shownAssessmentForm',
        'assessmentFieldsAvailable', 'DISCOVERY_METHOD_OPTIONS', 'RISK_LEVEL_OPTIONS', 'RISK_CONTROL_TYPE_OPTIONS', 'MAX_CONTROLS'
    ])('imports %s, which it uses', (name) => {
        expectImportedAndUsed(code, name)
    })

    it('binds each option list to its select', () => {
        expect(code).toMatch(/v-model:value="assessmentForm\.discoveryMethod"\s+:options="DISCOVERY_METHOD_OPTIONS"/)
        expect(code).toMatch(/v-model:value="assessmentForm\.riskLevel"\s+:options="RISK_LEVEL_OPTIONS"/)
        expect(code).toMatch(/v-model:value="control\.type"\s+:options="RISK_CONTROL_TYPE_OPTIONS"/)
    })

    it('gates the three controls on the edition not being OSS, then each on the state', () => {
        expect(code).toMatch(/const assessmentShown = computed\(\(\) => assessmentFieldsAvailable\(store\.getters\.myuser\?\.installationType\)\)/)
        const block = code.match(/<template v-if="assessmentShown">([\s\S]*?)\n {12}<\/template>/)?.[1] || ''
        expect(block).toContain('v-if="visibleAssessment.discoveryMethod"')
        expect(block).toContain('v-if="visibleAssessment.riskLevel"')
        expect(block).toContain('v-if="visibleAssessment.controls"')
        expect(code).toMatch(/const visibleAssessment = computed\(\(\) => visibleAssessmentFields\(formData\.value\.state\)\)/)
    })

    it('labels the fields as the documentation names them', () => {
        expect(code).toContain('label="Discovery method"')
        expect(code).toContain('label="Risk level"')
        expect(code).toContain('label="Risk controls"')
    })

    it('disables adding a control at the maximum', () => {
        expect(code).toMatch(/:disabled="assessmentForm\.controls\.length >= MAX_CONTROLS"/)
    })

    it('merges only the changed assessment keys into the input, and stops on refused controls', () => {
        expect(code).toMatch(/return assessmentInputOf\(initialAssessment\.value, shown\)/)
        expect(code).toMatch(/const assessment = assessmentInput\(\)\s+if \(!assessment\) return\s+Object\.assign\(input, assessment\)/)
        expect(code).toMatch(/if \(!assessmentShown\.value\) return \{\}/)
    })
})

describe('UpdateVulnAnalysisModal fills the fields from the record', () => {
    const code = source('./UpdateVulnAnalysisModal.vue')

    it('initial and form both come from the record', () => {
        expectImportedAndUsed(code, 'assessmentFormOf')
        expect(code).toMatch(/initialAssessment\.value = assessmentFormOf\(newRecord\)/)
        expect(code).toMatch(/assessmentForm\.value = assessmentFormOf\(newRecord\)/)
    })
})

describe('CreateVulnAnalysisModal starts each open with empty fields', () => {
    const code = source('./CreateVulnAnalysisModal.vue')

    it('resets initial and form on open', () => {
        const onOpen = code.match(/watch\(\(\) => props\.show, \(nowShown\) => \{[\s\S]*?\n\}\)/)?.[0] || ''
        expect(onOpen).toMatch(/initialAssessment\.value = emptyAssessmentForm\(\)/)
        expect(onOpen).toMatch(/assessmentForm\.value = emptyAssessmentForm\(\)/)
    })
})

describe.each(['./VulnerabilityAnalysis.vue', './ViewVulnAnalysisModal.vue'])('%s reads and permission', (file) => {
    const code = source(file)

    it.each(['vulnAnalysisFieldsFragment', 'canWriteFindingAnalysis', 'assessmentFieldsAvailable'])('imports %s, which it uses', (name) => {
        expectImportedAndUsed(code, name)
    })

    it('selects the Pro fields only on an edition that has them', () => {
        expect(code).toMatch(/vulnAnalysisFieldsFragment\(assessmentFieldsAvailable\(myuser\.value\?\.installationType\)\)/)
    })

    it('no longer holds the inline FINDING_ANALYSIS_WRITE test', () => {
        expect(code).not.toContain("functions.includes('FINDING_ANALYSIS_WRITE')")
        expect(code).not.toContain('FINDING_ANALYSIS_WRITE')
    })
})

describe('ViewVulnAnalysisModal history tooltip', () => {
    it('lists the typed fields of each history entry', () => {
        const code = source('./ViewVulnAnalysisModal.vue')
        expectImportedAndUsed(code, 'assessmentSummaryLines')
        expect(code).toMatch(/tooltipLines\.push\(\.\.\.assessmentSummaryLines\(history\)\)/)
    })
})
