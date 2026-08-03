package com.ghostchu.quickshop.addon.exchange.core.trust;

/** Progressive response to repeated counterparty behavior. */
public enum BehaviorRiskAction {
  NORMAL(0),
  OBSERVE(10),
  ALERT(20),
  PAIR_COOLDOWN(30);

  private final int severity;

  BehaviorRiskAction(int severity) {
    this.severity = severity;
  }

  public boolean isEscalationFrom(BehaviorRiskAction previous) {
    if (previous == null) {
      throw new IllegalArgumentException("previous behavior action is required");
    }
    return severity > previous.severity;
  }
}
