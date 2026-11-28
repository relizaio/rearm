import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { withoutComments } from '@/utils/specText'
import { FDA_SUMMARY_ONLY, FIELD_HELP } from '@/constants/vulnAnalysisFieldHelp'

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

describe.each(['./CreateVulnAnalysisModal.vue', './UpdateVulnAnalysisModal.vue'])('%s residual risk conclusion (FDAW-162 T-15)', (file) => {
    const code = source(file)
    const assessmentBlock = code.match(/<template v-if="assessmentShown">([\s\S]*?)\n {12}<\/template>/)?.[1] || ''
    const residualBlock = assessmentBlock.match(/<template v-if="visibleAssessment\.residualRisk">([\s\S]*?)\n {16}<\/template>/)?.[1] || ''

    it.each(['residualRiskMissing', 'RESIDUAL_RISK_OPTIONS', 'MAX_RESIDUAL_RISK_RATIONALE_LENGTH',
        'MAX_RISK_FILE_REFERENCE_LENGTH'])('imports %s, which it uses', (name) => {
        expectImportedAndUsed(code, name)
    })

    it('the three items sit under visibleAssessment.residualRisk inside assessmentShown, after the risk controls', () => {
        expect(residualBlock).toContain('path="residualRiskAcceptability"')
        expect(residualBlock).toContain('path="residualRiskRationale"')
        expect(residualBlock).toContain('path="riskFileReference"')
        expect(assessmentBlock.indexOf('path="controls"')).toBeLessThan(assessmentBlock.indexOf('visibleAssessment.residualRisk'))
    })

    it('binds the options, the two maximum lengths and the three tips', () => {
        expect(residualBlock).toMatch(/v-model:value="assessmentForm\.residualRiskAcceptability"\s+:options="RESIDUAL_RISK_OPTIONS"/)
        expect(residualBlock).toMatch(/v-model:value="assessmentForm\.residualRiskRationale"\s+type="textarea"[\s\S]*?:maxlength="MAX_RESIDUAL_RISK_RATIONALE_LENGTH"/)
        expect(residualBlock).toMatch(/v-model:value="assessmentForm\.riskFileReference"[\s\S]*?:maxlength="MAX_RISK_FILE_REFERENCE_LENGTH"/)
        expect(residualBlock).toContain(':tip="FIELD_HELP.residualRisk"')
        expect(residualBlock).toContain(':tip="FIELD_HELP.residualRiskRationale"')
        expect(residualBlock).toContain(':tip="FIELD_HELP.riskFileReference"')
        expect(residualBlock).toContain('label="Residual risk"')
        expect(residualBlock).toContain('label="Residual risk rationale"')
        expect(residualBlock).toContain('label="Risk file reference (optional)"')
    })

    it('the Residual risk item shows the warning as its feedback with status warning', () => {
        expect(code).toMatch(/const residualRiskWarning = computed\(\(\) => residualRiskMissing\(formData\.value\.state, assessmentForm\.value\)\)/)
        expect(residualBlock).toMatch(/path="residualRiskAcceptability"\s+:validation-status="residualRiskWarning \? 'warning' : undefined"\s+:feedback="residualRiskWarning \|\| undefined"/)
    })

    it('the Save handler does not read the warning: it never blocks', () => {
        const submit = code.match(/const handleSubmit = async \(\) => \{[\s\S]*?\n\}/)?.[0] || ''
        expect(submit.length).toBeGreaterThan(0)
        expect(submit).not.toContain('residualRiskWarning')
        expect(submit).not.toContain('residualRiskMissing')
    })
})

describe('the residual risk help texts say where the fields go (FDAW-162 F-3)', () => {
    it('the shared sentence, and the three entries that end with it', () => {
        expect(FDA_SUMMARY_ONLY).toBe('Printed in the vulnerability assessment summary of a regulatory package (fda/); not included in the VDR or VEX.')
        expect(FIELD_HELP.residualRisk.endsWith(' ' + FDA_SUMMARY_ONLY)).toBe(true)
        expect(FIELD_HELP.residualRiskRationale.endsWith(' ' + FDA_SUMMARY_ONLY)).toBe(true)
        expect(FIELD_HELP.riskFileReference.endsWith(' ' + FDA_SUMMARY_ONLY + ' Do not enter internal links or credentials.')).toBe(true)
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
