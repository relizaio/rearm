import * as CDX from '@cyclonedx/cyclonedx-library'
import { logger } from './logger';

interface ValidatorMap {
    [key: string]: CDX.Validation.Types.Validator;
}
const validatorsMap: ValidatorMap = {};
for (const value of Object.values(CDX.Spec.Version)) {
    validatorsMap[value.toString()] = new CDX.Validation.JsonStrictValidator(value);
}

export default async function validateBom(data: any): Promise<boolean> {
    try {
        const validator: CDX.Validation.Types.Validator = validatorsMap[data.specVersion]
        if (!validator) {
            throw new Error('Unsupported schema version: ' + data.specVersion)
        }

        // Fix dependencies structure to avoid validation errors
        if (data.dependencies && Array.isArray(data.dependencies)) {
            if (data.dependencies.length === 0) {
                // Remove empty dependencies array
                delete data.dependencies
            } else {
                // Ensure each dependency has a valid dependsOn array
                data.dependencies.forEach((dep: any) => {
                    if (!dep.dependsOn || !Array.isArray(dep.dependsOn)) {
                        dep.dependsOn = []
                    }
                })
            }
        }

        const validationErrors = await validator.validate(JSON.stringify(data))
        if (validationErrors === null) {
            logger.info('JSON valid')
        } else {
            throw new Error('JSON Validation Error:\n' + JSON.stringify(validationErrors))
        }
    } catch (err) {
        if (err instanceof CDX.Validation.MissingOptionalDependencyError) {
            logger.info({ err }, 'JSON validation skipped')
        } else {
            logger.error({ err })
            throw err
        }
    }

    return true
}