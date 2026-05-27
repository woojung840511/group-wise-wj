# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
./gradlew clean build          # Full build with tests
./gradlew build -x test        # Build without tests
./gradlew test                 # Run all tests
./gradlew test --tests "wj.flab.group_wise.service.GroupPurchaseServiceTest"  # Run specific test class
./gradlew test --tests "wj.flab.group_wise.service.GroupPurchaseServiceTest.createGroupPurchase"  # Run specific test method
```

Tests use `@ActiveProfiles("test")` with H2 in-memory database (`application-test.yml`). Flyway is disabled in test profile; Hibernate auto-creates the schema.

## Architecture Overview

This is a **group purchasing (공동구매) service API** built with Spring Boot 3.4.1 / Java 17, following DDD principles with event-driven architecture.

### Layer Structure

- **controller/** — REST API endpoints (`/api/auth/**` is public, all else requires JWT auth)
- **service/domain/** — Domain services with `@Transactional` business logic
- **service/event/** — Event publisher/listener for domain events (async notification delivery)
- **domain/** — JPA entities with encapsulated business logic (factory methods, state transitions)
- **domain/groupPurchase/event/** — Domain events published on group purchase state changes
- **dto/** — Request/response DTOs with validation annotations
- **repository/** — Spring Data JPA repositories; `GroupPurchaseRepositoryImpl` uses QueryDSL for complex queries
- **config/** — Security (JWT filter chain), async thread pool, JPA auditing

### Key Aggregates

**GroupPurchase** — Core aggregate root. Status lifecycle: `PENDING → ONGOING → COMPLETED_SUCCESS/COMPLETED_FAILURE/CANCELLED`. State transitions publish domain events that trigger notifications via `GroupPurchaseEventListener` (runs async with `@Async`).

**Product** — Manages attributes (e.g., Color, Size), attribute values (with additional pricing), and stock via cartesian product combinations (`ListUtils.cartesianProduct`). Stock is tracked per attribute-value combination through `ProductStock` ↔ `ProductAttributeValueStock` mapping.

**GroupPurchaseMember** — Join table between GroupPurchase and Member with wishlist/participation flags and ordered items (`GroupPurchaseItem`). Access is `protected` — managed only through GroupPurchase aggregate methods.

### Key Technical Details

- **QueryDSL**: Q-types generated to `src/main/generated/`. Run `./gradlew clean` to regenerate.
- **Database**: MySQL (local/prod) with Flyway migrations in `src/main/resources/db/migration/`. Production uses `ddl-auto: validate`.
- **Auth**: Stateless JWT (1-hour expiry). `JwtAuthenticationFilter` runs before `UsernamePasswordAuthenticationFilter`.
- **Async**: `notificationTaskExecutor` (5-15 threads, queue 100) handles notification delivery. CallerRunsPolicy as rejection handler.
- **Batch fetching**: Global `default_batch_fetch_size: 100` to mitigate N+1 queries. All relationships use `FetchType.LAZY`.
- **Environment variables**: `DB_HOST`, `DB_NAME_PROD`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET_KEY` (loaded via spring-dotenv).
- **Deployment**: Blue-green via GitHub Actions → EC2 with Nginx port switching (8080/8081).
