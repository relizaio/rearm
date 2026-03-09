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

    if (!trigger.conditionGroup || !trigger.conditionGroup.conditionGroups || trigger.conditionGroup.conditionGroups.length === 0) {
        return { valid: false, error: 'At least one condition group is required.' }
    }

    for (const group of trigger.conditionGroup.conditionGroups) {
        if (!group.conditions || group.conditions.length === 0) {
            return { valid: false, error: 'Each condition group must have at least one condition.' }
        }
    }

    if (!trigger.outputEvents || trigger.outputEvents.length === 0) {
        return { valid: false, error: 'At least one output event must be selected.' }
    }

    if (trigger.conditionGroup && trigger.conditionGroup.conditionGroups) {
        for (const group of trigger.conditionGroup.conditionGroups) {
            if (group.conditions) {
                for (const condition of group.conditions) {
                    if (condition.type === 'APPROVAL_ENTRY') {
                        if (!condition.approvalState || condition.approvalState === '') {
                            return { valid: false, error: 'Approval Entry conditions must have an approval state (APPROVED or DISAPPROVED) selected.' }
                        }
                    } else if (condition.type === 'LIFECYCLE') {
                        if (!condition.possibleLifecycles || condition.possibleLifecycles.length === 0) {
                            return { valid: false, error: 'Lifecycle conditions must have at least one lifecycle selected.' }
                        }
                    } else if (condition.type === 'BRANCH_TYPE') {
                        if (!condition.possibleBranchTypes || condition.possibleBranchTypes.length === 0) {
                            return { valid: false, error: 'Branch Type conditions must have at least one branch type selected.' }
                        }
                    } else if (condition.type === 'METRICS') {
                        if (!condition.metricsType || condition.metricsType === '') {
                            return { valid: false, error: 'Metrics conditions must have a metric type selected.' }
                        }
                        if (!condition.comparisonSign || condition.comparisonSign === '') {
                            return { valid: false, error: 'Metrics conditions must have a comparison sign selected.' }
                        }
                        if (condition.metricsValue === null || condition.metricsValue === undefined) {
                            return { valid: false, error: 'Metrics conditions must have a metric value set.' }
                        }
                    }
                }
            }
        }
    }

    return { valid: true }
}
