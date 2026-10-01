import { isTriviallyTrueCel } from './celConditionBuilder'

export interface TriggerValidationResult {
    valid: boolean
    error?: string
    // Set when the error belongs under the Condition builder rather than in a toast.
    field?: 'celExpression'
}

export function validateOutputTrigger(trigger: any): TriggerValidationResult {
    if (!trigger.name || trigger.name.trim() === '') {
        return { valid: false, error: 'Action name is required.' }
    }

    if (!trigger.type || trigger.type === '') {
        return { valid: false, error: 'Action type is required.' }
    }

    if (trigger.type === 'ADD_APPROVED_ENVIRONMENT' &&
        (!trigger.approvedEnvironment || String(trigger.approvedEnvironment).trim() === '')) {
        return { valid: false, error: 'Approved environment is required for Add Approved Environment actions.' }
    }

    return { valid: true }
}

export function validateInputTrigger(trigger: any): TriggerValidationResult {
    if (!trigger.name || trigger.name.trim() === '') {
        return { valid: false, error: 'Rule name is required.' }
    }

    if (!trigger.celExpression || trigger.celExpression.trim() === '') {
        return { valid: false, error: 'Condition is required.', field: 'celExpression' }
    }

    if (isTriviallyTrueCel(trigger.celExpression)) {
        return {
            valid: false,
            error: 'Condition contains a clause that is always true, so the rule would match every release. Complete or remove that clause.',
            field: 'celExpression'
        }
    }

    if (!trigger.outputEvents || trigger.outputEvents.length === 0) {
        return { valid: false, error: 'At least one action must be selected.' }
    }

    return { valid: true }
}
