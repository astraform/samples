package banking.simulator.remote.domain.scenariolab;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public final class ScenarioLabRunParameters {

    private String presetId;
    private String productCode;
    private String baselineRate;
    private String candidateRate;
    private String baselineLimit;
    private String candidateLimit;
    private String candidateStimulusAmount;
    private CandidateCrashEnum candidateCrash;
    private CandidateHardshipScanEnum candidateHardshipScan;
    private String candidateHardshipThreshold;
    private String candidateHardshipSubsidyAmount;
    private CandidateFraudAlertEnum candidateFraudAlert;
    private String candidateFraudSeverity;
    private String candidateFraudChannel;
    private String candidateFraudMessage;
    private String simulationHorizonDays;
    private String prePolicyDays;
    private String postPolicyDays;
    private String runMode;
    private String cadenceProfile;
    private String compareMode;

    public String getPresetId() {
        return presetId;
    }

    public ScenarioLabRunParameters presetId(String presetId) {
        this.presetId = presetId;
        return this;
    }

    public String getProductCode() {
        return productCode;
    }

    public ScenarioLabRunParameters productCode(String productCode) {
        this.productCode = productCode;
        return this;
    }

    public String getBaselineRate() {
        return baselineRate;
    }

    public ScenarioLabRunParameters baselineRate(String baselineRate) {
        this.baselineRate = baselineRate;
        return this;
    }

    public String getCandidateRate() {
        return candidateRate;
    }

    public ScenarioLabRunParameters candidateRate(String candidateRate) {
        this.candidateRate = candidateRate;
        return this;
    }

    public String getBaselineLimit() {
        return baselineLimit;
    }

    public ScenarioLabRunParameters baselineLimit(String baselineLimit) {
        this.baselineLimit = baselineLimit;
        return this;
    }

    public String getCandidateLimit() {
        return candidateLimit;
    }

    public ScenarioLabRunParameters candidateLimit(String candidateLimit) {
        this.candidateLimit = candidateLimit;
        return this;
    }

    public String getCandidateStimulusAmount() {
        return candidateStimulusAmount;
    }

    public ScenarioLabRunParameters candidateStimulusAmount(String candidateStimulusAmount) {
        this.candidateStimulusAmount = candidateStimulusAmount;
        return this;
    }

    public CandidateCrashEnum getCandidateCrash() {
        return candidateCrash;
    }

    public ScenarioLabRunParameters candidateCrash(CandidateCrashEnum candidateCrash) {
        this.candidateCrash = candidateCrash;
        return this;
    }

    public CandidateHardshipScanEnum getCandidateHardshipScan() {
        return candidateHardshipScan;
    }

    public ScenarioLabRunParameters candidateHardshipScan(CandidateHardshipScanEnum candidateHardshipScan) {
        this.candidateHardshipScan = candidateHardshipScan;
        return this;
    }

    public String getCandidateHardshipThreshold() {
        return candidateHardshipThreshold;
    }

    public ScenarioLabRunParameters candidateHardshipThreshold(String candidateHardshipThreshold) {
        this.candidateHardshipThreshold = candidateHardshipThreshold;
        return this;
    }

    public String getCandidateHardshipSubsidyAmount() {
        return candidateHardshipSubsidyAmount;
    }

    public ScenarioLabRunParameters candidateHardshipSubsidyAmount(String candidateHardshipSubsidyAmount) {
        this.candidateHardshipSubsidyAmount = candidateHardshipSubsidyAmount;
        return this;
    }

    public CandidateFraudAlertEnum getCandidateFraudAlert() {
        return candidateFraudAlert;
    }

    public ScenarioLabRunParameters candidateFraudAlert(CandidateFraudAlertEnum candidateFraudAlert) {
        this.candidateFraudAlert = candidateFraudAlert;
        return this;
    }

    public String getCandidateFraudSeverity() {
        return candidateFraudSeverity;
    }

    public ScenarioLabRunParameters candidateFraudSeverity(String candidateFraudSeverity) {
        this.candidateFraudSeverity = candidateFraudSeverity;
        return this;
    }

    public String getCandidateFraudChannel() {
        return candidateFraudChannel;
    }

    public ScenarioLabRunParameters candidateFraudChannel(String candidateFraudChannel) {
        this.candidateFraudChannel = candidateFraudChannel;
        return this;
    }

    public String getCandidateFraudMessage() {
        return candidateFraudMessage;
    }

    public ScenarioLabRunParameters candidateFraudMessage(String candidateFraudMessage) {
        this.candidateFraudMessage = candidateFraudMessage;
        return this;
    }

    public String getSimulationHorizonDays() {
        return simulationHorizonDays;
    }

    public ScenarioLabRunParameters simulationHorizonDays(String simulationHorizonDays) {
        this.simulationHorizonDays = simulationHorizonDays;
        return this;
    }

    public String getPrePolicyDays() {
        return prePolicyDays;
    }

    public ScenarioLabRunParameters prePolicyDays(String prePolicyDays) {
        this.prePolicyDays = prePolicyDays;
        return this;
    }

    public String getPostPolicyDays() {
        return postPolicyDays;
    }

    public ScenarioLabRunParameters postPolicyDays(String postPolicyDays) {
        this.postPolicyDays = postPolicyDays;
        return this;
    }

    public String getRunMode() {
        return runMode;
    }

    public ScenarioLabRunParameters runMode(String runMode) {
        this.runMode = runMode;
        return this;
    }

    public String getCadenceProfile() {
        return cadenceProfile;
    }

    public ScenarioLabRunParameters cadenceProfile(String cadenceProfile) {
        this.cadenceProfile = cadenceProfile;
        return this;
    }

    public String getCompareMode() {
        return compareMode;
    }

    public ScenarioLabRunParameters compareMode(String compareMode) {
        this.compareMode = compareMode;
        return this;
    }

    public enum CandidateCrashEnum {
        TRUE("true"),
        FALSE("false");

        private final String value;

        CandidateCrashEnum(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static CandidateCrashEnum fromValue(String value) {
            for (CandidateCrashEnum candidate : values()) {
                if (candidate.value.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Unexpected value '" + value + "'");
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public enum CandidateHardshipScanEnum {
        TRUE("true"),
        FALSE("false");

        private final String value;

        CandidateHardshipScanEnum(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static CandidateHardshipScanEnum fromValue(String value) {
            for (CandidateHardshipScanEnum candidate : values()) {
                if (candidate.value.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Unexpected value '" + value + "'");
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public enum CandidateFraudAlertEnum {
        TRUE("true"),
        FALSE("false");

        private final String value;

        CandidateFraudAlertEnum(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static CandidateFraudAlertEnum fromValue(String value) {
            for (CandidateFraudAlertEnum candidate : values()) {
                if (candidate.value.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Unexpected value '" + value + "'");
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
