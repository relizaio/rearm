-- Enforce one source_code_entry per (vcs, commit) pair.
-- Prevents duplicate SCE rows when two component releases in the same monorepo
-- come from the same commit and race in populateSourceCodeEntryByVcsAndCommit.
-- The pessimistic write lock on vcs_repositories that previously serialized this
-- find-or-create is being removed; this index is the durable replacement.
CREATE UNIQUE INDEX IF NOT EXISTS source_code_entries_vcs_commit_unique
ON rearm.source_code_entries ((record_data->>'vcs'), (record_data->>'commit'))
WHERE record_data->>'commit' IS NOT NULL
  AND record_data->>'vcs' IS NOT NULL;
