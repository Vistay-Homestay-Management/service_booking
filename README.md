# ViStay Booking Service

Booking lifecycle, inventory, guests, and booking snapshots.

This repository is part of the ViStay Homestay Management platform. Epic 1 establishes a buildable, testable application shell and platform integrations; domain workflows are delivered by their owning epics.

## Pricing API

The GraphQL endpoint exposes immutable, effective-dated pricing authoring and calculation operations:

- `createPropertyRateProfileVersion(input)` requires `pricing:update` and an `ALL` or matching `PARTITION(BRANCH, ...)` grant.
- `propertyRateProfileVersion(id)` and `propertyRateProfileVersions(propertyId)` require `pricing:read` with the same scope policy.
- `calculatePropertyPriceQuote(input)` requires `pricing:read` for the requested property and evaluates one explicitly selected immutable profile version. It returns the deterministic VND total, calculation fingerprint, matched/skipped rules, precedence, rounding policy, and price components without writing a quote or booking.
- `applyPropertyPriceOverride(input)` requires branch-scoped `pricing:override` and records one immutable, expiring reduction artifact; reductions above 20% require `pricing:approve-large-override`.
- `previewPropertyRateProfilePublication(rateProfileVersionId)` returns the canonical `PRICING_PUBLICATION_V1` profile fingerprint and current property publication head.
- `publishPropertyRateProfileVersion(input)` requires `pricing:approve`, checks the expected publication head under a property lock, and appends a monotonic property publication revision plus outbox event.
- `previewPublishedPropertyPriceQuote(input)` selects the published profile that covers the complete stay; clients cannot choose the active profile.
- `createPropertyPriceQuoteSnapshot(input)` persists the exact publication, input, base/final explanation, authorization evidence, and optional exact unexpired override. Snapshot pricing evidence is immutable and an override can be consumed only once.
- `PricingPublicationReconciliationService.ReconcilePricingPublications` is the internal mTLS/deadline-bound recovery read for Kafka revision gaps. It returns one property's current head plus an ascending, bounded page after the consumer's durable revision; pricing reconciliation has its own SPIFFE caller allow-list.

`propertyId` identifies the exact rentable apartment/unit in Property. Prices are whole VND amounts represented as strings at the API boundary. Every write requires an idempotency key, reason, and correlation ID. Explicit-version calculation remains a pure draft preview; published preview and snapshot creation select active pricing server-side. Historical snapshots are read from stored evidence and are never recalculated from current rules.

The canonical v1 defaults and ownership decisions are documented in `contracts/docs/pricing-v1-policy.md`.

## Inventory availability API

`propertyAvailability(input: PropertyAvailabilityInput!)` is an
authenticated, read-only GraphQL observation for an exact rentable unit. It
requires `inventory:read`, always resolves the Property-owned branch (including
for `ALL` grants), and accepts only global `ALL` or a matching
`PARTITION(BRANCH, ...)` grant. Unknown Property records and unavailable
Property/authorization dependencies fail closed.

The boundary uses the stable `inventory-v1` failures
`INVALID_INVENTORY_RANGE`, `UNAUTHENTICATED`, `INVENTORY_READ_FORBIDDEN`,
`PROPERTY_NOT_FOUND`, `PROPERTY_PARTITION_UNMAPPED`,
`PROPERTY_PARTITION_UNAVAILABLE`, `AUTHORIZATION_UNAVAILABLE`, and
`INVENTORY_OBSERVATION_FAILED`.

The range is half-open `[checkInDate, checkOutDate)`. `TENTATIVE` and `ACTIVE`
BOOKING, MAINTENANCE, CLEANING, OWNER_USE, MANUAL, and CHANNEL rows block the
unit; `RELEASED` and `CANCELLED` rows are returned only as sanitized historical
overlap evidence and do not block it. The response contains the monotonic
per-property inventory revision, Property branch/membership version, observation
time, and sanitized type/status/range/channel-provider evidence. It never
contains booking IDs, external channel references, reasons, guest/customer data,
or other PII. This read creates no hold; booking commands must revalidate and
rely on the database exclusion constraint for concurrency safety.

## Inventory block command API

`createInventoryBlock(input)` and `releaseInventoryBlock(input)` are the
authenticated Backoffice write boundary for explicit operator blocks. Both
require `inventory:block`, an idempotency key, an expected property inventory
revision, a reason, and a correlation ID. `SUPER_ADMIN` needs explicit `ALL`;
`MANAGER` needs the exact current `PARTITION(BRANCH, ...)`. Property authority
is resolved for both roles and captured in the immutable command outcome.

Create accepts only MAINTENANCE, CLEANING, OWNER_USE, or MANUAL and creates an
ACTIVE half-open date range. Release accepts only an operator block currently in
TENTATIVE or ACTIVE state. BOOKING and CHANNEL lifecycle rows cannot be changed
through these mutations. Exact retries return the original command result and
do not advance the inventory revision or emit another audit/outbox fact.

Every successful command atomically persists the block transition, monotonic
property revision, immutable command evidence, audit row, and
`vistay.booking.inventory-block-changed.v1` outbox event. The database exclusion
constraint remains the final overlap authority. SCRUM-361, rather than this
write API, owns stale/expired/orphan repair and revision-gap reconciliation.

### Inventory overlap concurrency certification

`InventoryOverlapConcurrencyIntegrationTest` exercises the GiST exclusion
constraint with independent JDBC transactions. An overlapping TENTATIVE or
ACTIVE writer waits for the uncommitted winner: winner commit produces SQLSTATE
`23P01` for the loser, while winner rollback lets the waiter commit. The suite
also proves adjacent half-open ranges and overlapping ranges on different
properties can both commit, and asserts the exact durable block count plus
`inventory_property_revisions` after every path.

Run the focused certification with:

```powershell
.\gradlew.bat test --tests `
  com.vistay.booking.application.InventoryOverlapConcurrencyIntegrationTest
```

Application availability checks are observational only. All Booking-owned
writers must persist their block in the same transaction as their owning state
change and treat `ex_inventory_block_overlap` as the final commit authority.
