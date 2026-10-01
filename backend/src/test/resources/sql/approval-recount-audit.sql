-- Approval recount audit (rearm-saas#728). Read-only, two queries. Run both against production
-- BEFORE deploying. Expected result of each: ZERO rows.
--
-- #728 counts approval votes per person instead of per event. Votes are stored as an append-only
-- event list (releases.approval_events) and nothing is migrated, so every stored release is
-- recounted the moment the new code reads it.
--
-- Query 1 computes each (release, approval entry) state both ways and lists every pair where they
-- differ, in either direction (change = 'gate'). A row is a stored release that would change
-- approval state. It also lists every pair whose state AS RULES SEE IT (release.approvals[entry])
-- would become more permissive (change = 'rules'): main's rules read any DISAPPROVED event as a
-- disapproved entry and any APPROVED event as approved. After #728 rules read the entry's state,
-- except that a stored disapproval stands until its voter overrides it with a role of the same
-- requirement. Rules reading an entry as
-- UNSET where main read APPROVED (approved on its first vote) is the intended #728 change and is
-- not listed.
-- Query 2 lists approval entries holding a count the new code reads differently (required below 1
-- or missing reads as 1, permitted below 0 or missing reads as 0). Such an entry changes the state
-- of every release under it, including releases nobody has voted on, which Query 1 cannot see.
--
-- Counting before #728 (main), per requirement of the entry:
--   approvals    = APPROVED events whose role the requirement allows
--   disapprovals = DISAPPROVED events whose role the requirement allows
-- Counting after #728, per requirement: one vote per person (wu.lastUpdatedActor, else
--   wu.lastUpdatedBy). Only APPROVED and DISAPPROVED are votes. A person's vote is DISAPPROVED if
--   they cast any DISAPPROVED at or after their last override, else APPROVED (no override before
--   #728, so: any DISAPPROVED). A DISAPPROVED vote weighs as many disapprovals as the person
--   stored before votes carried a kind (since that override), at least one: main counted events.
--   An event with no voter counts on its own. A stored required count
--   below 1 (or missing) reads as 1, a permitted count below 0 (or missing) as 0.
-- Both: DISAPPROVED when any requirement has more disapprovals than permitted, else APPROVED when
--   every requirement has at least the required approvals (an entry without requirements is
--   APPROVED), else PENDING. Main threw on a missing count, which shows as ERROR.
--
-- Query 1
WITH ev AS (
    SELECT r.uuid AS release_uuid,
           e.ord,
           (e.ev->>'approvalEntry')::uuid AS entry_uuid,
           e.ev->>'approvalRoleId' AS role_id,
           e.ev->>'state' AS state,
           COALESCE(e.ev->'wu'->>'lastUpdatedActor', e.ev->'wu'->>'lastUpdatedBy') AS voter,
           COALESCE(e.ev->>'kind' = 'OVERRIDE', false) AS is_override,
           e.ev->>'kind' IS NULL AS is_stored
    FROM rearm.releases r
    CROSS JOIN LATERAL jsonb_array_elements(r.approval_events) WITH ORDINALITY AS e(ev, ord)
    WHERE jsonb_typeof(r.approval_events) = 'array'
      AND e.ev->>'approvalEntry' IS NOT NULL
),
pairs AS (
    SELECT DISTINCT release_uuid, entry_uuid FROM ev
),
req AS (
    SELECT ae.uuid AS entry_uuid,
           q.req_ord,
           COALESCE(q.req->'allowedApprovalRoleIds', '[]'::jsonb) AS roles,
           (q.req->>'requiredNumberOfApprovals')::int AS required_raw,
           (q.req->>'permittedNumberOfDisapprovals')::int AS permitted_raw
    FROM rearm.approval_entries ae
    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(ae.record_data->'approvalRequirements', '[]'::jsonb))
        WITH ORDINALITY AS q(req, req_ord)
),
-- the votes that fall under each requirement
req_ev AS (
    SELECT ev.*, req.req_ord
    FROM ev
    JOIN req ON req.entry_uuid = ev.entry_uuid AND req.roles ? ev.role_id
    WHERE ev.state IN ('APPROVED', 'DISAPPROVED')
),
-- after #728: one vote per person and requirement
last_override AS (
    SELECT release_uuid, entry_uuid, req_ord, voter, max(ord) AS ord
    FROM req_ev
    WHERE voter IS NOT NULL AND is_override
    GROUP BY release_uuid, entry_uuid, req_ord, voter
),
person_vote AS (
    SELECT x.release_uuid, x.entry_uuid, x.req_ord, x.voter,
           CASE WHEN bool_or(x.state = 'DISAPPROVED') THEN 'DISAPPROVED' ELSE 'APPROVED' END AS state,
           CASE WHEN bool_or(x.state = 'DISAPPROVED')
                THEN GREATEST(count(*) FILTER (WHERE x.state = 'DISAPPROVED' AND x.is_stored), 1) ELSE 1 END AS weight
    FROM req_ev x
    LEFT JOIN last_override lo USING (release_uuid, entry_uuid, req_ord, voter)
    WHERE x.voter IS NOT NULL AND x.ord >= COALESCE(lo.ord, 0)
    GROUP BY x.release_uuid, x.entry_uuid, x.req_ord, x.voter
    UNION ALL
    SELECT release_uuid, entry_uuid, req_ord, NULL, state, 1 FROM req_ev WHERE voter IS NULL
),
main_counts AS (
    SELECT release_uuid, entry_uuid, req_ord,
           count(*) FILTER (WHERE state = 'APPROVED') AS approvals,
           count(*) FILTER (WHERE state = 'DISAPPROVED') AS disapprovals
    FROM req_ev
    GROUP BY release_uuid, entry_uuid, req_ord
),
new_counts AS (
    SELECT release_uuid, entry_uuid, req_ord,
           count(*) FILTER (WHERE state = 'APPROVED') AS approvals,
           COALESCE(sum(weight) FILTER (WHERE state = 'DISAPPROVED'), 0) AS disapprovals
    FROM person_vote
    GROUP BY release_uuid, entry_uuid, req_ord
),
per_req AS (
    SELECT p.release_uuid, p.entry_uuid, req.req_ord, req.required_raw, req.permitted_raw,
           COALESCE(m.approvals, 0) AS main_approvals,
           COALESCE(m.disapprovals, 0) AS main_disapprovals,
           COALESCE(n.approvals, 0) AS new_approvals,
           COALESCE(n.disapprovals, 0) AS new_disapprovals
    FROM pairs p
    JOIN req ON req.entry_uuid = p.entry_uuid
    LEFT JOIN main_counts m
        ON m.release_uuid = p.release_uuid AND m.entry_uuid = p.entry_uuid AND m.req_ord = req.req_ord
    LEFT JOIN new_counts n
        ON n.release_uuid = p.release_uuid AND n.entry_uuid = p.entry_uuid AND n.req_ord = req.req_ord
),
states AS (
    SELECT p.release_uuid, p.entry_uuid,
           CASE
               WHEN NOT EXISTS (SELECT 1 FROM rearm.approval_entries ae WHERE ae.uuid = p.entry_uuid) THEN 'NO_ENTRY'
               WHEN count(pr.req_ord) = 0 THEN 'APPROVED'
               WHEN bool_or(pr.required_raw IS NULL OR pr.permitted_raw IS NULL) THEN 'ERROR'
               WHEN bool_or(pr.main_disapprovals > pr.permitted_raw) THEN 'DISAPPROVED'
               WHEN bool_and(pr.main_approvals >= pr.required_raw) THEN 'APPROVED'
               ELSE 'PENDING'
           END AS main_state,
           CASE
               WHEN NOT EXISTS (SELECT 1 FROM rearm.approval_entries ae WHERE ae.uuid = p.entry_uuid) THEN 'NO_ENTRY'
               WHEN count(pr.req_ord) = 0 THEN 'APPROVED'
               WHEN bool_or(pr.new_disapprovals > GREATEST(COALESCE(pr.permitted_raw, 0), 0)) THEN 'DISAPPROVED'
               WHEN bool_and(pr.new_approvals >= GREATEST(COALESCE(pr.required_raw, 1), 1)) THEN 'APPROVED'
               ELSE 'PENDING'
           END AS new_state
    FROM pairs p
    LEFT JOIN per_req pr ON pr.release_uuid = p.release_uuid AND pr.entry_uuid = p.entry_uuid
    GROUP BY p.release_uuid, p.entry_uuid
),
rules AS (
    SELECT s.*,
           CASE WHEN EXISTS (SELECT 1 FROM ev x WHERE x.release_uuid = s.release_uuid AND x.entry_uuid = s.entry_uuid
                                 AND x.state = 'DISAPPROVED') THEN 'DISAPPROVED'
                WHEN EXISTS (SELECT 1 FROM ev x WHERE x.release_uuid = s.release_uuid AND x.entry_uuid = s.entry_uuid
                                 AND x.state = 'APPROVED') THEN 'APPROVED'
                ELSE 'UNSET' END AS main_rules,
           EXISTS (SELECT 1 FROM ev x WHERE x.release_uuid = s.release_uuid AND x.entry_uuid = s.entry_uuid
                       AND x.state = 'DISAPPROVED' AND x.is_stored
                       AND (x.voter IS NULL OR NOT EXISTS (
                           SELECT 1 FROM ev o WHERE o.release_uuid = x.release_uuid AND o.entry_uuid = x.entry_uuid
                               AND o.voter = x.voter AND o.is_override AND o.ord > x.ord
                               AND EXISTS (SELECT 1 FROM req q WHERE q.entry_uuid = x.entry_uuid
                                               AND q.roles ? x.role_id AND q.roles ? o.role_id)))) AS standing_stored_veto,
           EXISTS (SELECT 1 FROM req r2 WHERE r2.entry_uuid = s.entry_uuid) AS has_requirements
    FROM states s
),
views AS (
    SELECT r.*,
           CASE WHEN r.main_state = 'NO_ENTRY' OR NOT r.has_requirements THEN r.main_rules
                WHEN r.new_state = 'DISAPPROVED' OR r.standing_stored_veto THEN 'DISAPPROVED'
                WHEN r.new_state = 'APPROVED' THEN 'APPROVED'
                ELSE 'UNSET' END AS new_rules
    FROM rules r
)
SELECT v.release_uuid, v.entry_uuid, rel.record_data->>'lifecycle' AS lifecycle,
       CASE WHEN v.main_state <> v.new_state THEN 'gate' ELSE 'rules' END AS change,
       v.main_state, v.new_state, v.main_rules, v.new_rules
FROM views v
JOIN rearm.releases rel ON rel.uuid = v.release_uuid
WHERE v.main_state <> v.new_state
   OR (v.main_rules = 'DISAPPROVED' AND v.new_rules <> 'DISAPPROVED')
   OR (v.main_rules <> 'APPROVED' AND v.new_rules = 'APPROVED')
ORDER BY v.release_uuid, v.entry_uuid;

-- Query 2
SELECT ae.uuid AS entry_uuid, ae.record_data->>'approvalName' AS approval_name,
       ae.record_data->>'status' AS status, q.req AS requirement
FROM rearm.approval_entries ae
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(ae.record_data->'approvalRequirements', '[]'::jsonb)) AS q(req)
WHERE COALESCE((q.req->>'requiredNumberOfApprovals')::int, 0) < 1
   OR COALESCE((q.req->>'permittedNumberOfDisapprovals')::int, -1) < 0
ORDER BY ae.uuid;
