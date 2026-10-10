package banking.simulator.domain.banking;

import java.util.Optional;

public final class BankingRuntimeIdentitySupport {
    private BankingRuntimeIdentitySupport() {
    }

    public static Optional<String> preparedRuntimeIdentity(
            BankingRuntimeIdentityAccess access,
            Long provisionedIdentity) {
        if (provisionedIdentity != null) {
            return Optional.of(provisionedIdentity.toString());
        }
        return runtimeIdentity(access);
    }

    public static Optional<String> runtimeIdentity(BankingRuntimeIdentityAccess access) {
        if (access == null) {
            return Optional.empty();
        }
        if (hasText(access.runtimeIdentity())) {
            return Optional.of(access.runtimeIdentity().trim());
        }
        if (access.bankingCustomerId() != null) {
            return Optional.of(access.bankingCustomerId().toString());
        }
        return Optional.empty();
    }

    public static String registrationKey(BankingRuntimeIdentityAccess access) {
        return runtimeIdentity(access)
                .orElseGet(() -> {
                    if (access != null && hasText(access.personaName())) {
                        return access.personaName().trim();
                    }
                    if (access != null && hasText(access.conversationId())) {
                        return access.conversationId().trim();
                    }
                    return null;
                });
    }

    public static String stableMemoryKey(BankingRuntimeIdentityAccess access, String runtimeIdentity) {
        if (hasText(runtimeIdentity)) {
            return "customer:" + runtimeIdentity.trim();
        }
        Optional<String> resolvedRuntimeIdentity = runtimeIdentity(access);
        if (resolvedRuntimeIdentity.isPresent()) {
            return "customer:" + resolvedRuntimeIdentity.get();
        }
        if (access != null && hasText(access.personaName())) {
            return "persona:" + access.personaName().trim();
        }
        if (access != null && hasText(access.conversationId())) {
            return "conversation:" + access.conversationId().trim();
        }
        return null;
    }

    public static Optional<String> inspectionIdentifier(BankingRuntimeIdentityAccess access) {
        if (access == null || access.bankingCustomerId() == null) {
            return Optional.empty();
        }
        return Optional.of(access.bankingCustomerId().toString());
    }

    public static boolean matchesRuntimeIdentity(BankingRuntimeIdentityAccess access, String runtimeIdentity) {
        if (!hasText(runtimeIdentity)) {
            return false;
        }
        String normalized = runtimeIdentity.trim();
        return inspectionIdentifier(access)
                .filter(normalized::equals)
                .isPresent();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
