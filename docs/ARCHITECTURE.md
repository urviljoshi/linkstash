# LinkStash Architecture

## Overview

LinkStash is a Spring Boot 3.4 URL shortener with click analytics. It uses Java 25, Maven, JPA + H2 (in-memory), and Flyway migrations.

## Package Structure

```
com.linkstash
├── LinkStashApplication.java          # @SpringBootApplication entry point
├── controller/
│   └── LinkController.java            # REST layer — two route namespaces (see below)
├── domain/
│   └── Link.java                      # JPA entity → links table
├── dto/
│   ├── CreateLinkRequest.java         # record: url
│   ├── LinkResponse.java              # record: shortCode, shortUrl, originalUrl
│   └── LinkStatsResponse.java         # record: shortCode, originalUrl, clickCount, createdAt
├── exception/
│   └── GlobalExceptionHandler.java    # @RestControllerAdvice: NoSuchElement→404, IllegalArg→400
├── repository/
│   └── LinkRepository.java            # Spring Data JPA: findByShortCode, existsByShortCode
└── service/
    └── LinkService.java               # Business logic: 8-char base62 code generation + CRUD
```

## Endpoints

| Method | Path                              | Description                    |
|--------|-----------------------------------|--------------------------------|
| POST   | /api/v1/links                     | Create short link              |
| GET    | /{shortCode}                      | Redirect (302) to original URL |
| GET    | /api/v1/links/{shortCode}/stats   | Fetch click stats              |
| DELETE | /api/v1/links/{shortCode}         | Delete a short link            |

> **Route split**: management endpoints live under `/api/v1/links/...`; the redirect handler is at root `/{shortCode}`. New top-level routes risk shadowing short codes.

## Database Schema (Flyway-managed)

### V1__init.sql — `links` table

| Column       | Type          | Notes                        |
|--------------|---------------|------------------------------|
| id           | BIGINT        | PK, auto-increment           |
| short_code   | VARCHAR(8)    | UNIQUE, NOT NULL             |
| original_url | VARCHAR(2048) | NOT NULL                     |
| created_at   | TIMESTAMP     | NOT NULL                     |
| click_count  | BIGINT        | NOT NULL, DEFAULT 0          |

- `spring.jpa.hibernate.ddl-auto: validate` — Hibernate validates against schema; **never** let Hibernate own the schema.
- Data is H2 in-memory, wiped on restart.

## Key Design Decisions

- **Constructor injection only** — no `@Autowired` on fields.
- **DTOs are Java records**.
- **Service throws** `NoSuchElementException` (→ 404) and `IllegalArgumentException` (→ 400); controller has no try/catch.
- **`@Transactional`** on service methods that write.
- **`Instant`** for all stored timestamps.
- **Short-code generation**: 8-char base62 with collision retry against DB.
- **Click counting**: two separate transactions (incrementClick then getOriginalUrl) — no locking (fine for demo).

## Testing

- `LinkControllerTest` — `@SpringBootTest` + `@AutoConfigureMockMvc` (MockMvc, full Spring context, real H2).
- `LinkServiceTest` — `@ExtendWith(MockitoExtension.class)` (unit, mocked repository).
- Surefire configured with `-Dnet.bytebuddy.experimental=true` for Mockito on Java 25.

---

## Issue #1: Per-API-key Rate Limiting + Short Link Expiration

### Files to Create / Modify

#### New files

| File | Purpose |
|------|---------|
| `src/main/resources/db/migration/V2__add_api_keys.sql` | Create `api_keys` table; seed `test-key-12345` |
| `src/main/resources/db/migration/V3__add_link_expiry.sql` | Add `expires_at` column to `links` |
| `src/main/java/com/linkstash/domain/ApiKey.java` | JPA entity for `api_keys` table |
| `src/main/java/com/linkstash/repository/ApiKeyRepository.java` | `findByKeyValueAndActiveTrue` |
| `src/main/java/com/linkstash/ratelimit/RateLimiter.java` | In-memory token-bucket / sliding-window per API key (10 req/min) |
| `src/main/java/com/linkstash/filter/ApiKeyFilter.java` | `OncePerRequestFilter` — validates `X-API-Key` on POST /api/v1/links and enforces rate limit; returns 401 / 429 |
| `src/test/java/com/linkstash/ratelimit/RateLimiterTest.java` | Unit tests: under/at/over limit, multi-key isolation |
| `src/test/java/com/linkstash/controller/LinkExpiryIntegrationTest.java` | Integration tests: active link, expired link, no expiry; X-API-Key enforcement |

#### Modified files

| File | Change |
|------|--------|
| `src/main/java/com/linkstash/domain/Link.java` | Add `expiresAt` (`Instant`, nullable) field + getter/setter |
| `src/main/java/com/linkstash/dto/CreateLinkRequest.java` | Add optional `expiresAt` field (record component) |
| `src/main/java/com/linkstash/dto/LinkResponse.java` | Add `expiresAt` field |
| `src/main/java/com/linkstash/dto/LinkStatsResponse.java` | Add `expiresAt` field |
| `src/main/java/com/linkstash/service/LinkService.java` | Propagate `expiresAt` on create; check expiry in `getOriginalUrl` (throw new exception type or `410`-mapped exception) |
| `src/main/java/com/linkstash/controller/LinkController.java` | Handle `410 Gone` on expired redirect; accept (but not validate) `X-API-Key` (validation is in filter) |
| `src/main/java/com/linkstash/exception/GlobalExceptionHandler.java` | Add handler for `LinkExpiredException` → 410 with `{"error":"link expired"}` |
| `src/test/java/com/linkstash/controller/LinkControllerTest.java` | Add `X-API-Key` header to existing POST requests |
| `src/test/java/com/linkstash/service/LinkServiceTest.java` | Update for new `CreateLinkRequest` shape and expiry logic |

### Schema Changes

**V2__add_api_keys.sql**
```sql
CREATE TABLE api_keys (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_value  VARCHAR(64) NOT NULL UNIQUE,
    name       VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    active     BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_api_keys_key_value ON api_keys(key_value);
INSERT INTO api_keys (key_value, name, created_at, active)
VALUES ('test-key-12345', 'Test Key', CURRENT_TIMESTAMP, TRUE);
```

**V3__add_link_expiry.sql**
```sql
ALTER TABLE links ADD COLUMN expires_at TIMESTAMP NULL;
```

### Rate Limiter Design

Custom in-memory sliding-window (or fixed-window refill) counter:
- `ConcurrentHashMap<String, WindowState>` keyed by API key value.
- 10 requests per 60-second window.
- `Retry-After` = seconds until window resets.
- No external dependencies needed.

### Expiry Logic

In `LinkService.getOriginalUrl` (called by redirect):
```
if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(Instant.now())) {
    throw new LinkExpiredException();
}
```

New exception `LinkExpiredException` mapped to `410 Gone` + `{"error":"link expired"}` in `GlobalExceptionHandler`.
