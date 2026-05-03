import * as CDX from '@cyclonedx/cyclonedx-library'
import { logger } from './logger';
import { BomValidationError } from './types/errors';

interface ValidatorMap {
    [key: string]: CDX.Validation.Types.Validator;
}
const validatorsMap: ValidatorMap = {};
for (const value of Object.values(CDX.Spec.Version)) {
    validatorsMap[value.toString()] = new CDX.Validation.JsonStrictValidator(value);
}

/**
 * Resolve a JSON-pointer-style instancePath (e.g. "/components/0/type")
 * against the BOM and return the actual value at that path. ajv emits
 * the path on every validation error but not the value — without this
 * resolution the error log is "X must be equal to one of [...] " with
 * no clue what X actually was, so we couldn't tell whether scanners
 * are emitting unknown enum values, malformed types, etc. Returns
 * undefined when the path fails to resolve (e.g. the field is missing
 * entirely — which is itself a useful signal).
 */
function resolveInstancePath(data: any, instancePath: string): unknown {
    if (!instancePath || instancePath === '/') return data;
    // ajv uses RFC 6901 segments separated by '/' — leading slash, then
    // each segment is decoded (~1 -> /, ~0 -> ~). Numeric segments are
    // array indices.
    const segments = instancePath.split('/').slice(1).map((s) =>
        s.replace(/~1/g, '/').replace(/~0/g, '~')
    );
    let cursor: any = data;
    for (const seg of segments) {
        if (cursor == null) return undefined;
        cursor = Array.isArray(cursor) ? cursor[Number(seg)] : cursor[seg];
    }
    return cursor;
}

/**
 * Decorate ajv validation errors with the actual value found at each
 * error's instancePath so the failure log surfaces what the BOM
 * actually contained, not just what it should have. Truncates large
 * values (objects/arrays) to a stringified preview at 200 chars to
 * keep log lines bounded.
 */
function enrichErrorsWithActualValues(errors: any[], data: any): any[] {
    return errors.map((err) => {
        let actual: unknown;
        try {
            actual = resolveInstancePath(data, err.instancePath);
        } catch {
            actual = '<resolution-failed>';
        }
        let actualPreview: unknown;
        if (actual === undefined) {
            actualPreview = '<missing>';
        } else if (actual === null || typeof actual !== 'object') {
            actualPreview = actual;
        } else {
            const json = JSON.stringify(actual);
            actualPreview = json.length > 200 ? json.slice(0, 200) + '…<truncated>' : json;
        }
        return { ...err, actualValue: actualPreview };
    });
}

export default async function validateBom(data: any): Promise<boolean> {
    try {
        const validator: CDX.Validation.Types.Validator = validatorsMap[data.specVersion]
        if (!validator) {
            throw new BomValidationError('Unsupported schema version: ' + data.specVersion, {
                field: 'specVersion',
                value: data.specVersion,
                constraint: 'must be a supported CycloneDX spec version'
            })
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
            const enrichedErrors = enrichErrorsWithActualValues(validationErrors as any[], data);
            throw new BomValidationError('JSON Validation Error for BOM serialNumber=' + serialNumber + ':\n' + JSON.stringify(enrichedErrors), {
                field: 'bom',
                constraint: 'must pass CycloneDX schema validation',
                validationErrors: enrichedErrors
            })
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