package banking.simulator.remote.domain.contract;

import banking.simulator.domain.banking.BankingMerchantProfile;
import banking.simulator.domain.banking.BankingPlannedTransactionContract;
import banking.simulator.domain.banking.model.transaction.TransactionInstructionType;
import banking.simulator.remote.domain.contract.models.Merchant;
import banking.simulator.remote.domain.contract.models.PlannedTransactionEntity;
import banking.simulator.remote.domain.contract.models.PlannedTransactionEntityDetails;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class BankingPlannedTransactionContractSupport {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().rebuild().findAndAddModules().build();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Set<String> PLANNED_TRANSACTION_FIELDS = Set.of(
            "description",
            "frequency",
            "type",
            "amount",
            "isAmountExact",
            "day",
            "time",
            "applyTimeJitter",
            "date",
            "startDate",
            "endDate",
            "permittedChannels",
            "details");

    private BankingPlannedTransactionContractSupport() {
    }

    public static BankingPlannedTransactionContract fromEntity(PlannedTransactionEntity entity) {
        if (entity == null) {
            return null;
        }

        PlannedTransactionEntityDetails details = entity.getDetails();
        return new BankingPlannedTransactionContract(
                entity.getDescription(),
                details != null ? details.getDescription() : null,
                toInstructionType(entity.getType()),
                entity.getAmount(),
                entity.getIsAmountExact() == null || entity.getIsAmountExact(),
                entity.getFrequency() != null ? entity.getFrequency().getValue() : null,
                entity.getDay(),
                entity.getTime(),
                Boolean.TRUE.equals(entity.getApplyTimeJitter()),
                entity.getDate(),
                entity.getStartDate(),
                entity.getEndDate(),
                toPermittedChannelNames(entity.getPermittedChannels()),
                details != null ? details.getFrom() : null,
                details != null ? details.getTo() : null,
                details != null && details.getMerchant() != null
                        ? BankingRuntimeContractMappings.toMerchantProfile(details.getMerchant())
                        : null);
    }

    public static PlannedTransactionEntity toEntity(BankingPlannedTransactionContract contract) {
        if (contract == null) {
            return null;
        }

        PlannedTransactionEntityDetails details = new PlannedTransactionEntityDetails()
                .description(contract.detailsDescription())
                .from(List.copyOf(contract.fromAccounts()))
                .to(List.copyOf(contract.toAccounts()));
        if (contract.merchantProfile() != null) {
            details.setMerchant(toMerchant(contract.merchantProfile()));
        }

        return new PlannedTransactionEntity()
                .description(contract.description())
                .frequency(toFrequencyEnum(contract.frequencyName()))
                .type(toTypeEnum(contract.type()))
                .amount(contract.amount())
                .isAmountExact(contract.exactAmount())
                .time(contract.time())
                .applyTimeJitter(contract.applyTimeJitter())
                .day(contract.day())
                .date(contract.date())
                .startDate(contract.startDate())
                .endDate(contract.endDate())
                .permittedChannels(toPermittedChannels(contract.permittedChannelNames()))
                .details(details);
    }

    public static BankingPlannedTransactionContract fromScheduledWork(Map<String, Object> workItem) {
        if (workItem == null || workItem.isEmpty()) {
            return null;
        }
        PlannedTransactionEntity entity =
                OBJECT_MAPPER.convertValue(payloadBackedScheduledWork(workItem), PlannedTransactionEntity.class);
        return fromEntity(entity);
    }

    public static Map<String, Object> toScheduledWork(BankingPlannedTransactionContract contract) {
        PlannedTransactionEntity entity = toEntity(contract);
        if (entity == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> alias = canonicalizeScheduledWorkMap(OBJECT_MAPPER.convertValue(entity, MAP_TYPE));
        LinkedHashMap<String, Object> scheduledWork = new LinkedHashMap<>();
        putIfHasText(scheduledWork, "description", entity.getDescription());
        scheduledWork.put("kind", "transaction");
        putIfHasText(scheduledWork, "frequency", entity.getFrequency() != null ? entity.getFrequency().getValue() : null);
        putIfHasText(scheduledWork, "time", entity.getTime());
        putIfHasText(scheduledWork, "startDate", stringifyDate(entity.getStartDate()));
        putIfHasText(scheduledWork, "endDate", stringifyDate(entity.getEndDate()));
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>(alias);
        payload.remove("description");
        payload.remove("kind");
        payload.remove("frequency");
        payload.remove("time");
        payload.remove("startDate");
        payload.remove("endDate");
        if (!payload.isEmpty()) {
            scheduledWork.put("payload", payload);
        }
        return scheduledWork;
    }

    private static TransactionInstructionType toInstructionType(PlannedTransactionEntity.TypeEnum type) {
        return type == null ? null : TransactionInstructionType.fromString(type.name());
    }

    private static PlannedTransactionEntity.FrequencyEnum toFrequencyEnum(String frequencyName) {
        if (!hasText(frequencyName)) {
            return null;
        }
        String candidate = frequencyName.trim();
        for (PlannedTransactionEntity.FrequencyEnum value : PlannedTransactionEntity.FrequencyEnum.values()) {
            if (value.getValue().equalsIgnoreCase(candidate) || value.name().equalsIgnoreCase(candidate)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported planned transaction frequency: " + frequencyName);
    }

    private static PlannedTransactionEntity.TypeEnum toTypeEnum(TransactionInstructionType type) {
        if (type == null) {
            return null;
        }
        for (PlannedTransactionEntity.TypeEnum value : PlannedTransactionEntity.TypeEnum.values()) {
            if (value.name().equalsIgnoreCase(type.name())) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported planned transaction type: " + type);
    }

    private static List<String> toPermittedChannelNames(List<PlannedTransactionEntity.PermittedChannelsEnum> channels) {
        if (channels == null || channels.isEmpty()) {
            return List.of();
        }
        return channels.stream()
                .filter(Objects::nonNull)
                .map(PlannedTransactionEntity.PermittedChannelsEnum::getValue)
                .toList();
    }

    private static List<PlannedTransactionEntity.PermittedChannelsEnum> toPermittedChannels(List<String> channels) {
        if (channels == null || channels.isEmpty()) {
            return List.of();
        }
        return channels.stream()
                .filter(BankingPlannedTransactionContractSupport::hasText)
                .map(BankingPlannedTransactionContractSupport::toPermittedChannelEnum)
                .toList();
    }

    private static PlannedTransactionEntity.PermittedChannelsEnum toPermittedChannelEnum(String value) {
        String candidate = value.trim();
        for (PlannedTransactionEntity.PermittedChannelsEnum channel : PlannedTransactionEntity.PermittedChannelsEnum.values()) {
            if (channel.getValue().equalsIgnoreCase(candidate) || channel.name().equalsIgnoreCase(candidate)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("Unsupported planned transaction channel: " + value);
    }

    private static Merchant toMerchant(BankingMerchantProfile merchantProfile) {
        return new Merchant()
                .name(merchantProfile.name())
                .description(merchantProfile.description())
                .category(merchantProfile.category())
                .segment(merchantProfile.segment())
                .currency(merchantProfile.currency())
                .productCode(merchantProfile.productCode())
                .bsb(merchantProfile.bsb() != null ? Integer.toString(merchantProfile.bsb()) : null);
    }

    private static Map<String, Object> payloadBackedScheduledWork(Map<String, Object> workItem) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        mergeScheduledWorkPayload(payload, workItem.get("payload"));
        mergePlannedTransactionFields(payload, workItem);
        return payload;
    }

    private static void mergeScheduledWorkPayload(Map<String, Object> target, Object rawPayload) {
        if (rawPayload == null) {
            return;
        }
        Map<String, Object> payload = OBJECT_MAPPER.convertValue(rawPayload, MAP_TYPE);
        mergePlannedTransactionFields(target, payload);
        mergeScheduledWorkPayload(target, payload.get("payload"));
    }

    private static void mergePlannedTransactionFields(Map<String, Object> target, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        source.forEach((key, value) -> {
            if (PLANNED_TRANSACTION_FIELDS.contains(key) && value != null) {
                target.put(key, value);
            }
        });
    }

    private static LinkedHashMap<String, Object> canonicalizeScheduledWorkMap(Map<String, Object> raw) {
        LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return canonical;
        }
        raw.forEach((key, value) -> canonical.put(key, canonicalizeScheduledWorkValue(key, value)));
        return canonical;
    }

    private static Object canonicalizeScheduledWorkValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (isDateKey(key)) {
            String canonicalDate = coerceDateText(value);
            if (canonicalDate != null) {
                return canonicalDate;
            }
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof PlannedTransactionEntity.FrequencyEnum frequencyEnum) {
            return frequencyEnum.getValue();
        }
        if (value instanceof PlannedTransactionEntity.TypeEnum typeEnum) {
            return typeEnum.getValue();
        }
        if (value instanceof PlannedTransactionEntity.PermittedChannelsEnum channelEnum) {
            return channelEnum.getValue();
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) ->
                    nested.put(String.valueOf(nestedKey),
                            canonicalizeScheduledWorkValue(String.valueOf(nestedKey), nestedValue)));
            return nested;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> canonicalizeScheduledWorkValue(null, item))
                    .toList();
        }
        return value;
    }

    private static String stringifyDate(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static boolean isDateKey(String key) {
        return "date".equals(key) || "startDate".equals(key) || "endDate".equals(key);
    }

    private static String coerceDateText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof List<?> list && list.size() >= 3) {
            try {
                int year = Integer.parseInt(String.valueOf(list.get(0)));
                int month = Integer.parseInt(String.valueOf(list.get(1)));
                int day = Integer.parseInt(String.valueOf(list.get(2)));
                return LocalDate.of(year, month, day).toString();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static void putIfHasText(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isEmpty()) {
            target.put(key, value);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
