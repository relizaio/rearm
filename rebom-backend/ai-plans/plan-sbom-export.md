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
- [x] Fix or improve bomToCsv function.
- [x] Implement full CSV export logic for SBOM (handle nested/component data, formatting, etc).
- [x] Test CSV export functionality with CycloneDX BOMs.
- [ ] Document the CSV export process for users.
- [ ] Implement Excel export functionality for SBOM.
- [ ] Test Excel export features.
- [ ] Document the Excel export process for users.

## Current Goal
Test the CSV export functionality with CycloneDX BOMs.
