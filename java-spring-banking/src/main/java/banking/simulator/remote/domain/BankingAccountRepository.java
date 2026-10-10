package banking.simulator.remote.domain;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;
import org.erdtman.jcs.JsonCanonicalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Bank-owned records. The host passes references, never an account checkpoint. */
@Repository
@ConditionalOnProperty(name = "banking.backend.enabled", havingValue = "true")
public class BankingAccountRepository {
    private static final int DEFAULT_STATEMENT_LIMIT = 12;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public BankingAccountRepository(ObjectMapper mapper,
            @Value("${banking.backend.jdbc-url}") String url,
            @Value("${banking.backend.username}") String username,
            @Value("${banking.backend.password}") String password,
            @Value("${banking.backend.maximum-pool-size:8}") int poolSize) {
        this.mapper = mapper;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("banking-account-db");
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS banking_customer (
                  context_ref VARCHAR(128) NOT NULL, subject_ref VARCHAR(128) NOT NULL,
                  version BIGINT NOT NULL, logical_time_epoch_ms BIGINT NOT NULL,
                  seed_request TEXT NOT NULL, PRIMARY KEY(context_ref, subject_ref))
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS banking_account (
                  context_ref VARCHAR(128) NOT NULL, subject_ref VARCHAR(128) NOT NULL,
                  account_id VARCHAR(128) NOT NULL, account_data TEXT NOT NULL,
                  PRIMARY KEY(context_ref, account_id),
                  FOREIGN KEY(context_ref, subject_ref) REFERENCES banking_customer(context_ref, subject_ref))
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS banking_statement (
                  context_ref VARCHAR(128) NOT NULL, subject_ref VARCHAR(128) NOT NULL,
                  bill_id VARCHAR(128) NOT NULL, account_id VARCHAR(128) NOT NULL, statement_data TEXT NOT NULL,
                  PRIMARY KEY(context_ref, bill_id),
                  FOREIGN KEY(context_ref, subject_ref) REFERENCES banking_customer(context_ref, subject_ref),
                  FOREIGN KEY(context_ref, account_id) REFERENCES banking_account(context_ref, account_id))
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS banking_native_context (
                  context_id VARCHAR(256) PRIMARY KEY, context_ref VARCHAR(128) NOT NULL,
                  subject_ref VARCHAR(128) NOT NULL, metadata_json TEXT NOT NULL, configuration_json TEXT NOT NULL,
                  UNIQUE(context_ref, subject_ref),
                  FOREIGN KEY(context_ref, subject_ref) REFERENCES banking_customer(context_ref, subject_ref))
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS banking_native_operation (
                  operation_key VARCHAR(512) PRIMARY KEY, request_digest VARCHAR(71) NOT NULL,
                  response_json TEXT NOT NULL, context_id VARCHAR(256) NOT NULL,
                  FOREIGN KEY(context_id) REFERENCES banking_native_context(context_id))
                """);
        // Period identity and chronological bounded reads also apply to pre-existing seeded statements.
        jdbc.execute("ALTER TABLE banking_statement ADD COLUMN IF NOT EXISTS statement_start_date VARCHAR(10)");
        jdbc.execute("ALTER TABLE banking_statement ADD COLUMN IF NOT EXISTS statement_end_date VARCHAR(10)");
        transactions.executeWithoutResult(status -> jdbc.query("SELECT context_ref,bill_id,statement_data FROM banking_statement WHERE statement_end_date IS NULL",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    var statement = mapper.readValue(row.getString("statement_data"), MAP);
                    jdbc.update("UPDATE banking_statement SET statement_start_date=?,statement_end_date=? WHERE context_ref=? AND bill_id=?",
                            statement.get("statementStartDate"), statement.get("statementEndDate"), row.getString("context_ref"), row.getString("bill_id"));
                }));
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS banking_statement_period_idx ON banking_statement(context_ref,account_id,statement_start_date,statement_end_date)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS banking_account_customer_idx ON banking_account(context_ref, subject_ref)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS banking_statement_customer_idx ON banking_statement(context_ref, subject_ref)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS banking_statement_customer_period_idx ON banking_statement(context_ref,subject_ref,statement_end_date DESC,bill_id DESC)");
    }

    public Map<String, Object> seed(String contextRef, String subjectRef, Map<String, Object> request) {
        reference(contextRef); reference(subjectRef);
        Map<String, Object> normalized = validateSeed(request);
        try {
            return transactions.execute(status -> {
                var existing = jdbc.queryForList("SELECT seed_request FROM banking_customer WHERE context_ref=? AND subject_ref=?",
                        String.class, contextRef, subjectRef);
                if (!existing.isEmpty()) {
                    if (!validateSeed(mapper.readValue(existing.getFirst(), MAP)).equals(normalized)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Customer is already populated with different banking data");
                    }
                    return transactions.execute(readStatus -> resourceView(lockedResource(contextRef, subjectRef)));
                }
                jdbc.update("INSERT INTO banking_customer(context_ref,subject_ref,version,logical_time_epoch_ms,seed_request) VALUES(?,?,1,?,?)",
                        contextRef, subjectRef, normalized.get("logicalTimeEpochMs"), mapper.writeValueAsString(normalized));
                for (Map<String, Object> account : maps(normalized.get("accounts"))) {
                    jdbc.update("INSERT INTO banking_account(context_ref,subject_ref,account_id,account_data) VALUES(?,?,?,?)",
                            contextRef, subjectRef, account.get("accountId"), mapper.writeValueAsString(account));
                }
                for (Map<String, Object> statement : maps(normalized.get("statements"))) {
                    insertStatement(contextRef, subjectRef, statement);
                }
                return transactions.execute(readStatus -> resourceView(lockedResource(contextRef, subjectRef)));
            });
        } catch (org.springframework.dao.DuplicateKeyException conflict) {
            // Concurrent seeding cannot overwrite another customer's account or mutate an existing seed.
            var existing = jdbc.queryForList("SELECT seed_request FROM banking_customer WHERE context_ref=? AND subject_ref=?",
                    String.class, contextRef, subjectRef);
            if (!existing.isEmpty() && validateSeed(mapper.readValue(existing.getFirst(), MAP)).equals(normalized)) {
                return transactions.execute(readStatus -> resourceView(lockedResource(contextRef, subjectRef)));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Banking customer/account identity is already populated");
        }
    }

    public Map<String, Object> read(String contextRef, String subjectRef) {
        return read(contextRef, subjectRef, null);
    }

    public Map<String, Object> read(String contextRef, String subjectRef, Map<String, Object> guard) {
        return read(contextRef, subjectRef, guard, null);
    }

    public Map<String, Object> read(String contextRef, String subjectRef, Map<String, Object> guard,
            String statementPeriod) {
        reference(contextRef); reference(subjectRef);
        if (statementPeriod != null && (!statementPeriod.matches("(?!0000)[0-9]{4}-(0[1-9]|1[0-2])"))) {
            throw new IllegalArgumentException("statementPeriod must be YYYY-MM with a year from 0001 to 9999");
        }
        YearMonth period = statementPeriod == null ? null : YearMonth.parse(statementPeriod);
        return transactions.execute(status -> {
            Resource resource = lockedResource(contextRef, subjectRef, period, DEFAULT_STATEMENT_LIMIT);
            if (!resource.metadata().isEmpty() || guard != null) {
                requireFields(guard, Set.of("contextId", "resourceVersion", "logicalTimeEpochMs"),
                        Set.of("contextId", "resourceVersion", "logicalTimeEpochMs"));
                if (!java.util.Objects.equals(resource.metadata().get("contextId"), guard.get("contextId"))
                        || exactLong(guard.get("resourceVersion")) != resource.version()
                        || exactLong(guard.get("logicalTimeEpochMs")) != resource.logicalTime()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Banking read context/version/logical time does not match the authoritative resource");
                }
            }
            if (period != null && resource.state().creditCardBills(101).size() > 100) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Requested statementPeriod has more than 100 statements; no partial result was returned");
            }
            Map<String, Object> view = new LinkedHashMap<>(resourceView(resource, period == null ? DEFAULT_STATEMENT_LIMIT : 100));
            if (period != null) view.put("statementPeriod", statementPeriod);
            return view;
        });
    }

    private Map<String, Object> resourceView(Resource resource) {
        return resourceView(resource, DEFAULT_STATEMENT_LIMIT);
    }

    private Map<String, Object> resourceView(Resource resource, int statementLimit) {
        return Map.of("contextRef", resource.contextRef(), "subjectRef", resource.subjectRef(),
                "version", resource.version(), "logicalTimeEpochMs", resource.logicalTime(),
                "accounts", resource.state().customerAccounts(true).stream().map(account -> {
                    var view = new LinkedHashMap<>(account);
                    for (String key : List.of("balance", "limit", "currentMinPayment")) {
                        if (view.containsKey(key)) view.put(key, new BigDecimal(String.valueOf(view.get(key))).stripTrailingZeros().toPlainString());
                    }
                    return view;
                }).toList(),
                "statements", resource.state().creditCardBills(statementLimit).stream().map(BankingAccountRepository::statementView).toList());
    }

    record Resource(String contextRef, String subjectRef, long version, long logicalTime,
            BankingRemoteDomainState state, Map<String, Object> metadata, Map<String, Object> configuration) {}
    record Transition<T>(T response, BankingRemoteDomainState state,
            Map<String, Object> metadata, Map<String, Object> configuration) {}

    /** The callback performs bounded local banking computation only, never network/model work. */
    <T> T transact(String contextRef, String subjectRef, String operationKey, Object request,
            long deadline, Class<T> responseType, Function<Resource, Transition<T>> operation) {
        reference(contextRef); reference(subjectRef);
        if (operationKey == null || operationKey.isBlank() || operationKey.length() > 512) {
            throw new IllegalArgumentException("A bounded idempotency key is required");
        }
        String digest = requestDigest(request);
        return transactions.execute(status -> {
            // All mutations and reads for a customer take this short per-customer lock.
            Resource resource = lockedResource(contextRef, subjectRef);
            var receipts = jdbc.queryForList("SELECT request_digest,response_json FROM banking_native_operation WHERE operation_key=?", operationKey);
            if (!receipts.isEmpty()) {
                if (!digest.equals(receipts.getFirst().get("request_digest"))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was already used with a different request");
                }
                return mapper.readValue((String) receipts.getFirst().get("response_json"), responseType);
            }
            if (deadline <= System.currentTimeMillis()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "New banking work is past its accepted deadline");
            }
            Transition<T> transition = operation.apply(resource);
            var metadata = transition.metadata();
            String contextId = String.valueOf(metadata.get("contextId"));
            if (resource.metadata().isEmpty()) {
                jdbc.update("INSERT INTO banking_native_context(context_id,context_ref,subject_ref,metadata_json,configuration_json) VALUES(?,?,?,?,?)",
                        contextId, contextRef, subjectRef, mapper.writeValueAsString(metadata), mapper.writeValueAsString(transition.configuration()));
            } else {
                if (!contextId.equals(resource.metadata().get("contextId"))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Banking customer already has a simulation authority");
                }
                jdbc.update("UPDATE banking_native_context SET metadata_json=? WHERE context_id=?",
                        mapper.writeValueAsString(metadata), contextId);
            }
            for (var account : maps(transition.state().data().get("accounts"))) {
                jdbc.update("UPDATE banking_account SET account_data=? WHERE context_ref=? AND subject_ref=? AND account_id=?",
                        mapper.writeValueAsString(account), contextRef, subjectRef, account.get("accountId"));
            }
            for (var event : transition.state().lastEvidenceEvents()) {
                if ("remote_domain_credit_card_statement_closed".equals(event.get("eventType"))
                        && new BigDecimal(String.valueOf(event.get("closingAmount"))).compareTo(new BigDecimal("-0.01")) < 0) {
                    insertStatement(contextRef, subjectRef, statementView(event));
                }
            }
            jdbc.update("UPDATE banking_customer SET version=?,logical_time_epoch_ms=? WHERE context_ref=? AND subject_ref=?",
                    exactLong(metadata.get("resourceVersion")), exactLong(metadata.get("processedThroughEpochMs")), contextRef, subjectRef);
            jdbc.update("INSERT INTO banking_native_operation(operation_key,request_digest,response_json,context_id) VALUES(?,?,?,?)",
                    operationKey, digest, mapper.writeValueAsString(transition.response()), contextId);
            return transition.response();
        });
    }

    private Resource lockedResource(String contextRef, String subjectRef) {
        // Native banking calculations retain their existing bounded working history.
        return lockedResource(contextRef, subjectRef, null, 100);
    }

    private Resource lockedResource(String contextRef, String subjectRef, YearMonth statementPeriod, int unfilteredStatementLimit) {
        var rows = jdbc.queryForList("SELECT version,logical_time_epoch_ms FROM banking_customer WHERE context_ref=? AND subject_ref=? FOR UPDATE",
                contextRef, subjectRef);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Banking customer context not found");
        var accounts = jdbc.query("SELECT account_data FROM banking_account WHERE context_ref=? AND subject_ref=? ORDER BY account_id",
                (rs, row) -> mapper.readValue(rs.getString(1), MAP), contextRef, subjectRef);
        for (var account : accounts) {
            account.put("id", UUID.nameUUIDFromBytes((contextRef + ":" + subjectRef + ":" + account.get("accountId")).getBytes(StandardCharsets.UTF_8)).toString());
        }
        // Select the requested month before bounding the result, including history older than the default response window.
        // Inclusive month end also supports 9999-12 without constructing a year-10000 upper bound.
        var statements = statementPeriod == null
                ? jdbc.query("SELECT statement_data FROM banking_statement WHERE context_ref=? AND subject_ref=? ORDER BY statement_end_date DESC,bill_id DESC LIMIT ?",
                        (rs, row) -> mapper.readValue(rs.getString(1), MAP), contextRef, subjectRef, unfilteredStatementLimit)
                : jdbc.query("SELECT statement_data FROM banking_statement WHERE context_ref=? AND subject_ref=? AND statement_end_date>=? AND statement_end_date<=? ORDER BY statement_end_date DESC,bill_id DESC LIMIT 101",
                        (rs, row) -> mapper.readValue(rs.getString(1), MAP), contextRef, subjectRef,
                        statementPeriod.atDay(1).toString(), statementPeriod.atEndOfMonth().toString());
        java.util.Collections.reverse(statements);
        var contexts = jdbc.queryForList("SELECT metadata_json,configuration_json FROM banking_native_context WHERE context_ref=? AND subject_ref=?",
                contextRef, subjectRef);
        return new Resource(contextRef, subjectRef, ((Number) rows.getFirst().get("version")).longValue(),
                ((Number) rows.getFirst().get("logical_time_epoch_ms")).longValue(),
                new BankingRemoteDomainState(Map.of("accounts", accounts, "creditCardBills", statements)),
                contexts.isEmpty() ? Map.of() : mapper.readValue((String) contexts.getFirst().get("metadata_json"), MAP),
                contexts.isEmpty() ? Map.of() : mapper.readValue((String) contexts.getFirst().get("configuration_json"), MAP));
    }

    private void insertStatement(String contextRef, String subjectRef, Map<String, Object> statement) {
        jdbc.update("INSERT INTO banking_statement(context_ref,subject_ref,bill_id,account_id,statement_data,statement_start_date,statement_end_date) VALUES(?,?,?,?,?,?,?)",
                contextRef, subjectRef, statement.get("billId"), statement.get("accountId"), mapper.writeValueAsString(statement),
                statement.get("statementStartDate"), statement.get("statementEndDate"));
    }

    private static Map<String, Object> statementView(Map<String, Object> source) {
        Map<String, Object> view = new LinkedHashMap<>();
        for (String field : List.of("billId", "accountId", "closingAmount", "minimumAmountDue", "currency", "statementStartDate", "statementEndDate", "dueDate")) {
            Object value = source.get(field);
            if (List.of("closingAmount", "minimumAmountDue").contains(field)) {
                value = new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
            }
            view.put(field, value);
        }
        return view;
    }

    static long exactLong(Object raw) {
        if (!(raw instanceof Number)) throw new IllegalArgumentException("An exact integer is required");
        try { return new BigDecimal(raw.toString()).longValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("An exact integer is required", invalid); }
    }

    private String requestDigest(Object value) {
        try {
            byte[] canonical = new JsonCanonicalizer(mapper.writeValueAsString(value)).getEncodedUTF8();
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException invalid) {
            throw new IllegalArgumentException("Invalid canonical banking request", invalid);
        }
    }

    static String reference(Object value) {
        if (!(value instanceof String text) || !text.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("Banking references must be 1-128 letters, digits or _.:-");
        }
        return text;
    }

    private static Map<String, Object> validateSeed(Map<String, Object> request) {
        requireFields(request, Set.of("logicalTimeEpochMs", "accounts", "statements"), Set.of("logicalTimeEpochMs", "accounts", "statements"));
        Object rawTime = request.get("logicalTimeEpochMs");
        if (!(rawTime instanceof Number number) || !number.toString().matches("[0-9]+")) {
            throw new IllegalArgumentException("logicalTimeEpochMs must be an integer");
        }
        long time = Long.parseLong(number.toString());
        if (time > 253402300799998L) throw new IllegalArgumentException("logicalTimeEpochMs is outside the supported simulation range");
        List<Map<String, Object>> accounts = maps(request.get("accounts"));
        List<Map<String, Object>> statements = maps(request.get("statements"));
        if (accounts.isEmpty() || accounts.size() > 100 || statements.size() > 100) {
            throw new IllegalArgumentException("A customer requires 1-100 accounts and at most 100 initial statements");
        }
        Map<String, String> accountCurrencies = new LinkedHashMap<>();
        List<Map<String, Object>> normalizedAccounts = new ArrayList<>();
        for (var account : accounts) {
            requireFields(account, Set.of("accountId", "name", "productCode", "balance", "currency", "limit", "currentMinPayment"),
                    Set.of("accountId", "name", "productCode", "balance", "currency"));
            var normalized = new LinkedHashMap<>(account);
            String id = reference(account.get("accountId"));
            String currency = currency(account.get("currency"));
            if (accountCurrencies.putIfAbsent(id, currency) != null) throw new IllegalArgumentException("Duplicate accountId");
            for (String field : List.of("name", "productCode")) {
                if (!(account.get(field) instanceof String text) || text.isBlank() || text.length() > 256) {
                    throw new IllegalArgumentException("Invalid account " + field);
                }
            }
            normalized.put("balance", decimal(account.get("balance"), false));
            for (String field : List.of("limit", "currentMinPayment")) {
                if (account.containsKey(field)) normalized.put(field, decimal(account.get(field), true));
            }
            normalizedAccounts.add(normalized);
        }
        Set<String> billIds = new java.util.HashSet<>();
        List<Map<String, Object>> normalizedStatements = new ArrayList<>();
        for (var statement : statements) {
            Set<String> fields = Set.of("billId", "accountId", "closingAmount", "minimumAmountDue", "currency", "statementStartDate", "statementEndDate", "dueDate");
            requireFields(statement, fields, fields);
            if (!billIds.add(reference(statement.get("billId")))) throw new IllegalArgumentException("Duplicate billId");
            String account = reference(statement.get("accountId"));
            if (!currency(statement.get("currency")).equals(accountCurrencies.get(account))) {
                throw new IllegalArgumentException("Statement must belong to this customer's account and currency");
            }
            var normalized = new LinkedHashMap<>(statement);
            normalized.put("closingAmount", decimal(statement.get("closingAmount"), false));
            normalized.put("minimumAmountDue", decimal(statement.get("minimumAmountDue"), true));
            LocalDate start = LocalDate.parse(String.valueOf(statement.get("statementStartDate")));
            LocalDate end = LocalDate.parse(String.valueOf(statement.get("statementEndDate")));
            LocalDate due = LocalDate.parse(String.valueOf(statement.get("dueDate")));
            if (start.isAfter(end) || end.isAfter(due)
                    || end.isAfter(java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneOffset.UTC).toLocalDate())) {
                throw new IllegalArgumentException("Statement dates must be ordered and already closed at the seeded logical time");
            }
            normalizedStatements.add(normalized);
        }
        return Map.of("logicalTimeEpochMs", time, "accounts", normalizedAccounts, "statements", normalizedStatements);
    }

    private static String decimal(Object value, boolean nonnegative) {
        if (!(value instanceof String text) || !text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?") || text.length() > 40) {
            throw new IllegalArgumentException("Amounts must be bounded decimal strings");
        }
        BigDecimal number = new BigDecimal(text);
        if (number.precision() > 28 || number.scale() > 10 || (nonnegative && number.signum() < 0)) {
            throw new IllegalArgumentException("Amount outside supported precision or range");
        }
        return number.stripTrailingZeros().toPlainString();
    }

    private static String currency(Object value) {
        if (!(value instanceof String text) || !text.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid currency");
        return text;
    }

    private static void requireFields(Map<String, Object> value, Set<String> allowed, Set<String> required) {
        if (value == null || !allowed.containsAll(value.keySet()) || !value.keySet().containsAll(required)) {
            throw new IllegalArgumentException("Invalid banking fields; allowed=" + allowed + ", required=" + required);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object raw) {
        if (!(raw instanceof List<?> values) || values.stream().anyMatch(value -> !(value instanceof Map<?, ?>))) {
            throw new IllegalArgumentException("Expected an array of banking records");
        }
        return (List<Map<String, Object>>) raw;
    }

    @PreDestroy
    public void close() { dataSource.close(); }
}
