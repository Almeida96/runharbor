# System map

The example application has three services: accounts, checkout, and notifications.
Accounts owns login sessions. Checkout owns payment confirmation. Notifications delivers asynchronous messages.

## Data ownership

Each service owns its schema. Cross-service database writes are not supported.
Checkout stores a payment provider's reference, not card details.
Notification retries use a stable message ID to avoid duplicate deliveries.

## Operational ownership

The release owner coordinates deploys. The database owner reviews migration plans.
During an incident, the incident commander coordinates recovery and maintains the incident timeline.

## Onboarding exercise

Trace a checkout request through the three services, then identify which alerts detect a payment confirmation failure.
Use the release and incident runbooks to describe when a rollout must stop.
