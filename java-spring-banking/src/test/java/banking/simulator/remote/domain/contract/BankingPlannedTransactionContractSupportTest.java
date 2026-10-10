package banking.simulator.remote.domain.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import tools.jackson.databind.ObjectMapper;
import banking.simulator.domain.banking.BankingPlannedTransactionContract;
import banking.simulator.domain.banking.model.transaction.TransactionInstructionType;
import banking.simulator.remote.domain.contract.models.Merchant;
import banking.simulator.remote.domain.contract.models.PlannedTransactionEntity;
import banking.simulator.remote.domain.contract.models.PlannedTransactionEntityDetails;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BankingPlannedTransactionContractSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper().rebuild().findAndAddModules().build();

    @Test
    void roundTripsPlannedTransactionEntityThroughBankingContract() {
        PlannedTransactionEntity entity = new PlannedTransactionEntity()
                .description("Weekly Groceries")
                .frequency(PlannedTransactionEntity.FrequencyEnum.WEEKLY)
                .type(PlannedTransactionEntity.TypeEnum.PAYMENT)
                .amount(125.50)
                .isAmountExact(true)
                .time("18:30")
                .applyTimeJitter(true)
                .day("MONDAY")
                .date(LocalDate.parse("2026-04-07"))
                .startDate(LocalDate.parse("2026-04-01"))
                .endDate(LocalDate.parse("2026-05-01"))
                .permittedChannels(List.of(
                        PlannedTransactionEntity.PermittedChannelsEnum.DIGITAL,
                        PlannedTransactionEntity.PermittedChannelsEnum.CC))
                .details(new PlannedTransactionEntityDetails()
                        .description("Weekly Groceries")
                        .from(List.of("Credit Card"))
                        .to(List.of("Offset"))
                        .merchant(new Merchant()
                                .name("Fresh Mart")
                                .description("Grocer")
                                .category("Groceries")
                                .segment("Retail")
                                .currency("AUD")
                                .productCode("SAVINGS")
                                .bsb("123456")));

        BankingPlannedTransactionContract contract = BankingPlannedTransactionContractSupport.fromEntity(entity);

        assertNotNull(contract);
        assertEquals(TransactionInstructionType.PAYMENT, contract.type());
        assertEquals("weekly", contract.frequencyName());
        assertEquals(List.of("Digital", "CC"), contract.permittedChannelNames());
        assertEquals("Fresh Mart", contract.merchantProfile().name());

        PlannedTransactionEntity reconstructed = BankingPlannedTransactionContractSupport.toEntity(contract);
        assertEquals(entity.getDescription(), reconstructed.getDescription());
        assertEquals(entity.getFrequency(), reconstructed.getFrequency());
        assertEquals(entity.getType(), reconstructed.getType());
        assertEquals(entity.getAmount(), reconstructed.getAmount());
        assertEquals(entity.getTime(), reconstructed.getTime());
        assertEquals(entity.getDay(), reconstructed.getDay());
        assertEquals(entity.getDate(), reconstructed.getDate());
        assertEquals(entity.getStartDate(), reconstructed.getStartDate());
        assertEquals(entity.getEndDate(), reconstructed.getEndDate());
        assertEquals(entity.getPermittedChannels(), reconstructed.getPermittedChannels());
        assertNotNull(reconstructed.getDetails());
        assertEquals(entity.getDetails().getFrom(), reconstructed.getDetails().getFrom());
        assertEquals(entity.getDetails().getTo(), reconstructed.getDetails().getTo());
        assertNotNull(reconstructed.getDetails().getMerchant());
        assertEquals(entity.getDetails().getMerchant().getName(), reconstructed.getDetails().getMerchant().getName());
    }

    @Test
    void deserializesLegacyTransactionPayloadValuesWithoutChangingStoredFormat() {
        PlannedTransactionEntity entity = objectMapper.convertValue(Map.of(
                "description", "Salary",
                "frequency", "FORTNIGHTLY",
                "type", "DEPOSIT",
                "amount", 2400.0,
                "permittedChannels", List.of("Direct"),
                "details", Map.of(
                        "from", List.of("Employer"),
                        "to", List.of("Everyday"))), PlannedTransactionEntity.class);

        assertNotNull(entity);
        assertEquals(PlannedTransactionEntity.FrequencyEnum.FORTNIGHTLY, entity.getFrequency());
        assertEquals(PlannedTransactionEntity.TypeEnum.DEPOSIT, entity.getType());
        assertEquals(List.of(PlannedTransactionEntity.PermittedChannelsEnum.DIRECT), entity.getPermittedChannels());

        BankingPlannedTransactionContract contract = BankingPlannedTransactionContractSupport.fromEntity(entity);
        assertEquals("fortnightly", contract.frequencyName());
        assertEquals(List.of("Direct"), contract.permittedChannelNames());
    }

    @Test
    void deserializesTransferTypeFromStoredPayloads() {
        PlannedTransactionEntity entity = objectMapper.convertValue(Map.of(
                "description", "Savings Sweep",
                "frequency", "MONTHLY",
                "type", "TRANSFER",
                "amount", 500.0,
                "details", Map.of(
                        "from", List.of("Everyday"),
                        "to", List.of("Saver"))), PlannedTransactionEntity.class);

        assertNotNull(entity);
        assertEquals(PlannedTransactionEntity.TypeEnum.TRANSFER, entity.getType());

        BankingPlannedTransactionContract contract = BankingPlannedTransactionContractSupport.fromEntity(entity);
        assertEquals(TransactionInstructionType.TRANSFER, contract.type());

        PlannedTransactionEntity reconstructed = BankingPlannedTransactionContractSupport.toEntity(contract);
        assertEquals(PlannedTransactionEntity.TypeEnum.TRANSFER, reconstructed.getType());
    }

    @Test
    void fromScheduledWork_unwrapsNestedPayloadEnvelopeBeforeDtoConversion() {
        BankingPlannedTransactionContract contract = BankingPlannedTransactionContractSupport.fromScheduledWork(Map.of(
                "workId", "rent-work-1",
                "kind", "transaction",
                "description", "Monthly Rent",
                "frequency", "MONTHLY",
                "time", "09:00",
                "payload", Map.of(
                        "description", "Nested description should not break parsing",
                        "payload", Map.of(
                                "type", "PAYMENT",
                                "amount", 850.0,
                                "isAmountExact", true,
                                "details", Map.of(
                                        "from", List.of("Everyday"),
                                        "to", List.of("Landlord"))))));

        assertNotNull(contract);
        assertEquals("Monthly Rent", contract.description());
        assertEquals(TransactionInstructionType.PAYMENT, contract.type());
        assertEquals(850.0, contract.amount());
        assertEquals("monthly", contract.frequencyName());
        assertEquals("09:00", contract.time());
        assertEquals(List.of("Everyday"), contract.fromAccounts());
        assertEquals(List.of("Landlord"), contract.toAccounts());
    }

    @Test
    void fromScheduledWork_acceptsLegacyTopLevelTransactionFieldsWithoutPayload() {
        BankingPlannedTransactionContract contract = BankingPlannedTransactionContractSupport.fromScheduledWork(Map.of(
                "workId", "salary-work-1",
                "kind", "transaction",
                "description", "Salary",
                "frequency", "FORTNIGHTLY",
                "type", "DEPOSIT",
                "amount", 2400.0,
                "details", Map.of("to", List.of("Everyday"))));

        assertNotNull(contract);
        assertEquals("Salary", contract.description());
        assertEquals(TransactionInstructionType.DEPOSIT, contract.type());
        assertEquals(2400.0, contract.amount());
        assertEquals("fortnightly", contract.frequencyName());
        assertEquals(List.of("Everyday"), contract.toAccounts());
    }

    @Test
    void toScheduledWork_canonicalizesDateFieldsAsIsoStrings() {
        BankingPlannedTransactionContract contract = new BankingPlannedTransactionContract(
                "Stimulus Check",
                "Stimulus Check",
                TransactionInstructionType.DEPOSIT,
                1000.0,
                true,
                "daily",
                null,
                null,
                false,
                LocalDate.parse("2026-04-23"),
                LocalDate.parse("2026-04-23"),
                LocalDate.parse("2026-04-23"),
                List.of("Digital"),
                List.of(),
                List.of("Everyday"),
                null);

        Map<String, Object> scheduledWork = BankingPlannedTransactionContractSupport.toScheduledWork(contract);

        assertEquals("transaction", scheduledWork.get("kind"));
        assertEquals("Daily", scheduledWork.get("frequency"));
        assertEquals("2026-04-23", scheduledWork.get("startDate"));
        assertEquals("2026-04-23", scheduledWork.get("endDate"));

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = assertInstanceOf(Map.class, scheduledWork.get("payload"));
        assertEquals("2026-04-23", payload.get("date"));
    }
}
