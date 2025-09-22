-- ============================================================================
-- V8: Create BOM OCI references table
-- All BOMs stored in OCI - critical requirement
-- ============================================================================

CREATE TABLE rebom.bom_oci_references (
    uuid UUID PRIMARY KEY,
    
    -- OCI storage (REQUIRED - all BOMs are in OCI)
    oci_reference TEXT NOT NULL, -- OCI artifact reference (registry/repo:tag)
    oci_digest VARCHAR(255) NOT NULL, -- OCI content digest for integrity
    oci_size_bytes INTEGER NOT NULL, -- Size of BOM in OCI
    oci_media_type VARCHAR(100) DEFAULT 'application/vnd.cyclonedx+json', -- MIME type
    
    -- Content metadata (derived from OCI)
    content_hash VARCHAR(64) NOT NULL, -- SHA256 of BOM content for integrity
    compression_used VARCHAR(20), -- OCI layer compression: gzip, etc.
    
    -- Performance tracking
    access_count INTEGER DEFAULT 0,
    last_accessed TIMESTAMPTZ DEFAULT NOW(),
    
    -- OCI push/pull metadata
    oci_pushed_date TIMESTAMPTZ NOT NULL,
    oci_last_verified TIMESTAMPTZ DEFAULT NOW(), -- Last time we verified OCI artifact exists
    
    -- Audit
    created_date TIMESTAMPTZ DEFAULT NOW(),
    
    -- Foreign key to metadata
    FOREIGN KEY (uuid) REFERENCES rebom.bom_metadata(uuid) ON DELETE CASCADE
);

-- OCI reference indexes
CREATE INDEX idx_bom_oci_reference ON rebom.bom_oci_references(oci_reference);
CREATE INDEX idx_bom_oci_digest ON rebom.bom_oci_references(oci_digest);
CREATE INDEX idx_bom_oci_size ON rebom.bom_oci_references(oci_size_bytes);
CREATE INDEX idx_bom_oci_accessed ON rebom.bom_oci_references(last_accessed DESC);
CREATE INDEX idx_bom_oci_verified ON rebom.bom_oci_references(oci_last_verified DESC);
