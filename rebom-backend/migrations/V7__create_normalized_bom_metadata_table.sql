-- ============================================================================
-- V7: Create normalized BOM metadata table
-- HIGH RISK CLEAN MIGRATION - No backward compatibility
-- ============================================================================

-- Create normalized metadata table
CREATE TABLE rebom.bom_metadata (
    uuid UUID PRIMARY KEY,
    
    -- Core identifiers (100% completeness in production)
    serial_number VARCHAR(255) UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    version VARCHAR(255) NOT NULL,
    
    -- BOM versioning (93.24% completeness)
    bom_version VARCHAR(50) DEFAULT '1',
    
    -- Classification (75.68% completeness)
    belongs_to VARCHAR(50), -- DELIVERABLE, SCE, RELEASE
    hash VARCHAR(255),       -- SHA256 digest (73.72% completeness)
    
    -- Processing options (100% completeness)
    tld_only BOOLEAN DEFAULT false,
    ignore_dev BOOLEAN DEFAULT false,  -- 21.16% usage but growing
    structure VARCHAR(50) DEFAULT 'FLAT',
    strip_bom BOOLEAN DEFAULT true,
    
    -- Package URL (50.27% completeness)
    purl TEXT,
    
    -- Processing metadata
    bom_digest VARCHAR(255) NOT NULL, -- Computed hash (100% completeness)
    notes TEXT DEFAULT 'sent from ReArm',
    mod VARCHAR(50) DEFAULT 'raw',
    bom_state VARCHAR(50) DEFAULT 'raw',
    
    -- Advanced options (45.04% completeness)
    root_component_merge_mode VARCHAR(100),
    
    -- Audit fields
    organization UUID NOT NULL,
    created_date TIMESTAMPTZ DEFAULT NOW(),
    last_updated_date TIMESTAMPTZ DEFAULT NOW(),
    
    -- Source tracking
    source_format VARCHAR(20) DEFAULT 'CYCLONEDX', -- CYCLONEDX, SPDX
    source_spdx_uuid UUID NULL, -- Reference to SPDX source if converted
    
    -- Status tracking
    public BOOLEAN DEFAULT false,
    duplicate BOOLEAN DEFAULT false
);

-- Primary lookup indexes
CREATE INDEX idx_bom_metadata_serial_org ON rebom.bom_metadata(serial_number, organization);
CREATE INDEX idx_bom_metadata_org_created ON rebom.bom_metadata(organization, created_date DESC);
CREATE INDEX idx_bom_metadata_belongs_to ON rebom.bom_metadata(belongs_to) WHERE belongs_to IS NOT NULL;

-- Query optimization indexes
CREATE INDEX idx_bom_metadata_name_group ON rebom.bom_metadata(name, group_name);
CREATE INDEX idx_bom_metadata_version ON rebom.bom_metadata(version);
CREATE INDEX idx_bom_metadata_hash ON rebom.bom_metadata(hash) WHERE hash IS NOT NULL;

-- Processing option indexes
CREATE INDEX idx_bom_metadata_tld_ignore_dev ON rebom.bom_metadata(tld_only, ignore_dev);
CREATE INDEX idx_bom_metadata_structure ON rebom.bom_metadata(structure);
