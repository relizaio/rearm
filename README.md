# ReARM
ReARM SBOM / xBOM and Release Management - Community Edition

## Developing ReARM

1. Create a docker container for database:
```
docker run --name rearm-postgres -d -p 5440:5432 -e POSTGRES_PASSWORD=relizaPass postgres:16
```