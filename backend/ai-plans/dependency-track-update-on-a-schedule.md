# Plan: Scheduled Update of Vulnerability and Violation Data from Dependency Track

## Objective
Automatically update vulnerability and violation data from Dependency Track on a regular schedule to keep the system's security information up to date.

## Status: ✅ COMPLETED
The Dependency Track scheduled synchronization feature has been fully implemented and is operational.

## Steps
- [x] **Create a field to store last sync time**
    - [x] Added `lastDtrackSync` field of type `ZonedDateTime` to `SystemInfoData` class
    - [x] Implemented `getLastDtrackSync()` method in `SystemInfoService`
    - [x] Implemented `setLastDtrackSync(ZonedDateTime)` method in `SystemInfoService`

- [x] **Design Update Workflow:**
    - [x] Define what data needs to be fetched from Dependency Track (vulnerabilities, violations, etc).
    - [x] Determine the mapping between Dependency Track data and the system's data model.
    - [x] Designed incremental sync workflow using last sync timestamps
    - [x] Implemented unified project UUID retrieval and artifact processing

- [x] **Implement Scheduled Task:**
    - [x] Use a scheduling mechanism (e.g., Spring @Scheduled) to run the update at a defined interval (daily at 21:50).
    - [x] Added `SYNC_DEPENDENCY_TRACK_DATA` advisory lock key to prevent concurrent execution
    - [x] Implemented `syncDependencyTrackData()` method in `SchedulingService`
    - [x] Added `OrganizationService` dependency to retrieve all organizations
    - [x] Configured to run once daily at 21:50 (9:50 PM) using cron expression
    - [x] Includes comprehensive error handling and logging per organization

- [x] **Integrate Dependency Track API:**
    - [x] Implement service(s) to call Dependency Track's API endpoints for vulnerabilities and violations.
    - [x] Added `retrieveUnsyncedDtrackVulnerabilities()` private method to fetch vulnerability project UUIDs
    - [x] Added `retrieveUnsyncedDtrackViolations()` private method to fetch violation project UUIDs
    - [x] Added `retrieveUnsyncedDtrackProjects()` public wrapper method that combines both methods
    - [x] Handle authentication and error handling for API calls.
    - [x] Implemented date filtering using ISO-8601 format ("YYYY-MM-DD")
    - [x] Return `Set<UUID>` for automatic deduplication
    - [x] Added comprehensive logging for monitoring and debugging

- [x] **Store/Update Data:**
    - [x] Implemented `syncUnsyncedDependencyTrackData()` orchestration method in `ArtifactService`
    - [x] Retrieves unsynced projects and processes associated artifacts
    - [x] Calls existing `fetchDependencyTrackDataForArtifact()` method for each artifact
    - [x] Updates last sync timestamp after successful completion
    - [x] Handles errors gracefully with detailed logging
    - [x] Returns count of processed artifacts for monitoring

- [x] **Testing & Monitoring:**
    - [x] Added comprehensive logging at debug, info, warn, and error levels
    - [x] Implemented error isolation (errors in one org don't affect others)
    - [x] Added advisory locks to prevent concurrent execution
    - [x] Tested with live system - successfully processes organizations and artifacts
    - [x] Monitoring through log aggregation and artifact processing counts


## Sample Integration Calls
curl -q -H "X-API-Key: odt_" "https://dtrackdev.rearmhq.com/api/v1/finding?pageNumber=1&pageSize=10000&attributedOnDateFrom=2025-07-15"
curl -q -H "X-API-Key: odt_" "https://dtrackdev.rearmhq.com/api/v1/violation?pageNumber=1&pageSize=10000&occurredOnDateFrom=2025-07-15"

## Example Schedule (Spring)
```java
@Scheduled(cron = "0 0 * * * *") // Runs every hour
public void updateVulnerabilityDataFromDependencyTrack() {
    // Implementation here
}
```

## Next Steps
- Confirm workflow and requirements with stakeholders.
- Implement the scheduled update service.
- Monitor and iterate as needed.
