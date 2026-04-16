export interface TriggerValidationResult {
    valid: boolean
    error?: string
}

export function validateOutputTrigger(trigger: any): TriggerValidationResult {
    if (!trigger.name || trigger.name.trim() === '') {
        return { valid: false, error: 'Output event name is required.' }
    }

    if (!trigger.type || trigger.type === '') {
        return { valid: false, error: 'Output event type is required.' }
    }

    return { valid: true }
}

export function validateInputTrigger(trigger: any): TriggerValidationResult {
    if (!trigger.name || trigger.name.trim() === '') {
        return { valid: false, error: 'Input event name is required.' }
    }

    if (!trigger.celExpression || trigger.celExpression.trim() === '') {
        return { valid: false, error: 'CEL expression is required.' }
    }

    if (!trigger.outputEvents || trigger.outputEvents.length === 0) {
        return { valid: false, error: 'At least one output event must be selected.' }
    }

    return { valid: true }
}
