package banking.simulator.domain.banking;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class BankingFailureSemantics {
    public static final String FAILURE_KIND_INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String FAILURE_KIND_SUPPRESSED = "SUPPRESSED";
    public static final String FAILURE_KIND_RETRY_EXHAUSTED = "RETRY_EXHAUSTED";
    public static final String FAILURE_KIND_OTHER = "OTHER_FAILURE";

    public static final String PAYMENT_CLASS_OBLIGATION = "OBLIGATION";
    public static final String PAYMENT_CLASS_ESSENTIAL = "ESSENTIAL";
    public static final String PAYMENT_CLASS_DISCRETIONARY = "DISCRETIONARY";
    public static final String PAYMENT_CLASS_OTHER = "OTHER";

    private static final Set<String> OBLIGATION_PAYMENT_KEYWORDS = Set.of(
            "rent",
            "mortgage",
            "loan",
            "utility",
            "utilities",
            "insurance",
            "childcare",
            "school",
            "software",
            "supplies",
            "tax");
    private static final Set<String> ESSENTIAL_PAYMENT_KEYWORDS = Set.of(
            "grocer",
            "grocery",
            "groceries",
            "petrol",
            "gas",
            "fuel",
            "food",
            "medical",
            "medicine",
            "pharmacy",
            "internet",
            "phone",
            "mobile",
            "transport");
    private static final Set<String> DISCRETIONARY_PAYMENT_KEYWORDS = Set.of(
            "coffee",
            "uber",
            "ride",
            "rideshare",
            "netflix",
            "stream",
            "travel",
            "restaurant",
            "dining",
            "shopping",
            "entertainment",
            "movie",
            "gym");

    public String classifyPaymentStress(String workName) {
        String normalized = normalize(workName);
        if (containsAnyKeyword(normalized, OBLIGATION_PAYMENT_KEYWORDS)) {
            return PAYMENT_CLASS_OBLIGATION;
        }
        if (containsAnyKeyword(normalized, ESSENTIAL_PAYMENT_KEYWORDS)) {
            return PAYMENT_CLASS_ESSENTIAL;
        }
        if (containsAnyKeyword(normalized, DISCRETIONARY_PAYMENT_KEYWORDS)) {
            return PAYMENT_CLASS_DISCRETIONARY;
        }
        return PAYMENT_CLASS_OTHER;
    }

    public String classifyScheduledFailureKind(String message) {
        String normalized = normalize(message);
        if (normalized.contains("insufficient funds")) {
            return FAILURE_KIND_INSUFFICIENT_FUNDS;
        }
        if (normalized.contains("suppressed")) {
            return FAILURE_KIND_SUPPRESSED;
        }
        if (normalized.contains("executor not ready") || normalized.contains("come back later")) {
            return FAILURE_KIND_RETRY_EXHAUSTED;
        }
        return FAILURE_KIND_OTHER;
    }

    public String stressEscalationLevel(int recentInsufficientFundsCount) {
        if (recentInsufficientFundsCount >= 5) {
            return "SEVERE";
        }
        if (recentInsufficientFundsCount >= 3) {
            return "REPEATED";
        }
        return "INITIAL";
    }

    public String buildTransactionFailureMessage(String description, String reason) {
        return String.format(
                Locale.ROOT,
                "TRANSACTION FAILED: %s. Reason: %s. Payment class: %s",
                description,
                reason,
                classifyPaymentStress(description));
    }

    public String buildScheduledWorkFailureSummary(
            String workName,
            String failureKind,
            String paymentClass,
            Double availableAmount,
            Double requiredAmount,
            String message,
            int recentInsufficientFundsCount) {
        String summary = "Scheduled work failure";
        if (hasText(workName)) {
            summary += ": " + workName.trim();
        }
        if (hasText(failureKind)) {
            summary += " [" + failureKind + "]";
        }
        if (hasText(paymentClass)) {
            summary += " class=" + paymentClass;
        }
        if (availableAmount != null || requiredAmount != null) {
            summary += " available=" + (availableAmount != null ? availableAmount : "?")
                    + " required=" + (requiredAmount != null ? requiredAmount : "?");
        }
        if (recentInsufficientFundsCount > 0) {
            summary += " recentInsufficientFunds=" + recentInsufficientFundsCount;
        }
        if (hasText(message)) {
            summary += " reason=" + message;
        }
        return summary;
    }

    public Map<String, Object> buildInsufficientFundsPayload(
            String workType,
            String workName,
            String paymentClass,
            Double availableAmount,
            Double requiredAmount,
            int recentInsufficientFundsCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alertType", "insufficient_funds");
        payload.put("workType", workType);
        payload.put("workName", workName);
        payload.put("paymentClass", paymentClass);
        payload.put("availableAmount", availableAmount);
        payload.put("requiredAmount", requiredAmount);
        payload.put("recentInsufficientFundsCount", recentInsufficientFundsCount);
        payload.put(
                "essential",
                PAYMENT_CLASS_ESSENTIAL.equals(paymentClass) || PAYMENT_CLASS_OBLIGATION.equals(paymentClass));
        return payload;
    }

    public String buildInsufficientFundsDescription(
            String workName,
            String paymentClass,
            Double availableAmount,
            Double requiredAmount,
            int recentInsufficientFundsCount) {
        String effectiveWorkName = hasText(workName) ? workName : "scheduled payment";
        String effectivePaymentClass = hasText(paymentClass) ? paymentClass : PAYMENT_CLASS_OTHER;
        String amounts = availableAmount != null && requiredAmount != null
                ? String.format(
                        Locale.ROOT,
                        "available %.2f AUD vs required %.2f AUD",
                        availableAmount,
                        requiredAmount)
                : "balance shortfall detected";
        return String.format(
                Locale.ROOT,
                "Insufficient funds blocked %s (%s). %s. Recent shortfall events: %d.",
                effectiveWorkName,
                effectivePaymentClass.toLowerCase(Locale.ROOT),
                amounts,
                recentInsufficientFundsCount);
    }

    private boolean containsAnyKeyword(String normalizedText, Set<String> keywords) {
        if (!hasText(normalizedText)) {
            return false;
        }
        return keywords.stream().anyMatch(normalizedText::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
