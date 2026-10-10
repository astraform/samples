package banking.simulator.remote.domain.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import banking.simulator.domain.banking.BankingAccountView;
import banking.simulator.domain.banking.BankingMerchantProfile;
import banking.simulator.domain.banking.BankingSeedAccountPlan;
import banking.simulator.remote.domain.contract.models.AccountDto;
import banking.simulator.remote.domain.contract.models.Merchant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BankingRuntimeContractMappingsTest {

    @Test
    void toSeedAccountPlans_preservesBankingSeedConfiguration() {
        AccountDto account = new AccountDto()
                .name("Everyday")
                .productCode("TX001")
                .balance(1250.0)
                .limit(4000.0)
                .bsb(123456)
                .currency("AUD")
                .fromAccountId("11")
                .investAmount(500.0)
                .term("6")
                .interestPaymentFrequency("MONTHLY")
                .debitCard(true);

        List<BankingSeedAccountPlan> plans = BankingRuntimeContractMappings.toSeedAccountPlans(List.of(account));

        assertThat(plans).hasSize(1);
        BankingSeedAccountPlan plan = plans.getFirst();
        assertThat(plan.debitCardRequested()).isTrue();
        assertThat(plan.seedAccount().name()).isEqualTo("Everyday");
        assertThat(plan.seedAccount().productCode()).isEqualTo("TX001");
        assertThat(plan.seedAccount().balance()).isEqualTo(1250.0);
        assertThat(plan.seedAccount().limit()).isEqualTo(4000.0);
        assertThat(plan.seedAccount().bsb()).isEqualTo(123456);
        assertThat(plan.seedAccount().currency()).isEqualTo("AUD");
        assertThat(plan.seedAccount().fromAccountId()).isEqualTo("11");
        assertThat(plan.seedAccount().investAmount()).isEqualTo(500.0);
        assertThat(plan.seedAccount().term()).isEqualTo("6");
        assertThat(plan.seedAccount().interestPaymentFrequency()).isEqualTo("MONTHLY");
    }

    @Test
    void toMerchantProfile_mapsScheduledMerchantContractToBankingDomainProfile() {
        Merchant merchant = new Merchant()
                .name("Corner Store")
                .description("Local groceries")
                .category("groceries")
                .segment("retail")
                .currency("AUD")
                .productCode("TX001")
                .bsb("987654");

        BankingMerchantProfile profile = BankingRuntimeContractMappings.toMerchantProfile(merchant);

        assertThat(profile.name()).isEqualTo("Corner Store");
        assertThat(profile.description()).isEqualTo("Local groceries");
        assertThat(profile.category()).isEqualTo("groceries");
        assertThat(profile.segment()).isEqualTo("retail");
        assertThat(profile.currency()).isEqualTo("AUD");
        assertThat(profile.productCode()).isEqualTo("TX001");
        assertThat(profile.bsb()).isEqualTo(987654);
    }

    @Test
    void toMerchantProfile_rejectsNonNumericBsb() {
        Merchant merchant = new Merchant().name("Bad Merchant").bsb("abc");

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> BankingRuntimeContractMappings.toMerchantProfile(merchant));

        assertThat(error).hasMessageContaining("merchant.bsb");
    }

    @Test
    void toAccountDtos_projectsBankingViewsToCompatibilityDtos() {
        List<AccountDto> accountDtos = BankingRuntimeContractMappings.toAccountDtos(List.of(
                new BankingAccountView(10L, "Saver", "TS001", 2200.0, null, 111111, "AUD"),
                new BankingAccountView(20L, "Credit", "CC001", -150.0, 5000.0, null, "AUD")));

        assertThat(accountDtos).hasSize(2);
        assertThat(accountDtos.get(0).getId()).isEqualTo(10L);
        assertThat(accountDtos.get(0).getName()).isEqualTo("Saver");
        assertThat(accountDtos.get(0).getProductCode()).isEqualTo("TS001");
        assertThat(accountDtos.get(0).getBalance()).isEqualTo(2200.0);
        assertThat(accountDtos.get(0).getBsb()).isEqualTo(111111);
        assertThat(accountDtos.get(1).getLimit()).isEqualTo(5000.0);
    }
}
