// Domain-driven terminology for the Distribution module. Model field names
// (UDI / UDI-DI / GUDID) are unchanged regardless of domain; this only adjusts
// section/screen labels and emphasis per a client's DistributionDomain so the
// same backend can present as medical-device, defense-fielded-systems, or a
// neutral distribution context.

export type DistributionDomain = 'GENERIC' | 'MEDICAL' | 'DEFENSE'

export interface DistributionTerms {
    shipments: string       // plural section title for shipment records
    shipment: string        // singular
    shipAction: string      // create-button label
    installedBase: string   // current-fielded view label
    fieldState: string      // observed/actual state label
    actualReport: string    // phone-home / reported label
    deviceIdentity: string  // resolved DI section label
    unit: string            // a single fielded item
}

const GENERIC: DistributionTerms = {
    shipments: 'Shipments',
    shipment: 'Shipment',
    shipAction: 'Record Shipment',
    installedBase: 'Installed Base',
    fieldState: 'Field State',
    actualReport: 'Reported Version',
    deviceIdentity: 'Device Identity',
    unit: 'unit'
}

const MEDICAL: DistributionTerms = {
    shipments: 'Shipments',
    shipment: 'Shipment',
    shipAction: 'Record Shipment',
    installedBase: 'Installed Base',
    fieldState: 'Field Status',
    actualReport: 'Reported Version',
    deviceIdentity: 'Device Identity (UDI)',
    unit: 'device'
}

const DEFENSE: DistributionTerms = {
    shipments: 'Fielded Units',
    shipment: 'Fielding',
    shipAction: 'Field Unit',
    installedBase: 'Installed Base',
    fieldState: 'As-Maintained',
    actualReport: 'Field Report',
    deviceIdentity: 'Device Identity',
    unit: 'unit'
}

export const DISTRIBUTION_DOMAIN_OPTIONS = [
    { label: 'Generic', value: 'GENERIC' },
    { label: 'Medical (UDI / GUDID)', value: 'MEDICAL' },
    { label: 'Defense (fielded systems)', value: 'DEFENSE' }
]

export function termsFor (domain?: string | null): DistributionTerms {
    switch (domain) {
    case 'MEDICAL': return MEDICAL
    case 'DEFENSE': return DEFENSE
    default: return GENERIC
    }
}
