# FirstClub Membership Program — Setup Guide

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Java | 17+ | `java -version` |
| Maven | 3.6+ | `mvn -version` |
| Docker | any | `docker --version` |

---

## Quick Start (local development)

### 1. Start PostgreSQL

```bash
docker-compose up -d postgres
```

This starts a PostgreSQL 15 container on port 5432 with the database `membershipdb`.

### 2. Run the application (dev profile)

```bash
mvn spring-boot:run -Dspring.profiles.active=dev
```

Flyway runs on startup and applies `V1__init_schema.sql` then `V2__add_tier_eligibility.sql`.
The application seeds three membership tiers and nine plans automatically.
In the `dev` profile, three sample users are also created for the demo flow.

### 3. Open Swagger UI

```
http://localhost:8080/swagger-ui.html
```

---

## Environment variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `membershipdb` | Database name |
| `DB_USERNAME` | `postgres` | DB user |
| `DB_PASSWORD` | `postgres` | DB password |
| `SERVER_PORT` | `8080` | Application HTTP port |

Override any of these to point the app at a remote database.

---

## Demo flow (Swagger)

1. **GET** `/api/v1/users` — view the three pre-loaded sample users
2. **GET** `/api/v1/membership/plans` — browse all 9 plans across Silver / Gold / Platinum
3. **POST** `/api/v1/membership/subscriptions` — subscribe user 1 to plan 4 (Gold Monthly):
   ```json
   { "userId": 1, "planId": 4, "autoRenewal": true }
   ```
4. **GET** `/api/v1/membership/subscriptions/user/1/active` — see the active subscription
5. **GET** `/api/v1/users/1/tier-eligibility` — evaluate which tier the user qualifies for
6. **GET** `/api/v1/membership/health` — system health and live metrics
7. **GET** `/api/v1/membership/analytics` — revenue and tier-popularity breakdown

---

## Running tests

Tests use an H2 in-memory database with Flyway disabled.
No running PostgreSQL is required to run tests.

```bash
mvn test
```

---

## Production deployment

Set `SPRING_PROFILES_ACTIVE=prod` and provide all `DB_*` environment variables
pointing to a production PostgreSQL instance. Flyway will apply any pending
migrations automatically on startup.

```bash
SPRING_PROFILES_ACTIVE=prod \
DB_HOST=<host> DB_NAME=<db> DB_USERNAME=<user> DB_PASSWORD=<secret> \
java -jar target/membership-program-1.0.0.jar
```

---

## Key endpoints

| Method | URL | Description |
|--------|-----|-------------|
| GET | `/api/v1/membership/plans` | All active plans |
| GET | `/api/v1/membership/tiers` | All tiers with benefits |
| POST | `/api/v1/membership/subscriptions` | Create subscription |
| PUT | `/api/v1/membership/subscriptions/{id}/cancel` | Cancel subscription |
| PUT | `/api/v1/membership/subscriptions/{id}/upgrade` | Upgrade plan |
| GET | `/api/v1/users/{id}/tier-eligibility` | Tier eligibility evaluation |
| GET | `/api/v1/membership/health` | System health + metrics |
| GET | `/api/v1/membership/analytics` | Business analytics |
| GET | `/actuator/health` | Spring Boot health check |
