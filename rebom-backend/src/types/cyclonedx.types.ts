import { VulnerabilitySeverity } from './sarif.types';

export type VulnerabilityDto = {
    purl: string;
    vulnId: string;
    severity: VulnerabilitySeverity;
}
