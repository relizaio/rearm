import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { withoutHtmlComments } from '@/utils/specText'

/**
 * Wiring guard for the incomplete-condition block on the two rule editors. While the builder has
 * an incomplete clause it emits no CEL, so a Save that ignored its issues would silently keep the
 * previous condition. Scope is bindings only; the builder logic runs in celConditionBuilder.spec.ts.
 */
function source (file: string): string {
    return withoutHtmlComments(readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8'))
}

describe.each([
    ['ComponentView.vue', 'rule', 'inputTrigger', 'rule-save'],
    ['OrgSettings.vue', 'globalRule', 'globalInputEvent', 'global-rule-save']
])('%s rule editor', (file, prefix, model, testId) => {
    const code = source(`./${file}`)

    it('collects the issues of both the condition and the precondition builder', () => {
        expect(code).toMatch(new RegExp(`v-model="${model}\\.celExpression"\\s*@update:issues="\\(v: string\\[\\]\\) => \\{ ${prefix}ConditionIssues = v \\}"`))
        expect(code).toMatch(new RegExp(`v-model="${model}\\.preconditionCelExpression"\\s*@update:issues="\\(v: string\\[\\]\\) => \\{ ${prefix}PreconditionIssues = v \\}"`))
    })

    it('disables Save while either has an issue', () => {
        const save = code.match(new RegExp(`<n-button(?:(?!<n-button)[\\s\\S])*?data-testid="${testId}"`))?.[0] ?? ''
        expect(save).toContain(`:disabled="${prefix}ConditionIssues.length > 0 || ${prefix}PreconditionIssues.length > 0"`)
    })

    it('routes condition errors by field, not by message text', () => {
        expect(code).toContain("validation.field === 'celExpression'")
        expect(code).not.toContain("validation.error === 'Condition is required.'")
    })
})

describe('CelExpressionBuilder mode toggle', () => {
    const code = source('./CelExpressionBuilder.vue')

    it('is controlled, so switchMode can refuse to leave an incomplete builder', () => {
        // With v-model the mode is already switched when switchMode runs: an incomplete builder
        // then showed the last complete CEL, reported no issues, and Save kept the stale value.
        expect(code).toMatch(/<n-radio-group v-if="!celOnly" :value="mode"[^>]*@update:value="switchMode"/)
        expect(code).not.toContain('v-model:value="mode"')
    })
})
