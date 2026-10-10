package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BankingRuntimeIdentitySupportTest {

    @Test
    void runtimeIdentity_fallsBackToCustomerIdWhenExplicitIdentityMissing() {
        BankingRuntimeIdentityAccess access = access(null, 42L, "conv-1", "Hana");

        assertEquals("42", BankingRuntimeIdentitySupport.runtimeIdentity(access).orElse(null));
        assertEquals("42", BankingRuntimeIdentitySupport.preparedRuntimeIdentity(access, null).orElse(null));
        assertEquals("42", BankingRuntimeIdentitySupport.registrationKey(access));
        assertEquals("customer:42", BankingRuntimeIdentitySupport.stableMemoryKey(access, null));
    }

    @Test
    void stableMemoryKey_fallsBackToPersonaAndConversationWhenCustomerIdentityMissing() {
        BankingRuntimeIdentityAccess personaAccess = access(null, null, "conv-2", "Hana");
        BankingRuntimeIdentityAccess conversationAccess = access(null, null, "conv-3", null);

        assertEquals("persona:Hana", BankingRuntimeIdentitySupport.stableMemoryKey(personaAccess, null));
        assertEquals("conversation:conv-3", BankingRuntimeIdentitySupport.stableMemoryKey(conversationAccess, null));
    }

    @Test
    void inspectionIdentifierAndMatchUseLegacyCustomerIdentity() {
        BankingRuntimeIdentityAccess access = access("runtime-7", 77L, "conv-7", "Hana");

        assertEquals("77", BankingRuntimeIdentitySupport.inspectionIdentifier(access).orElse(null));
        assertTrue(BankingRuntimeIdentitySupport.matchesRuntimeIdentity(access, "77"));
        assertFalse(BankingRuntimeIdentitySupport.matchesRuntimeIdentity(access, "runtime-7"));
    }

    private static BankingRuntimeIdentityAccess access(
            String runtimeIdentity,
            Long bankingCustomerId,
            String conversationId,
            String personaName) {
        return new BankingRuntimeIdentityAccess() {
            @Override
            public String runtimeIdentity() {
                return runtimeIdentity;
            }

            @Override
            public Long bankingCustomerId() {
                return bankingCustomerId;
            }

            @Override
            public String conversationId() {
                return conversationId;
            }

            @Override
            public String personaName() {
                return personaName;
            }
        };
    }
}
