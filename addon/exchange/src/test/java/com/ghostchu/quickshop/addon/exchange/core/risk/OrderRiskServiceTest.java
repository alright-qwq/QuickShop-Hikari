package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRiskServiceTest {
  @Test
  void rejectsSixthOperationInSecondAndSixtyFirstInMinute() {
    OrderRateLimiter limiter = new OrderRateLimiter(5, 60);
    UUID account = UUID.randomUUID();
    Instant now = Instant.EPOCH;
    for (int index = 0; index < 5; index++) {
      assertThat(limiter.allow(account, now)).isTrue();
    }
    assertThat(limiter.allow(account, now)).isFalse();
    assertThat(limiter.allow(account, now.plusSeconds(1))).isTrue();
  }

  @Test
  void enforcesAccountExposureLimits() {
    AccountRiskSnapshot snapshot = new AccountRiskSnapshot(
        100_000, new BigDecimal("10000000.00"), 100);

    assertThat(snapshot.canAddHolding(1, 100_000)).isFalse();
    assertThat(snapshot.canFreeze(new BigDecimal("0.01"), new BigDecimal("10000000.00"))).isFalse();
    assertThat(snapshot.canOpenOrder(100)).isFalse();
  }
}
