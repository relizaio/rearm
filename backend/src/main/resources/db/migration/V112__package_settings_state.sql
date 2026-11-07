-- V112__package_settings_state.sql (FDAW-18, B5): the Boolean switch of a package settings row becomes the
-- PackageKindState enum. A non-FOLLOW row with enabled true becomes ENABLED, every other non-FOLLOW row (false,
-- absent) DISABLED; a FOLLOW_PERSPECTIVE row carries no switch. schema_version stays 0 and revisions are untouched.
-- Rerunnable: a row that already carries state is left alone, so a second run writes nothing.
UPDATE rearm.package_settings
   SET record_data = (record_data - 'enabled')
       || jsonb_build_object('state', CASE WHEN record_data->>'enabled' = 'true' THEN 'ENABLED' ELSE 'DISABLED' END)
 WHERE record_data->>'mode' IS DISTINCT FROM 'FOLLOW_PERSPECTIVE' AND NOT (record_data ? 'state');
UPDATE rearm.package_settings
   SET record_data = record_data - 'enabled'
 WHERE record_data->>'mode' = 'FOLLOW_PERSPECTIVE' AND record_data ? 'enabled';
