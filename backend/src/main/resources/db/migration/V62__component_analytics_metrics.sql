-- Per-component / per-product daily finding-count rollups (counts ONLY — no
-- finding detail arrays; drill-downs recompute via the existing
-- findingsPerDayForComponent hot path). Serves the most-vulnerable
-- components/products widget and PRODUCT-perspective findings-over-time as
-- indexed table reads instead of a per-page-load walk over every branch's
-- latest release metrics.
--
-- Rows are written by the same walks that maintain org-level
-- analytics_metrics (midnight seed + change-driven today refresh), plus a
-- self-backfill on the 20-minute analytics tick: products missing
-- yesterday's row get 60 days recomputed, components missing yesterday's
-- row get yesterday recomputed (mirrors the perspective-analytics
-- if-yesterday-missing pattern), so widgets work immediately on rollout and
-- new components/products self-heal.
CREATE TABLE rearm.component_analytics_metrics (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY DEFAULT gen_random_uuid(),
    revision integer NOT NULL DEFAULT 0,
    schema_version integer NOT NULL DEFAULT 0,
    created_date timestamptz NOT NULL DEFAULT now(),
    last_updated_date timestamptz NOT NULL DEFAULT now(),
    org uuid NOT NULL,
    component uuid NOT NULL,
    component_type text NOT NULL,
    date_key text NOT NULL,
    numeric_metrics jsonb NOT NULL
);

CREATE UNIQUE INDEX component_analytics_org_component_date_idx
    ON rearm.component_analytics_metrics (org, component, date_key);

-- widget read: today's rows per org+type, ordered by severity counts
CREATE INDEX component_analytics_org_type_date_idx
    ON rearm.component_analytics_metrics (org, component_type, date_key);

-- chart read: one component/product across a date range
CREATE INDEX component_analytics_component_date_idx
    ON rearm.component_analytics_metrics (component, date_key);
