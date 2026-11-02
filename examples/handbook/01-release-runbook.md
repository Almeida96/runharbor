# Release runbook

This fictional handbook is provided for local demonstrations.

## Before deployment

- The release owner checks that CI passed for the exact commit being deployed.
- The database owner verifies the latest backup and records a restore-test result.
- The on-call engineer confirms that dashboards and alerts are receiving data.
- A second engineer reviews migration compatibility with the previous application version.

## Rollout

Deploy to staging first. Run the checkout and account-access smoke tests.
Start the production rollout with 10% of traffic and observe it for ten minutes.
The release owner may proceed to 50% and then 100% only when the error rate stays below 1%.

## Rollback

Roll back when the error rate exceeds 2% for five consecutive minutes.
The incident commander owns the rollback decision.
Pause the rollout immediately if payment confirmation is unavailable, regardless of the aggregate error rate.
Application rollback does not reverse a database migration. Escalate incompatible schema changes to the database owner.

## Completion

Record the deployed commit, migration version, start/end times, and observed error rate in the release log.
Keep the release channel open for thirty minutes after reaching 100% traffic.
