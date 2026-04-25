# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

LinkStash is a Spring Boot 3.4 URL shortener with click analytics. Java 25, Maven, JPA + H2 (in-memory), Flyway migrations.

## Common commands

```bash
# Run the app (starts on http://localhost:8080)
./mvnw spring-boot:run

# Build (skip tests)
./mvnw -DskipTests package

# All tests
./mvnw test

# Single test class / single method
./mvnw test -Dtest=LinkServiceTest
./mvnw test -Dtest=LinkControllerTest#createLink_returnsShortCode
```

H2 web console is enabled at `/h2-console` (jdbc URL `jdbc:h2:mem:linkstash`, blank password) when the app is running.

Surefire is configured with `-Dnet.bytebuddy.experimental=true` so Mockito works on Java 25 — keep that argLine if you touch `pom.xml`.

## Architecture

Standard layered Spring Boot, all under `com.linkstash`:

- `controller/LinkController` — REST endpoints. Note the routing split: management endpoints live under `/api/v1/links/...`, but the redirect handler is mounted at the root (`GET /{shortCode}`). Any new top-level routes risk shadowing short codes.
- `service/LinkService` — business logic + 8-char base62 short-code generation with collision retry against the DB. The base URL returned in `LinkResponse.shortUrl` comes from the `linkstash.base-url` config property injected via `@Value`.
- `repository/LinkRepository` — Spring Data JPA, derived queries (`findByShortCode`, `existsByShortCode`).
- `domain/Link` — JPA entity mapped to `links` table.
- `dto/` — request/response Java records.
- `exception/GlobalExceptionHandler` — `@RestControllerAdvice` mapping `NoSuchElementException` → 404 and `IllegalArgumentException` → 400. Service code throws these directly; do not add `try/catch` in the controller.

### Database / schema changes

- `spring.jpa.hibernate.ddl-auto: validate` — Hibernate will refuse to start if the entity does not match the schema. **Schema is owned by Flyway**, not Hibernate.
- Any change to `Link.java` requires a new migration in `src/main/resources/db/migration/` (e.g. `V2__add_xxx.sql`). Never edit `V1__init.sql` once it has been applied.
- Storage is H2 in-memory: every restart wipes data. Tests rely on this for isolation.

### Click counting

`LinkController.redirect` calls `incrementClick` then `getOriginalUrl` — two separate transactions, read-modify-write on `click_count` with no locking. Fine for the demo, but be aware before adding concurrency-sensitive features.

## Conventions
- Constructor injection only. Never field injection (no `@Autowired` on fields).
- DTOs are Java records.
- Entities live in `domain/`.
- Use `@Transactional` on service methods that write.
- Validation with `@Valid` + `jakarta.validation` annotations on controllers.
- Use `Instant` for timestamps, never `Date` or `LocalDateTime` for stored times.

## Database
- H2 in-memory for dev.
- Flyway migrations in `src/main/resources/db/migration/`.
- Never use `ddl-auto: update`. Always write a migration.
- Index columns used in WHERE clauses.

## Testing
- MockMvc for controller tests.
- `@SpringBootTest` for integration tests.
- Test the failure paths, not just happy paths.

## What not to do
- Don't add Lombok.
- Don't add caching, async, or messaging unless the task requires it.
- Don't push to `main`. Always work on a feature branch.
- Don't modify `application.yml` unless the task requires config changes.
