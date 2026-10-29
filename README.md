# ViStay Booking Service

Booking lifecycle, inventory, guests, and booking snapshots.

This repository is part of the ViStay Homestay Management platform. Epic 1 establishes a buildable, testable application shell and platform integrations; domain workflows are delivered by their owning epics.

## Pricing authoring API

The GraphQL endpoint exposes immutable, effective-dated pricing authoring operations:

- `createPropertyRateProfileVersion(input)` requires `pricing:update` and an `ALL` or matching `PARTITION(BRANCH, ...)` grant.
- `propertyRateProfileVersion(id)` and `propertyRateProfileVersions(propertyId)` require `pricing:read` with the same scope policy.

`propertyId` identifies the exact rentable apartment/unit in Property. Prices are whole VND amounts represented as strings at the API boundary. Every write requires an idempotency key and change reason. Creating a version does not publish it; calculation, overrides, and publication are tracked separately by SCRUM-355, SCRUM-356, and SCRUM-357.

The canonical v1 defaults and ownership decisions are documented in `contracts/docs/pricing-v1-policy.md`.
