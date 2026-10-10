package banking.simulator.domain.banking;

import java.util.Locale;

public final class BankingProductSemantics {
    private static final double DEFAULT_CC001_LIMIT = 10_000.0;
    private static final double DEFAULT_CC002_LIMIT = 5_000.0;
    private static final double DEFAULT_GENERIC_CREDIT_LIMIT = 5_000.0;
    private static final String CREDIT_AND_CHARGE_CATEGORY = "CRED_AND_CHRG_CARDS";
    private static final String FUNDING_CATEGORY = "TRANS_AND_SAVINGS_ACCOUNTS";
    private static final String TERM_DEPOSIT_CATEGORY = "TERM_DEPOSITS";
    private static final String PERSONAL_LOANS_CATEGORY = "PERS_LOANS";
    private static final String RESIDENTIAL_MORTGAGES_CATEGORY = "RESIDENTIAL_MORTGAGES";

    private BankingProductSemantics() {
    }

    public static String normalizeProductCode(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    public static boolean isSavingsProduct(String productCode) {
        String normalized = normalizeProductCode(productCode);
        return normalized != null && (normalized.startsWith("SAV") || normalized.startsWith("TD"));
    }

    public static boolean isTermDepositProduct(String productCode) {
        String normalized = normalizeProductCode(productCode);
        return normalized != null && normalized.startsWith("TD");
    }

    public static boolean isDebtProduct(String productCode) {
        String normalized = normalizeProductCode(productCode);
        return normalized != null && (normalized.startsWith("CC")
                || normalized.startsWith("HL")
                || normalized.startsWith("PL")
                || normalized.startsWith("SL")
                || normalized.startsWith("OL"));
    }

    public static double resolveInitialCreditLimit(String productCode, Double explicitLimit) {
        if (explicitLimit != null && explicitLimit > 0.0) {
            return explicitLimit;
        }

        return switch (normalizeProductCode(productCode)) {
            case "CC001" -> DEFAULT_CC001_LIMIT;
            case "CC002" -> DEFAULT_CC002_LIMIT;
            default -> DEFAULT_GENERIC_CREDIT_LIMIT;
        };
    }

    public static BankingProductAccountKind resolveAccountKind(String productCategory) {
        return switch (normalizeCategory(productCategory)) {
            case CREDIT_AND_CHARGE_CATEGORY -> BankingProductAccountKind.CREDIT;
            case FUNDING_CATEGORY -> BankingProductAccountKind.TRANSACTION;
            case TERM_DEPOSIT_CATEGORY -> BankingProductAccountKind.TERM_DEPOSIT;
            case PERSONAL_LOANS_CATEGORY, RESIDENTIAL_MORTGAGES_CATEGORY -> BankingProductAccountKind.LOAN;
            default -> throw new IllegalArgumentException("Unsupported banking product category: " + productCategory);
        };
    }

    public static boolean isFundingCategory(String productCategory) {
        return normalizeCategory(productCategory).equals(FUNDING_CATEGORY);
    }

    public static boolean isCreditCategory(String productCategory) {
        return normalizeCategory(productCategory).equals(CREDIT_AND_CHARGE_CATEGORY);
    }

    private static String normalizeCategory(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
