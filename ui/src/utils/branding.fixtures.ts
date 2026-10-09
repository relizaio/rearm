// Branding settings fixtures for the specs (task WL-3). Not a suite: vitest collects *.spec.ts only.
// MEDWARE_SETTINGS is what the backend answers for the shipped medware preset; EXAMPLE_SETTINGS is the
// backend's test-only example preset, which sets every field that is not a fallback, so the specs
// exercise each one; CONSENT_SHOWN_SETTINGS is the one shape the shipped preset cannot reach.
import type { BrandingSettings } from './branding'

export const MEDWARE_SETTINGS: BrandingSettings = {
    preset: 'medware',
    isDefault: false,
    titleText: 'MedWare Cyber Dossier',
    organizationText: 'MedWare Cyber, LLC.',
    navProductName: 'MedWare Cyber Dossier',
    supportName: 'Reliza Support',
    supportEmail: 'info@reliza.io',
    documentationUrl: 'https://docs.rearmhq.com',
    termsOfServiceUrl: 'https://rearmhq.com/tos.html',
    privacyPolicyUrl: 'https://rearmhq.com/privacy.html',
    legalUrl: 'https://rearmhq.com/tos.html',
    marketingConsent: 'HIDDEN',
    marketingConsentText: 'Agree to receive news and promotions from Reliza by email (Optional).',
    logoUrl: '/api/branding/v1/asset/logo?v=19741e4ca576',
    faviconUrl: '/api/branding/v1/asset/favicon?v=652d6f562b4d',
    signUpBackgroundUrl: '/reliza_in_sand_right_corner.jpg'
}

export const EXAMPLE_SETTINGS: BrandingSettings = {
    preset: 'example',
    isDefault: false,
    titleText: 'Example Release Hub',
    organizationText: 'Example Organization',
    navProductName: 'Example Hub',
    supportName: 'Example Support',
    supportEmail: 'support@example.com',
    documentationUrl: 'https://docs.example.com',
    termsOfServiceUrl: 'https://example.com/terms',
    privacyPolicyUrl: 'https://rearmhq.com/privacy.html',
    legalUrl: 'https://example.com/legal',
    marketingConsent: 'HIDDEN',
    marketingConsentText: 'Agree to receive news and promotions from Reliza by email (Optional).',
    logoUrl: '/api/branding/v1/asset/logo?v=0123456789ab',
    faviconUrl: '/api/branding/v1/asset/favicon?v=ba9876543210',
    signUpBackgroundUrl: '/reliza_in_sand_right_corner.jpg'
}

export const CONSENT_SHOWN_SETTINGS: BrandingSettings = {
    ...EXAMPLE_SETTINGS,
    marketingConsent: 'SHOWN',
    marketingConsentText: 'Agree to receive news from Example Organization (Optional).'
}
