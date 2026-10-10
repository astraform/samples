package banking.simulator.domain.banking;

import banking.simulator.domain.banking.model.Account;
import banking.simulator.domain.banking.model.CreditAccount;
import banking.simulator.domain.banking.model.Merchant;
import banking.simulator.domain.banking.model.TransactionAccount;
import banking.simulator.domain.banking.model.transaction.TransactionInstruction;
import banking.simulator.domain.banking.model.transaction.TransactionInstructionType;
import java.time.ZonedDateTime;
import java.util.List;

public final class BankingChannelPostingFactory {
    public static final long INTERBANK_SETTLEMENT_ACCOUNT_ID = 55_555_555L;
    public static final int INTERBANK_SETTLEMENT_BSB = 67_777;
    public static final long ATM_CASH_SETTLEMENT_ACCOUNT_ID = 55_555_556L;
    public static final int ATM_CASH_SETTLEMENT_BSB = 69_999;
    public static final long BPAY_CLEARING_ACCOUNT_ID = 33_444_333L;
    public static final int BPAY_CLEARING_BSB = 68_778;

    private static final long BRANCH_CASH_SETTLEMENT_ACCOUNT_ID = 22_222_222L;
    private static final int BRANCH_CASH_SETTLEMENT_BSB = 62_409;
    private static final long BRANCH_EXTERNAL_PAYMENT_ACCOUNT_ID = 33_333_333L;
    private static final int BRANCH_EXTERNAL_PAYMENT_BSB = 68_888;
    private static final int INTERNAL_BSB = 62_204;

    private BankingChannelPostingFactory() {
    }

    public static BankingDoubleEntryPosting create(BankingChannelType channelType, TransactionInstruction tx,
            ZonedDateTime now) {
        if (channelType == null) {
            throw new IllegalArgumentException("channelType is required");
        }
        if (tx == null || tx.getAmount() == null || now == null) {
            throw new IllegalArgumentException("tx with amount and now are required");
        }

        BankingPostingLeg credit = prefillLeg(tx, tx.getAmount(), now, AccountingType.CREDIT);
        BankingPostingLeg debit = prefillLeg(tx, tx.getAmount(), now, AccountingType.DEBIT);

        return switch (channelType) {
            case ATM -> buildAtm(tx, credit, debit);
            case BRANCH -> buildBranch(tx, credit, debit);
            case CC -> buildCreditCard(tx, credit, debit);
            case DIGITAL -> buildDigital(tx, credit, debit);
            case DIRECT -> buildDirect(tx, credit, debit);
        };
    }

    public static double calculateAvailableFunds(TransactionInstruction tx) {
        if (tx == null || tx.getFrom() == null) {
            return 0.0;
        }
        Double balance = tx.getFrom().getBalance();
        if (balance == null) {
            balance = 0.0;
        }

        double availableFunds = balance;
        Account sourceAccount = tx.getFrom();
        if (sourceAccount instanceof CreditAccount creditAccount) {
            Double creditLimit = creditAccount.getCreditLimit();
            if (creditLimit != null && creditLimit > 0) {
                availableFunds = creditLimit + balance;
            }
        }
        return availableFunds;
    }

    public static void validateSufficientFunds(TransactionInstruction tx) {
        if (tx == null || tx.getType() == TransactionInstructionType.DEPOSIT || tx.getFrom() == null) {
            return;
        }
        Double amount = tx.getAmount();
        if (amount == null) {
            return;
        }
        double availableFunds = calculateAvailableFunds(tx);
        if (availableFunds < amount) {
            throw new IllegalStateException("Insufficient funds: available=%s required=%s"
                    .formatted(availableFunds, amount));
        }
    }

    public static boolean isInsufficientFundsFailure(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("insufficient funds")
                || normalized.contains("insufficient credit limit");
    }

    private static BankingDoubleEntryPosting buildAtm(
            TransactionInstruction tx,
            BankingPostingLeg credit,
            BankingPostingLeg debit) {
        return switch (tx.getType()) {
            case WITHDRAWAL -> singlePosting(
                    credit.withDescription("ATM CASH ACCT TRANSFER")
                            .withAccount(ATM_CASH_SETTLEMENT_ACCOUNT_ID, ATM_CASH_SETTLEMENT_BSB),
                    debit.withDescription("Wdl ATM"));
            case DEPOSIT -> singlePosting(
                    credit.withDescription("Dep ATM CBA"),
                    debit.withDescription("ATM CASH ACCT TRANSFER")
                            .withAccount(ATM_CASH_SETTLEMENT_ACCOUNT_ID, ATM_CASH_SETTLEMENT_BSB));
            case TRANSFER -> singlePosting(
                    credit.withDescription("Tfr ATM CBA"),
                    debit.withDescription("Tfr ATM CBA"));
            default -> null;
        };
    }

