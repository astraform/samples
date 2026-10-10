package banking.simulator.remote.domain.contract;

import banking.simulator.domain.banking.BankingAccountView;
import banking.simulator.domain.banking.BankingMerchantProfile;
import banking.simulator.domain.banking.BankingSeedAccountPlan;
import banking.simulator.domain.banking.BankingSeedAccountSpec;
import banking.simulator.remote.domain.contract.models.AccountDto;
import banking.simulator.remote.domain.contract.models.Merchant;

import java.util.List;
import java.util.Objects;

public final class BankingRuntimeContractMappings {

    private BankingRuntimeContractMappings() {
    }

    public static List<BankingSeedAccountPlan> toSeedAccountPlans(List<AccountDto> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return List.of();
        }
        return accounts.stream()
                .filter(Objects::nonNull)
                .map(BankingRuntimeContractMappings::toSeedAccountPlan)
                .toList();
    }

    public static BankingSeedAccountPlan toSeedAccountPlan(AccountDto account) {
        if (account == null) {
            throw new IllegalArgumentException("account is required");
        }
        return new BankingSeedAccountPlan(
                toSeedAccountSpec(account),
                Boolean.TRUE.equals(account.getDebitCard()));
    }

    public static BankingSeedAccountSpec toSeedAccountSpec(AccountDto account) {
        if (account == null) {
            throw new IllegalArgumentException("account is required");
        }
        return new BankingSeedAccountSpec(
                account.getName(),
                account.getProductCode(),
                account.getBalance(),
                account.getLimit(),
                account.getBsb(),
                account.getCurrency(),
                account.getFromAccountId(),
                account.getInvestAmount(),
                account.getTerm(),
                account.getInterestPaymentFrequency());
    }

    public static BankingMerchantProfile toMerchantProfile(Merchant merchant) {
        if (merchant == null) {
            throw new IllegalArgumentException("merchant is required");
        }
        return new BankingMerchantProfile(
                merchant.getName(),
                merchant.getDescription(),
                merchant.getCategory(),
                merchant.getSegment(),
                merchant.getCurrency(),
                merchant.getProductCode(),
                parseNullableInteger(merchant.getBsb(), "merchant.bsb"));
    }

    public static List<AccountDto> toAccountDtos(List<BankingAccountView> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return List.of();
        }
        return accounts.stream()
                .filter(Objects::nonNull)
                .map(BankingRuntimeContractMappings::toAccountDto)
                .toList();
    }

    public static AccountDto toAccountDto(BankingAccountView account) {
        if (account == null) {
            return null;
        }
        return new AccountDto()
                .id(account.id())
                .name(account.name())
                .productCode(account.productCode())
                .balance(account.balance())
                .limit(account.limit())
                .bsb(account.bsb())
                .currency(account.currency());
    }

    private static Integer parseNullableInteger(String rawValue, String fieldName) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(rawValue.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(fieldName + " must be numeric", ex);
        }
    }
}
