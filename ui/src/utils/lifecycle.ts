import constants from '@/utils/constants'

/**
 * Display label of a release lifecycle, from the same table the lifecycle pickers use: GENERAL_AVAILABILITY
 * reads "Shipped", READY_TO_SHIP "Ready to Ship". Render this rather than the raw value, so a release never
 * reads "GENERAL_AVAILABILITY" on one screen and "Shipped" on the next. An unknown value passes through as is.
 */
export function lifecycleLabel (lifecycle: string | null | undefined): string {
    if (!lifecycle) return ''
    return constants.LifecycleOptions.find((lo: any) => lo.key === lifecycle)?.label ?? lifecycle
}
