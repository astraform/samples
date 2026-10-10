package banking.simulator.domain.banking;

import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

public final class BankingAccountOriginationSupport {
    private static final Logger LOG = Logger.getLogger(BankingAccountOriginationSupport.class.getName());
    private static final double DEFAULT_MIN_TERM_DEPOSIT = 5_000.0;
    private static final String DEFAULT_TERM_DEPOSIT_TERM_MONTHS = "12";
    private static final String DEFAULT_TERM_DEPOSIT_INTEREST_FREQUENCY = "P1Y";

    private BankingAccountOriginationSupport() {
    }

    public static BankingSeedAccountSpec normalizeSeedAccount(
            BankingSeedAccountSpec seedAccount,
            List<BankingAccountView> fundingCandidates) {
        if (seedAccount == null) {
            throw new IllegalArgumentException("seedAccount is required");
        }
        if (!BankingProductSemantics.isTermDepositProduct(seedAccount.productCode())) {
            return seedAccount;
        }

        BankingAccountView source = fundingCandidates == null
                ? null
                : fundingCandidates.stream()
                        .filter(candidate -> candidate != null && candidate.id() != null)
                        .max(Comparator.comparingDouble(candidate -> candidate.balance() != null ? candidate.balance() : 0.0))
                        .orElse(null);

        if (source == null) {
            throw new IllegalArgumentException(
                    "Term deposit account '" + seedAccount.name()
                            + "' requires a transaction/savings account before it in initialAccounts");
        }

        String fromAccountId = hasText(seedAccount.fromAccountId())
                ? seedAccount.fromAccountId()
                : String.valueOf(source.id());
        double available = source.balance() != null ? source.balance() : 0.0;
        double configuredTargetBalance = seedAccount.balance() != null ? Math.max(0.0, seedAccount.balance()) : 0.0;

        double investAmount;
        if (seedAccount.investAmount() != null && seedAccount.investAmount() > 0.0) {
            investAmount = seedAccount.investAmount();
        } else if (configuredTargetBalance > 0.0) {
            investAmount = configuredTargetBalance;
        } else {
            investAmount = available;
        }

        if (investAmount > available) {
            LOG.warning("Clamping term-deposit investAmount for '" + seedAccount.name() + "' from "
                    + investAmount + " to source balance " + available + " (sourceAccountId=" + source.id() + ")");
            investAmount = available;
        }

        if (investAmount <= 0.0) {
            throw new IllegalArgumentException(
                    "Term deposit account '" + seedAccount.name() + "' cannot be funded from source account '"
                            + source.name() + "'");
        }

        if (investAmount < DEFAULT_MIN_TERM_DEPOSIT) {
            LOG.warning("Term-deposit investAmount " + investAmount + " for '" + seedAccount.name()
                    + "' is below expected minimum " + DEFAULT_MIN_TERM_DEPOSIT
                    + "; bank validation may reject it");
        }

        return seedAccount.withFundingDetails(
                0.0,
                fromAccountId,
                investAmount,
                hasText(seedAccount.term()) ? seedAccount.term() : DEFAULT_TERM_DEPOSIT_TERM_MONTHS,
                hasText(seedAccount.interestPaymentFrequency())
                        ? seedAccount.interestPaymentFrequency()
                        : DEFAULT_TERM_DEPOSIT_INTEREST_FREQUENCY);
    }

    public static BankingAccountOpeningRequest toOpeningRequest(
            Long customerId,
            BankingCustomerProfile customerProfile,
            BankingSeedAccountSpec seedAccount) {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (customerProfile == null) {
            throw new IllegalArgumentException("customerProfile is required");
        }
        if (seedAccount == null) {
            throw new IllegalArgumentException("seedAccount is required");
        }
        return new BankingAccountOpeningRequest(
                customerId,
                customerProfile.fullName(),
                customerProfile.persona(),
                customerProfile.dateOfBirth(),
                seedAccount.name(),
                seedAccount.productCode(),
                seedAccount.balance(),
                seedAccount.limit(),
                seedAccount.bsb(),
                seedAccount.currency(),
                seedAccount.fromAccountId(),
                seedAccount.investAmount(),
                seedAccount.term(),
                seedAccount.interestPaymentFrequency());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
