package banking.simulator.remote.domain.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import banking.simulator.domain.banking.BankingCustomerProfile;
import banking.simulator.domain.banking.BankingPlannedTransactionContract;
import banking.simulator.domain.banking.BankingSeedAccountPlan;
import banking.simulator.domain.banking.model.transaction.TransactionInstructionType;
import banking.simulator.remote.domain.contract.models.AccountDto;
import banking.simulator.remote.domain.contract.models.AgentPersona;
import banking.simulator.remote.domain.contract.models.Goal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BankingRuntimePersonaContractSupportTest {

    @Test
    void customerProfile_mapsPersonaFields() {
        AgentPersona persona = new AgentPersona();
        persona.setPersonaName("Hana");
        persona.setPersonaDescription("Careful saver");
        persona.setDateOfBirth(LocalDate.of(1991, 2, 3));

        BankingCustomerProfile profile = BankingRuntimePersonaContractSupport.customerProfile(persona);

        assertNotNull(profile);
        assertEquals("Hana", profile.fullName());
        assertEquals("Careful saver", profile.persona());
        assertEquals(LocalDate.of(1991, 2, 3), profile.dateOfBirth());
    }

    @Test
    void initialAccountPlans_mapsSeedAccountsFromPersona() {
        AgentPersona persona = new AgentPersona();
        persona.setInitialAccounts(List.of(new AccountDto()
                .name("Main Account")
                .productCode("TX001")
                .balance(2500.0)
                .debitCard(true)));

        List<BankingSeedAccountPlan> plans = BankingRuntimePersonaContractSupport.initialAccountPlans(persona);

        assertEquals(1, plans.size());
        assertEquals("Main Account", plans.getFirst().seedAccount().name());
        assertEquals("TX001", plans.getFirst().seedAccount().productCode());
        assertEquals(2500.0, plans.getFirst().seedAccount().balance());
        assertTrue(plans.getFirst().debitCardRequested());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void initialAccountPlans_acceptLegacyMapBackedAccountsWithPurposeAndCreditLimit() {
        AgentPersona persona = new AgentPersona();
        persona.setInitialAccounts((List) List.of(Map.of(
                "name", "Rewards Card",
                "productCode", "CC001",
                "balance", -800.0,
                "purpose", "CREDITCARD",
                "creditLimit", 5000.0,
                "debitCard", false)));

        List<BankingSeedAccountPlan> plans = BankingRuntimePersonaContractSupport.initialAccountPlans(persona);

        assertEquals(1, plans.size());
        assertEquals("Rewards Card", plans.getFirst().seedAccount().name());
        assertEquals("CC001", plans.getFirst().seedAccount().productCode());
        assertEquals(-800.0, plans.getFirst().seedAccount().balance());
        assertEquals(5000.0, plans.getFirst().seedAccount().limit());
        assertFalse(plans.getFirst().debitCardRequested());
    }

    @Test
    void transactionScheduledWorkContracts_acceptCanonicalPayloadEnvelope() {
        AgentPersona persona = new AgentPersona();
        persona.setScheduledWork(List.of(Map.of(
                "workId", "rent-work-1",
                "kind", "transaction",
                "description", "Monthly Rent",
                "frequency", "Monthly",
                "time", "09:30",
                "startDate", "2026-05-01",
                "payload", Map.of(
                        "type", "Payment",
                        "amount", 1500.0,
                        "isAmountExact", true,
                        "details", Map.of(
                                "from", List.of("Main Account"),
                                "to", List.of("Landlord"))))));

        List<BankingPlannedTransactionContract> contracts =
                BankingRuntimePersonaContractSupport.transactionScheduledWorkContracts(persona);

        assertEquals(1, contracts.size());
        BankingPlannedTransactionContract contract = contracts.getFirst();
        assertEquals("Monthly Rent", contract.description());
        assertEquals(TransactionInstructionType.PAYMENT, contract.type());
        assertEquals(1500.0, contract.amount());
        assertTrue(contract.exactAmount());
        assertEquals("monthly", contract.frequencyName());
        assertEquals("09:30", contract.time());
        assertEquals(LocalDate.of(2026, 5, 1), contract.startDate());
        assertEquals(List.of("Main Account"), contract.fromAccounts());
        assertEquals(List.of("Landlord"), contract.toAccounts());
    }

    @Test
    void isPolicyAgent_detectsPolicyType() {
        AgentPersona persona = new AgentPersona();
        persona.setAgentType(AgentPersona.AgentTypeEnum.POLICY);

        assertTrue(BankingRuntimePersonaContractSupport.isPolicyAgent(persona));
        assertFalse(BankingRuntimePersonaContractSupport.isPolicyAgent(new AgentPersona()));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void goals_acceptMapBackedPayloadsFromJsonInitRequests() {
        AgentPersona persona = new AgentPersona();
        persona.setGoals((List) List.of(Map.of(
                "goalId", "graduate_debt_free",
                "description", "Finish university with zero student debt.",
                "targetAmount", 0.0,
                "priority", "high")));

        List<Goal> goals = persona.getGoals();

        assertEquals(1, goals.size());
        assertEquals("graduate_debt_free", goals.getFirst().getGoalId());
        assertEquals("Finish university with zero student debt.", goals.getFirst().getDescription());
        assertEquals(0.0, goals.getFirst().getTargetAmount());
        assertEquals(Goal.PriorityEnum.HIGH, goals.getFirst().getPriority());
    }
}
