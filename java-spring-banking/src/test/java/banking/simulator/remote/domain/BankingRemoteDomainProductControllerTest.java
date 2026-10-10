package banking.simulator.remote.domain;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BankingRemoteDomainProductControllerTest {

    private final BankingRemoteDomainProductController controller = new BankingRemoteDomainProductController();

    @Test
    void exposesDomainOwnedProductCatalogInBankingListShape() {
        Map<String, Object> body = controller.getProducts(null).getBody();

        assertThat(products(body))
                .extracting(product -> product.get("productCode"))
                .contains("TS001", "SAV001", "CC001", "CC002", "HL001", "SL001", "TD001");
    }

    @Test
    void filtersProductCatalogByCategory() {
        Map<String, Object> body = controller.getProducts("CRED_AND_CHRG_CARDS").getBody();

        assertThat(products(body))
                .extracting(product -> product.get("productCode"))
                .containsExactly("CC001", "CC002");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> products(Map<String, Object> body) {
        return (List<Map<String, Object>>) ((Map<String, Object>) body.get("data")).get("products");
    }
}
