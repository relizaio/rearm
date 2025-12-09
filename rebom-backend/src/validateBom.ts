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
        const validationErrors = await validator.validate(JSON.stringify(data))
        if (validationErrors === null) {
            logger.info('JSON valid')
        } else {
            let serialNumber = 'unknown'
            try {
                serialNumber = data.serialNumber || 'not present'
            } catch {
                // ignore parsing errors
            }
            throw new Error('JSON Validation Error for BOM serialNumber=' + serialNumber + ':\n' + JSON.stringify(validationErrors))
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