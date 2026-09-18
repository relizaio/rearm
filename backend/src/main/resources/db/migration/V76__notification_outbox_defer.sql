-- ---------------------------------------------------------------------------
-- Deferred re-attempt for outbox events.
--
-- Motivation: NEW_VULN_AFFECTS_RELEASES / VULNERABILITY_RECORD_UPDATED are
-- emitted by the vuln-record upsert with affectedReleases left null, and the
-- fan-out worker resolves that list at delivery time from
-- artifacts.metrics->'vulnerabilityDetails'. But the outbox row commits with
-- the upsert (Propagation.MANDATORY) while artifacts.metrics is written later
-- in the SAME synthetic-SBOM pass -- ingestOrgBuckets() then fanOutOrg(), back
-- to back in SchedulingService's ~1/min per-org loop. The outbox drain runs
-- independently every 5 seconds, so it can read the event before the
-- CVE -> release link exists and resolve an empty list. An affected release
-- then reports as unaffected, with a payload identical to the genuine
-- "nothing you ship carries this" case.
--
-- These two columns let fan-out leave such an event PENDING and come back to
-- it, instead of terminating it on the first look. next_attempt_at mirrors the
-- notification_deliveries column of the same name from V42.
--
-- The counter deliberately does NOT mirror notification_deliveries.attempt_count
-- and is not spelled the same way: that column counts delivery FAILURES, this
-- one counts deliberate deferrals (a fan-out throw terminates the event as
-- FAILED and never increments this). Same name for opposite meanings would let
-- a future transient-retry path bump the counter on a DB blip and make the
-- affected-release guard suppress a real vulnerability event on its first look.
--
-- NOTE on ordering: unlike notification_deliveries, whose findReadyForDelivery
-- both filters AND orders by next_attempt_at, the outbox drain keeps its
-- ORDER BY occurred_at and gains only the "AND next_attempt_at <= now()"
-- eligibility filter. That is deliberate: occurred_at FIFO is a documented
-- correctness invariant for the approvals path (see markResolvedRequestsRead
-- in NotificationFanOutService -- APPROVAL_REQUESTED must fan out before the
-- APPROVAL_RESOLVED that marks its rows read, including within one batch).
-- Deferral here changes WHEN an event becomes eligible, never the order
-- eligible events are processed in, so that invariant is untouched.
--
-- The V42 (status, occurred_at) index alone would have served this while
-- PENDING stayed near-empty, which was true when every PENDING row was due.
-- Deferral is exactly what breaks that assumption: a burst of vuln events that
-- all resolve empty leaves thousands of PENDING-but-not-due rows sitting at the
-- head of the occurred_at ordering, and every 5-second tick would walk and
-- heap-fetch all of them before it could fill LIMIT 50 with due ones -- inside
-- the global DRAIN_NOTIFICATION_OUTBOX advisory lock, delaying every other
-- org's notifications. The (status, next_attempt_at) index below lets the
-- planner seek straight to the due rows and sort that much smaller set instead.
--
-- Backfill note: existing rows take the defaults, which make every PENDING row
-- immediately eligible -- identical to the pre-migration behaviour, so an
-- in-flight queue drains unchanged across the upgrade.
-- ---------------------------------------------------------------------------
ALTER TABLE rearm.notification_outbox_events
    ADD COLUMN enrichment_attempt_count integer NOT NULL default 0,
    ADD COLUMN next_attempt_at timestamptz NOT NULL default now();

CREATE INDEX notification_outbox_events_status_next_attempt_idx
    ON rearm.notification_outbox_events (status, next_attempt_at);
