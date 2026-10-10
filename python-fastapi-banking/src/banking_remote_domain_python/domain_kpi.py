from __future__ import annotations

from typing import Any, Mapping


def evaluate_fast_batch_kpis(parameters: Mapping[str, Any]) -> dict[str, int]:
    """Preserve the legacy Fast Batch banking KPI calculation as a pure function."""
    customer_count = int(parameters["customerCount"])
    hardship_requests = round(customer_count * int(parameters["hardshipRequestRate"]) / 100)
    approved_relief = round(hardship_requests * int(parameters["candidateReliefRate"]) / 100)
    baseline_approved_relief = round(hardship_requests * 45 / 100)
    unresolved = max(0, hardship_requests - approved_relief)
    baseline_unresolved = max(0, hardship_requests - baseline_approved_relief)
    exposure = unresolved * int(parameters["averageExposure"])
    capacity_gap = max(0, unresolved - int(parameters["reviewCapacity"]))
    customer_impact_risk = min(
        100,
        round((unresolved / max(1, hardship_requests)) * 70 + capacity_gap * 3),
    )
    return {
        "customerCount": customer_count,
        "hardshipRequests": hardship_requests,
        "approvedRelief": approved_relief,
        "baselineApprovedRelief": baseline_approved_relief,
        "unresolvedHardshipRequests": unresolved,
        "baselineUnresolvedHardshipRequests": baseline_unresolved,
        "reviewCapacity": int(parameters["reviewCapacity"]),
        "exposureAtRisk": exposure,
        "averageExposure": int(parameters["averageExposure"]),
        "customerImpactRisk": customer_impact_risk,
        "liquidityBufferPressure": min(100, round(exposure / 10000)),
        "evidenceSignals": 5,
    }
