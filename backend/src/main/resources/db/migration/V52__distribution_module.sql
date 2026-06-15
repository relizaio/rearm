-- Distribution module (FDA Readiness #4) — consolidated migration.
-- Client + Site + ShippedProduct + Device + HbomComponent + DeviceEvent.
-- Full design in backend/ai-plans/distribution-module/.
--
-- Org-scoped entities in the standard single-record_data JSONB shape.
-- Hierarchy Client -< Site -< ShippedProduct -< Device is carried in
-- record_data with NO FOREIGN KEYs (per coding_principles.md — referential
-- integrity is the service layer's job: SiteService validates the parent
-- client, ClientService cascades site deletes, ShippedProductService
-- validates the parent site and cascades device deletes, DeviceService /
-- DeviceEventService validate parents).
--
-- IF NOT EXISTS throughout: this consolidates four earlier per-feature
-- migrations (V42–V45 on the development branch), so environments that ran
-- those apply this as a no-op after their flyway history is reconciled.

-- ===== Client =====
CREATE TABLE IF NOT EXISTS rearm.clients (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS clients_org ON rearm.clients ((record_data->>'org'));

-- (org, lower(name)) — admin-side natural key for a client. Partial: archived
-- (soft-deleted) rows release the name for reuse.
CREATE UNIQUE INDEX IF NOT EXISTS clients_org_name
    ON rearm.clients ((record_data->>'org'), lower(record_data->>'name'))
    WHERE COALESCE(record_data->>'status', 'ACTIVE') != 'ARCHIVED';


-- ===== Site (flat child of Client) =====
CREATE TABLE IF NOT EXISTS rearm.sites (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS sites_org ON rearm.sites ((record_data->>'org'));

-- Enumerate a client's sites, fast.
CREATE INDEX IF NOT EXISTS sites_client ON rearm.sites ((record_data->>'client'));

-- (org, client, lower(name)) — a site name is unique within its client.
-- Partial: archived (soft-deleted) rows release the name for reuse.
CREATE UNIQUE INDEX IF NOT EXISTS sites_org_client_name
    ON rearm.sites ((record_data->>'org'),
                    (record_data->>'client'),
                    lower(record_data->>'name'))
    WHERE COALESCE(record_data->>'status', 'ACTIVE') != 'ARCHIVED';


-- ===== ShippedProduct (append-only shipment / lot record) =====
-- Each shipment/update is a new row; installed base = the latest row per
-- (site, feature set); recall scope = all rows for a release. Carries the
-- lot's CDX component-choice resolutions (a produced lot fixes all choices).
CREATE TABLE IF NOT EXISTS rearm.shipped_products (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS shipped_products_org ON rearm.shipped_products ((record_data->>'org'));

-- Installed-base read: shipments at a site (newest first).
CREATE INDEX IF NOT EXISTS shipped_products_site ON rearm.shipped_products ((record_data->>'site'));

-- Installed-base / DI read: shipments of a feature set.
CREATE INDEX IF NOT EXISTS shipped_products_feature_set ON rearm.shipped_products ((record_data->>'featureSet'));

-- Recall scope: every shipment of a release.
CREATE INDEX IF NOT EXISTS shipped_products_release ON rearm.shipped_products ((record_data->>'release'));


-- ===== Device (individual fielded unit; digital-twin anchor) =====
-- Instance-like: plan (expected release) vs actual (phone-home) plus the
-- latest reconciled observedState; 821 disposition (tracking) per unit.
CREATE TABLE IF NOT EXISTS rearm.devices (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS devices_org ON rearm.devices ((record_data->>'org'));

-- Enumerate a shipment's devices.
CREATE INDEX IF NOT EXISTS devices_shipped_product ON rearm.devices ((record_data->>'shippedProduct'));

-- Per-site device queries (installed base at a site).
CREATE INDEX IF NOT EXISTS devices_site ON rearm.devices ((record_data->>'site'));


-- ===== HbomComponent (parsed hardware nodes per release) =====
-- Mirrors the SBOM-component idea but simpler: a per-release cache rebuilt by
-- HbomComponentService from the release's hardware BOM artifact (via rebom's
-- parseHbomById). Hardware nodes have no purl, so there's no org-wide
-- canonical dedup; rows are keyed to a release. Includes CDX component-choice
-- slots (type component-choice, operator XOR/AND/OPTIONAL) with their option
-- nodes as children via parentRef.
CREATE TABLE IF NOT EXISTS rearm.hbom_components (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS hbom_components_org ON rearm.hbom_components ((record_data->>'org'));

-- Primary read: a release's hardware components.
CREATE INDEX IF NOT EXISTS hbom_components_release ON rearm.hbom_components ((record_data->>'release'));


-- ===== DeviceEvent (digital-twin event layer) =====
-- Append-only field events (failure / repair / replacement / inspection /
-- observation) anchored to the release ontology: hardware events reference an
-- HBOM node by its bom-ref, software events a purl; OBSERVATION events embed
-- the full reconciled report.
CREATE TABLE IF NOT EXISTS rearm.device_events (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS device_events_org ON rearm.device_events ((record_data->>'org'));

-- Timeline of a device's events.
CREATE INDEX IF NOT EXISTS device_events_device ON rearm.device_events ((record_data->>'device'));
