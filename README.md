# Pharmacy Inventory & Sales Management System

Batch-level inventory, purchasing, sales and returns for a family-run retail pharmacy.

![Java](https://img.shields.io/badge/Java-25-b07219)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-6DB33F)
![Thymeleaf](https://img.shields.io/badge/Thymeleaf-server--rendered-005F0F)
![MySQL](https://img.shields.io/badge/MySQL-8.x-4479A1)
![Flyway](https://img.shields.io/badge/Flyway-migrations-CC0200)

## Table of Contents

- [About](#about)
- [My Contributions](#my-contributions)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [Getting Started](#getting-started)
- [Deployment](#deployment)
- [Project Status](#project-status)
- [Team](#team)
- [License](#license)

## About

The pharmacy tracked stock, debts and daily cash on paper. Expiry dates were checked by hand, supplier debt lived in a notebook, and the end-of-shift count was reconciled from memory. This system replaces that with a single internal tool used by the whole shop.

It is an internal application — there is no public storefront and no customer-facing portal. Everything is server-rendered and access is decided per role on every request.

Three roles share one instance:

| Role | Responsibilities |
| --- | --- |
| **Owner** | Full access: approves every slip, manages users and permissions, master data, financial settings |
| **Pharmacist** | Sells at the counter, opens and closes shifts, manages customers, raises return and stock-adjustment slips |
| **Accountant** | Purchase invoices, income and expense vouchers, debt tracking, tax-period reports |

Built as a five-person capstone project (SEP490, FPT University, 2026) for a real pharmacy, from requirements gathering through to a deployed, running system.

## My Contributions

This is a team repository. Of its 1,028 commits, **266 on `main` are mine**. I owned the following modules end to end — requirements, database mapping, business logic, screens:

**Authentication & account security**
Form login with one active session per account, password reset by email (single-use token stored as a SHA-256 hash, 30-minute expiry, 30-second resend cooldown), password change, and live permission enforcement — a role change takes effect on the user's very next request instead of at the next login.

**Partner management** — suppliers, their supplied-product lines and the preferred-supplier rule; customers, with validation that changes shape between individual (national ID) and business (tax code) customers.

**Returns, customer side** — full and partial returns against a sales invoice, seven eligibility rules (return window, prescription invoices, closed tax periods, discounted invoices, already-returned lines, non-returnable product types, superseded invoices), automatic debt netting, a configurable refund rate, and re-stocking into a separate traceable batch rather than back into the original one.

**Returns, supplier side** — returning goods to a supplier against a purchase invoice, FIFO allocation across batches, and offsetting the refund against outstanding supplier debt.

**Stock adjustments** — seven adjustment types (destroy, staff-liable destroy, internal use, sample, gift, stock-count correction, expiry-date correction), sourced either manually or from an approved stock-review slip, with reversible cancellation that refuses to reverse once a later transaction has touched the batch.

**Shift reports** — opening a shift lazily on the first transaction, cash reconciliation at handover, the discrepancy posting into the cash fund on approval, and an interceptor that blocks work until yesterday's unclosed shift is settled.

**Approval queue** — one screen aggregating five slip types awaiting the Owner, with bulk approval.

**Shared UI fragments** — toast notifications, confirmation dialogs, field-level validation messages, live duplicate checking, a double-submit guard and the error pages. These are used across the whole application, including screens owned by other team members.

**Deployment** — provisioned the Ubuntu VM, MySQL, nginx reverse proxy, HTTPS via Let's Encrypt, systemd service and nightly database backups.

### Engineering decisions worth calling out

- **Optimistic locking on stock batches.** Concurrent writes to the same batch silently lost updates. I chose an optimistic `@Version` column over pessimistic row locks because a pessimistic lock only protects the call sites that remember to take it — and the busiest write path, the sales counter, lives in a module I did not own. The version column protects every write path automatically, including other people's code. Verified by firing six concurrent requests at one batch: five committed, one was rejected, and the resulting stock figure was exact.
- **Validation in two layers that share one rule.** The browser blocks bad input early, the service is the real gate, and both call the same helper so the two can never drift apart. Uniqueness is additionally backed by database indexes, because a check-then-write in the service can always be beaten by a race; the service catches the constraint violation and translates it into the same Vietnamese message the user would have seen from the early check.
- **Duplicate submission prevention.** A client-side guard cannot help when the response is lost in flight and the user retries — the write has already committed. Slip creation therefore also compares against a content signature within a short window and returns the existing slip instead of creating a second one.
- **Money is never derived twice.** Refund, debt-offset and replacement-invoice figures are computed once and stored, so a screen, a report and a voucher can never disagree; rounding remainders are pushed onto the last line so line totals always sum to the slip total.

## Features

- **Master data** — product categories, manufacturers, shelf positions, units of measure and pack-size conversions
- **Purchasing** — procurement plans, purchase invoices, goods receipt into batches with expiry tracking
- **Sales** — counter screen with batch selection, prescription handling, split cash/transfer payment, printable invoices and receipts
- **Inventory** — batch-level stock, stock reviews (count, expiry, condition), adjustments, low-stock and near-expiry notifications pushed to the browser
- **Returns** — customer returns with replacement invoices, supplier returns
- **Finance** — income and expense vouchers, customer and supplier debt, shift and daily reports, tax-period snapshots
- **Administration** — users, role permissions, financial settings

## Tech Stack

| Layer | Choice |
| --- | --- |
| Language / runtime | Java 25 |
| Framework | Spring Boot 4.0.6 — Web MVC, Data JPA, Security, Validation, Mail |
| Views | Thymeleaf, server-side rendered; vanilla JavaScript, no frontend framework |
| Database | MySQL 8.x, schema owned by Flyway migrations, `ddl-auto=validate` |
| Media | Cloudinary for product images |
| Realtime | Server-Sent Events for stock and expiry notifications |
| Tests | JUnit 5 and Mockito over the service layer |
| Build | Maven |

Deliberately **not** used: microservices, message queues, a cache tier, Docker, JWT. The system serves about ten users at a single site over relational data that needs transactions; each of those tools would have added moving parts without a problem to solve. Sessions were kept over JWT specifically because the requirements call for instant revocation — one session per account, and a password change must end other sessions immediately.

## Architecture

A single Spring Boot application, rendered on the server. There is no separate frontend project, no REST API for third parties, and one deployable artifact.

```
controller  →  service  →  repository  →  entity  →  MySQL
    ↓
 Thymeleaf template  →  HTML
```

Dependencies point one way: `entity` and `repository` import nothing from the layers above them. JSON endpoints exist only where a screen needs to fetch data without a full page reload.

```
src/main/java/com/example/project/
├── config/          # security, interceptors, scheduling      (7 files)
├── controller/      # one per feature area                   (31 files)
├── service/         # business rules and transactions        (43 files)
├── repository/      # Spring Data JPA                        (32 files)
├── entity/          # JPA entities, one per table            (32 files)
└── dto/             # request and response models       (37 / 118 files)

src/main/resources/
├── db/migration/    # Flyway V1..V14                         (14 files)
└── templates/       # Thymeleaf views and shared fragments   (93 files)
```

## Getting Started

### Requirements

- JDK 25
- MySQL 8.x
- Maven 3.9+

### Setup

1. **Create an empty database and import the schema.** The Flyway migrations in this repository seed reference data; they do not create the tables, and the application starts with `ddl-auto=validate`, so the schema has to exist first.

   ```sql
   CREATE DATABASE hang_ngoc_pisms CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
   ```

   ```bash
   mysql -u root -p hang_ngoc_pisms < docs/schema.sql
   ```

2. **Point the application at your database** in `src/main/resources/application.properties`:

   ```properties
   spring.datasource.url = jdbc:mysql://localhost:3306/hang_ngoc_pisms
   spring.datasource.username = root
   spring.datasource.password = your_password
   ```

3. **Optional environment variables.** Both integrations are optional — leave them unset and the application still starts, only those two features are unavailable.

   | Variable | Enables |
   | --- | --- |
   | `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM` | password-reset email |
   | `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` | product image upload |

4. **Run it.**

   ```bash
   mvn spring-boot:run
   ```

   On first start Flyway applies `V1`–`V14`, seeding roles, accounts, product categories and reference data. The application is then at `http://localhost:8080`, which redirects to the sign-in page.

### Tests

```bash
mvn test
```

## Deployment

The system ran in production for the pharmacy on an Azure Ubuntu 24.04 VM: the Spring Boot jar behind an nginx reverse proxy, HTTPS from Let's Encrypt with automatic renewal, MySQL on the same host, the application managed by systemd with automatic restart, and a nightly `mysqldump` retained for fourteen days. Tomcat was bound to `127.0.0.1` so that a single misconfigured firewall rule could not expose it directly.

The environment was decommissioned after the course ended, so the public URL is no longer live.

## Project Status

Feature-complete and archived. Development finished with the capstone course in September 2026; the repository is kept as a portfolio reference and is not actively maintained.

## Team

Five students, FPT University, SEP490 Summer 2026. The work above under [My Contributions](#my-contributions) is mine; every other module belongs to a teammate, and the commit history shows who wrote what.

## License

Academic coursework, published for portfolio purposes. The code is jointly owned by the project team and is not licensed for reuse or redistribution. Please get in touch before using any part of it.
