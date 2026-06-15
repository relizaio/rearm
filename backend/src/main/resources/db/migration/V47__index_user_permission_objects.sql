-- Phase 3a-3: make the inverse object->users permission lookup
-- (UserRepository.findUsersByPermissionObject, used by ComponentTeamService to
-- derive a component's team/approvers) index-friendly.
--
-- The query is rewritten in VariableQueries to a jsonb @> containment test
-- against the permission array; jsonb_path_ops GIN supports @> and is smaller
-- and faster than the default jsonb_ops for pure containment.
CREATE INDEX IF NOT EXISTS idx_users_permission_objects
    ON rearm.users
    USING gin ((record_data -> 'permissions' -> 'permissions') jsonb_path_ops);
