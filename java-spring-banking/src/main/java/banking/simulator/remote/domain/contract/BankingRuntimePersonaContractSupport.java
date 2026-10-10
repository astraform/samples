package banking.simulator.remote.domain.contract;

import banking.simulator.domain.banking.BankingCustomerProfile;
import banking.simulator.domain.banking.BankingPlannedTransactionContract;
import banking.simulator.domain.banking.BankingSeedAccountPlan;
import banking.simulator.remote.domain.contract.models.AccountDto;
import banking.simulator.remote.domain.contract.models.AgentPersona;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BankingRuntimePersonaContractSupport {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().rebuild().findAndAddModules().build();

    private BankingRuntimePersonaContractSupport() {
    }

    public static BankingCustomerProfile customerProfile(AgentPersona persona) {
        if (persona == null) {
            return null;
        }
        return new BankingCustomerProfile(
                persona.getPersonaName(),
                persona.getPersonaDescription(),
                persona.getDateOfBirth());
    }

    public static List<BankingSeedAccountPlan> initialAccountPlans(AgentPersona persona) {
        if (persona == null) {
            return List.of();
        }
        return BankingRuntimeContractMappings.toSeedAccountPlans(Optional.ofNullable(persona.getInitialAccounts())
                .orElse(List.of())
                .stream()
                .map(BankingRuntimePersonaContractSupport::toAccountDto)
                .filter(java.util.Objects::nonNull)
                .toList());
    }

    public static List<BankingPlannedTransactionContract> transactionScheduledWorkContracts(AgentPersona persona) {
        if (persona == null) {
            return List.of();
        }
        return Optional.ofNullable(persona.getScheduledWork()).orElse(List.of()).stream()
                .filter(BankingRuntimePersonaContractSupport::isTransactionWorkItem)
                .map(BankingPlannedTransactionContractSupport::fromScheduledWork)
                .filter(Objects::nonNull)
                .toList();
    }

    public static boolean isPolicyAgent(AgentPersona persona) {
        return persona != null
                && persona.getAgentType() != null
                && "Policy".equalsIgnoreCase(persona.getAgentType().toString());
    }

    private static AccountDto toAccountDto(Object account) {
        if (account == null) {
            return null;
        }
        if (account instanceof AccountDto dto) {
            return dto;
        }
        if (account instanceof Map<?, ?> map) {
            return new AccountDto()
                    .name(asString(map.get("name")))
                    .productCode(asString(map.get("productCode")))
                    .balance(asDouble(map.get("balance")))
                    .limit(firstPresentDouble(map.get("limit"), map.get("creditLimit")))
                    .bsb(asInteger(map.get("bsb")))
                    .currency(asString(map.get("currency")))
                    .fromAccountId(asString(map.get("fromAccountId")))
                    .investAmount(asDouble(map.get("investAmount")))
                    .term(asString(map.get("term")))
                    .interestPaymentFrequency(asString(map.get("interestPaymentFrequency")))
                    .debitCard(asBoolean(map.get("debitCard")));
        }
        return OBJECT_MAPPER.convertValue(account, AccountDto.class);
    }

    private static boolean isTransactionWorkItem(Object workItem) {
        if (!(workItem instanceof Map<?, ?> map)) {
            return false;
        }
        Object rawKind = map.get("kind");
        return rawKind != null && "transaction".equalsIgnoreCase(String.valueOf(rawKind).trim());
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String candidate = value.toString().trim();
        return candidate.isEmpty() ? null : candidate;
    }

    private static Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String candidate = asString(value);
        return candidate == null ? null : Double.valueOf(candidate);
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String candidate = asString(value);
        return candidate == null ? null : Integer.valueOf(candidate);
    }

    private static Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String candidate = asString(value);
        return candidate == null ? null : Boolean.valueOf(candidate);
    }

    private static Double firstPresentDouble(Object primary, Object fallback) {
        Double primaryValue = asDouble(primary);
        return primaryValue != null ? primaryValue : asDouble(fallback);
    }
}
