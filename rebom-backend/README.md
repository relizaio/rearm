Flyway command:

docker run --rm -v ./migrations:/flyway/sql flyway/flyway -url=jdbc:postgresql://host.docker.internal:5438/postgres -user=postgres -password=password -defaultSchema=rebom -schemas='rebom' migrate


Test SARIF parser:

```
npx ts-node test/test-sarif.ts
```
