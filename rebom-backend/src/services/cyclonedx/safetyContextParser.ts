import { SafetyContextDto } from '../../types';

export function validateSafetyContext(data: any): boolean {
    if (!data || typeof data !== 'object') {
        return false;
    }

    if (data.bomFormat !== 'CycloneDX') {
        return false;
    }

    return true;
}

export function parseSafetyContextToRecords(
    data: any
): SafetyContextDto[] {
    const records: SafetyContextDto[] = [];

    const components = data?.components || [];

    for (const component of components) {
        const purl = component?.purl;

        if (!purl) {
            continue;
        }

        records.push({
            purl,
            refId: component?.refId,
            bomRef: component?.['bom-ref'],
            rawJson: component
        });
    }

    return records;
}