# Incident response

## Severity

SEV1: payment confirmation or account access is unavailable to most users.
SEV2: a core operation is degraded for a subset of users, with a viable workaround.
SEV3: a non-core operation is degraded without a material loss of service.

## First fifteen minutes

1. The on-call engineer opens an incident record and assigns an incident commander.
2. The commander assigns investigation, communication, and mitigation owners.
3. Preserve relevant application logs, deployment identifiers, and dashboard links.
4. Check recent deployments before modifying infrastructure.
5. Publish an internal status update every fifteen minutes while the incident is active.

## Recovery

Recovery requires successful smoke tests and an error rate below 1% for fifteen minutes.
The incident commander declares recovery and records the evidence supporting that decision.
A follow-up review should distinguish observed facts, hypotheses, and unverified assumptions.

## Known gaps

This handbook does not define customer notification templates or contractual response-time obligations.
Do not infer those requirements from the internal update cadence.
