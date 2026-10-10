package banking.simulator.remote.domain;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BankingRemoteDomainProductController {

    @GetMapping("/api/products")
    public ResponseEntity<Map<String, Object>> getProducts(
            @RequestParam(name = "productCategory", required = false) String productCategory) {
        String normalizedCategory = normalize(productCategory);
        List<Map<String, Object>> products = BankingRemoteDomainState.defaultProductCatalog().stream()
                .filter(product -> normalizedCategory.isBlank()
                        || normalizedCategory.equals(normalize(product.get("productCategory")))
                        || normalizedCategory.equals(normalize(product.get("category"))))
                .toList();
        return ResponseEntity.ok(Map.of("data", Map.of("products", products)));
    }

    private static String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }
}
