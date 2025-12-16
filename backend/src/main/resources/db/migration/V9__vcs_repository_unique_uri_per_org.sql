-- Add unique constraint on VCS repository URI per organization
-- This ensures no duplicate VCS repositories with the same URI within an organization
-- The constraint applies to ALL repositories (active and archived) to prevent any duplicates

CREATE UNIQUE INDEX IF NOT EXISTS vcs_repositories_org_uri_unique 
ON rearm.vcs_repositories ((record_data->>'org'), (record_data->>'uri'));
