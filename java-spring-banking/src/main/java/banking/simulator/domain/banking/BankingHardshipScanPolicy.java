package banking.simulator.domain.banking;

public record BankingHardshipScanPolicy(
        double hardshipThreshold,
        double subsidyAmount) {
    private static final double DEFAULT_HARDSHIP_THRESHOLD = 100.00;
    private static final double DEFAULT_SUBSIDY_AMOUNT = 500.00;

    public BankingHardshipScanPolicy {
        if (!Double.isFinite(hardshipThreshold) || hardshipThreshold < 0) {
            throw new IllegalArgumentException("hardshipThreshold must be greater than or equal to 0");
        }
        if (!Double.isFinite(subsidyAmount) || subsidyAmount <= 0) {
            throw new IllegalArgumentException("subsidyAmount must be greater than 0");
        }
    }

    public static BankingHardshipScanPolicy defaults() {
        return new BankingHardshipScanPolicy(DEFAULT_HARDSHIP_THRESHOLD, DEFAULT_SUBSIDY_AMOUNT);
    }
}
