package banking.simulator.domain.banking;

import java.time.LocalDate;
import java.util.Locale;

public final class BankingAccountOriginationSemantics {
    private static final String DEFAULT_CURRENCY = "AUD";
    private static final int DEFAULT_BSB = 0;
    private static final int DEFAULT_CREDIT_STATEMENT_MONTHS = 1;

    private BankingAccountOriginationSemantics() {
    }

    public static String resolveOpeningCurrency(BankingProductAccountKind accountKind, String requestedCurrency) {
        if (accountKind == BankingProductAccountKind.TERM_DEPOSIT) {
            return DEFAULT_CURRENCY;
        }
        if (requestedCurrency == null) {
            return DEFAULT_CURRENCY;
        }
        String normalized = requestedCurrency.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? DEFAULT_CURRENCY : normalized;
    }

    public static int resolveOpeningBsb(Integer requestedBsb) {
        return requestedBsb != null ? requestedBsb : DEFAULT_BSB;
    }

    public static LocalDate resolveOpeningDate(LocalDate requestedOpenDate, LocalDate fallbackDate) {
        if (requestedOpenDate != null) {
            return requestedOpenDate;
        }
        if (fallbackDate == null) {
            throw new IllegalArgumentException("fallbackDate is required when requestedOpenDate is missing");
        }
        return fallbackDate;
    }

    public static BankingCreditStatementWindow resolveInitialCreditStatementWindow(LocalDate openDate) {
        if (openDate == null) {
            throw new IllegalArgumentException("openDate is required");
        }
        return new BankingCreditStatementWindow(openDate, openDate.plusMonths(DEFAULT_CREDIT_STATEMENT_MONTHS));
    }

    public static BankingTermDepositMaturityKind resolveInitialTermDepositMaturityInstruction() {
        return BankingTermDepositMaturityKind.PAID_OUT_AT_MATURITY;
    }
}
