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
