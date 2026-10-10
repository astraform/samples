from banking_remote_domain_python import service
from banking_remote_domain_python.domain_kpi import evaluate_fast_batch_kpis


def test_fast_batch_kpi_characterization_and_legacy_delegation() -> None:
    parameters = {
        "customerCount": 100,
        "hardshipRequestRate": 18,
        "candidateReliefRate": 72,
        "reviewCapacity": 12,
        "averageExposure": 8500,
    }
    expected = {
        "customerCount": 100,
        "hardshipRequests": 18,
        "approvedRelief": 13,
        "baselineApprovedRelief": 8,
        "unresolvedHardshipRequests": 5,
        "baselineUnresolvedHardshipRequests": 10,
        "reviewCapacity": 12,
        "exposureAtRisk": 42500,
        "averageExposure": 8500,
        "customerImpactRisk": 19,
        "liquidityBufferPressure": 4,
        "evidenceSignals": 5,
    }

    assert evaluate_fast_batch_kpis(parameters) == expected
    assert service.wind_tunnel_metrics(parameters) == expected
