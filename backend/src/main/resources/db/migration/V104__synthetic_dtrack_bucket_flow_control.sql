-- Per-bucket fan-out bookkeeping (TPF-137): flow_control.fanOutChanged maps a component uuid to
-- the bucket date at which that component's findings last changed and has not yet been fanned
-- out; flow_control.recordedAt is the bucket date of the last save that maintained the map
-- (a bucket whose date differs from it was moved by a writer that recorded nothing and is caught
-- up by the next fan-out pass). Nullable, no default, no index: a metadata-only ALTER on a table of
-- one row per (org, bucket).
ALTER TABLE rearm.synthetic_dtrack_bucket ADD COLUMN IF NOT EXISTS flow_control jsonb;