    private static BankingDoubleEntryPosting buildBranch(
            TransactionInstruction tx,
            BankingPostingLeg credit,
            BankingPostingLeg debit) {
        return switch (tx.getType()) {
            case WITHDRAWAL -> singlePosting(
                    credit.withDescription("Branch CASH ACCT TRANSFER")
                            .withAccount(BRANCH_CASH_SETTLEMENT_ACCOUNT_ID, BRANCH_CASH_SETTLEMENT_BSB),
                    debit.withDescription("Wdl Branch"));
            case DEPOSIT -> singlePosting(
                    credit.withDescription("Dep Branch"),
                    debit.withDescription("Branch CASH ACCT TRANSFER")
                            .withAccount(BRANCH_CASH_SETTLEMENT_ACCOUNT_ID, BRANCH_CASH_SETTLEMENT_BSB));
            case TRANSFER -> singlePosting(
                    credit.withDescription("Tfr Branch CBA NEWTOWN 2042"),
                    debit.withDescription("Tfr Branch CBA NEWTOWN 2042"));
            case PAYMENT -> singlePosting(
                    tx.getTo() == null
                            ? credit.withDescription("Payment Received")
                                    .withAccount(BRANCH_EXTERNAL_PAYMENT_ACCOUNT_ID, BRANCH_EXTERNAL_PAYMENT_BSB)
                            : credit.withDescription("Payment Received"),
                    debit.withDescription("Payment to Acct. CBA NEWTOWN NSW 2042"));
            default -> null;
        };
    }

    private static BankingDoubleEntryPosting buildCreditCard(
            TransactionInstruction tx,
            BankingPostingLeg credit,
            BankingPostingLeg debit) {
        return switch (tx.getType()) {
            case WITHDRAWAL -> singlePosting(
                    credit.withDescription("ATM CASH ACCT TRANSFER")
                            .withAccount(ATM_CASH_SETTLEMENT_ACCOUNT_ID, ATM_CASH_SETTLEMENT_BSB),
                    debit.withDescription("ATM CASH ACCT TRANSFER"));
            case DEPOSIT -> singlePosting(
                    credit.withDescription("Payment Received. " + tx.getDescription()),
                    debit.withDescription("ATM CASH ACCT TRANSFER")
                            .withAccount(ATM_CASH_SETTLEMENT_ACCOUNT_ID, ATM_CASH_SETTLEMENT_BSB));
            case TRANSFER -> {
                BankingPostingLeg nextCredit = credit.withDescription("ATM Dep " + tx.getDescription());
                BankingPostingLeg nextDebit = debit;
                if (tx.getFrom() instanceof CreditAccount) {
                    nextDebit = nextDebit.withDescription("Cash Advance " + tx.getDescription());
                    nextCredit = nextCredit.withDescription("ATM Cash Advance " + tx.getDescription());
                }
                if (tx.getTo() instanceof CreditAccount) {
                    nextDebit = nextDebit.withDescription("Payment Received. " + tx.getDescription());
                    nextCredit = nextCredit.withDescription("ATM Payment " + tx.getDescription());
                }
                yield singlePosting(nextCredit, nextDebit);
            }
            case PAYMENT -> {
                BankingPostingLeg nextCredit = credit.withDescription("CC Payment");
                BankingPostingLeg nextDebit = debit;
                Merchant merchant = tx.getMerchant();
                if (merchant != null) {
                    Integer bsb = merchant.getSchemeSettlemeAccount() != null
                            ? merchant.getSchemeSettlemeAccount().getBsb()
                            : nextCredit.bsb();
                    nextCredit = nextCredit
                            .withMerchantId(merchant.getId())
                            .withAccount(
                                    merchant.getSchemeSettlemeAccount() != null
                                            ? merchant.getSchemeSettlemeAccount().getNumber()
                                            : nextCredit.accountId(),
                                    bsb);
                    nextDebit = nextDebit.withDescription(
                            merchant.merchantDescription() != null
                                    ? merchant.merchantDescription()
                                    : merchant.getName());
                } else {
                    if (tx.getTo() == null || tx.getTo().getNumber() == null) {
                        nextCredit = nextCredit.withAccount(BPAY_CLEARING_ACCOUNT_ID, BPAY_CLEARING_BSB);
                    }
                    nextDebit = nextDebit.withDescription(tx.getDescription());
                }
                yield singlePosting(nextCredit, nextDebit);
            }
            default -> null;
        };
    }

    private static BankingDoubleEntryPosting buildDigital(
            TransactionInstruction tx,
            BankingPostingLeg credit,
            BankingPostingLeg debit) {
        return switch (tx.getType()) {
            case WITHDRAWAL -> null;
            case DEPOSIT -> singlePosting(
                    credit.withDescription("Stimulus Deposit: " + tx.getDescription()),
                    debit.withDescription("Stimulus Funding: " + tx.getDescription()));
            case TRANSFER -> {
                String fromRef = tx.getFrom() != null && tx.getFrom().getNumber() != null
                        ? tx.getFrom().getNumber().toString()
                        : "external-source";
                String toRef = tx.getTo() != null && tx.getTo().getNumber() != null
                        ? tx.getTo().getNumber().toString()
                        : "external-beneficiary";
                yield singlePosting(
                        credit.withDescription("Transfer from Commbank App " + fromRef),
                        debit.withDescription("Transfer to CBA A/c CommBank app " + toRef));
            }
            case PAYMENT -> {
                BankingPostingLeg nextCredit = credit.withDescription("BPAY 040386");
                if (tx.getTo() == null) {
                    nextCredit = nextCredit.withAccount(BPAY_CLEARING_ACCOUNT_ID, BPAY_CLEARING_BSB);
                }
                yield singlePosting(
                        nextCredit,
                        debit.withDescription("BPay Payment CommBank app Biller Code 040386"));
            }
            case PAYROLL -> null;
            default -> null;
        };
    }

