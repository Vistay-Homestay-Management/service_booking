# ViStay Booking Service

Booking lifecycle, inventory, guests, and booking snapshots.

This repository is part of the ViStay Homestay Management platform. Epic 1 establishes a buildable, testable application shell and platform integrations; domain workflows are delivered by their owning epics.

## Pricing API

The GraphQL endpoint exposes immutable, effective-dated pricing authoring and calculation operations:

- `createPropertyRateProfileVersion(input)` requires `pricing:update` and an `ALL` or matching `PARTITION(BRANCH, ...)` grant.
- `propertyRateProfileVersion(id)` and `propertyRateProfileVersions(propertyId)` require `pricing:read` with the same scope policy.
- `calculatePropertyPriceQuote(input)` requires `pricing:read` for the requested property and evaluates one explicitly selected immutable profile version. It returns the deterministic VND total, calculation fingerprint, matched/skipped rules, precedence, rounding policy, and price components without writing a quote or booking.

`propertyId` identifies the exact rentable apartment/unit in Property. Prices are whole VND amounts represented as strings at the API boundary. Every write requires an idempotency key and change reason. Calculation is pure and does not select or publish an active version; manual overrides and publication/snapshots remain tracked separately by SCRUM-356 and SCRUM-357.

The canonical v1 defaults and ownership decisions are documented in `contracts/docs/pricing-v1-policy.md`.
