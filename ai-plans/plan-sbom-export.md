# Plan: Export Human-Readable SBOM (CSV, Excel)

## Notes
- User requested to add export functionality for human-readable SBOM.
- Start with CSV export, then add Excel export.
- For CSV stage, create new GraphQL interfaces alongside existing ones (bomById, rawBomId, mergeAndStoreBoms) to return CSV-formatted BOMs.
- New GraphQL endpoints (bomByIdCsv, rawBomIdCsv, mergeAndStoreBomsCsv) have been added.
- Initial CSV export stub implemented in backend (bomToCsv).
- Add a task to fix or improve bomToCsv function as needed.

## Task List
- [x] Design the SBOM export feature interface.
- [x] Create new GraphQL endpoints for CSV export (bomById, rawBomId, mergeAndStoreBoms).
- [x] Implement CSV export functionality for SBOM (basic stub).
- [ ] Fix or improve bomToCsv function.
- [ ] Implement full CSV export logic for SBOM (handle nested/component data, formatting, etc).
- [ ] Implement Excel export functionality for SBOM.
- [ ] Test CSV and Excel export features.
- [ ] Document the export process for users.

## Current Goal
Design and implement full CSV export logic.
