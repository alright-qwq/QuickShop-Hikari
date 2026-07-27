The original Task 2 implementer report was not retained. Review the Task 2 diff and current
call sites as the authoritative implementation evidence; do not infer unrecorded test results.

## Risk enforcement remediation

### Scope

- Limit orders outside the live price cage now reject with `PRICE_OUTSIDE_CAGE`.
- Preflight applies the rate limit and, after startup recovery has established a committed runtime
  snapshot, rejects cage violations and self trades before settlement begins.
- Settlement repeats cage and self-trade checks after loading the transaction's current market
  state and open orders.
- Non-open markets now reject with `MARKET_NOT_OPEN`; self trades reject with `SELF_TRADE`
  before reservation or order insertion.
- A durable duplicate request still returns its stored receipt when it reaches the rate limit.

### Red evidence

`mvn -pl addon/exchange -Dtest=PersistentOrderServiceTest test` initially compiled and ran 33
tests with 4 expected failures:

- non-open market returned `market diamond-usd is CLOSED` instead of `MARKET_NOT_OPEN`;
- an outside-cage limit order entered settlement instead of rejecting;
- a limit price that became outside the cage in the transaction snapshot was accepted;
- a matching order against the submitter's own resting order returned normally instead of
  `SELF_TRADE`.

After adding the idempotency regression, the targeted test for a rate-limited duplicate retry
failed with `IllegalStateException: RATE_LIMITED`, proving that moving rate checks to preflight
would otherwise change the stored-receipt contract.

### Green evidence

Final focused verification:

```text
mvn -pl addon/exchange -Dtest=OrderRiskServiceTest,MarketRiskTest,PersistentOrderServiceTest test
Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The same command compiled the exchange module and its 44 test sources successfully. Maven
reported existing project-model and dependency warnings only; no test or compilation failures.
