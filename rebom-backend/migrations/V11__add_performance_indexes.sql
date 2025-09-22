-- ============================================================================
-- V11: Simplified migration
-- ============================================================================

-- Index for health monitoring queries (done in application)
-- Note: Removed time-based partial index as NOW() is not immutable
CREATE INDEX IF NOT EXISTS idx_bom_oci_health_check ON rebom.bom_oci_references(oci_last_verified);

-- Index for performance monitoring queries (done in application)  
CREATE INDEX IF NOT EXISTS idx_bom_oci_access_stats ON rebom.bom_oci_references(access_count DESC, last_accessed DESC);

-- Index for size-based queries (done in application)
CREATE INDEX IF NOT EXISTS idx_bom_metadata_created_org ON rebom.bom_metadata(organization, created_date DESC);

