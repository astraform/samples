package banking.simulator.domain.banking;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;

public final class BankingDepositRateSelectionSemantics {

    private BankingDepositRateSelectionSemantics() {
    }

    public static boolean matches(
            BankingDepositRateSelectionRule rule,
            BankingTermDepositSelectionInput input) {
        if (rule == null || input == null) {
            return false;
        }
        if (!Objects.equals(rule.applicationFrequency(), input.interestPaymentFrequency())) {
            return false;
        }

        double investment = input.investAmount() != null ? input.investAmount() : 0.0;
        Integer termInMonths = parseTermInMonths(input.term());
        List<BankingDepositRateTierSelectionRule> tiers = rule.tiers() != null ? rule.tiers() : List.of();

        boolean hasAmountTier = false;
        boolean hasTermTier = false;
        boolean amountMatches = false;
        boolean termMatches = false;

        for (BankingDepositRateTierSelectionRule tier : tiers) {
            if (tier == null) {
                continue;
            }

            String tierName = normalizeTierName(tier.name());
            if ("amount".equals(tierName) || "standard".equals(tierName)) {
                hasAmountTier = true;
                if (withinRange(investment, tier.minimumValue(), tier.maximumValue())) {
                    amountMatches = true;
                }
            } else if ("term".equals(tierName)) {
                hasTermTier = true;
                if (termInMonths != null && withinRange(termInMonths.doubleValue(), tier.minimumValue(), tier.maximumValue())) {
                    termMatches = true;
                }
            }
        }

        if (hasAmountTier && !amountMatches) {
            return false;
        }
        if (hasTermTier && !termMatches) {
            return false;
        }
        return true;
    }

    public static OptionalInt firstMatchingIndex(
            List<BankingDepositRateSelectionRule> rules,
            BankingTermDepositSelectionInput input) {
        if (rules == null || rules.isEmpty()) {
            return OptionalInt.empty();
        }
        for (int i = 0; i < rules.size(); i++) {
            if (matches(rules.get(i), input)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    static Integer parseTermInMonths(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String normalized = term.trim().toUpperCase(Locale.ROOT);
        try {
            if (normalized.startsWith("P") && normalized.endsWith("M")) {
                return Integer.parseInt(normalized.substring(1, normalized.length() - 1));
            }
            if (normalized.startsWith("P") && normalized.endsWith("Y")) {
                return Integer.parseInt(normalized.substring(1, normalized.length() - 1)) * 12;
            }
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean withinRange(double value, Double minimum, Double maximum) {
        if (minimum != null && value < minimum) {
            return false;
        }
        if (maximum != null && value > maximum) {
            return false;
        }
        return true;
    }

    private static String normalizeTierName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
