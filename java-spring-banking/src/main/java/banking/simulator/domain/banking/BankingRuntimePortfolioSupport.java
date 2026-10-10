package banking.simulator.domain.banking;

import banking.simulator.domain.banking.model.Account;
import banking.simulator.domain.banking.model.Card;
import banking.simulator.domain.banking.model.CreditAccount;
import banking.simulator.domain.banking.model.TransactionAccount;
import java.util.ArrayList;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BankingRuntimePortfolioSupport {
    private static final Logger LOG = Logger.getLogger(BankingRuntimePortfolioSupport.class.getName());
    private static final Random CARD_NUMBER_GENERATOR = new Random();
    private static final String CARD_BIN_RANGE = "53531620";

    private BankingRuntimePortfolioSupport() {
    }

    public static Account toRuntimeAccount(BankingAccountView account, BankingProductView product) {
        if (account == null || product == null) {
            throw new IllegalArgumentException("account and product are required");
        }
        return BankingRuntimeModelMapper.toRuntimeAccount(BankingAccountModeling.toModelSpec(account, product));
    }

    public static BankingRuntimePortfolioBootstrapResult originateAccounts(
            BankingRuntimeGateway gateway,
            Long customerId,
            BankingCustomerProfile customerProfile,
            List<BankingSeedAccountSpec> seedAccounts) {
        if (gateway == null) {
            throw new IllegalArgumentException("gateway is required");
        }
        if (customerId == null) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (customerProfile == null) {
            throw new IllegalArgumentException("customerProfile is required");
        }
        if (seedAccounts == null || seedAccounts.isEmpty()) {
            return new BankingRuntimePortfolioBootstrapResult(List.of(), Map.of(), Map.of());
        }

        List<Long> createdAccountIds = new ArrayList<>();
        List<BankingAccountView> fundingCandidates = new ArrayList<>();
        Map<String, Account> accountsByName = new LinkedHashMap<>();
        Map<String, BankingAccountView> accountViewsByName = new LinkedHashMap<>();

        for (BankingSeedAccountSpec requestedAccount : seedAccounts) {
            if (requestedAccount == null) {
                continue;
            }
            BankingSeedAccountSpec seedAccount = BankingAccountOriginationSupport.normalizeSeedAccount(
                    requestedAccount,
                    fundingCandidates);
            BankingAccountView createdAccount = gateway.openAccount(
                    BankingAccountOriginationSupport.toOpeningRequest(
                            customerId,
                            customerProfile,
                            seedAccount));
            BankingProductView product = gateway.getProduct(createdAccount.productCode())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Product not found for product code " + createdAccount.productCode()));
            Account runtimeAccount = toRuntimeAccount(createdAccount, product);
            String normalizedName = seedAccount.name().trim();
            accountsByName.put(normalizedName, runtimeAccount);
            accountViewsByName.put(normalizedName, createdAccount);
            if (createdAccount.id() != null) {
                createdAccountIds.add(createdAccount.id());
            }
            if (BankingAccountModeling.isFundingCandidate(product)) {
                fundingCandidates.add(createdAccount);
            }
        }

        return new BankingRuntimePortfolioBootstrapResult(
                createdAccountIds,
                accountsByName,
                accountViewsByName);
    }

    public static BankingRuntimePortfolioBootstrapResult bootstrapPortfolio(
            BankingRuntimeGateway gateway,
            Long customerId,
            BankingCustomerProfile customerProfile,
            List<BankingSeedAccountPlan> initialAccountPlans,
            BankingRuntimePortfolioAccess portfolio) {
        List<BankingSeedAccountPlan> safePlans = initialAccountPlans == null ? List.of() : initialAccountPlans;
        BankingRuntimePortfolioBootstrapResult bootstrap = originateAccounts(
                gateway,
                customerId,
                customerProfile,
                safePlans.stream()
                        .map(BankingSeedAccountPlan::seedAccount)
                        .toList());
        if (portfolio == null) {
            return bootstrap;
        }
        portfolio.originatedAccounts().putAll(bootstrap.accountsByName());
        for (BankingSeedAccountPlan initialAccountPlan : safePlans) {
            if (initialAccountPlan == null || !hasText(initialAccountPlan.accountName())) {
                continue;
            }
            String accountName = initialAccountPlan.accountName().trim();
            Account internalAccountModel = bootstrap.accountsByName().get(accountName);
            if (internalAccountModel != null) {
                issueRequestedCards(gateway, customerId, portfolio, initialAccountPlan, internalAccountModel, accountName);
            }
        }
        return bootstrap;
    }

    public static List<BankingAccountView> toAccountViews(Map<String, Account> accountsByName) {
        if (accountsByName == null || accountsByName.isEmpty()) {
            return List.of();
        }
        return accountsByName.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> BankingRuntimeModelMapper.toAccountView(entry.getKey(), entry.getValue()))
                .toList();
    }

    public static List<BankingAccountView> originatedAccountViewsSnapshot(BankingRuntimePortfolioAccess portfolio) {
        if (portfolio == null) {
            return List.of();
        }
        return toAccountViews(portfolio.originatedAccounts());
    }

    public static Account resolveAccount(BankingRuntimePortfolioAccess portfolio, String name) {
        if (portfolio == null || !hasText(name)) {
            return null;
        }
        return portfolio.originatedAccounts().get(name.trim());
    }

    public static Card randomCard(BankingRuntimePortfolioAccess portfolio, Random rng) {
        if (portfolio == null || portfolio.originatedCards().isEmpty() || rng == null) {
            return null;
        }
        return portfolio.originatedCards().get(rng.nextInt(portfolio.originatedCards().size()));
    }

    public static Card creditCard(BankingRuntimePortfolioAccess portfolio) {
        if (portfolio == null || portfolio.originatedCards().isEmpty()) {
            return null;
        }
        return portfolio.originatedCards().stream()
                .filter(card -> card instanceof banking.simulator.domain.banking.model.CreditCard)
                .findFirst()
                .orElse(null);
    }

    public static void refreshPortfolioIfStale(
            BankingRuntimeGateway gateway,
            Long customerId,
            BankingRuntimePortfolioAccess portfolio,
            Duration refreshTtl,
            Function<String, Optional<BankingProductView>> productResolver) {
        if (gateway == null || customerId == null || portfolio == null) {
            return;
        }
        Duration ttl = refreshTtl != null ? refreshTtl : Duration.ZERO;
        Instant now = Instant.now();
        Instant lastAttempt = portfolio.lastRefreshAttemptAt();
        if (lastAttempt != null && lastAttempt.plus(ttl).isAfter(now)) {
            return;
        }

        synchronized (portfolio.refreshMonitor()) {
            now = Instant.now();
            lastAttempt = portfolio.lastRefreshAttemptAt();
            if (lastAttempt != null && lastAttempt.plus(ttl).isAfter(now)) {
                return;
            }
            portfolio.lastRefreshAttemptAt(now);

            try {
                List<BankingAccountView> liveAccounts = gateway.getAccountViewsForCustomer(customerId);
                if (liveAccounts == null || liveAccounts.isEmpty()) {
                    return;
                }
                syncPortfolioFromLive(portfolio, liveAccounts, productResolver);
            } catch (Exception ignored) {
                // Host-side logging should decide whether refresh failures deserve noise.
            }
        }
    }

    public static void syncPortfolioFromLive(
            BankingRuntimePortfolioAccess portfolio,
            List<BankingAccountView> liveAccounts,
            Function<String, Optional<BankingProductView>> productResolver) {
        if (portfolio == null || liveAccounts == null || liveAccounts.isEmpty()) {
            return;
        }
        BankingRuntimePortfolioProjection projection = projectAccounts(liveAccounts, productResolver);
        portfolio.originatedAccounts().putAll(projection.accountsByName());
        BankingRuntimeCardBindings.refreshSettlementAccounts(
                portfolio.originatedCards(),
                projection.accountsByNumber());
    }

    public static BankingRuntimePortfolioProjection projectAccounts(
            List<BankingAccountView> liveAccounts,
            Function<String, Optional<BankingProductView>> productResolver) {
        if (liveAccounts == null || liveAccounts.isEmpty()) {
            return new BankingRuntimePortfolioProjection(Map.of(), Map.of());
        }
        if (productResolver == null) {
            throw new IllegalArgumentException("productResolver is required");
        }

        Map<String, Optional<BankingProductView>> productByCode = new HashMap<>();
        Map<String, Account> accountsByName = new LinkedHashMap<>();
        Map<Long, Account> accountsByNumber = new LinkedHashMap<>();

        for (BankingAccountView liveAccount : liveAccounts) {
            if (liveAccount == null || !hasText(liveAccount.name()) || !hasText(liveAccount.productCode())) {
                continue;
            }

            Optional<BankingProductView> product = productByCode.computeIfAbsent(
                    liveAccount.productCode(),
                    productResolver);
            if (product.isEmpty()) {
                continue;
            }

            Account refreshedAccount = toRuntimeAccount(liveAccount, product.get());
            accountsByName.put(liveAccount.name().trim(), refreshedAccount);
            if (refreshedAccount.getNumber() != null) {
                accountsByNumber.put(refreshedAccount.getNumber(), refreshedAccount);
            }
        }

        return new BankingRuntimePortfolioProjection(accountsByName, accountsByNumber);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void issueRequestedCards(
            BankingRuntimeGateway gateway,
            Long customerId,
            BankingRuntimePortfolioAccess portfolio,
            BankingSeedAccountPlan initialAccountPlan,
            Account internalAccountModel,
            String accountName) {
        if (gateway == null || customerId == null || portfolio == null || initialAccountPlan == null || internalAccountModel == null) {
            return;
        }

        if (initialAccountPlan.debitCardRequested() && internalAccountModel instanceof TransactionAccount transactionAccount) {
            Card card = BankingRuntimeCardBindings.debitCard(nextCardNumber(), transactionAccount);
            gateway.issueCard(new BankingCardIssuanceRequest(
                    customerId,
                    BankingCardType.DEBIT,
                    card.getNumber(),
                    card.type().name().toLowerCase(Locale.ROOT)));
            portfolio.originatedCards().add(card);
        }

        if (internalAccountModel instanceof CreditAccount creditAccount) {
            try {
                Card card = BankingRuntimeCardBindings.creditCard(nextCardNumber(), creditAccount);
                gateway.issueCard(new BankingCardIssuanceRequest(
                        customerId,
                        BankingCardType.CREDIT,
                        card.getNumber(),
                        card.type().name().toLowerCase(Locale.ROOT)));
                portfolio.originatedCards().add(card);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Failed to create credit card for account '" + accountName + "'", e);
            }
        }
    }

    private static long nextCardNumber() {
        return Long.parseLong(CARD_BIN_RANGE + Integer.valueOf(CARD_NUMBER_GENERATOR.nextInt(99_999_999)).toString());
    }
}