    private static BankingDoubleEntryPosting buildDirect(
            TransactionInstruction tx,
            BankingPostingLeg credit,
            BankingPostingLeg debit) {
        return switch (tx.getType()) {
            case WITHDRAWAL, DIRECTDEBIT -> singlePosting(
                    tx.getTo() == null
                            ? credit.withDescription("DD Interbank settlement ")
                                    .withAccount(INTERBANK_SETTLEMENT_ACCOUNT_ID, INTERBANK_SETTLEMENT_BSB)
                            : credit.withDescription("DD Interbank settlement "),
                    debit.withDescription("Direct Debit"));
            case DEPOSIT -> singlePosting(
                    tx.getTo() != null && tx.getTo().getNumber() != null
                            ? credit.withDescription("Direct Deposit " + tx.getDescription())
                                    .withAccount(tx.getTo().getNumber(), credit.bsb())
                            : credit.withDescription("Direct Deposit " + tx.getDescription()),
                    tx.getFrom() == null
                            ? debit.withDescription("DD Interbank settlement ")
                                    .withAccount(INTERBANK_SETTLEMENT_ACCOUNT_ID, INTERBANK_SETTLEMENT_BSB)
                            : debit.withDescription("DD Interbank settlement "));
            case TRANSFER -> {
                String fromRef = tx.getFrom() != null && tx.getFrom().getNumber() != null
                        ? tx.getFrom().getNumber().toString()
                        : "external-source";
                String toRef = tx.getTo() != null && tx.getTo().getNumber() != null
                        ? tx.getTo().getNumber().toString()
                        : "external-beneficiary";
                yield singlePosting(
                        credit.withDescription("Transfer from direct " + fromRef),
                        debit.withDescription("Transfer to direct " + toRef));
            }
            case PAYMENT -> singlePosting(
                    tx.getTo() == null
                            ? credit.withDescription("Payment Received: " + tx.getDescription())
                                    .withAccount(INTERBANK_SETTLEMENT_ACCOUNT_ID, INTERBANK_SETTLEMENT_BSB)
                            : credit.withDescription("Payment Received: " + tx.getDescription()),
                    debit.withDescription("Direct Payment: " + tx.getDescription()));
            case PAYROLL -> singlePosting(
                    credit.withDescription("Salary Credit: " + tx.getDescription()),
                    tx.getFrom() == null
                            ? debit.withDescription("Payroll Debit: " + tx.getDescription())
                                    .withAccount(INTERBANK_SETTLEMENT_ACCOUNT_ID, INTERBANK_SETTLEMENT_BSB)
                            : debit.withDescription("Payroll Debit: " + tx.getDescription()));
            default -> null;
        };
    }

    private static BankingDoubleEntryPosting singlePosting(BankingPostingLeg credit, BankingPostingLeg debit) {
        return new BankingDoubleEntryPosting(List.of(credit), List.of(debit));
    }

    private static BankingPostingLeg prefillLeg(
            TransactionInstruction tx,
            double amount,
            ZonedDateTime now,
            AccountingType type) {
        if (type == AccountingType.DEBIT) {
            if (tx.getFrom() != null && tx.getFrom().getNumber() != null) {
                return new BankingPostingLeg(
                        tx.getFrom().getNumber(),
                        tx.getFrom() instanceof TransactionAccount transactionAccount
                                ? transactionAccount.getBsb()
                                : INTERNAL_BSB,
                        amount,
                        "AUD",
                        now.toOffsetDateTime(),
                        null,
                        null);
            }
            return settlementLeg(INTERBANK_SETTLEMENT_ACCOUNT_ID, INTERBANK_SETTLEMENT_BSB, amount, now);
        }

        if (tx.getTo() != null && tx.getTo().getNumber() != null) {
            return new BankingPostingLeg(
                    tx.getTo().getNumber(),
                    tx.getTo() instanceof TransactionAccount transactionAccount
                            ? transactionAccount.getBsb()
                            : INTERNAL_BSB,
                    amount,
                    "AUD",
                    now.toOffsetDateTime(),
                    null,
                    null);
        }
        return settlementLeg(INTERBANK_SETTLEMENT_ACCOUNT_ID, INTERBANK_SETTLEMENT_BSB, amount, now);
    }

    private static BankingPostingLeg settlementLeg(
            long accountId,
            int bsb,
            double amount,
            ZonedDateTime now) {
        return new BankingPostingLeg(
                accountId,
                bsb,
                amount,
                "AUD",
                now.toOffsetDateTime(),
                null,
                null);
    }

    private enum AccountingType {
        DEBIT,
        CREDIT
    }
}
