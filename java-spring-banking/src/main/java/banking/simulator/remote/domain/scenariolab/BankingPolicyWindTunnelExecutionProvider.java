package banking.simulator.remote.domain.scenariolab;

import ai.astraform.remote.domain.sdk.EvaluationRunReport;
import ai.astraform.remote.domain.sdk.RegressionDeltaReport;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArchivePayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArtifactMetadata;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArtifactPayload;
import ai.astraform.remote.domain.sdk.DomainOutcomeScorecardEvaluator;
import ai.astraform.remote.domain.sdk.PolicyExpressionContext;
import ai.astraform.remote.domain.sdk.PolicyExpressionStage;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelStoredRun;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelRunUpdate;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionProvider;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelRunRecord;
import ai.astraform.remote.domain.sdk.RemoteDomainProtocolException;
import ai.astraform.remote.domain.openapi.models.RemoteDomainErrorEnvelopeError;
import banking.simulator.remote.domain.BankingRemotePolicyWindTunnelExecutionContext;
import banking.simulator.remote.domain.BankingRemotePolicyWindTunnelExecutionContext.ExecutionClaim;
import banking.simulator.remote.domain.BankingRemotePolicyWindTunnelExecutionContext.LaunchAdmission;
import banking.simulator.remote.domain.BankingRemotePolicyWindTunnelExecutionContext.LaunchAdmissionOutcome;
import banking.simulator.remote.domain.BankingRemotePolicyWindTunnelExecutionContext.LaunchExecutionDirective;
import banking.simulator.remote.domain.BankingRemotePolicyWindTunnelExecutionContext.StaleExecutionClaimException;
import banking.simulator.remote.domain.BankingClockDrivenRateChangePlan;
import banking.simulator.remote.domain.BankingRemotePolicyWindTunnelService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.*;

/**
 * Banking-owned execution provider for the Policy Wind Tunnel.
 */
public final class BankingPolicyWindTunnelExecutionProvider implements PolicyWindTunnelExecutionProvider {

    private static final Logger log = LoggerFactory.getLogger(BankingPolicyWindTunnelExecutionProvider.class);
    private static final String EXECUTION_MODE_CLOCK_DRIVEN_HYBRID = "CLOCK_DRIVEN_HYBRID";
    private static final String LAUNCH_DIGEST_PREFIX = "sha256:";
    private static final String AGENT_DECISION_MODE_DETERMINISTIC = "DETERMINISTIC";
    private static final String AGENT_DECISION_MODE_STUB_LLM = "STUB_LLM";
    private static final String AGENT_DECISION_MODE_REAL_LLM = "REAL_LLM";
    private static final String COHORT_SNAPSHOT_SCHEMA = "scenario_lab_cohort_artifact_snapshot.v1";
    private static final int MAX_COHORT_SNAPSHOT_BYTES = 16 * 1024 * 1024;
    private static final String ACTIVATION_SETTINGS_SNAPSHOT_SCHEMA =
            "scenario_lab_pinned_activation_settings_snapshot.v1";
    private static final String CLOCK_DRIVEN_INTERVENTION_PLAN_FILE = "clock-driven-intervention-plan.json";
    private static final String CLOCK_DRIVEN_INTERVENTION_INSTALLATION_FILE =
            "clock-driven-rate-change-installation.json";
    private static final String CLOCK_DRIVEN_INTERVENTION_PLAN_DIGEST_FIELD =
            "clockDrivenInterventionPlanDigest";
    private static final String CLOCK_DRIVEN_INTERVENTION_PLAN_SCHEMA_FIELD =
            "clockDrivenInterventionPlanSchemaVersion";
    private static final int MAX_ACTIVATION_SETTINGS_BYTES = 16 * 1024;
    private static final int MAX_CORRELATION_AUDIT_BYTES = 4 * 1024 * 1024;
    private static final String OPPORTUNITY_RECONCILIATION_SCHEMA_VERSION =
            "customer_decision_opportunity_reconciliation.v1";
    private static final String OPPORTUNITY_AGENT_AUDIT_SCHEMA_VERSION =
            "customer_decision_opportunity_agent_audit.v1";
    private static final String OPPORTUNITY_CORRELATION_AUDIT_SCHEMA_VERSION =
            "customer_decision_opportunity_correlation_audit.v1";
    private static final String RATE_CHANGE_PROOF_SCHEMA_VERSION =
            "banking.clock-driven-rate-change-proof.v1";
    private static final String RATE_CHANGE_TRIGGER_TYPE = "rate_or_policy_change";
    private static final Set<String> RATE_CHANGE_TRIGGER_PAYLOAD_FIELDS = Set.of(
            "policyVersion",
            "numericEncoding",
            "normalizedEventType",
            "sourceEventType",
            "eventType",
            "businessEventId",
            "simulationRunId",
            "customerId",
            "interventionId",
            "operationId",
            "planDigest",
            "occurrenceKey",
            "workId",
            "productCode",
            "scheduledForEpochMs",
            "effectiveAtEpochMs",
            "appliedAtEpochMs",
            "affectedAccountCount",
            "oldRate",
            "newRate",
            "outcome");
    private static final Set<String> OPPORTUNITY_CORRELATION_AUDIT_FIELDS = Set.of(
            "schemaVersion",
            "simulationRunId",
            "triggerType",
            "opportunityCount",
            "opportunities",
            "asOf");
    private static final Set<String> OPPORTUNITY_CORRELATION_ROW_FIELDS = Set.of(
            "opportunityId",
            "agentId",
            "domainId",
            "triggerId",
            "triggerType",
            "triggerRef",
            "scheduledForEpochMs",
            "priority",
            "reason",
            "status",
            "attemptCount",
            "decisionMode",
            "triggerDigest",
            "payload",
            "evidenceRefs",
            "decisionEventId",
            "decisionAttempt",
            "decisionContractValid");
    private static final String OPPORTUNITY_AUDIT_TOTALS_SCHEMA_VERSION =
            "customer_decision_opportunity_audit_totals.v1";
    private static final String OPPORTUNITY_GENERATION_MODE = "EVENT_DRIVEN_DECISION_TRIGGERS";
    private static final Set<String> OPPORTUNITY_LIFECYCLE_STATUSES = Set.of(
            "PENDING", "RUNNING", "SUCCEEDED", "DEFERRED", "BLOCKED", "FAILED", "CANCELLED");
    // customer_decision_opportunity_lifecycle_event.v1 DEFERRED bindings.
    private static final Set<String> OPPORTUNITY_DEFERRED_REASON_CODES_V1 = Set.of(
            "background_admission_limit",
            "background_turn_capacity_timeout",
            "background_turn_capacity_interrupted",
            "permit_timeout",
            "autonomous_backfill_window_limit",
            "agent_not_active",
            "worker_lease_expired",
            "worker_transient_error",
            "provider_pacing_limit",
            "provider_unavailable",
            "provider_rate_limited",
            "provider_timeout",
            "provider_transient_error",
            "opportunity_worker_authorization_unavailable");
    private static final String LEGACY_UNSPECIFIED_DEFERRED_REASON_CODE = "unspecified";
    private static final Set<String> OPPORTUNITY_RECONCILIATION_STATUSES = Set.of(
            "UNKNOWN_RUN",
            "GENERATION_FAILED",
            "INITIALIZATION_MANIFEST_OPEN",
            "INITIALIZATION_MANIFEST_UNVERIFIED",
            "INITIALIZATION_MANIFEST_INCONSISTENT",
            "INITIALIZATION_PENDING",
            "TICK_FAILURE_LEDGER_INCONSISTENT",
            "TICK_FAILURE_DEBT",
            "GENERATION_OPEN",
            "EMPTY",
            "DRAINING",
            "RECONCILED",
            "INCONSISTENT");
    private static final String LIFECYCLE_ACTION_CANCEL = "CANCEL";
    private static final String LIFECYCLE_ACTION_PAUSE = "PAUSE";
    private static final String LIFECYCLE_ACTION_RESUME = "RESUME";
    private static final String LIFECYCLE_ACTION_SET_PLAYBACK_RATE = "SET_PLAYBACK_RATE";
    private static final String LIFECYCLE_ACTION_FINALIZE = "FINALIZE";
    private static final String FINALIZATION_OPERATION_METADATA = "scenarioLabFinalizationOperation";
    private static final String FINALIZATION_OPERATION_PREFIX = "scenario-lab-finalize:";
    private static final String FINALIZATION_MODE_FIELD = "opportunityGenerationFinalizationMode";
    private static final String FINALIZATION_MODE_LEGACY_V1 = "LEGACY_V1";
    private static final String FINALIZATION_MODE_PLATFORM_OWNED_V1 = "PLATFORM_OWNED_V1";
    private static final String FINALIZATION_MODE_PLATFORM_OWNED_V2 = "PLATFORM_OWNED_V2";
    private static final String FINALIZATION_RECEIPT_SCHEMA =
            "scenario_lab_provider_finalization_receipt.v2";
    private static final String FINALIZATION_RECEIPT_PREPARED = "PREPARED";
    private static final String FINALIZATION_RECEIPT_PUBLISHED = "PUBLISHED";
    private static final String FINALIZATION_NOTIFICATION_PENDING = "PENDING";
    private static final String FINALIZATION_NOTIFICATION_CONFIRMED = "CONFIRMED";
    private static final String FINALIZATION_NOTIFICATION_NOT_REQUIRED = "NOT_REQUIRED";
    private static final String FINALIZATION_RECEIPT_FILE = "finalization-receipt.json";
    private static final String FINALIZATION_RECEIPT_LOCK_FILE = "finalization-receipt.lock";
    private static final Set<String> FINALIZATION_RECEIPT_FIELDS = Set.of(
            "schemaVersion", "runId", "finalizationMode", "providerRevision", "operationId",
            "inputDigest", "state", "completionNotificationState", "completionNotificationPayload",
            "runSnapshot", "artifactManifest", "updatedAt");
    private static final Set<String> FINALIZATION_RUN_SNAPSHOT_FIELDS = Set.of(
            "status", "summaryPath", "bundlePath", "startedAtEpochMs", "completedAtEpochMs", "metadata");
    private static final Set<String> FINALIZATION_ARTIFACT_FIELDS = Set.of(
            "artifactRef", "contentType", "sha256", "byteLength");
    private static final Set<String> FINALIZATION_NOTIFICATION_FIELDS = Set.of(
            "runId", "status", "source", "summaryPath", "bundlePath", "operationId",
            "inputDigest", "providerRevision", "completedAtEpochMs", "metadata");
    private static final Map<String, String> FINALIZATION_ARTIFACT_CONTENT_TYPES = Map.ofEntries(
            Map.entry("summary", "application/json"),
            Map.entry("impactedCohortsCsv", "text/csv"),
            Map.entry("experimentCard", "text/markdown"),
            Map.entry("comparisonReport", "application/json"),
            Map.entry("checkpointsJson", "application/json"),
            Map.entry("agentDiffsJson", "application/json"),
            Map.entry("notableActionsJson", "application/json"),
            Map.entry("scheduledWorkFailuresJson", "application/json"),
            Map.entry("simulationTimelineJson", "application/json"),
            Map.entry("sideEffectLedgerJson", "application/json"),
            Map.entry("domainOutcomeScoreJson", "application/json"),
            Map.entry("domainOutcomeScoresJson", "application/json"),
            Map.entry("branchStateProjectionJson", "application/json"),
            Map.entry("hybridAgentAuditJson", "application/json"));
    private static final ConcurrentMap<Path, Object> FINALIZATION_RECEIPT_LOCKS =
            new ConcurrentHashMap<>();
    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String STATUS_READY_TO_FINALIZE = "READY_TO_FINALIZE";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String PHASE_QUEUED = "QUEUED";
    private static final String PHASE_PREPARING_CLOCK = "PREPARING_CLOCK";
    private static final String PHASE_ACTIVATING_COHORT = "ACTIVATING_COHORT";
    private static final String PHASE_CLOCK_RUNNING = "CLOCK_RUNNING";
    private static final String PHASE_READY_TO_FINALIZE = "READY_TO_FINALIZE";
    private static final String PHASE_FINALIZING_REPORT = "FINALIZING_REPORT";
    private static final String PHASE_PAUSED = "PAUSED";
    private static final String PHASE_COMPLETED = "COMPLETED";
    private static final String PHASE_CANCELLED = "CANCELLED";
    private static final String PHASE_FAILED = "FAILED";
    private static final String TRANSITION_CANCEL = "CANCEL";
    private static final String TRANSITION_PAUSE = "PAUSE";
    private static final String TRANSITION_RESUME_SETUP = "RESUME_SETUP";
    private static final String TRANSITION_RESUME_CLOCK = "RESUME_CLOCK";
    private static final String TRANSITION_HORIZON_READY = "HORIZON_READY";
    private static final String TRANSITION_FINALIZE_START = "FINALIZE_START";
    private static final String TRANSITION_FINALIZE_COMPLETE = "FINALIZE_COMPLETE";
    private static final String TRANSITION_WORKER_PREPARE = "WORKER_PREPARE";
    private static final String TRANSITION_WORKER_ACTIVATE = "WORKER_ACTIVATE";
    private static final String TRANSITION_WORKER_START_CLOCK = "WORKER_START_CLOCK";
    private static final String TRANSITION_FAIL = "FAIL";
    private static final Map<String, LifecycleTransitionRule> CLOCK_DRIVEN_LIFECYCLE_TRANSITIONS =
            Map.ofEntries(
                    transition(
                            TRANSITION_WORKER_PREPARE,
                            Set.of(
                                    state(STATUS_QUEUED, PHASE_QUEUED),
                                    state(STATUS_RUNNING, PHASE_QUEUED)),
                            state(STATUS_RUNNING, PHASE_PREPARING_CLOCK)),
                    transition(
                            TRANSITION_WORKER_ACTIVATE,
                            state(STATUS_RUNNING, PHASE_PREPARING_CLOCK),
                            state(STATUS_RUNNING, PHASE_ACTIVATING_COHORT)),
                    transition(
                            TRANSITION_WORKER_START_CLOCK,
                            state(STATUS_RUNNING, PHASE_ACTIVATING_COHORT),
                            state(STATUS_RUNNING, PHASE_CLOCK_RUNNING)),
                    transition(
                            TRANSITION_PAUSE,
                            Set.of(
                                    state(STATUS_RUNNING, PHASE_PREPARING_CLOCK),
                                    state(STATUS_RUNNING, PHASE_ACTIVATING_COHORT),
                                    state(STATUS_RUNNING, PHASE_CLOCK_RUNNING),
                                    state(STATUS_READY_TO_FINALIZE, PHASE_READY_TO_FINALIZE)),
                            state(STATUS_PAUSED, PHASE_PAUSED)),
                    transition(
                            TRANSITION_RESUME_SETUP,
                            state(STATUS_PAUSED, PHASE_PAUSED),
                            state(STATUS_RUNNING, PHASE_PREPARING_CLOCK)),
                    transition(
                            TRANSITION_RESUME_CLOCK,
                            state(STATUS_PAUSED, PHASE_PAUSED),
                            state(STATUS_RUNNING, PHASE_CLOCK_RUNNING)),
                    transition(
                            TRANSITION_HORIZON_READY,
                            state(STATUS_RUNNING, PHASE_CLOCK_RUNNING),
                            state(STATUS_READY_TO_FINALIZE, PHASE_READY_TO_FINALIZE)),
                    transition(
                            TRANSITION_FINALIZE_START,
                            Set.of(
                                    state(STATUS_RUNNING, PHASE_CLOCK_RUNNING),
                                    state(STATUS_READY_TO_FINALIZE, PHASE_READY_TO_FINALIZE),
                                    state(STATUS_PAUSED, PHASE_PAUSED)),
                            state(STATUS_RUNNING, PHASE_FINALIZING_REPORT)),
                    transition(
                            TRANSITION_FINALIZE_COMPLETE,
                            state(STATUS_RUNNING, PHASE_FINALIZING_REPORT),
                            state(STATUS_COMPLETED, PHASE_COMPLETED)),
                    transition(
                            TRANSITION_CANCEL,
                            Set.of(
                                    state(STATUS_QUEUED, PHASE_QUEUED),
                                    state(STATUS_RUNNING, PHASE_QUEUED),
                                    state(STATUS_RUNNING, PHASE_PREPARING_CLOCK),
                                    state(STATUS_RUNNING, PHASE_ACTIVATING_COHORT),
                                    state(STATUS_RUNNING, PHASE_CLOCK_RUNNING),
                                    state(STATUS_READY_TO_FINALIZE, PHASE_READY_TO_FINALIZE),
                                    state(STATUS_RUNNING, PHASE_FINALIZING_REPORT),
                                    state(STATUS_PAUSED, PHASE_PAUSED)),
                            state(STATUS_CANCELLED, PHASE_CANCELLED)),
                    transition(
                            TRANSITION_FAIL,
                            Set.of(
                                    state(STATUS_QUEUED, PHASE_QUEUED),
                                    state(STATUS_RUNNING, PHASE_QUEUED),
                                    state(STATUS_RUNNING, PHASE_PREPARING_CLOCK),
                                    state(STATUS_RUNNING, PHASE_ACTIVATING_COHORT),
                                    state(STATUS_RUNNING, PHASE_CLOCK_RUNNING),
                                    state(STATUS_RUNNING, PHASE_FINALIZING_REPORT)),
                            state(STATUS_FAILED, PHASE_FAILED)));
    private static final int HYBRID_ACTIVATION_CHUNK_SIZE = 50;
    private static final int HYBRID_ACTIVATION_INIT_MAX_CONCURRENCY = intEnv(
            "ASTRAFORM_HYBRID_ACTIVATION_INIT_MAX_CONCURRENCY",
            4);
    private static final long HYBRID_REPLAY_SEED = 20_260_306L;
    private static final double HYBRID_FAST_PLAYBACK_RATE = 1_800.0d;
    private static final double HYBRID_NO_TOKEN_EVENT_ONLY_PLAYBACK_RATE = 7_200.0d;
    private static final double HYBRID_REAL_LLM_MAX_PLAYBACK_RATE = doubleEnv(
            "ASTRAFORM_REAL_LLM_CANARY_MAX_PLAYBACK_RATE",
            600.0d);
    private static final double HYBRID_MIN_PLAYBACK_RATE = 0.1d;
    private static final int REAL_LLM_CANARY_MAX_CUSTOMERS = intEnv(
            "ASTRAFORM_REAL_LLM_CANARY_MAX_CUSTOMERS",
            10);
    private static final int REAL_LLM_CANARY_MAX_HORIZON_DAYS = intEnv(
            "ASTRAFORM_REAL_LLM_CANARY_MAX_HORIZON_DAYS",
            30);
    private static final int REAL_LLM_CANARY_MAX_CALLS = intEnv(
            "ASTRAFORM_REAL_LLM_CANARY_MAX_CALLS",
            70);
    private static final double REAL_LLM_CANARY_MAX_USD = doubleEnv(
            "ASTRAFORM_REAL_LLM_CANARY_MAX_USD",
            1.0d);
    private static final int REAL_LLM_CANARY_MAX_INPUT_TOKENS = intEnv(
            "ASTRAFORM_REAL_LLM_CANARY_MAX_INPUT_TOKENS",
            150_000);
    private static final int REAL_LLM_CANARY_MAX_OUTPUT_TOKENS = intEnv(
            "ASTRAFORM_REAL_LLM_CANARY_MAX_OUTPUT_TOKENS",
            25_000);
    private static final String REAL_LLM_DEFAULT_PROVIDER = stringEnv(
            "ASTRAFORM_REAL_LLM_CANARY_PROVIDER",
            "openai");
    private static final String REAL_LLM_DEFAULT_MODEL = stringEnv(
            "ASTRAFORM_REAL_LLM_CANARY_MODEL",
            "gemini-2.5-flash-lite");
    private static final long MILLIS_PER_SIMULATION_DAY = 86_400_000L;
    private static final int HYBRID_CLOCK_DRIVEN_MAX_COHORT_SIZE = intEnv(
            "ASTRAFORM_HYBRID_CLOCK_DRIVEN_MAX_COHORT_SIZE",
            100);
    private static final int HYBRID_STATE_MUTATION_SAMPLE_LIMIT = 100;
    private static final int HYBRID_DECISION_AUDIT_SETTLE_TIMEOUT_MILLIS = intEnv(
            "ASTRAFORM_HYBRID_DECISION_AUDIT_SETTLE_TIMEOUT_MILLIS",
            120_000);
    private static final int HYBRID_DECISION_AUDIT_SETTLE_POLL_MILLIS = intEnv(
            "ASTRAFORM_HYBRID_DECISION_AUDIT_SETTLE_POLL_MILLIS",
            250);
    private static final int HYBRID_DECISION_AUDIT_STABLE_SAMPLES = intEnv(
            "ASTRAFORM_HYBRID_DECISION_AUDIT_STABLE_SAMPLES",
            4);
    private static final int RUNTIME_TICK_DRAIN_STABLE_SAMPLES = intEnv(
            "ASTRAFORM_RUNTIME_TICK_DRAIN_STABLE_SAMPLES",
            20);
    private static final int RUNTIME_TICK_DRAIN_TIMEOUT_MILLIS = intEnv(
            "ASTRAFORM_RUNTIME_TICK_DRAIN_TIMEOUT_MILLIS",
            180_000);
    private static final int FINALIZATION_NOTIFICATION_RETRY_BASE_MILLIS = Math.max(
            50,
            intEnv("ASTRAFORM_FINALIZATION_NOTIFICATION_RETRY_BASE_MILLIS", 1_000));
    private static final int FINALIZATION_NOTIFICATION_RETRY_MAX_MILLIS = Math.max(
            FINALIZATION_NOTIFICATION_RETRY_BASE_MILLIS,
            intEnv("ASTRAFORM_FINALIZATION_NOTIFICATION_RETRY_MAX_MILLIS", 60_000));

    private final ObjectMapper objectMapper;
    private final BankingPolicyScenarioLabPresetService presetService;
    private final BankingPolicyWindTunnelEvidenceService evidenceService;
    private final DomainOutcomeScorecardEvaluator scorecardEvaluator;
    private final RestTemplate restTemplate;
    private final Executor executor;
    private final LongSupplier currentTimeMillis;
    private final Runnable afterLaunchAdmission;
    private final BiConsumer<String, String> afterFinalizationReceiptPrepared;
    private final ConcurrentMap<String, Object> lifecycleLocks = new ConcurrentHashMap<>();
    private final Set<String> pendingFinalizationNotificationRetries = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Integer> finalizationNotificationRetryAttempts =
            new ConcurrentHashMap<>();
    private record LifecycleState(String status, String phase) {
        private LifecycleState {
            status = stringValue(status).toUpperCase(Locale.ROOT);
            phase = stringValue(phase).toUpperCase(Locale.ROOT);
        }
    }

    private record LifecycleTransitionRule(
            String name,
            Set<LifecycleState> sources,
            LifecycleState target) {
        boolean allows(String status, String phase) {
            return sources.contains(state(status, phase));
        }
    }

    private record CohortArtifactSnapshot(
            String artifactDigest,
            String identitySetDigest,
            byte[] content) {
        private CohortArtifactSnapshot {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    private record ActivationSettingsSnapshot(
            String digest,
            String encodedContent,
            Map<String, Object> settings) {
        private ActivationSettingsSnapshot {
            settings = Map.copyOf(settings);
        }
    }

    record RateChangeProofAssessment(
            boolean required,
            boolean chainIntegrityVerified,
            boolean realizedReactionVerified,
            String reason,
            Map<String, Object> evidence) {
        RateChangeProofAssessment {
            reason = stringValue(reason);
            evidence = Map.copyOf(new LinkedHashMap<>(evidence));
        }

        boolean verified() {
            return chainIntegrityVerified;
        }
    }

    public BankingPolicyWindTunnelExecutionProvider() {
        this(
                new ObjectMapper(),
                patchCapableRestTemplate(),
                Executors.newCachedThreadPool(),
                System::currentTimeMillis,
                () -> {
                },
                (runId, operationId) -> {
                });
    }

    public BankingPolicyWindTunnelExecutionProvider(
            ObjectMapper objectMapper,
            RestTemplate restTemplate,
            Executor executor) {
        this(objectMapper, restTemplate, executor, System::currentTimeMillis, () -> {
        }, (runId, operationId) -> {
        });
    }

    BankingPolicyWindTunnelExecutionProvider(
            ObjectMapper objectMapper,
            RestTemplate restTemplate,
            Executor executor,
            LongSupplier currentTimeMillis) {
        this(objectMapper, restTemplate, executor, currentTimeMillis, () -> {
        }, (runId, operationId) -> {
        });
    }

    BankingPolicyWindTunnelExecutionProvider(
            ObjectMapper objectMapper,
            RestTemplate restTemplate,
            Executor executor,
            LongSupplier currentTimeMillis,
            Runnable afterLaunchAdmission) {
        this(
                objectMapper,
                restTemplate,
                executor,
                currentTimeMillis,
                afterLaunchAdmission,
                (runId, operationId) -> {
                });
    }

    BankingPolicyWindTunnelExecutionProvider(
            ObjectMapper objectMapper,
            RestTemplate restTemplate,
            Executor executor,
            LongSupplier currentTimeMillis,
            Runnable afterLaunchAdmission,
            BiConsumer<String, String> afterFinalizationReceiptPrepared) {
        this.objectMapper = objectMapper.rebuild()
                .findAndAddModules()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .build();
        this.presetService = new BankingPolicyScenarioLabPresetService(this.objectMapper);
        this.evidenceService = new BankingPolicyWindTunnelEvidenceService(this.objectMapper);
        this.scorecardEvaluator = new DomainOutcomeScorecardEvaluator();
        this.restTemplate = restTemplate;
        this.executor = executor;
        this.currentTimeMillis = currentTimeMillis;
        this.afterLaunchAdmission = afterLaunchAdmission;
        this.afterFinalizationReceiptPrepared = afterFinalizationReceiptPrepared;
    }

    @Override
    public String packId() {
        return BankingPolicyWindTunnelManifest.PACK_ID;
    }

    public void recoverPendingFinalizationNotifications(PolicyWindTunnelExecutionContext context) {
        Path artifactRoot = context.workspaceRoot()
                .resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT)
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(artifactRoot)) {
            return;
        }
        try (var runDirectories = Files.list(artifactRoot)) {
            runDirectories
                    .filter(Files::isDirectory)
                    .filter(runDirectory -> Files.isRegularFile(
                            runDirectory.resolve(FINALIZATION_RECEIPT_FILE)))
                    .map(runDirectory -> runDirectory.getFileName().toString())
                    .forEach(runId -> scheduleFinalizationNotificationRetry(context, runId));
        } catch (IOException recoveryFailure) {
            log.warn(
                    "Scenario Lab pending completion notifications could not be enumerated: {}",
                    recoveryFailure.getMessage());
        }
    }

    @Override
    public PolicyWindTunnelRunRecord startRun(
            PolicyWindTunnelExecutionContext context,
            Map<String, Object> payload,
            String executionRole) {
        requireCanonicalLaunchExecutionMode(payload);
        BankingPolicyScenarioLabRequest resolved = presetService.resolveRequest(payload, executionRole);
        validateAndPinLaunchFinalizationMode(resolved.normalizedPayload());
        requirePlatformOwnedV2Storage(context, resolved.normalizedPayload());

        ensureExperimentRegistered(context);

        if (isClockDrivenHybrid(resolved.normalizedPayload())) {
            String requestedAgentDecisionMode = requestedAgentDecisionMode(resolved.normalizedPayload());
            resolved.normalizedPayload().put("playbackRate",
                    playbackRate(resolved.normalizedPayload(), requestedAgentDecisionMode, context));
        }

        String runId = platformOwnedRunId(resolved.normalizedPayload().get("simulationRunId"));
        BankingClockDrivenRateChangePlan.Snapshot rateChangePlan =
                BankingClockDrivenRateChangePlan.compileForLaunch(
                                runId,
                                resolved.preset().presetId(),
                                resolved.normalizedPayload())
                        .orElse(null);
        if (rateChangePlan != null) {
            resolved.normalizedPayload().put(
                    CLOCK_DRIVEN_INTERVENTION_PLAN_DIGEST_FIELD,
                    rateChangePlan.planDigest());
            resolved.normalizedPayload().put(
                    CLOCK_DRIVEN_INTERVENTION_PLAN_SCHEMA_FIELD,
                    BankingClockDrivenRateChangePlan.PLAN_SCHEMA_VERSION);
        }
        String executionClaimOwner = "launch:" + UUID.randomUUID();
        long nowEpochMs = currentTimeMillis.getAsLong();
        CohortArtifactSnapshot cohortSnapshot = captureCohortArtifactSnapshot(
                resolved.normalizedPayload());
        if (isClockDrivenHybrid(resolved.normalizedPayload())) {
            activationSettingsSnapshot(resolved.normalizedPayload());
        }
        String launchDigest = LAUNCH_DIGEST_PREFIX + sha256Hex(canonicalJsonBytes(Map.of(
                "packId", BankingPolicyWindTunnelManifest.PACK_ID,
                "providerRevision", BankingRemotePolicyWindTunnelService.PROVIDER_REVISION,
                "executionRole", firstNonBlank(executionRole, "REMOTE_PROVIDER"),
                "payload", canonicalLaunchPayload(payload, resolved.normalizedPayload()))));
        resolved.normalizedPayload().remove("cohortArtifactSnapshot");
        Map<String, Object> metadata = new LinkedHashMap<>(BankingPolicyScenarioLabRunMetadata
                .queued(resolved.normalizedPayload())
                .writeTo(Map.of()));
        metadata.put(
                BankingRemotePolicyWindTunnelExecutionContext.LAUNCH_DIGEST_METADATA_KEY,
                launchDigest);
        metadata.put(
                BankingRemotePolicyWindTunnelExecutionContext.EXECUTION_CLAIM_OWNER_METADATA_KEY,
                executionClaimOwner);
        metadata.put(
                BankingRemotePolicyWindTunnelExecutionContext.EXECUTION_CLAIM_EXPIRES_AT_METADATA_KEY,
                Math.addExact(
                        nowEpochMs,
                        BankingRemotePolicyWindTunnelExecutionContext.EXECUTION_CLAIM_LEASE_MS));

        PolicyWindTunnelRunCreateRequest createRequest = new PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                scenarioIdForPreset(resolved.preset().presetId()),
                "CANDIDATE",
                resolved.parameters().getCompareMode(),
                resolved.parameters().getPresetId(),
                "QUEUED",
                nowEpochMs,
                null,
                null,
                metadata);

        PolicyWindTunnelStoredRun admitted;
        ExecutionClaim executionClaim = null;
        LaunchExecutionDirective executionDirective;
        if (context instanceof BankingRemotePolicyWindTunnelExecutionContext durableContext) {
            if (FINALIZATION_MODE_PLATFORM_OWNED_V2.equals(
                    resolved.normalizedPayload().get(FINALIZATION_MODE_FIELD))) {
                restoreFinalizationReceiptIfPresent(context, runId);
            }
            LaunchAdmission admission = durableContext.admitLaunch(
                    createRequest,
                    launchDigest,
                    executionClaimOwner,
                    nowEpochMs);
            if (admission.outcome() == LaunchAdmissionOutcome.CONFLICT) {
                throw launchConflict(runId);
            }
            admitted = admission.run();
            executionClaim = admission.executionClaim();
            executionDirective = admission.executionDirective();
        } else {
            context.createRun(createRequest);
            admitted = context.findRun(runId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Scenario Lab provider launch admission was not durably persisted"));
            String storedDigest = stringValue(admitted.metadata().get(
                    BankingRemotePolicyWindTunnelExecutionContext.LAUNCH_DIGEST_METADATA_KEY));
            if (!launchDigest.equals(storedDigest)) {
                throw launchConflict(runId);
            }
            executionDirective = executionClaimOwner.equals(stringValue(admitted.metadata().get(
                    BankingRemotePolicyWindTunnelExecutionContext.EXECUTION_CLAIM_OWNER_METADATA_KEY)))
                            ? LaunchExecutionDirective.START_NEW
                            : LaunchExecutionDirective.NONE;
        }
        if (cohortSnapshot != null) {
            persistCohortArtifactSnapshot(context, runId, cohortSnapshot);
        }
        if (rateChangePlan != null) {
            persistClockDrivenInterventionPlan(context, runId, rateChangePlan);
        }
        if (executionDirective == LaunchExecutionDirective.NONE) {
            return getRunStatus(context, runId);
        }
        afterLaunchAdmission.run();

        ExecutionClaim claimedExecution = executionClaim;
        Runnable execution = executionDirective == LaunchExecutionDirective.RECOVER_EXISTING
                ? () -> recoverExistingExecution(context, runId)
                : isClockDrivenHybrid(resolved.normalizedPayload())
                        ? () -> executeClockDrivenHybridRun(context, runId, resolved)
                        : () -> executeRun(context, runId, resolved);
        executor.execute(() -> executeWithClaim(context, claimedExecution, execution));
        return getRunStatus(context, runId);
    }

    public static void requireCanonicalLaunchExecutionMode(Map<String, Object> payload) {
        Object rawMode = payload == null ? null : payload.get("executionMode");
        if (!(rawMode instanceof String mode) || !EXECUTION_MODE_CLOCK_DRIVEN_HYBRID.equals(mode)) {
            throw RemoteDomainProtocolException.invalidRequest(
                    UUID.randomUUID(),
                    "policy-wind-tunnel-run",
                    "scenario_lab_execution_mode_retired",
                    "New Scenario Lab launches require executionMode=CLOCK_DRIVEN_HYBRID; "
                            + "FAST_BATCH_COMPARE supports historical reads and lifecycle cleanup only.",
                    Map.of("requiredExecutionMode", EXECUTION_MODE_CLOCK_DRIVEN_HYBRID));
        }
    }

    void recoverExistingExecution(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        PolicyWindTunnelStoredRun storedRun = context.findRun(runId).orElse(null);
        if (storedRun == null || isTerminalStatus(storedRun.status())) {
            return;
        }
        BankingPolicyScenarioLabRunMetadata metadata = readPolicyMetadata(storedRun.metadata());
        if (!isClockDrivenHybrid(metadata.parameters())) {
            failAmbiguousRecoveredRun(
                    context,
                    runId,
                    "Expired provider ownership cannot safely replay a non-clock-driven execution.");
            return;
        }
        String status = stringValue(storedRun.status()).toUpperCase(Locale.ROOT);
        String phase = firstNonBlank(metadata.phase(), status).toUpperCase(Locale.ROOT);
        switch (phase) {
            case PHASE_QUEUED -> {
                if (!STATUS_QUEUED.equals(status) && !STATUS_RUNNING.equals(status)) {
                    failInvalidRecoveredPhase(context, runId, status, phase);
                    return;
                }
                executeClockDrivenHybridRun(
                        context,
                        runId,
                        resolveClockDrivenRequestForResume(metadata),
                        null);
            }
            case PHASE_PREPARING_CLOCK -> {
                if (!STATUS_RUNNING.equals(status)) {
                    failInvalidRecoveredPhase(context, runId, status, phase);
                    return;
                }
                executeClockDrivenHybridRun(
                        context,
                        runId,
                        resolveClockDrivenRequestForResume(metadata),
                        PHASE_PREPARING_CLOCK);
            }
            case PHASE_ACTIVATING_COHORT -> failAmbiguousRecoveredRun(
                    context,
                    runId,
                    "Provider ownership expired during cohort activation; activation was not replayed because its committed boundary is ambiguous.");
            case PHASE_CLOCK_RUNNING -> {
                if (!STATUS_RUNNING.equals(status)) {
                    failInvalidRecoveredPhase(context, runId, status, phase);
                    return;
                }
                startClockHorizonMonitor(context, runId);
            }
            case PHASE_PAUSED -> {
                if (!STATUS_PAUSED.equals(status)) {
                    failInvalidRecoveredPhase(context, runId, status, phase);
                }
            }
            case PHASE_READY_TO_FINALIZE -> {
                if (!STATUS_READY_TO_FINALIZE.equals(status)) {
                    failInvalidRecoveredPhase(context, runId, status, phase);
                }
            }
            case PHASE_FINALIZING_REPORT -> {
                if (!STATUS_RUNNING.equals(status)) {
                    failInvalidRecoveredPhase(context, runId, status, phase);
                }
            }
            default -> failInvalidRecoveredPhase(context, runId, status, phase);
        }
    }

    private void failInvalidRecoveredPhase(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String status,
            String phase) {
        failAmbiguousRecoveredRun(
                context,
                runId,
                "Expired provider ownership found an invalid lifecycle state "
                        + status + "/" + phase + ".");
    }

    private void failAmbiguousRecoveredRun(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String message) {
        handleClockDrivenWorkerFailure(context, runId, new IllegalStateException(message));
    }

    private RemoteDomainProtocolException launchConflict(String runId) {
        return new RemoteDomainProtocolException(
                HttpStatus.CONFLICT,
                UUID.randomUUID(),
                "policy-wind-tunnel-run",
                "scenario_lab_launch_conflict",
                RemoteDomainErrorEnvelopeError.CategoryEnum.INVALID_REQUEST,
                "Scenario Lab runId is already bound to a different launch request.",
                false,
                Map.of("runId", runId));
    }

    private Map<String, Object> canonicalLaunchPayload(
            Map<String, Object> requestedPayload,
            Map<String, Object> resolvedPayload) {
        Map<String, Object> canonical = new LinkedHashMap<>(objectMap(resolvedPayload));
        if (!objectMap(requestedPayload).containsKey("simulationStartEpochMs")) {
            canonical.put("simulationStartEpochMs", "PROVIDER_ADMISSION_TIME");
        }
        return canonical;
    }

    private void requirePlatformOwnedV2Storage(
            PolicyWindTunnelExecutionContext context,
            Map<String, Object> payload) {
        if (FINALIZATION_MODE_PLATFORM_OWNED_V2.equals(payload.get(FINALIZATION_MODE_FIELD))
                && (!(context instanceof BankingRemotePolicyWindTunnelExecutionContext durableContext)
                || !durableContext.platformOwnedV2StorageReady())) {
            throw new IllegalStateException(
                    "PLATFORM_OWNED_V2 launch requires verified shared durable provider storage");
        }
    }

    private void executeWithClaim(
            PolicyWindTunnelExecutionContext context,
            ExecutionClaim claim,
            Runnable execution) {
        if (context instanceof BankingRemotePolicyWindTunnelExecutionContext durableContext
                && claim != null) {
            durableContext.executeWithClaim(claim, execution);
            return;
        }
        execution.run();
    }

    @Override
    public PolicyWindTunnelRunRecord getRunStatus(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        restoreFinalizationReceiptIfPresent(context, runId);
        var record = context.findRun(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown runId: " + runId));
        return BankingPolicyScenarioLabRunMetadata.fromMetadata(record.metadata())
                .toStatus(record, BankingPolicyWindTunnelManifest.ROUTE_BASE_PATH + "/runs/");
    }

    @Override
    public PolicyWindTunnelRunRecord controlRun(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String action,
            Map<String, Object> payload) {
        return controlRun(context, runId, action, payload, "");
    }

    public PolicyWindTunnelRunRecord controlRun(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String action,
            Map<String, Object> payload,
            String opportunityCorrelationCapability) {
        restoreFinalizationReceiptIfPresent(context, runId);
        var storedRun = context.findRun(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown runId: " + runId));
        String normalizedAction = stringValue(action).toUpperCase(Locale.ROOT);
        if (!LIFECYCLE_ACTION_FINALIZE.equals(normalizedAction)
                && !stringValue(objectMap(payload).get("operationId")).isBlank()) {
            throw new IllegalArgumentException(
                    "Scenario Lab finalization operationId is only valid for FINALIZE");
        }
        if (isTerminalStatus(storedRun.status())
                && !LIFECYCLE_ACTION_FINALIZE.equals(normalizedAction)) {
            return getRunStatus(context, runId);
        }
        BankingPolicyScenarioLabRunMetadata policyMetadata = readPolicyMetadata(context, runId);
        boolean clockDriven = isClockDrivenHybrid(policyMetadata.parameters());
        if ((LIFECYCLE_ACTION_PAUSE.equals(normalizedAction)
                || LIFECYCLE_ACTION_RESUME.equals(normalizedAction)
                || LIFECYCLE_ACTION_SET_PLAYBACK_RATE.equals(normalizedAction)
                || LIFECYCLE_ACTION_FINALIZE.equals(normalizedAction))
                && !clockDriven) {
            throw new IllegalArgumentException(
                    "Pause, resume, playback-rate changes, and finalize only apply to CLOCK_DRIVEN_HYBRID Scenario Lab runs.");
        }

        Runnable deferredLifecycleWork = null;
        switch (normalizedAction) {
            case LIFECYCLE_ACTION_CANCEL -> {
                synchronized (lifecycleLock(runId)) {
                    if (clockDriven && !lifecycleTransitionAllows(context, runId, TRANSITION_CANCEL)) {
                        appendLifecycleIgnoredLog(context, runId, "Cancel");
                    } else if (!runIsTerminal(context, runId)) {
                        publishTerminalRunState(
                                context,
                                runId,
                                STATUS_CANCELLED,
                                PHASE_CANCELLED,
                                null,
                                "Cancel requested. Captured evidence remains available in the run ledger.");
                        if (clockDriven) {
                            applyPlaybackControlForRun(context, runId, true, 0.0, "Cancel requested");
                            clearHybridRuntimeCohort(context, runId);
                        }
                    }
                }
            }
            case LIFECYCLE_ACTION_PAUSE -> {
                synchronized (lifecycleLock(runId)) {
                    if (!runIsTerminal(context, runId)) {
                        BankingPolicyScenarioLabRunMetadata currentMetadata = readPolicyMetadata(context, runId);
                        if (!clockDrivenRunCanPause(context, runId, currentMetadata)) {
                            appendLifecycleIgnoredLog(context, runId, "Pause");
                            break;
                        }
                        if (clockDrivenRunIsReadyToFinalize(context, runId)) {
                            if (applyPlaybackControlForRun(context, runId, true, 0.0, "Platform finalization pause requested")) {
                                updateRunStateAndAppendLog(
                                        context,
                                        runId,
                                        STATUS_READY_TO_FINALIZE,
                                        PHASE_READY_TO_FINALIZE,
                                        currentMetadata.progress(),
                                        null,
                                        null,
                                        null,
                                        true,
                                        "Platform finalization pause confirmed. Run remains ready for a retryable platform seal.");
                            }
                        } else if (clockDrivenClockHasStarted(currentMetadata)) {
                            if (applyPlaybackControlForRun(context, runId, true, 0.0, "Pause requested")) {
                                updateRunState(context, runId, "PAUSED", "PAUSED", currentMetadata.progress(), null, null, null, false);
                                appendLog(context, runId, "Pause requested. Simulator playback rate set to 0.0.");
                            }
                        } else {
                            updateRunState(context, runId, "PAUSED", "PAUSED", currentMetadata.progress(), null, null, null, false);
                            appendLog(context, runId, "Pause requested. Run paused before simulator clock playback started.");
                            try {
                                applyPlaybackControlForRun(context, runId, true, 0.0, "Pause requested");
                            } catch (Exception e) {
                                appendLog(context, runId, "Pause requested. Clock pause attempt was not confirmed: "
                                        + firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
                            }
                        }
                    }
                }
            }
            case LIFECYCLE_ACTION_RESUME -> {
                synchronized (lifecycleLock(runId)) {
                    if (!runIsTerminal(context, runId)) {
                        BankingPolicyScenarioLabRunMetadata currentMetadata = readPolicyMetadata(context, runId);
                        if (!clockDrivenRunCanResume(context, runId, currentMetadata)) {
                            appendLifecycleIgnoredLog(context, runId, "Resume");
                            break;
                        }
                        if ("CLOCK_RUNNING".equalsIgnoreCase(currentMetadata.pausedFromPhase())) {
                            String agentDecisionMode = requestedAgentDecisionMode(currentMetadata.parameters());
                            Map<String, Object> resumePayload = new LinkedHashMap<>(currentMetadata.parameters());
                            resumePayload.putAll(objectMap(payload));
                            double requestedPlaybackRate = requestedPlaybackRate(resumePayload, agentDecisionMode);
                            double playbackRate = playbackRate(resumePayload, agentDecisionMode, context);
                            if (applyPlaybackControlForRun(context, runId, false, playbackRate, "Resume requested")) {
                                updateRunPlaybackRate(context, runId, playbackRate);
                                updateRunState(context, runId, "RUNNING", "CLOCK_RUNNING", currentMetadata.progress(), null, null, null, false);
                                if (requestedPlaybackRate > playbackRate) {
                                    appendLog(context, runId, "Resume requested at " + formatPlaybackRate(requestedPlaybackRate)
                                            + "; simulator accepted " + formatPlaybackRate(playbackRate)
                                            + " after playback limit negotiation.");
                                } else {
                                    appendLog(context, runId, "Resume requested. Simulator playback rate set to "
                                            + formatPlaybackRate(playbackRate) + ".");
                                }
                            }
                        } else {
                            BankingPolicyScenarioLabRequest resumeRequest =
                                    resolveClockDrivenRequestForResume(currentMetadata);
                            String pausedFromPhase = firstNonBlank(currentMetadata.pausedFromPhase(), "PREPARING_CLOCK");
                            updateRunState(context, runId, "RUNNING", "PREPARING_CLOCK", 0.05, null, null, null, false);
                            appendLog(context, runId, "Resume requested. Restarting clock-driven setup from "
                                    + pausedFromPhase + ".");
                            deferredLifecycleWork = () -> executeClockDrivenHybridRun(
                                    context,
                                    runId,
                                    resumeRequest,
                                    pausedFromPhase);
                        }
                    }
                }
            }
            case LIFECYCLE_ACTION_SET_PLAYBACK_RATE -> {
                synchronized (lifecycleLock(runId)) {
                    if (!runIsTerminal(context, runId)) {
                        BankingPolicyScenarioLabRunMetadata currentMetadata = readPolicyMetadata(context, runId);
                        if (!clockDrivenWorkerStateMatches(context, runId, "RUNNING", "CLOCK_RUNNING")) {
                            appendLifecycleIgnoredLog(context, runId, "Playback rate change");
                            break;
                        }
                        String agentDecisionMode = requestedAgentDecisionMode(currentMetadata.parameters());
                        Map<String, Object> playbackPayload = new LinkedHashMap<>(currentMetadata.parameters());
                        Map<String, Object> controlPayload = objectMap(payload);
                        playbackPayload.putAll(controlPayload);
                        if (controlPayload.containsKey("playbackRate")
                                && !positiveFiniteDoubleValue(controlPayload.get("playbackRate"))) {
                            playbackPayload.put("playbackRate", currentMetadata.parameters().getOrDefault(
                                    "playbackRate",
                                    defaultPlaybackRate(playbackPayload, agentDecisionMode)));
                        }
                        double requestedPlaybackRate = requestedPlaybackRate(playbackPayload, agentDecisionMode);
                        double playbackRate = playbackRate(playbackPayload, agentDecisionMode, context);
                        if (applyPlaybackControlForRun(context, runId, false, playbackRate, "Playback rate change requested")) {
                            updateRunPlaybackRate(context, runId, playbackRate);
                            if (requestedPlaybackRate > playbackRate) {
                                appendLog(context, runId, "Playback rate change requested at "
                                        + formatPlaybackRate(requestedPlaybackRate)
                                        + "; simulator accepted " + formatPlaybackRate(playbackRate)
                                        + " after playback limit negotiation.");
                            } else {
                                appendLog(context, runId, "Playback rate changed to "
                                        + formatPlaybackRate(playbackRate) + ".");
                            }
                        }
                    }
                }
            }
            case LIFECYCLE_ACTION_FINALIZE -> finalizeClockDrivenHybridRun(
                    context, runId, payload, opportunityCorrelationCapability);
            default -> throw new IllegalArgumentException("Unsupported Scenario Lab lifecycle action: " + action);
        }
        if (deferredLifecycleWork != null) {
            executor.execute(deferredLifecycleWork);
        }
        return getRunStatus(context, runId);
    }

    private BankingPolicyScenarioLabRequest resolveClockDrivenRequestForResume(
            BankingPolicyScenarioLabRunMetadata metadata) {
        return presetService.resolveRequest(metadata.parameters(), "PLATFORM_ADMIN");
    }

    @Override
    public Map<String, Object> loadBundle(
            PolicyWindTunnelExecutionContext context,
            String runId) throws IOException {
        PolicyWindTunnelStoredRun storedRun = findStoredRun(context, runId);
        if (shouldUseLiveHybridBundle(storedRun)) {
            return buildClockDrivenHybridLiveBundle(context, storedRun);
        }
        return evidenceService.loadBundle(context, runId);
    }

    @Override
    public PolicyWindTunnelArtifactPayload readArtifact(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String artifactType) throws IOException {
        PolicyWindTunnelArtifactPayload finalizationArtifact =
                readFinalizationConformanceArtifact(context, runId, artifactType);
        if (finalizationArtifact != null) {
            return finalizationArtifact;
        }
        PolicyWindTunnelStoredRun storedRun = findStoredRun(context, runId);
        if (shouldUseLiveHybridBundle(storedRun)) {
            return buildClockDrivenHybridLiveArtifact(context, storedRun, artifactType);
        }
        return evidenceService.readArtifact(context, runId, artifactType);
    }

    @Override
    public PolicyWindTunnelArtifactMetadata readArtifactMetadata(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String artifactType) throws IOException {
        PolicyWindTunnelArtifactMetadata finalizationArtifact =
                finalizationConformanceArtifactMetadata(context, runId, artifactType);
        if (finalizationArtifact != null) {
            return finalizationArtifact;
        }
        PolicyWindTunnelStoredRun storedRun = findStoredRun(context, runId);
        if (shouldUseLiveHybridBundle(storedRun)) {
            return clockDrivenHybridLiveArtifactMetadata(artifactType);
        }
        return evidenceService.readArtifactMetadata(context, runId, artifactType);
    }

    private PolicyWindTunnelArtifactPayload readFinalizationConformanceArtifact(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String artifactType) throws IOException {
        String normalizedType = kebabCaseArtifactType(artifactType);
        Map<String, Object> receipt = loadFinalizationReceipt(context, runId);
        if (receipt.isEmpty()) {
            return null;
        }
        if ("finalization-receipt".equals(normalizedType)) {
            Path receiptPath = finalizationReceiptPath(context, runId);
            return new PolicyWindTunnelArtifactPayload(
                    "finalization-receipt",
                    "application/json",
                    FINALIZATION_RECEIPT_FILE,
                    Files.readAllBytes(receiptPath));
        }
        Map<String, Object> entry = finalizationArtifactEntry(receipt, normalizedType);
        if (entry.isEmpty()) {
            return null;
        }
        Path path = resolveFinalizationArtifact(context, stringValue(entry.get("artifactRef")));
        return new PolicyWindTunnelArtifactPayload(
                normalizedType,
                stringValue(entry.get("contentType")),
                path.getFileName().toString(),
                Files.readAllBytes(path));
    }

    private PolicyWindTunnelArtifactMetadata finalizationConformanceArtifactMetadata(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String artifactType) {
        String normalizedType = kebabCaseArtifactType(artifactType);
        Map<String, Object> receipt = loadFinalizationReceipt(context, runId);
        if (receipt.isEmpty()) {
            return null;
        }
        if ("finalization-receipt".equals(normalizedType)) {
            return new PolicyWindTunnelArtifactMetadata(
                    "application/json", FINALIZATION_RECEIPT_FILE);
        }
        Map<String, Object> entry = finalizationArtifactEntry(receipt, normalizedType);
        if (entry.isEmpty()) {
            return null;
        }
        Path path = resolveFinalizationArtifact(context, stringValue(entry.get("artifactRef")));
        return new PolicyWindTunnelArtifactMetadata(
                stringValue(entry.get("contentType")), path.getFileName().toString());
    }

    private Map<String, Object> finalizationArtifactEntry(
            Map<String, Object> receipt,
            String normalizedType) {
        for (Map.Entry<String, Object> candidate :
                objectMap(receipt.get("artifactManifest")).entrySet()) {
            if (kebabCaseArtifactType(candidate.getKey()).equals(normalizedType)) {
                return objectMap(candidate.getValue());
            }
        }
        return Map.of();
    }

    private String kebabCaseArtifactType(String artifactType) {
        return stringValue(artifactType)
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replace('_', '-')
                .toLowerCase(Locale.ROOT);
    }

    @Override
    public PolicyWindTunnelArchivePayload buildEvidencePack(
            PolicyWindTunnelExecutionContext context,
            String runId) throws IOException {
        PolicyWindTunnelStoredRun storedRun = findStoredRun(context, runId);
        if (shouldUseLiveHybridBundle(storedRun)) {
            return buildClockDrivenHybridEvidencePack(context, storedRun);
        }
        return evidenceService.buildEvidencePack(context, runId);
    }

    @Override
    public PolicyWindTunnelArtifactMetadata buildEvidencePackMetadata(
            PolicyWindTunnelExecutionContext context,
            String runId) throws IOException {
        PolicyWindTunnelStoredRun storedRun = findStoredRun(context, runId);
        if (shouldUseLiveHybridBundle(storedRun)) {
            return new PolicyWindTunnelArtifactMetadata(
                    "application/zip",
                    "scenario-lab-live-evidence-" + safeToken(storedRun.runId()) + ".zip");
        }
        return evidenceService.buildEvidencePackMetadata(context, runId);
    }

    private void executeClockDrivenHybridRun(
            PolicyWindTunnelExecutionContext context,
            String runId,
            BankingPolicyScenarioLabRequest resolved) {
        executeClockDrivenHybridRun(context, runId, resolved, null);
    }

    private void executeClockDrivenHybridRun(
            PolicyWindTunnelExecutionContext context,
            String runId,
            BankingPolicyScenarioLabRequest resolved,
            String resumeSourcePhase) {
        BankingPolicyScenarioLabRuntime runtime =
                new BankingPolicyScenarioLabRuntime(objectMapper, restTemplate, context);
        try {
            if (!ensureClockDrivenPreparingPhase(context, runId)) {
                return;
            }
            appendLog(context, runId, "Preparing Clock-Driven Simulation run with explicit E24 evidence scope.");

            String cohortId = selectedCohortId(resolved.normalizedPayload());
            if (cohortId.isBlank()) {
                throw new IllegalArgumentException(
                        "Clock-Driven Simulation requires a generated Cohort Factory cohort in this E25 slice.");
            }
            String agentDecisionMode = agentDecisionMode(resolved.normalizedPayload());
            double playbackRate = playbackRate(resolved.normalizedPayload(), agentDecisionMode, context);
            BankingClockDrivenRateChangePlan.Snapshot rateChangePlan =
                    resolveClockDrivenInterventionPlan(context, runId, resolved);
            boolean platformPinnedActivation = !stringValue(
                    resolved.normalizedPayload().get("cohortArtifactDigest")).isBlank();
            CohortArtifactSnapshot cohortSnapshot = platformPinnedActivation
                    ? loadCohortArtifactSnapshot(context, runId, resolved.normalizedPayload())
                    : null;

            appendLog(context, runId, "Loading generated cohort " + cohortId + " for streaming activation.");
            List<Map<String, Object>> agents;
            if (platformPinnedActivation) {
                if (cohortSnapshot == null) {
                    throw new IllegalStateException("Platform-owned run has no pinned cohort artifact snapshot.");
                }
                agents = runtime.loadPinnedCohortAgentsForStreaming(
                        cohortId,
                        cohortSnapshot.content(),
                        cohortSnapshot.artifactDigest(),
                        cohortSnapshot.identitySetDigest(),
                        agentDecisionMode,
                        resolved.normalizedPayload());
            } else {
                agents = runtime.loadGeneratedCohortAgentsForStreaming(
                        cohortId, agentDecisionMode, resolved.normalizedPayload());
            }
            enforceRealLlmCanary(resolved.normalizedPayload(), agentDecisionMode, agents.size());
            enforceHybridCohortLimit(cohortId, agents.size());

            synchronized (lifecycleLock(runId)) {
                if (!clockDrivenWorkerStateMatches(context, runId, "RUNNING", "PREPARING_CLOCK")) {
                    appendClockDrivenStartStoppedLog(context, runId, "before preparing simulator clock");
                    return;
                }
                if (!applyHybridReplayContract(context, runId, resolved, playbackRate, resumeSourcePhase)) {
                    String pausedFromPhase = firstNonBlank(resumeSourcePhase, "PREPARING_CLOCK");
                    updateRunState(
                            context,
                            runId,
                            "PAUSED",
                            "PAUSED",
                            0.05,
                            null,
                            null,
                            null,
                            false,
                            pausedFromPhase);
                    String actionLabel = blank(resumeSourcePhase) ? "Start requested" : "Resume requested";
                    appendLog(context, runId, actionLabel
                            + ". Setup remains paused because simulator replay ownership was not empty or owned by this run.");
                    return;
                }
            }
            synchronized (lifecycleLock(runId)) {
                if (!clockDrivenWorkerStateMatches(context, runId, "RUNNING", "PREPARING_CLOCK")) {
                    appendClockDrivenStartStoppedLog(context, runId, "after preparing simulator clock");
                    return;
                }
                if (!platformPinnedActivation) {
                    clearActiveAgents(context, runId);
                }
            }
            if (!transitionClockDrivenWorkerPhase(
                    context,
                    runId,
                    "RUNNING",
                    "PREPARING_CLOCK",
                    "RUNNING",
                    "ACTIVATING_COHORT",
                    0.15,
                    "before activating cohort")) {
                return;
            }
            appendLog(context, runId, "Activating " + agents.size() + " customers in chunks of "
                    + HYBRID_ACTIVATION_CHUNK_SIZE + ".");
            appendLog(context, runId, "Agent Decision Mode: " + agentDecisionMode + ".");

            if (cohortSnapshot != null) {
                if (stopIfClockDrivenActivationShouldStop(context, runId, "before pinned cohort activation")) {
                    return;
                }
                activatePinnedCohort(
                        context,
                        runId,
                        cohortId,
                        cohortSnapshot,
                        agentDecisionMode,
                        resolved.normalizedPayload(),
                        agents.size());
                synchronized (lifecycleLock(runId)) {
                    if (stopIfClockDrivenActivationShouldStop(context, runId, "after pinned cohort activation")) {
                        return;
                    }
                    updateRunState(context, runId, "RUNNING", "ACTIVATING_COHORT", 0.50, null, null, null, false);
                }
            } else {
                int chunkCount = Math.max(1, (int) Math.ceil(agents.size() / (double) HYBRID_ACTIVATION_CHUNK_SIZE));
                for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                    if (stopIfClockDrivenActivationShouldStop(context, runId, "before activating chunk " + (chunkIndex + 1))) {
                        return;
                    }
                    int fromIndex = chunkIndex * HYBRID_ACTIVATION_CHUNK_SIZE;
                    int toIndex = Math.min(agents.size(), fromIndex + HYBRID_ACTIVATION_CHUNK_SIZE);
                    List<Map<String, Object>> chunk = new ArrayList<>(agents.subList(fromIndex, toIndex));
                    activateAgentChunk(
                            context,
                            runId,
                            cohortId,
                            null,
                            chunkIndex + 1,
                            chunkCount,
                            chunk);
                    double progress = 0.15 + (0.35 * ((chunkIndex + 1) / (double) chunkCount));
                    synchronized (lifecycleLock(runId)) {
                        if (stopIfClockDrivenActivationShouldStop(context, runId, "after activating chunk " + (chunkIndex + 1))) {
                            return;
                        }
                        updateRunState(context, runId, "RUNNING", "ACTIVATING_COHORT", progress, null, null, null, false);
                    }
                }
            }

            if (rateChangePlan != null) {
                Set<String> expectedRateChangeAgentIds = agents.stream()
                        .map(agent -> firstNonBlank(
                                stringValue(agent.get("personaName")),
                                stringValue(agent.get("agentId"))))
                        .collect(Collectors.toCollection(TreeSet::new));
                if (expectedRateChangeAgentIds.contains("")
                        || expectedRateChangeAgentIds.size() != agents.size()) {
                    throw new IllegalStateException(
                            "Activated cohort has invalid rate-change customer identities.");
                }
                String expectedIdentitySetDigest = BankingClockDrivenRateChangePlan.agentIdentitySetDigest(
                        List.copyOf(expectedRateChangeAgentIds));
                if (cohortSnapshot != null
                        && !expectedIdentitySetDigest.equals(cohortSnapshot.identitySetDigest())) {
                    throw new IllegalStateException(
                            "Activated rate-change cohort does not match the platform-pinned identity set.");
                }
                installClockDrivenRateChangePlan(
                        context,
                        runId,
                        rateChangePlan,
                        expectedRateChangeAgentIds,
                        expectedIdentitySetDigest,
                        cohortSnapshot == null ? "" : cohortSnapshot.artifactDigest());
            }

            if (!startSimulatorClockAfterActivation(context, runId, playbackRate)) {
                return;
            }
            appendLog(context, runId, "Clock-driven cohort activated. Simulator clock now owns progression; E24 evidence records runtime activity.");
            appendLog(context, runId, "Clock target: " + resolved.parameters().getSimulationHorizonDays()
                    + " simulated day(s) at " + formatPlaybackRate(playbackRate)
                    + " playback (~" + estimateWallClockDuration(resolved.parameters().getSimulationHorizonDays(),
                    playbackRate)
                    + "). At the horizon, the run enters the platform-owned pause, drain, and finalization handoff; use Finalize early when evidence is sufficient.");
            startClockHorizonMonitor(context, runId);
        } catch (Exception e) {
            handleClockDrivenWorkerFailure(context, runId, e);
        }
    }

    private void executeRun(
            PolicyWindTunnelExecutionContext context,
            String runId,
            BankingPolicyScenarioLabRequest resolved) {
        BankingPolicyScenarioLabRuntime runtime =
                new BankingPolicyScenarioLabRuntime(objectMapper, restTemplate, context);
        try {
            appendLog(context, runId, "Validating dependencies and loading product metadata.");
            updateRunState(context, runId, "RUNNING", "MATERIALIZING", 0.05, null, null, null, false);

            BankingPolicyScenarioExecutionRuntime.ExecutionPlan executionPlan =
                    runtime.materializeExecutionPlan(resolved.parameters(), resolved.normalizedPayload());
            Path runRoot = context.workspaceRoot()
                    .resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT)
                    .resolve(runId)
                    .normalize();
            Path baselineDir = runRoot.resolve("baseline");
            Path candidateDir = runRoot.resolve("candidate");
            Path evaluationDir = runRoot.resolve("evaluation");
            Path summaryDir = runRoot.resolve("summary");
            Files.createDirectories(baselineDir);
            Files.createDirectories(candidateDir);
            Files.createDirectories(evaluationDir);
            Files.createDirectories(summaryDir);

            updateRunState(context, runId, "RUNNING", "RUNNING_BASELINE", 0.20, null, null, null, false);
            appendLog(context, runId, "Executing baseline seeded compare run.");
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult baseline = runtime.executeScenario(
                    runId + "-baseline",
                    "baseline",
                    baselineDir,
                    executionPlan.seededAgents(),
                    executionPlan.baselineEvents(),
                    executionPlan.batchSteps(),
                    resolved.parameters());

            updateRunState(context, runId, "RUNNING", "RUNNING_CANDIDATE", 0.48, null, null, null, false);
            appendLog(context, runId, "Executing candidate seeded compare run.");
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult candidate = runtime.executeScenario(
                    runId + "-candidate",
                    "candidate",
                    candidateDir,
                    executionPlan.seededAgents(),
                    executionPlan.candidateEvents(),
                    executionPlan.batchSteps(),
                    resolved.parameters());

            appendLog(context, runId, "Scoring baseline and candidate runs with evaluator.");
            updateRunState(context, runId, "RUNNING", "EVALUATING", 0.72, null, null, null, false);
            Map<String, Object> baselineSuite = baseline.suiteResult();
            Map<String, Object> candidateSuite = candidate.suiteResult();
            writeJson(evaluationDir.resolve("baseline-suite.json"), baselineSuite);
            writeJson(evaluationDir.resolve("candidate-suite.json"), candidateSuite);

            Map<String, Object> baselineReportMap = context.evaluateSuite(baselineSuite, null);
            EvaluationRunReport baselineReport = objectMapper.convertValue(baselineReportMap, EvaluationRunReport.class);
            Map<String, Object> candidateReportMap = context.evaluateSuite(
                    candidateSuite,
                    baselineReport.getRun().getRunId());
            EvaluationRunReport candidateReport = objectMapper.convertValue(candidateReportMap, EvaluationRunReport.class);
            Map<String, Object> comparisonReportMap = context.compareEvaluations(
                    baselineReportMap,
                    candidateReportMap);
            RegressionDeltaReport comparisonReport =
                    objectMapper.convertValue(comparisonReportMap, RegressionDeltaReport.class);

            Path baselineReportPath = evaluationDir.resolve("baseline-evaluation.json");
            Path candidateReportPath = evaluationDir.resolve("candidate-evaluation.json");
            Path comparisonReportPath = evaluationDir.resolve("regression-comparison.json");
            writeJson(baselineReportPath, baselineReportMap);
            writeJson(candidateReportPath, candidateReportMap);
            writeJson(comparisonReportPath, comparisonReportMap);

            updateRunState(context, runId, "RUNNING", "ASSEMBLING_BUNDLE", 0.86, null, null, null, false);
            appendLog(context, runId, "Building summary, CSV, markdown, and registry-backed artifacts.");
            SummaryArtifacts artifacts = runtime.buildSummaryArtifacts(
                    runId,
                    resolved,
                    executionPlan,
                    baseline,
                    candidate,
                    baselineReportPath,
                    candidateReportPath,
                    comparisonReportPath,
                    baselineReport,
                    candidateReport,
                    comparisonReport,
                    summaryDir,
                    baselineDir,
                    candidateDir);

            String summaryPath = relativizeToWorkspace(context.workspaceRoot(), artifacts.summaryPath());
            context.updateRun(runId, new PolicyWindTunnelRunUpdate(
                    "COMPLETED",
                    summaryPath,
                    "/api/experiment-runs/" + urlEncode(runId) + "/scenario-lab/bundle",
                    System.currentTimeMillis(),
                    readPolicyMetadata(context, runId)
                            .withLifecycle(
                                    "COMPLETED",
                                    1.0,
                                    true,
                                    "/api/experiment-runs/" + urlEncode(runId) + "/scenario-lab/bundle",
                                    "/api/experiment-runs/" + urlEncode(runId) + "/scenario-lab/evidence-pack",
                                    null)
                            .writeTo(readRunMetadata(context, runId))));
            context.linkEvaluation(runId, candidateReport.getRun().getRunId());
            context.registerArtifact(runId, "summary", summaryPath, "application/json");
            context.registerArtifact(runId, "impactedCohortsCsv",
                    relativizeToWorkspace(context.workspaceRoot(), artifacts.impactedCohortsCsvPath()), "text/csv");
            context.registerArtifact(runId, "experimentCard",
                    relativizeToWorkspace(context.workspaceRoot(), artifacts.experimentCardPath()), "text/markdown");
            context.registerArtifact(runId, "comparisonReport",
                    relativizeToWorkspace(context.workspaceRoot(), comparisonReportPath), "application/json");
            context.registerArtifact(runId, "checkpointsJson",
                    relativizeToWorkspace(context.workspaceRoot(), artifacts.checkpointsJsonPath()), "application/json");
            context.registerArtifact(runId, "agentDiffsJson",
                    relativizeToWorkspace(context.workspaceRoot(), artifacts.agentDiffsJsonPath()), "application/json");
            context.registerArtifact(runId, "notableActionsJson",
                    relativizeToWorkspace(context.workspaceRoot(), artifacts.notableActionsJsonPath()), "application/json");
            context.registerArtifact(runId, "scheduledWorkFailuresJson",
                    relativizeToWorkspace(context.workspaceRoot(), artifacts.scheduledWorkFailuresJsonPath()), "application/json");
            context.registerArtifact(runId, "simulationTimelineJson",
                    relativizeToWorkspace(context.workspaceRoot(), artifacts.simulationTimelineJsonPath()), "application/json");
            context.registerArtifact(runId, "sideEffectLedgerJson",
                    relativizeToWorkspace(context.workspaceRoot(), artifacts.sideEffectLedgerJsonPath()), "application/json");
            context.registerArtifact(runId, "branchStateProjectionJson",
                    relativizeToWorkspace(context.workspaceRoot(), artifacts.branchStateProjectionJsonPath()), "application/json");
            appendLog(context, runId, "Run completed and artifacts registered.");
        } catch (Exception e) {
            log.warn("Scenario Lab run {} failed: {}", runId, e.getMessage(), e);
            Map<String, Object> error = Map.of(
                    "message", firstNonBlank(e.getMessage(), "Scenario Lab run failed"),
                    "type", e.getClass().getSimpleName());
            updateRunState(context, runId, "FAILED", "FAILED", 1.0, null, null, error, false);
            appendLog(context, runId, "Run failed: " + firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private boolean isClockDrivenHybrid(Map<String, Object> payload) {
        return EXECUTION_MODE_CLOCK_DRIVEN_HYBRID.equals(stringValue(objectMap(payload).get("executionMode"))
                .toUpperCase(Locale.ROOT));
    }

    private boolean isEventOnlyLifecycle(Map<String, Object> payload) {
        return "EVENT_ONLY".equals(normalizedDecisionCadence(
                objectMap(payload).get("agentDecisionCadence")));
    }

    private void validateAndPinLaunchFinalizationMode(Map<String, Object> payload) {
        boolean supplied = payload.containsKey(FINALIZATION_MODE_FIELD);
        String finalizationMode;
        if (supplied) {
            Object rawMode = payload.get(FINALIZATION_MODE_FIELD);
            finalizationMode = requireExactFinalizationMode(rawMode);
            payload.put(FINALIZATION_MODE_FIELD, finalizationMode);
        } else {
            if (isClockDrivenHybrid(payload)) {
                throw new IllegalArgumentException(
                        "CLOCK_DRIVEN_HYBRID launches require an explicitly pinned PLATFORM_OWNED_V2 finalization mode.");
            }
            finalizationMode = FINALIZATION_MODE_LEGACY_V1;
            payload.put(FINALIZATION_MODE_FIELD, finalizationMode);
        }
        if (isClockDrivenHybrid(payload)
                && !FINALIZATION_MODE_PLATFORM_OWNED_V2.equals(finalizationMode)) {
            throw new IllegalArgumentException(
                    "New CLOCK_DRIVEN_HYBRID launches require opportunityGenerationFinalizationMode=PLATFORM_OWNED_V2; V1 modes are recovery-only.");
        }
        if (isEventOnlyLifecycle(payload)) {
            if (!isClockDrivenHybrid(payload)) {
                throw new IllegalArgumentException(
                        "EVENT_ONLY launches require executionMode=CLOCK_DRIVEN_HYBRID.");
            }
            if (!FINALIZATION_MODE_PLATFORM_OWNED_V2.equals(finalizationMode)) {
                throw new IllegalArgumentException(
                        "EVENT_ONLY launches require opportunityGenerationFinalizationMode=PLATFORM_OWNED_V2.");
            }
        }
    }

    private static String requireExactFinalizationMode(Object rawMode) {
        if (!(rawMode instanceof String mode)
                || !mode.equals(mode.trim())
                || (!FINALIZATION_MODE_PLATFORM_OWNED_V2.equals(mode)
                        && !FINALIZATION_MODE_PLATFORM_OWNED_V1.equals(mode)
                        && !FINALIZATION_MODE_LEGACY_V1.equals(mode))) {
            throw new IllegalArgumentException(
                    "opportunityGenerationFinalizationMode must be exactly PLATFORM_OWNED_V2, PLATFORM_OWNED_V1, or LEGACY_V1.");
        }
        return mode;
    }

    private String normalizedDecisionCadence(Object value) {
        String cadence = firstNonBlank(stringValue(value), "DAILY")
                .trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        if (!Set.of("EVENT_ONLY", "HOURLY", "DAILY", "WEEKLY").contains(cadence)) {
            throw new IllegalArgumentException(
                    "agentDecisionCadence must be EVENT_ONLY, HOURLY, DAILY, or WEEKLY.");
        }
        return cadence;
    }

    private CohortArtifactSnapshot captureCohortArtifactSnapshot(Map<String, Object> payload) {
        if (!isClockDrivenHybrid(payload) || payload.get("cohortArtifactSnapshot") == null) {
            return null;
        }
        Map<String, Object> envelope = objectMap(payload.get("cohortArtifactSnapshot"));
        String artifactDigest = stringValue(payload.get("cohortArtifactDigest")).trim();
        String identitySetDigest = stringValue(payload.get("cohortAgentIdentitySetDigest")).trim();
        if (!COHORT_SNAPSHOT_SCHEMA.equals(stringValue(envelope.get("schemaVersion")))
                || !"application/json".equals(stringValue(envelope.get("contentType")))
                || !"base64".equals(stringValue(envelope.get("encoding")))
                || !artifactDigest.matches("[0-9a-f]{64}")
                || !identitySetDigest.matches("[0-9a-f]{64}")
                || !artifactDigest.equals(stringValue(envelope.get("sha256")))) {
            throw new IllegalArgumentException("Platform-owned activation requires a valid pinned cohort snapshot.");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(stringValue(envelope.get("content")));
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("Platform-owned cohort snapshot is not valid base64.", invalidBase64);
        }
        if (content.length == 0 || content.length > MAX_COHORT_SNAPSHOT_BYTES
                || !artifactDigest.equals(sha256Hex(content))) {
            throw new IllegalArgumentException("Platform-owned cohort snapshot does not match its pinned digest.");
        }
        return new CohortArtifactSnapshot(artifactDigest, identitySetDigest, content);
    }

    private ActivationSettingsSnapshot activationSettingsSnapshot(Map<String, Object> payload) {
        Map<String, Object> envelope = objectMap(payload.get("activationSettingsSnapshot"));
        String digest = stringValue(payload.get("activationSettingsDigest")).trim();
        String encodedContent = stringValue(envelope.get("content")).trim();
        if (!ACTIVATION_SETTINGS_SNAPSHOT_SCHEMA.equals(stringValue(envelope.get("schemaVersion")))
                || !"application/json".equals(stringValue(envelope.get("contentType")))
                || !"base64url".equals(stringValue(envelope.get("encoding")))
                || !digest.matches("[0-9a-f]{64}")
                || !digest.equals(stringValue(envelope.get("sha256")))) {
            throw new IllegalArgumentException(
                    "Platform-owned activation requires pinned activation settings.");
        }
        byte[] content;
        Map<String, Object> settings;
        try {
            content = Base64.getUrlDecoder().decode(encodedContent);
            settings = objectMap(objectMapper.readValue(content, Map.class));
        } catch (Exception invalidSettings) {
            throw new IllegalArgumentException(
                    "Platform-owned activation settings snapshot is invalid.",
                    invalidSettings);
        }
        if (content.length == 0
                || content.length > MAX_ACTIVATION_SETTINGS_BYTES
                || !digest.equals(sha256Hex(content))
                || !"scenario_lab_pinned_cohort_activation.v1".equals(
                        stringValue(settings.get("schemaVersion")))
                || !normalizedDecisionCadence(payload.get("agentDecisionCadence"))
                        .equals(stringValue(settings.get("agentDecisionCadence")))) {
            throw new IllegalArgumentException(
                    "Platform-owned activation settings do not match the platform digest.");
        }
        return new ActivationSettingsSnapshot(digest, encodedContent, settings);
    }

    private void persistCohortArtifactSnapshot(
            PolicyWindTunnelExecutionContext context,
            String runId,
            CohortArtifactSnapshot snapshot) {
        Path snapshotPath = cohortArtifactSnapshotPath(context, runId);
        Path pendingPath = null;
        try {
            Path parent = snapshotPath.getParent();
            createDirectoriesDurably(parent);
            pendingPath = Files.createTempFile(
                    parent,
                    snapshotPath.getFileName().toString() + ".",
                    ".pending");
            Files.write(
                    pendingPath,
                    snapshot.content(),
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try (FileChannel pending = FileChannel.open(pendingPath, StandardOpenOption.WRITE)) {
                pending.force(true);
            }
            try {
                Files.createLink(snapshotPath, pendingPath);
            } catch (FileAlreadyExistsException existing) {
                byte[] existingContent = Files.readAllBytes(snapshotPath);
                if (!MessageDigest.isEqual(existingContent, snapshot.content())) {
                    throw new IllegalStateException(
                            "Platform-owned run already has a different pinned cohort snapshot.");
                }
            }
            forceDirectory(parent);
        } catch (IOException persistenceFailure) {
            throw new IllegalStateException(
                    "Platform-owned cohort snapshot could not be persisted before run acceptance.",
                    persistenceFailure);
        } finally {
            if (pendingPath != null) {
                try {
                    Files.deleteIfExists(pendingPath);
                } catch (IOException cleanupFailure) {
                    log.warn("Could not remove pending platform-owned cohort snapshot {}", pendingPath);
                }
            }
        }
    }

    private void persistClockDrivenInterventionPlan(
            PolicyWindTunnelExecutionContext context,
            String runId,
            BankingClockDrivenRateChangePlan.Snapshot snapshot) {
        Path planPath = clockDrivenInterventionPlanPath(context, runId);
        byte[] content = BankingClockDrivenRateChangePlan.canonicalSnapshotBytes(snapshot);
        Path pendingPath = null;
        try {
            Path parent = planPath.getParent();
            createDirectoriesDurably(parent);
            pendingPath = Files.createTempFile(parent, planPath.getFileName().toString() + ".", ".pending");
            Files.write(
                    pendingPath,
                    content,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try (FileChannel pending = FileChannel.open(pendingPath, StandardOpenOption.WRITE)) {
                pending.force(true);
            }
            try {
                Files.createLink(planPath, pendingPath);
            } catch (FileAlreadyExistsException existing) {
                byte[] existingContent = Files.readAllBytes(planPath);
                if (!MessageDigest.isEqual(existingContent, content)) {
                    throw new IllegalStateException(
                            "Scenario Lab run already has a contradictory clock-driven intervention plan.");
                }
            }
            forceDirectory(parent);
        } catch (IOException persistenceFailure) {
            throw new IllegalStateException(
                    "Clock-driven intervention plan could not be durably pinned at launch.",
                    persistenceFailure);
        } finally {
            if (pendingPath != null) {
                try {
                    Files.deleteIfExists(pendingPath);
                } catch (IOException cleanupFailure) {
                    log.warn("Could not remove pending clock-driven intervention plan {}", pendingPath);
                }
            }
        }
    }

    private BankingClockDrivenRateChangePlan.Snapshot resolveClockDrivenInterventionPlan(
            PolicyWindTunnelExecutionContext context,
            String runId,
            BankingPolicyScenarioLabRequest resolved) {
        BankingClockDrivenRateChangePlan.Snapshot compiled =
                BankingClockDrivenRateChangePlan.compileForLaunch(
                                runId,
                                resolved.preset().presetId(),
                                resolved.normalizedPayload())
                        .orElse(null);
        Map<String, Object> storedParameters = readPolicyMetadata(context, runId).parameters();
        String storedDigest = stringValue(storedParameters.get(
                CLOCK_DRIVEN_INTERVENTION_PLAN_DIGEST_FIELD)).trim();
        if (compiled == null) {
            if (!storedDigest.isBlank()) {
                throw new IllegalStateException(
                        "Run metadata declares an intervention plan that the resolved preset cannot reproduce.");
            }
            return null;
        }
        if (!compiled.planDigest().equals(storedDigest)
                || !BankingClockDrivenRateChangePlan.PLAN_SCHEMA_VERSION.equals(stringValue(
                        storedParameters.get(CLOCK_DRIVEN_INTERVENTION_PLAN_SCHEMA_FIELD)))) {
            throw new IllegalStateException(
                    "Run metadata does not match the compiled clock-driven intervention plan.");
        }
        Path planPath = clockDrivenInterventionPlanPath(context, runId);
        if (Files.notExists(planPath)) {
            persistClockDrivenInterventionPlan(context, runId, compiled);
        }
        try {
            BankingClockDrivenRateChangePlan.Snapshot persisted =
                    BankingClockDrivenRateChangePlan.requireSnapshot(Files.readAllBytes(planPath));
            if (!persisted.equals(compiled)) {
                throw new IllegalStateException(
                        "Durable clock-driven intervention plan contradicts the launch metadata.");
            }
            return persisted;
        } catch (IOException unavailable) {
            throw new IllegalStateException(
                    "Durable clock-driven intervention plan is unavailable.",
                    unavailable);
        }
    }

    private void installClockDrivenRateChangePlan(
            PolicyWindTunnelExecutionContext context,
            String runId,
            BankingClockDrivenRateChangePlan.Snapshot snapshot,
            Set<String> expectedAgentIds,
            String expectedIdentitySetDigest,
            String cohortArtifactDigest) {
        int expectedAgentCount = expectedAgentIds.size();
        String operationId = BankingClockDrivenRateChangePlan.installOperationId(snapshot);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("domainId", BankingPolicyWindTunnelManifest.DOMAIN_ID);
        body.put("actionId", BankingClockDrivenRateChangePlan.ACTION_ID);
        body.put("operationId", operationId);
        body.put("simulationRunId", runId);
        body.put("target", Map.of(
                "scope", "BROADCAST",
                "selector", Map.of("simulationRunId", runId)));
        body.put("inputs", BankingClockDrivenRateChangePlan.controlActionInputs(snapshot));
        body.put("requestedBy", "banking-policy-wind-tunnel-provider");
        body.put("reason", "Install the launch-pinned day-10 credit-card rate-change plan");
        body.put("simulationTimeEpochMs", snapshot.plan().get("simulationStartEpochMs"));

        Map<String, Object> response = postJsonObject(
                context.agentRuntimeUrl() + "/api/domain-control-actions/execute",
                body,
                HttpHeaders.EMPTY);
        int matched = exactResponseCount(response.get("matchedAgents"), "matchedAgents");
        int succeeded = exactResponseCount(response.get("succeeded"), "succeeded");
        int failed = exactResponseCount(response.get("failed"), "failed");
        if (!runId.equals(stringValue(response.get("simulationRunId")))
                || !BankingClockDrivenRateChangePlan.ACTION_ID.equals(stringValue(response.get("actionId")))
                || !operationId.equals(stringValue(response.get("operationId")))
                || matched != expectedAgentCount
                || succeeded != expectedAgentCount
                || failed != 0) {
            throw new IllegalStateException(
                    "Agent runtime did not prove exact installation of the clock-driven intervention plan.");
        }
        List<Map<String, Object>> results = listOfMaps(response.get("results"));
        if (results.size() != expectedAgentCount) {
            throw new IllegalStateException(
                    "Agent runtime omitted per-agent rate-change installation evidence.");
        }
        List<BankingClockDrivenRateChangePlan.InstallationCustomer> customers = new ArrayList<>();
        Set<String> cohortAgentIds = new HashSet<>();
        Set<String> providerRuntimeAgentIds = new HashSet<>();
        Set<String> opportunityAgentIds = new HashSet<>();
        for (Map<String, Object> result : results) {
            String cohortAgentId = exactProofText(result.get("cohortAgentId"), "cohortAgentId");
            String providerRuntimeAgentId = exactProofText(
                    result.get("providerRuntimeAgentId"), "providerRuntimeAgentId");
            String opportunityAgentId = exactProofText(
                    result.get("opportunityAgentId"), "opportunityAgentId");
            Map<String, Object> controlAction = objectMap(result.get("controlAction"));
            int eligibleAccountCount = exactResponseCount(
                    controlAction.get("eligibleAccountCount"),
                    "eligibleAccountCount");
            if (!cohortAgentIds.add(cohortAgentId)
                    || !providerRuntimeAgentIds.add(providerRuntimeAgentId)
                    || !opportunityAgentIds.add(opportunityAgentId)
                    || !BankingClockDrivenRateChangePlan.ACTION_ID.equals(
                            stringValue(controlAction.get("actionId")))
                    || !operationId.equals(stringValue(controlAction.get("operationId")))
                    || !"PLAN_INSTALLED".equals(stringValue(controlAction.get("outcome")))
                    || !snapshot.planDigest().equals(stringValue(controlAction.get("planDigest")))) {
                throw new IllegalStateException(
                        "Agent runtime returned contradictory per-agent rate-change installation evidence.");
            }
            customers.add(new BankingClockDrivenRateChangePlan.InstallationCustomer(
                    cohortAgentId,
                    providerRuntimeAgentId,
                    opportunityAgentId,
                    eligibleAccountCount));
        }
        if (!cohortAgentIds.equals(expectedAgentIds)) {
            throw new IllegalStateException(
                    "Agent runtime installed the rate-change plan for a different customer set.");
        }
        BankingClockDrivenRateChangePlan.InstallationManifest installation =
                BankingClockDrivenRateChangePlan.installationManifest(
                        snapshot, customers, cohortArtifactDigest);
        if (!installation.agentIdentitySetDigest().equals(expectedIdentitySetDigest)) {
            throw new IllegalStateException(
                    "Rate-change installation identity digest contradicts the pinned cohort.");
        }
        persistClockDrivenInstallationManifest(context, runId, installation);
        appendLog(
                context,
                runId,
                "Pinned rate-change plan installed for " + succeeded
                        + " customers; CC001 changes from 19% to 24% at simulated day 10.");
    }

    private void persistClockDrivenInstallationManifest(
            PolicyWindTunnelExecutionContext context,
            String runId,
            BankingClockDrivenRateChangePlan.InstallationManifest manifest) {
        Path path = clockDrivenInterventionInstallationPath(context, runId);
        byte[] content = BankingClockDrivenRateChangePlan.canonicalInstallationManifestBytes(manifest);
        Path pendingPath = null;
        try {
            Path parent = path.getParent();
            createDirectoriesDurably(parent);
            pendingPath = Files.createTempFile(parent, path.getFileName().toString() + ".", ".pending");
            Files.write(
                    pendingPath,
                    content,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try (FileChannel pending = FileChannel.open(pendingPath, StandardOpenOption.WRITE)) {
                pending.force(true);
            }
            try {
                Files.createLink(path, pendingPath);
            } catch (FileAlreadyExistsException existing) {
                if (!MessageDigest.isEqual(Files.readAllBytes(path), content)) {
                    throw new IllegalStateException(
                            "Scenario Lab run already has contradictory rate-change installation evidence.");
                }
            }
            forceDirectory(parent);
        } catch (IOException persistenceFailure) {
            throw new IllegalStateException(
                    "Rate-change installation evidence could not be durably persisted.",
                    persistenceFailure);
        } finally {
            if (pendingPath != null) {
                try {
                    Files.deleteIfExists(pendingPath);
                } catch (IOException cleanupFailure) {
                    log.warn("Could not remove pending rate-change installation evidence {}", pendingPath);
                }
            }
        }
    }

    private Path clockDrivenInterventionPlanPath(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        Path artifactRoot = context.workspaceRoot()
                .resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT)
                .normalize();
        Path runRoot = artifactRoot.resolve(stringValue(runId)).normalize();
        if (!runRoot.startsWith(artifactRoot)) {
            throw new IllegalArgumentException("simulationRunId resolved outside the artifact root");
        }
        return runRoot.resolve(CLOCK_DRIVEN_INTERVENTION_PLAN_FILE);
    }

    private Path clockDrivenInterventionInstallationPath(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        Path artifactRoot = context.workspaceRoot()
                .resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT)
                .normalize();
        Path runRoot = artifactRoot.resolve(stringValue(runId)).normalize();
        if (!runRoot.startsWith(artifactRoot)) {
            throw new IllegalArgumentException("simulationRunId resolved outside the artifact root");
        }
        return runRoot.resolve(CLOCK_DRIVEN_INTERVENTION_INSTALLATION_FILE);
    }

    private int exactResponseCount(Object raw, String field) {
        if (!(raw instanceof Number number)) {
            throw new IllegalStateException(field + " must be an exact non-negative response integer");
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(number.toString());
            int value = decimal.intValueExact();
            if (value < 0) {
                throw new ArithmeticException("negative");
            }
            return value;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IllegalStateException(field + " must be an exact non-negative response integer", invalid);
        }
    }

    private static void createDirectoriesDurably(Path directory) throws IOException {
        List<Path> missingDirectories = new ArrayList<>();
        for (Path candidate = directory;
                candidate != null && Files.notExists(candidate);
                candidate = candidate.getParent()) {
            missingDirectories.add(candidate);
        }
        Files.createDirectories(directory);
        java.util.Collections.reverse(missingDirectories);
        for (Path createdDirectory : missingDirectories) {
            Path parent = createdDirectory.getParent();
            if (parent != null) {
                forceDirectory(parent);
            }
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private CohortArtifactSnapshot loadCohortArtifactSnapshot(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Map<String, Object> parameters) {
        String expectedArtifactDigest = stringValue(parameters.get("cohortArtifactDigest")).trim();
        String expectedIdentitySetDigest = stringValue(parameters.get("cohortAgentIdentitySetDigest")).trim();
        if (!expectedArtifactDigest.matches("[0-9a-f]{64}")
                || !expectedIdentitySetDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Platform-owned run metadata is missing its pinned cohort identity.");
        }
        try {
            byte[] content = Files.readAllBytes(cohortArtifactSnapshotPath(context, runId));
            if (content.length == 0
                    || content.length > MAX_COHORT_SNAPSHOT_BYTES
                    || !expectedArtifactDigest.equals(sha256Hex(content))) {
                throw new IllegalStateException("Platform-owned durable cohort snapshot no longer matches the pinned digest.");
            }
            return new CohortArtifactSnapshot(expectedArtifactDigest, expectedIdentitySetDigest, content);
        } catch (IOException unavailable) {
            throw new IllegalStateException("Platform-owned durable cohort snapshot is unavailable.", unavailable);
        }
    }

    private Path cohortArtifactSnapshotPath(PolicyWindTunnelExecutionContext context, String runId) {
        Path snapshotRoot = context.workspaceRoot()
                .resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT)
                .resolve("cohort-snapshots")
                .normalize();
        String runKey = sha256Hex(stringValue(runId).getBytes(StandardCharsets.UTF_8));
        Path snapshotPath = snapshotRoot.resolve(runKey + ".customers.json").normalize();
        if (!snapshotPath.startsWith(snapshotRoot)) {
            throw new IllegalArgumentException("simulationRunId resolved outside the cohort snapshot root");
        }
        return snapshotPath;
    }

    private String selectedCohortId(Map<String, Object> normalizedPayload) {
        Map<String, Object> payload = objectMap(normalizedPayload);
        String direct = stringValue(payload.get("cohortId"));
        if (!direct.isBlank()) {
            return direct;
        }
        return stringValue(objectMap(payload.get("cohortSelection")).get("cohortId"));
    }

    private String agentDecisionMode(Map<String, Object> normalizedPayload) {
        return requestedAgentDecisionMode(normalizedPayload);
    }

    private void enforceRealLlmCanary(Map<String, Object> normalizedPayload, String agentDecisionMode, int agentCount) {
        if (!AGENT_DECISION_MODE_REAL_LLM.equals(agentDecisionMode)) {
            return;
        }
        Map<String, Object> payload = objectMap(normalizedPayload);
        if (!booleanValue(payload.get("realLlmOptIn"), false)) {
            throw new IllegalArgumentException(
                    "Agent Decision Mode REAL_LLM requires explicit realLlmOptIn=true and budget limits.");
        }

        Map<String, Object> suppliedBudget = objectMap(payload.get("realLlmBudget"));
        requireRealLlmCanaryModel(suppliedBudget);
        int horizonDays = Math.max(1, intValue(payload.get("simulationHorizonDays"), 30));
        String cadence = firstNonBlank(stringValue(payload.get("agentDecisionCadence")), "DAILY")
                .toUpperCase(Locale.ROOT);
        int maxCalls = requiredIntValue(suppliedBudget, "maxCalls", "REAL_LLM canary budget");
        double maxUsd = requiredFiniteDoubleValue(suppliedBudget, "maxUsd", "REAL_LLM canary budget");
        int maxInputTokens = requiredIntValue(suppliedBudget, "maxInputTokens", "REAL_LLM canary budget");
        int maxOutputTokens = requiredIntValue(suppliedBudget, "maxOutputTokens", "REAL_LLM canary budget");

        if (agentCount > REAL_LLM_CANARY_MAX_CUSTOMERS) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary runs are capped at " + REAL_LLM_CANARY_MAX_CUSTOMERS
                            + " customers. Use Stub LLM for full-scale E27 proof.");
        }
        if (horizonDays > REAL_LLM_CANARY_MAX_HORIZON_DAYS) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary runs are capped at " + REAL_LLM_CANARY_MAX_HORIZON_DAYS
                            + " simulated days by the banking domain policy.");
        }
        if ("HOURLY".equals(cadence)) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary runs must use EVENT_ONLY, DAILY, or WEEKLY cadence; hourly real-token loops are not product-safe.");
        }
        if (maxCalls <= 0 || maxCalls > REAL_LLM_CANARY_MAX_CALLS) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary maxCalls must be between 1 and " + REAL_LLM_CANARY_MAX_CALLS + ".");
        }
        if (maxUsd <= 0.0d || maxUsd > REAL_LLM_CANARY_MAX_USD) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary maxUsd must be between 0 and " + REAL_LLM_CANARY_MAX_USD + ".");
        }
        if (maxInputTokens <= 0 || maxOutputTokens <= 0) {
            throw new IllegalArgumentException("REAL_LLM canary token budgets must be positive.");
        }
        if (maxInputTokens > REAL_LLM_CANARY_MAX_INPUT_TOKENS) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary maxInputTokens must be between 1 and "
                            + REAL_LLM_CANARY_MAX_INPUT_TOKENS + ".");
        }
        if (maxOutputTokens > REAL_LLM_CANARY_MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary maxOutputTokens must be between 1 and "
                            + REAL_LLM_CANARY_MAX_OUTPUT_TOKENS + ".");
        }
    }

    private Map<String, Object> realLlmBudget(Map<String, Object> normalizedPayload) {
        Map<String, Object> supplied = objectMap(objectMap(normalizedPayload).get("realLlmBudget"));
        requireRealLlmCanaryModel(supplied);
        int maxCalls = requiredIntValue(supplied, "maxCalls", "REAL_LLM canary budget");
        double maxUsd = requiredFiniteDoubleValue(supplied, "maxUsd", "REAL_LLM canary budget");
        int maxInputTokens = requiredIntValue(supplied, "maxInputTokens", "REAL_LLM canary budget");
        int maxOutputTokens = requiredIntValue(supplied, "maxOutputTokens", "REAL_LLM canary budget");
        if (maxCalls <= 0 || maxCalls > REAL_LLM_CANARY_MAX_CALLS) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary maxCalls must be between 1 and " + REAL_LLM_CANARY_MAX_CALLS + ".");
        }
        if (maxUsd <= 0.0d || maxUsd > REAL_LLM_CANARY_MAX_USD) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary maxUsd must be between 0 and " + REAL_LLM_CANARY_MAX_USD + ".");
        }
        if (maxInputTokens <= 0 || maxOutputTokens <= 0) {
            throw new IllegalArgumentException("REAL_LLM canary token budgets must be positive.");
        }
        if (maxInputTokens > REAL_LLM_CANARY_MAX_INPUT_TOKENS) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary maxInputTokens must be between 1 and "
                            + REAL_LLM_CANARY_MAX_INPUT_TOKENS + ".");
        }
        if (maxOutputTokens > REAL_LLM_CANARY_MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary maxOutputTokens must be between 1 and "
                            + REAL_LLM_CANARY_MAX_OUTPUT_TOKENS + ".");
        }
        return linkedMap(
                "provider", REAL_LLM_DEFAULT_PROVIDER,
                "model", REAL_LLM_DEFAULT_MODEL,
                "maxCalls", maxCalls,
                "maxUsd", maxUsd,
                "maxInputTokens", maxInputTokens,
                "maxOutputTokens", maxOutputTokens,
                "stopPolicy", firstNonBlank(stringValue(supplied.get("stopPolicy")), "STOP_AT_BUDGET"));
    }

    private void requireRealLlmCanaryModel(Map<String, Object> suppliedBudget) {
        String provider = requiredStringValue(suppliedBudget, "provider", "REAL_LLM canary budget");
        String model = requiredStringValue(suppliedBudget, "model", "REAL_LLM canary budget");
        if (!REAL_LLM_DEFAULT_PROVIDER.equals(provider) || !REAL_LLM_DEFAULT_MODEL.equals(model)) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary runs are restricted to "
                            + REAL_LLM_DEFAULT_PROVIDER
                            + "/"
                            + REAL_LLM_DEFAULT_MODEL
                            + ".");
        }
    }

    private void enforceHybridCohortLimit(String cohortId, int agentCount) {
        if (agentCount <= HYBRID_CLOCK_DRIVEN_MAX_COHORT_SIZE) {
            return;
        }
        throw new IllegalArgumentException(
                "Clock-Driven Simulation is currently capped at "
                        + HYBRID_CLOCK_DRIVEN_MAX_COHORT_SIZE
                        + " customers for the local remote-domain path. Cohort "
                        + cohortId
                        + " has "
                        + agentCount
                        + ". Use a validated cohort of up to 100 customers for Clock-Driven Simulation.");
    }

    private String requestedAgentDecisionMode(Map<String, Object> normalizedPayload) {
        String normalized = firstNonBlank(
                        stringValue(objectMap(normalizedPayload).get("agentDecisionMode")),
                        AGENT_DECISION_MODE_DETERMINISTIC)
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        return switch (normalized) {
            case AGENT_DECISION_MODE_STUB_LLM -> AGENT_DECISION_MODE_STUB_LLM;
            case AGENT_DECISION_MODE_REAL_LLM -> AGENT_DECISION_MODE_REAL_LLM;
            default -> AGENT_DECISION_MODE_DETERMINISTIC;
        };
    }

    private double playbackRate(
            Map<String, Object> payload,
            String agentDecisionMode,
            PolicyWindTunnelExecutionContext context) {
        double requested = requestedPlaybackRate(payload, agentDecisionMode);
        double effectiveMax = Math.min(maxPlaybackRate(payload, agentDecisionMode), simulatorMaxPlaybackRate(context));
        return Math.max(HYBRID_MIN_PLAYBACK_RATE, Math.min(requested, effectiveMax));
    }

    private double requestedPlaybackRate(Map<String, Object> payload, String agentDecisionMode) {
        return toDouble(objectMap(payload).get("playbackRate"), defaultPlaybackRate(payload, agentDecisionMode));
    }

    private boolean positiveFiniteDoubleValue(Object value) {
        if (value == null) {
            return false;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) && parsed > 0.0d;
        } catch (Exception e) {
            return false;
        }
    }

    private double defaultPlaybackRate(Map<String, Object> payload, String agentDecisionMode) {
        if (AGENT_DECISION_MODE_REAL_LLM.equals(agentDecisionMode)) {
            return HYBRID_REAL_LLM_MAX_PLAYBACK_RATE;
        }
        return isEventOnlyLifecycle(payload)
                ? HYBRID_NO_TOKEN_EVENT_ONLY_PLAYBACK_RATE
                : HYBRID_FAST_PLAYBACK_RATE;
    }

    private double maxPlaybackRate(Map<String, Object> payload, String agentDecisionMode) {
        return defaultPlaybackRate(payload, agentDecisionMode);
    }

    private double simulatorMaxPlaybackRate(PolicyWindTunnelExecutionContext context) {
        try {
            String body = restTemplate.getForObject(context.simulatorUrl() + "/api/clock/rate/info", String.class);
            if (blank(body)) {
                return HYBRID_FAST_PLAYBACK_RATE;
            }
            Map<String, Object> payload = objectMap(objectMapper.readValue(body, Map.class));
            double max = toDouble(payload.get("max"), HYBRID_FAST_PLAYBACK_RATE);
            return max > 0 ? max : HYBRID_FAST_PLAYBACK_RATE;
        } catch (Exception e) {
            log.debug("Simulator playback metadata unavailable; using policy playback max {}: {}",
                    formatPlaybackRate(HYBRID_FAST_PLAYBACK_RATE),
                    e.getMessage());
            return HYBRID_FAST_PLAYBACK_RATE;
        }
    }

    private boolean applyHybridReplayContract(
            PolicyWindTunnelExecutionContext context,
            String runId,
            BankingPolicyScenarioLabRequest resolved,
            double playbackRate,
            String resumeSourcePhase) {
        List<String> expectedRunIds = replayClaimExpectedRunIds(runId, resumeSourcePhase);
        for (String expectedRunId : expectedRunIds) {
            Map<String, Object> replay = hybridReplayContractPayload(runId, resolved);
            if (expectedRunId != null) {
                replay.put("expectedRunId", expectedRunId);
            }
            try {
                postJson(context.simulatorUrl() + "/api/clock/replay", replay, HttpHeaders.EMPTY);
                appendLog(context, runId, "Applied deterministic replay contract to simulator clock.");
                return true;
            } catch (IllegalStateException e) {
                if (isReplayOwnershipConflict(e)) {
                    continue;
                }
                throw e;
            }
        }
        appendLog(context, runId,
                "Replay contract was not applied because simulator replay ownership was neither empty nor owned by this run.");
        return false;
    }

    private List<String> replayClaimExpectedRunIds(String runId, String resumeSourcePhase) {
        if (blank(resumeSourcePhase)) {
            return List.of("");
        }
        if ("PREPARING_CLOCK".equalsIgnoreCase(resumeSourcePhase)) {
            return List.of(runId, "");
        }
        return List.of(runId);
    }

    private Map<String, Object> hybridReplayContractPayload(
            String runId,
            BankingPolicyScenarioLabRequest resolved) {
        Map<String, Object> replay = linkedMap(
                "seed", HYBRID_REPLAY_SEED,
                "simulationStartEpochMs", simulationStartEpochMs(resolved.normalizedPayload()),
                "playback", linkedMap("rate", 0.0d, "paused", true),
                "deterministic", linkedMap(
                        "enabled", true,
                        "resetStateOnClockRewind", true,
                        "strictClock", true),
                "executionContext", linkedMap(
                        "experimentId", BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                        "runId", runId,
                        "scenarioId", scenarioIdForPreset(resolved.preset().presetId()),
                        "branchType", "CANDIDATE",
                        "compareMode", resolved.parameters().getCompareMode(),
                        "presetId", resolved.parameters().getPresetId(),
                        "startedAtEpochMs", System.currentTimeMillis()));
        return replay;
    }

    private void handleClockDrivenWorkerFailure(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Exception e) {
        log.warn("Clock-Driven Simulation Scenario Lab run {} failed: {}", runId, e.getMessage(), e);
        synchronized (lifecycleLock(runId)) {
            if (!lifecycleTransitionAllows(context, runId, TRANSITION_FAIL)) {
                appendLog(context, runId, "Run worker failed after lifecycle moved away from a failable state; "
                        + "preserving current run state. Error: "
                        + firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
                return;
            }
            try {
                applyPlaybackControlForRun(context, runId, true, 0.0, "Run failure pause requested");
            } catch (Exception pauseError) {
                appendLog(context, runId, "Run failed, but simulator clock pause was not confirmed: "
                        + firstNonBlank(pauseError.getMessage(), pauseError.getClass().getSimpleName()));
            }
            clearHybridRuntimeCohort(context, runId);
            Map<String, Object> error = Map.of(
                    "message", firstNonBlank(e.getMessage(), "Clock-Driven Simulation Scenario Lab run failed"),
                    "type", e.getClass().getSimpleName());
            publishTerminalRunState(
                    context,
                    runId,
                    STATUS_FAILED,
                    PHASE_FAILED,
                    error,
                    "Run failed: " + firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private void clearActiveAgents(PolicyWindTunnelExecutionContext context, String runId) {
        ResponseEntity<String> response = restTemplate.exchange(
                context.agentRuntimeUrl() + "/api/agents/simulation-runs/" + urlEncode(runId),
                HttpMethod.DELETE,
                new HttpEntity<>(null, HttpHeaders.EMPTY),
                String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Agent reset failed with status " + response.getStatusCode().value());
        }
    }

    private void activateAgentChunk(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String cohortId,
            String cohortArtifactDigest,
            int chunkNumber,
            int chunkCount,
            List<Map<String, Object>> agents) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Astraform-Run-Id", runId);
        headers.set("X-Astraform-Cohort-Id", cohortId);
        if (!stringValue(cohortArtifactDigest).isBlank()) {
            headers.set("X-Astraform-Cohort-Artifact-Digest", cohortArtifactDigest);
        }
        headers.set("X-Astraform-Activation-Id", runId + "-chunk-" + chunkNumber);
        headers.set("X-Astraform-Init-Max-Concurrency", String.valueOf(HYBRID_ACTIVATION_INIT_MAX_CONCURRENCY));
        try {
            postJson(context.agentRuntimeUrl() + "/api/agents/init", agents, headers);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Agent activation chunk " + chunkNumber + "/" + chunkCount
                    + " failed: " + e.getMessage(), e);
        }
        appendLog(context, runId, "Activated chunk " + chunkNumber + "/" + chunkCount
                + " (" + agents.size() + " customers).");
    }

    private void activatePinnedCohort(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String cohortId,
            CohortArtifactSnapshot snapshot,
            String agentDecisionMode,
            Map<String, Object> parameters,
            int agentCount) {
        ActivationSettingsSnapshot activationSettings = activationSettingsSnapshot(parameters);
        if (!agentDecisionMode.equals(stringValue(
                activationSettings.settings().get("agentDecisionMode")))) {
            throw new IllegalStateException(
                    "Platform-owned activation settings do not match the accepted run mode.");
        }
        String activationId = stringValue(parameters.get("activationId"));
        if (activationId.isBlank()) {
            throw new IllegalStateException(
                    "Platform-owned activation requires the platform-owned activationId.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Astraform-Run-Id", runId);
        headers.set("X-Astraform-Cohort-Id", cohortId);
        headers.set("X-Astraform-Cohort-Artifact-Digest", snapshot.artifactDigest());
        headers.set("X-Astraform-Activation-Id", activationId);
        headers.set("X-Astraform-Init-Max-Concurrency", String.valueOf(HYBRID_ACTIVATION_INIT_MAX_CONCURRENCY));
        try {
            headers.set("X-Astraform-Activation-Settings", activationSettings.encodedContent());
            ResponseEntity<String> response = restTemplate.exchange(
                    context.agentRuntimeUrl() + "/api/agents/init/pinned-cohort",
                    HttpMethod.POST,
                    new HttpEntity<>(snapshot.content(), headers),
                    String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException(
                        "Agent runtime returned status " + response.getStatusCode().value());
            }
        } catch (Exception activationFailure) {
            throw new IllegalStateException(
                    "Pinned cohort activation failed: "
                            + firstNonBlank(activationFailure.getMessage(), activationFailure.getClass().getSimpleName()),
                    activationFailure);
        }
        appendLog(context, runId, "Activated pinned cohort (" + agentCount + " customers).");
    }

    private boolean startSimulatorClockAfterActivation(
            PolicyWindTunnelExecutionContext context,
            String runId,
            double playbackRate) {
        synchronized (lifecycleLock(runId)) {
            if (!clockDrivenRunIsReadyToStart(context, runId)) {
                appendClockDrivenStartStoppedLog(context, runId, "before starting simulator clock");
                return false;
            }
            if (!applyPlaybackControlForRun(context, runId, false, playbackRate, "Start requested")) {
                return false;
            }
            if (!clockDrivenRunIsReadyToStart(context, runId)) {
                appendClockDrivenStartStoppedLog(context, runId, "after simulator accepted start");
                applyPlaybackControlForRun(context, runId, true, 0.0, "Start rollback requested");
                return false;
            }
            updateRunState(context, runId, "RUNNING", "CLOCK_RUNNING", 0.55, null, null, null, false);
            return true;
        }
    }

    private boolean stopIfClockDrivenActivationShouldStop(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String checkpoint) {
        if (clockDrivenRunIsReadyToStart(context, runId)) {
            return false;
        }
        appendClockDrivenStartStoppedLog(context, runId, checkpoint);
        return true;
    }

    private boolean ensureClockDrivenPreparingPhase(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        synchronized (lifecycleLock(runId)) {
            if (clockDrivenWorkerStateMatches(context, runId, "RUNNING", "PREPARING_CLOCK")) {
                return true;
            }
            if (lifecycleTransitionAllows(context, runId, TRANSITION_WORKER_PREPARE)) {
                updateRunState(context, runId, "RUNNING", "PREPARING_CLOCK", 0.05, null, null, null, false);
                return true;
            }
            appendClockDrivenStartStoppedLog(context, runId, "before preparing simulator clock");
            return false;
        }
    }

    private boolean transitionClockDrivenWorkerPhase(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String expectedStatus,
            String expectedPhase,
            String nextStatus,
            String nextPhase,
            double progress,
            String checkpoint) {
        synchronized (lifecycleLock(runId)) {
            String transitionName = clockDrivenWorkerTransitionName(expectedStatus, expectedPhase, nextStatus, nextPhase);
            boolean allowed = blank(transitionName)
                    ? clockDrivenWorkerStateMatches(context, runId, expectedStatus, expectedPhase)
                    : lifecycleTransitionAllows(context, runId, transitionName);
            if (!allowed) {
                appendClockDrivenStartStoppedLog(context, runId, checkpoint);
                return false;
            }
            updateRunState(context, runId, nextStatus, nextPhase, progress, null, null, null, false);
            return true;
        }
    }

    private String clockDrivenWorkerTransitionName(
            String expectedStatus,
            String expectedPhase,
            String nextStatus,
            String nextPhase) {
        LifecycleState source = state(expectedStatus, expectedPhase);
        LifecycleState target = state(nextStatus, nextPhase);
        for (LifecycleTransitionRule rule : CLOCK_DRIVEN_LIFECYCLE_TRANSITIONS.values()) {
            if (rule.sources().contains(source) && rule.target().equals(target)) {
                return rule.name();
            }
        }
        return "";
    }

    private boolean clockDrivenWorkerStateMatches(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String expectedStatus,
            String expectedPhase) {
        return context.findRun(runId)
                .map(storedRun -> expectedStatus.equalsIgnoreCase(stringValue(storedRun.status()))
                        && expectedPhase.equalsIgnoreCase(readPolicyMetadata(storedRun.metadata()).phase()))
                .orElse(false);
    }

    private Object lifecycleLock(String runId) {
        return lifecycleLocks.computeIfAbsent(runId, ignored -> new Object());
    }

    private boolean clockDrivenRunIsReadyToStart(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        return context.findRun(runId)
                .map(storedRun -> lifecycleTransitionAllows(storedRun, TRANSITION_WORKER_START_CLOCK))
                .orElse(false);
    }

    private boolean runIsTerminal(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        return context.findRun(runId)
                .map(storedRun -> isTerminalStatus(storedRun.status()))
                .orElse(true);
    }

    private boolean clockDrivenRunCanPause(
            PolicyWindTunnelExecutionContext context,
            String runId,
            BankingPolicyScenarioLabRunMetadata metadata) {
        return context.findRun(runId)
                .map(storedRun -> lifecycleTransitionAllows(storedRun, TRANSITION_PAUSE))
                .orElse(false);
    }

    private boolean clockDrivenRunIsReadyToFinalize(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        return context.findRun(runId)
                .map(storedRun -> STATUS_READY_TO_FINALIZE.equalsIgnoreCase(stringValue(storedRun.status()))
                        && PHASE_READY_TO_FINALIZE.equalsIgnoreCase(readPolicyMetadata(storedRun.metadata()).phase()))
                .orElse(false);
    }

    private boolean clockDrivenClockHasStarted(BankingPolicyScenarioLabRunMetadata metadata) {
        return "CLOCK_RUNNING".equalsIgnoreCase(metadata.phase());
    }

    private boolean clockDrivenRunCanResume(
            PolicyWindTunnelExecutionContext context,
            String runId,
            BankingPolicyScenarioLabRunMetadata metadata) {
        return context.findRun(runId)
                .map(storedRun -> lifecycleTransitionAllows(
                        storedRun,
                        clockDrivenResumeTransition(metadata.pausedFromPhase())))
                .orElse(false);
    }

    private boolean clockDrivenResumeSourcePhase(String phase) {
        return "PREPARING_CLOCK".equalsIgnoreCase(phase)
                || "ACTIVATING_COHORT".equalsIgnoreCase(phase)
                || "CLOCK_RUNNING".equalsIgnoreCase(phase);
    }

    private String clockDrivenResumeTransition(String pausedFromPhase) {
        return "CLOCK_RUNNING".equalsIgnoreCase(pausedFromPhase)
                ? TRANSITION_RESUME_CLOCK
                : clockDrivenResumeSourcePhase(pausedFromPhase)
                ? TRANSITION_RESUME_SETUP
                : "";
    }

    private static Map.Entry<String, LifecycleTransitionRule> transition(
            String name,
            LifecycleState source,
            LifecycleState target) {
        return transition(name, Set.of(source), target);
    }

    private static Map.Entry<String, LifecycleTransitionRule> transition(
            String name,
            Set<LifecycleState> sources,
            LifecycleState target) {
        return Map.entry(name, new LifecycleTransitionRule(name, sources, target));
    }

    private static LifecycleState state(String status, String phase) {
        return new LifecycleState(status, phase);
    }

    private boolean lifecycleTransitionAllows(
            PolicyWindTunnelStoredRun storedRun,
            String transitionName) {
        LifecycleTransitionRule rule = CLOCK_DRIVEN_LIFECYCLE_TRANSITIONS.get(transitionName);
        if (rule == null || storedRun == null) {
            return false;
        }
        return rule.allows(stringValue(storedRun.status()), readPolicyMetadata(storedRun.metadata()).phase());
    }

    private boolean lifecycleTransitionAllows(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String transitionName) {
        return context.findRun(runId)
                .map(storedRun -> lifecycleTransitionAllows(storedRun, transitionName))
                .orElse(false);
    }

    private void appendLifecycleIgnoredLog(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String action) {
        PolicyWindTunnelStoredRun storedRun = context.findRun(runId).orElse(null);
        String status = storedRun == null ? "MISSING" : firstNonBlank(stringValue(storedRun.status()), "UNKNOWN");
        String phase = storedRun == null ? "MISSING" : firstNonBlank(readPolicyMetadata(storedRun.metadata()).phase(), "UNKNOWN");
        appendLog(context, runId, action + " ignored because run state is " + status + "/" + phase + ".");
    }

    private void appendClockDrivenStartStoppedLog(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String checkpoint) {
        PolicyWindTunnelStoredRun storedRun = context.findRun(runId).orElse(null);
        String status = storedRun == null ? "MISSING" : firstNonBlank(stringValue(storedRun.status()), "UNKNOWN");
        String phase = storedRun == null ? "MISSING" : firstNonBlank(readPolicyMetadata(storedRun.metadata()).phase(), "UNKNOWN");
        if (isTerminalStatus(status)) {
            clearHybridRuntimeCohort(context, runId);
        }
        appendLog(context, runId, "Clock-driven start worker stopped " + checkpoint
                + " because run state is " + status + "/" + phase + ".");
    }

    private boolean applyPlaybackControlForRun(
            PolicyWindTunnelExecutionContext context,
            String runId,
            boolean paused,
            double playbackRate,
            String actionLabel) {
        return postReplayContractUpdateForRun(
                context,
                runId,
                Map.of("playback", playbackPayload(paused, playbackRate)),
                actionLabel);
    }

    private Map<String, Object> playbackPayload(boolean paused, double playbackRate) {
        Map<String, Object> playback = new LinkedHashMap<>();
        playback.put("paused", paused);
        playback.put("rate", paused ? 0.0 : playbackRate);
        return playback;
    }

    private String activeReplayRunId(PolicyWindTunnelExecutionContext context) {
        return stringValue(objectMap(fetchSimulatorReplay(context).get("executionContext")).get("runId"));
    }

    private boolean postReplayContractUpdateForRun(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Map<String, Object> update,
            String actionLabel) {
        Map<String, Object> guardedUpdate = new LinkedHashMap<>(update);
        guardedUpdate.put("expectedRunId", runId);
        try {
            postJson(context.simulatorUrl() + "/api/clock/replay", guardedUpdate, HttpHeaders.EMPTY);
            return true;
        } catch (IllegalStateException e) {
            if (isReplayOwnershipConflict(e)) {
                appendLog(context, runId, actionLabel
                        + ". Simulator playback was not changed because replay ownership changed before the simulator accepted the update.");
                return false;
            }
            throw e;
        }
    }

    private boolean isReplayOwnershipConflict(Exception e) {
        return stringValue(e.getMessage()).contains("status 409");
    }

    private void releaseHybridReplayContract(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("playback", linkedMap("paused", true, "rate", 0.0d));
        release.put("executionContext", Map.of());
        if (postReplayContractUpdateForRun(context, runId, release, "Simulator clock ownership release requested")) {
            appendLog(context, runId, "Released Scenario Lab simulator clock ownership.");
        }
    }

    private void clearHybridRuntimeCohort(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        try {
            clearActiveAgents(context, runId);
            appendLog(context, runId, "Cleared active Scenario Lab runtime cohort.");
        } catch (Exception e) {
            appendLog(context, runId, "Active runtime cohort cleanup was not confirmed: "
                    + firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private void postJson(String url, Object body, HttpHeaders extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            headers.addAll(extraHeaders);
        }
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("POST " + url + " failed with status " + response.getStatusCode().value());
            }
        } catch (HttpStatusCodeException e) {
            String responseBody = e.getResponseBodyAsString();
            throw new IllegalStateException("POST " + url + " failed with status " + e.getStatusCode().value()
                    + (blank(responseBody) ? "" : ": " + responseBody), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postJsonObject(String url, Object body, HttpHeaders extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            headers.addAll(extraHeaders);
        }
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);
            if (!response.getStatusCode().is2xxSuccessful()
                    || response.getBody() == null
                    || response.getBody().isBlank()) {
                throw new IllegalStateException(
                        "POST " + url + " did not return a successful JSON object response");
            }
            Object decoded = objectMapper.readValue(response.getBody(), Object.class);
            if (!(decoded instanceof Map<?, ?> map)) {
                throw new IllegalStateException("POST " + url + " response must be a JSON object");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalStateException("POST " + url + " response keys must be strings");
                }
                result.put(key, entry.getValue());
            }
            return result;
        } catch (HttpStatusCodeException e) {
            String responseBody = e.getResponseBodyAsString();
            throw new IllegalStateException("POST " + url + " failed with status " + e.getStatusCode().value()
                    + (blank(responseBody) ? "" : ": " + responseBody), e);
        } catch (JacksonException invalidJson) {
            throw new IllegalStateException("POST " + url + " returned invalid JSON", invalidJson);
        }
    }

    private void ensureExperimentRegistered(PolicyWindTunnelExecutionContext context) {
        context.createExperiment(
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                BankingPolicyWindTunnelManifest.LABEL,
                "Async orchestration and evidence owner for banking Scenario Lab compare runs.",
                BankingPolicyWindTunnelManifest.DOMAIN_ID,
                "banking-policy-wind-tunnel-provider",
                "ACTIVE");
    }

    private void updateRunState(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String status,
            String phase,
            double progress,
            String bundleUrl,
            String evidencePackUrl,
            Map<String, Object> error,
            boolean bundleReady) {
        updateRunState(context, runId, status, phase, progress, bundleUrl, evidencePackUrl, error, bundleReady, null, null);
    }

    private void updateRunState(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String status,
            String phase,
            double progress,
            String bundleUrl,
            String evidencePackUrl,
            Map<String, Object> error,
            boolean bundleReady,
            String pausedFromPhaseOverride) {
        updateRunState(
                context,
                runId,
                status,
                phase,
                progress,
                bundleUrl,
                evidencePackUrl,
                error,
                bundleReady,
                pausedFromPhaseOverride,
                null);
    }

    private void updateRunStateAndAppendLog(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String status,
            String phase,
            double progress,
            String bundleUrl,
            String evidencePackUrl,
            Map<String, Object> error,
            boolean bundleReady,
            String logLine) {
        updateRunState(
                context,
                runId,
                status,
                phase,
                progress,
                bundleUrl,
                evidencePackUrl,
                error,
                bundleReady,
                null,
                logLine);
    }

    private void updateRunState(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String status,
            String phase,
            double progress,
            String bundleUrl,
            String evidencePackUrl,
            Map<String, Object> error,
            boolean bundleReady,
            String pausedFromPhaseOverride,
            String logLine) {
        Map<String, Object> currentMetadata = readRunMetadata(context, runId);
        BankingPolicyScenarioLabRunMetadata policyMetadata = readPolicyMetadata(currentMetadata)
                .withLifecycle(phase, progress, bundleReady, bundleUrl, evidencePackUrl, error, pausedFromPhaseOverride);
        if (!blank(logLine)) {
            policyMetadata = policyMetadata.appendLog(logLine);
        }
        Map<String, Object> metadata = policyMetadata.writeTo(currentMetadata);
        context.updateRun(runId, new PolicyWindTunnelRunUpdate(
                status,
                null,
                null,
                isTerminalStatus(status) ? System.currentTimeMillis() : null,
                metadata));
    }

    private void appendLog(PolicyWindTunnelExecutionContext context, String runId, String line) {
        Map<String, Object> metadata = readRunMetadata(context, runId);
        metadata = readPolicyMetadata(metadata)
                .appendLog(line)
                .writeTo(metadata);
        context.updateRun(runId, new PolicyWindTunnelRunUpdate(null, null, null, null, metadata));
    }

    private void appendLogOnce(PolicyWindTunnelExecutionContext context, String runId, String line) {
        Map<String, Object> metadata = readRunMetadata(context, runId);
        BankingPolicyScenarioLabRunMetadata policyMetadata = readPolicyMetadata(metadata);
        if (policyMetadata.logTail().contains(line)) {
            return;
        }
        metadata = policyMetadata
                .appendLog(line)
                .writeTo(metadata);
        context.updateRun(runId, new PolicyWindTunnelRunUpdate(null, null, null, null, metadata));
    }

    private void updateRunPlaybackRate(
            PolicyWindTunnelExecutionContext context,
            String runId,
            double playbackRate) {
        Map<String, Object> currentMetadata = readRunMetadata(context, runId);
        BankingPolicyScenarioLabRunMetadata policyMetadata = readPolicyMetadata(currentMetadata);
        Map<String, Object> parameters = new LinkedHashMap<>(objectMap(policyMetadata.parameters()));
        parameters.put("playbackRate", playbackRate);
        Map<String, Object> metadata = policyMetadata
                .withParameters(parameters)
                .writeTo(currentMetadata);
        context.updateRun(runId, new PolicyWindTunnelRunUpdate(null, null, null, null, metadata));
    }

    private Map<String, Object> readRunMetadata(PolicyWindTunnelExecutionContext context, String runId) {
        return context.findRun(runId)
                .map(PolicyWindTunnelExecutionContext.PolicyWindTunnelStoredRun::metadata)
                .map(BankingPolicyScenarioSupport::objectMap)
                .orElseGet(Map::of);
    }

    private PolicyWindTunnelStoredRun findStoredRun(PolicyWindTunnelExecutionContext context, String runId) {
        restoreFinalizationReceiptIfPresent(context, runId);
        return context.findRun(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown runId: " + runId));
    }

    private BankingPolicyScenarioLabRunMetadata readPolicyMetadata(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        return readPolicyMetadata(readRunMetadata(context, runId));
    }

    private BankingPolicyScenarioLabRunMetadata readPolicyMetadata(Map<String, Object> metadata) {
        return BankingPolicyScenarioLabRunMetadata.fromMetadata(metadata);
    }

    private boolean shouldUseLiveHybridBundle(PolicyWindTunnelStoredRun storedRun) {
        BankingPolicyScenarioLabRunMetadata policyMetadata = readPolicyMetadata(storedRun.metadata());
        return isClockDrivenHybrid(policyMetadata.parameters()) && stringValue(storedRun.summaryPath()).isBlank();
    }

    private void startClockHorizonMonitor(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        ExecutionClaim executionClaim = context instanceof BankingRemotePolicyWindTunnelExecutionContext durableContext
                ? durableContext.currentExecutionClaim().orElse(null)
                : null;
        Thread worker = new Thread(
                () -> executeWithClaim(
                        context,
                        executionClaim,
                        () -> monitorClockDrivenHorizon(context, runId)),
                "banking-wt-horizon-" + runId);
        worker.setDaemon(true);
        worker.start();
    }

    private void monitorClockDrivenHorizon(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        while (true) {
            try {
                if (context instanceof BankingRemotePolicyWindTunnelExecutionContext durableContext
                        && durableContext.currentExecutionClaim().isPresent()) {
                    durableContext.renewCurrentExecutionClaim();
                }
                var record = context.findRun(runId).orElse(null);
                if (record == null || !"RUNNING".equalsIgnoreCase(stringValue(record.status()))) {
                    return;
                }
                BankingPolicyScenarioLabRunMetadata policyMetadata = readPolicyMetadata(record.metadata());
                if (!"CLOCK_RUNNING".equalsIgnoreCase(policyMetadata.phase())
                        || !isClockDrivenHybrid(policyMetadata.parameters())) {
                    return;
                }
                maybeAdvanceClockDrivenLifecycle(context, runId);
                record = context.findRun(runId).orElse(null);
                if (record == null || !"RUNNING".equalsIgnoreCase(stringValue(record.status()))) {
                    return;
                }
                policyMetadata = readPolicyMetadata(record.metadata());
                if (!"CLOCK_RUNNING".equalsIgnoreCase(policyMetadata.phase())) {
                    return;
                }
                Thread.sleep(clockHorizonMonitorPollMillis(policyMetadata.parameters()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (StaleExecutionClaimException e) {
                return;
            } catch (Exception e) {
                appendLog(context, runId, "Clock horizon monitor stopped before finalization: "
                        + firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
                return;
            }
        }
    }

    private long clockHorizonMonitorPollMillis(Map<String, Object> parameters) {
        double playbackRate = Math.max(0.1d, toDouble(parameters.get("playbackRate"), 1.0d));
        // Keep automatic horizon overshoot below roughly 0.02 simulated days for common playback rates.
        return Math.max(250L, Math.min(2_000L, Math.round((0.02d * 86_400_000d) / playbackRate)));
    }

    private void maybeAdvanceClockDrivenLifecycle(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        var record = context.findRun(runId).orElse(null);
        if (record == null || !"RUNNING".equalsIgnoreCase(stringValue(record.status()))) {
            return;
        }
        BankingPolicyScenarioLabRunMetadata policyMetadata = readPolicyMetadata(record.metadata());
        if (!"CLOCK_RUNNING".equalsIgnoreCase(policyMetadata.phase())
                || !isClockDrivenHybrid(policyMetadata.parameters())) {
            return;
        }
        String activeRunId = activeReplayRunId(context);
        if (!runId.equals(activeRunId)) {
            appendLogOnce(context, runId, activeRunId.isBlank()
                    ? "Clock horizon check skipped because simulator replay ownership could not be verified for this run."
                    : "Clock horizon check skipped because " + activeRunId + " is now the active replay owner.");
            return;
        }
        Long simulationTimeEpochMs = fetchSimulationTimeEpochMs(context);
        if (simulationTimeEpochMs == null) {
            return;
        }

        int horizonDays = Math.max(1, intValue(policyMetadata.parameters().get("simulationHorizonDays"), 30));
        long horizonMs = horizonDays * MILLIS_PER_SIMULATION_DAY;
        long elapsedMs = Math.max(0L, simulationTimeEpochMs - simulationStartEpochMs(policyMetadata.parameters()));
        double completion = Math.min(1.0d, elapsedMs / (double) horizonMs);
        double progress = Math.min(0.90d, 0.55d + (0.35d * completion));
        if (completion >= 1.0d) {
            synchronized (lifecycleLock(runId)) {
                if (!lifecycleTransitionAllows(context, runId, TRANSITION_HORIZON_READY)) {
                    return;
                }
                if (!applyPlaybackControlForRun(
                        context,
                        runId,
                        true,
                        0.0,
                        "Simulation horizon reached")) {
                    appendLogOnce(
                            context,
                            runId,
                            "Simulation horizon reached, but platform-owned replay pause was not confirmed. Retrying before publishing finalization readiness.");
                    return;
                }
                updateRunStateAndAppendLog(
                        context,
                        runId,
                        STATUS_READY_TO_FINALIZE,
                        PHASE_READY_TO_FINALIZE,
                        0.91d,
                        null,
                        null,
                        null,
                        true,
                        "Clock-driven horizon reached after "
                                + formatDecimal(elapsedMs / (double) MILLIS_PER_SIMULATION_DAY)
                                + " simulated day(s). Playback is paused; awaiting platform drain and generation seal before report finalization.");
            }
            return;
        }
        if (progress - policyMetadata.progress() >= 0.01d) {
            updateRunState(context, runId, "RUNNING", "CLOCK_RUNNING", progress, null, null, null, false);
        }
    }

    private Map<String, Object> fetchSimulatorReplay(PolicyWindTunnelExecutionContext context) {
        try {
            String body = restTemplate.getForObject(context.simulatorUrl() + "/api/clock/replay", String.class);
            if (blank(body)) {
                return Map.of();
            }
            return objectMap(objectMapper.readValue(body, Map.class));
        } catch (Exception e) {
            log.debug("Simulator replay context unavailable for clock-driven lifecycle: {}", e.getMessage());
            return Map.of();
        }
    }

    private Long fetchSimulationTimeEpochMs(PolicyWindTunnelExecutionContext context) {
        try {
            return restTemplate.getForObject(context.simulatorUrl() + "/api/clock/time", Long.class);
        } catch (Exception e) {
            log.debug("Simulator time unavailable for clock-driven lifecycle: {}", e.getMessage());
            return null;
        }
    }

    private String estimateWallClockDuration(String horizonDaysValue, double playbackRate) {
        int horizonDays = Math.max(1, intValue(horizonDaysValue, 30));
        double wallSeconds = (horizonDays * 86_400.0d) / Math.max(0.1d, playbackRate);
        if (wallSeconds < 60.0d) {
            return formatDecimal(wallSeconds) + " seconds";
        }
        double wallMinutes = wallSeconds / 60.0d;
        if (wallMinutes < 60.0d) {
            return formatDecimal(wallMinutes) + " minutes";
        }
        return formatDecimal(wallMinutes / 60.0d) + " hours";
    }

    private String formatPlaybackRate(double playbackRate) {
        return Math.rint(playbackRate) == playbackRate
                ? String.valueOf((long) playbackRate) + "x"
                : formatDecimal(playbackRate) + "x";
    }

    private String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private Map<String, Object> buildClockDrivenHybridLiveBundle(
            PolicyWindTunnelExecutionContext context,
            PolicyWindTunnelStoredRun storedRun) {
        BankingPolicyScenarioLabRunMetadata policyMetadata = readPolicyMetadata(storedRun.metadata());
        Map<String, Object> parameters = objectMap(policyMetadata.parameters());
        String runId = storedRun.runId();
        String status = firstNonBlank(stringValue(storedRun.status()), "RUNNING").toUpperCase(Locale.ROOT);
        String phase = firstNonBlank(policyMetadata.phase(), status);
        String presetId = firstNonBlank(stringValue(parameters.get("presetId")), "custom");
        String cohortId = selectedCohortId(parameters);
        int cohortSize = countGeneratedCohortCustomers(
                context,
                cohortId,
                intValue(parameters.get("cohortTargetSize"), 0));
        String productCode = firstNonBlank(stringValue(parameters.get("productCode")), "TS001");
        String policyType = policyTypeForPreset(presetId);

        Map<String, Object> artifactDownloads = liveHybridArtifactDownloads(runId);
        Map<String, Object> artifactPaths = linkedMap(
                "summary", "live-generated",
                "experimentCard", "live-generated",
                "simulationTimelineJson", "live-generated",
                "sideEffectLedgerJson", "live-generated",
                "branchStateProjectionJson", "live-generated",
                "hybridAgentAuditJson", "live-generated",
                "simulationRunEvidence", "output/simulation-runs/" + runId);

        Map<String, Object> decisionReadiness = liveHybridDecisionReadiness(status, phase);
        Map<String, Object> runConfig = new LinkedHashMap<>(parameters);
        Map<String, Object> simulationWindow = clockDrivenSimulationWindow(context, runId, parameters);
        runConfig.put("executionMode", EXECUTION_MODE_CLOCK_DRIVEN_HYBRID);
        runConfig.put("lifecycleState", status);
        runConfig.put("phase", phase);
        runConfig.put("cohortSize", cohortSize);
        runConfig.put("cohortId", cohortId);
        runConfig.putAll(simulationWindow);

        Map<String, Object> policyToggle = linkedMap(
                "productCode", productCode,
                "productName", productNameFor(productCode),
                "policyType", policyType,
                "baselineRate", parameters.get("baselineRate"),
                "candidateRate", parameters.get("candidateRate"));

        Map<String, Object> decisionComparison = linkedMap(
                "schemaVersion", "customer_outcome_decision_comparison.v1",
                "comparisonId", presetId + "-clock-driven-simulation-live",
                "scenarioId", scenarioIdForPreset(presetId),
                "title", "Live Clock-Driven Simulation Decision Review",
                "decisionQuestion", "Is there enough captured evidence to make a production decision?",
                "baseline", linkedMap(
                        "branchId", "baseline-current-policy",
                        "status", "PENDING_FINAL_COMPARISON",
                        "evidenceLinks", Map.of()),
                "proposed", linkedMap(
                        "branchId", "clock-driven-simulation-run",
                        "status", status,
                        "evidenceLinks", linkedMap(
                                "simulationRunEvidencePack", artifactDownloads.get("simulationRunEvidence"))),
                "metricDeltas", List.of(),
                "replayEvidence", linkedMap(
                        "requiredEvidence", List.of(
                                "customerProfile",
                                "simulationTimeline",
                                "sideEffectLedger",
                                "domainCheckpoints",
                                "simulationRunEvidencePack"),
                        "shared", linkedMap(
                                "simulationRunEvidencePack", artifactDownloads.get("simulationRunEvidence"),
                                "scenarioLabEvidencePack", artifactDownloads.get("evidencePack"))));

        Map<String, Object> simulationTimeline = buildLiveHybridTimeline(policyMetadata, simulationWindow);
        Map<String, Object> sideEffectLedger = buildLiveHybridSideEffectLedger(runId, status, phase, artifactDownloads);
        Map<String, Object> decisionAudit = loadRuntimeDecisionAudit(context, runId, false);
        Map<String, Object> opportunityReconciliation = unavailableOpportunityReconciliation(runId);
        Map<String, Object> agenticSimulation = buildAgenticSimulationSummary(
                parameters,
                cohortSize,
                decisionAudit,
                artifactDownloads,
                simulationWindow,
                runId,
                opportunityReconciliation,
                false);
        Map<String, Object> branchStateProjection = linkedMap(
                "schemaVersion", "customer_outcome_branch_state_projection.v1",
                "branches", List.of(
                        linkedMap(
                                "branchId", "baseline-current-policy",
                                "branchRole", "baseline",
                                "agentCount", cohortSize,
                                "status", "PENDING_FINAL_COMPARISON"),
                        linkedMap(
                                "branchId", "clock-driven-simulation-run",
                                "branchRole", "proposed",
                                "agentCount", cohortSize,
                                "status", status)),
                "mutations", List.of(),
                "metricTraces", List.of());

        Map<String, Object> evaluator = linkedMap(
                "status", "WATCH",
                "verdict", "WATCH",
                "breachCount", 0,
                "failBreaches", 0,
                "decisionGates", linkedMap(
                        "breachedGateCount", 1,
                        "failGateCount", 0,
                        "gates", List.of(
                                linkedMap(
                                        "gateId", "cohort-activated",
                                        "label", "Cohort activated",
                                        "status", cohortSize > 0 ? "PASS" : "WATCH",
                                        "narrative", "Generated customers were activated into agent-runtime in chunks."),
                                linkedMap(
                                        "gateId", "evidence-capture",
                                        "label", "Evidence capture",
                                        "status", "WATCH",
                                        "narrative", "E24 run ledger is collecting runtime evidence while the clock advances."),
                                linkedMap(
                                        "gateId", "decision-summary",
                                        "label", "Decision summary",
                                        "status", "BLOCKED",
                                        "narrative", "No finalized baseline-vs-proposed outcome report exists yet."))));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", BankingPolicyWindTunnelManifest.SUMMARY_SCHEMA_VERSION);
        summary.put("summaryMode", "CLOCK_DRIVEN_HYBRID_LIVE");
        summary.put("runId", runId);
        summary.put("scenarioId", scenarioIdForPreset(presetId));
        summary.put("demoPreset", Map.of("presetId", presetId));
        summary.put("decisionReadiness", decisionReadiness);
        summary.put("decisionComparison", decisionComparison);
        summary.put("simulationTimeline", simulationTimeline);
        summary.put("sideEffectLedger", sideEffectLedger);
        summary.put("branchStateProjection", branchStateProjection);
        summary.put("runConfig", runConfig);
        summary.put("policyToggle", policyToggle);
        summary.put("hybridDecisionAuditSummary", decisionAudit.get("summary"));
        summary.put("agenticSimulation", agenticSimulation);
        summary.put("opportunityReconciliation", opportunityReconciliation);
        summary.put("headline", linkedMap(
                "title", "Live Clock-Driven Simulation Evidence",
                "summary", "The selected cohort is running under simulator clock control. Evidence is durable, but the launch decision is not ready until the clock-driven run is finalized into an outcome report.",
                "verdict", "WATCH",
                "simulatedSubjects", cohortSize,
                "phase", phase));
        summary.put("candidate", linkedMap(
                "activeAgents", cohortSize,
                "impactedAgents", 0,
                "evidenceState", "CAPTURING"));
        summary.put("delta", linkedMap(
                "primaryOutcomeDelta", null,
                "counterMetricDelta", null));
        summary.put("evaluator", evaluator);
        summary.put("exposureSummary", linkedMap(
                "scopeLabel", cohortId.isBlank() ? "Generated customer cohort" : cohortId,
                "cohortAgents", cohortSize,
                "eligibleAgents", cohortSize,
                "nonEligibleAgents", 0,
                "impactedAgents", 0,
                "exposureUnit", "count"));
        summary.put("artifacts", artifactPaths);
        summary.put("limitations", List.of(
                "This live bundle is operational evidence, not a completed launch recommendation.",
                "Metrics remain intentionally empty until the clock-driven run is finalized into a replay-linked outcome comparison.",
                "Domain-internal external calls are only visible when the domain emits evidence events."));

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("schemaVersion", "policy_wind_tunnel_bundle.v1");
        bundle.put("summarySchemaVersion", BankingPolicyWindTunnelManifest.SUMMARY_SCHEMA_VERSION);
        bundle.put("domainId", BankingPolicyWindTunnelManifest.DOMAIN_ID);
        bundle.put("packId", BankingPolicyWindTunnelManifest.PACK_ID);
        bundle.put("experimentId", BankingPolicyWindTunnelManifest.EXPERIMENT_ID);
        bundle.put("runId", runId);
        bundle.put("presetId", presetId);
        bundle.put("lookupMode", "live-hybrid-run-record");
        bundle.put("loadedAt", OffsetDateTime.now(ZoneOffset.UTC));
        bundle.put("decisionReadiness", decisionReadiness);
        bundle.put("decisionComparison", decisionComparison);
        bundle.put("simulationTimeline", simulationTimeline);
        bundle.put("sideEffectLedger", sideEffectLedger);
        bundle.put("branchStateProjection", branchStateProjection);
        bundle.put("branches", linkedMap(
                "policyToggle", policyToggle,
                "baseline", Map.of("branchId", "baseline-current-policy", "status", "PENDING_FINAL_COMPARISON"),
                "candidate", Map.of("branchId", "clock-driven-hybrid-run", "status", status),
                "delta", Map.of()));
        bundle.put("metrics", linkedMap(
                "headline", linkedMap(
                        "simulatedSubjects", cohortSize,
                        "decisionReadiness", decisionReadiness.get("status"),
                        "phase", phase),
                "evidence", linkedMap(
                        "status", "CAPTURING",
                        "simulationRunEvidencePack", artifactDownloads.get("simulationRunEvidence"))));
        bundle.put("segments", linkedMap(
                "exposure", summary.get("exposureSummary"),
                "impacted", List.of(),
                "impactSummary", Map.of()));
        bundle.put("timeline", linkedMap(
                "events", simulationTimeline.get("events"),
                "sideEffects", sideEffectLedger.get("entries"),
                "stateProjection", branchStateProjection));
        bundle.put("decisionGates", evaluator.get("decisionGates"));
        bundle.put("agenticSimulation", agenticSimulation);
        bundle.put("opportunityReconciliation", opportunityReconciliation);
        bundle.put("artifacts", linkedMap(
                "paths", artifactPaths,
                "downloads", artifactDownloads));
        bundle.put("summary", summary);
        bundle.put("domainPayload", summary);
        bundle.put("experimentCardMarkdown", buildClockDrivenHybridExperimentCard(summary));
        bundle.put("artifactPaths", artifactPaths);
        bundle.put("artifactDownloads", artifactDownloads);
        bundle.put("hybrid", linkedMap(
                "available", true,
                "live", true,
                "executionMode", EXECUTION_MODE_CLOCK_DRIVEN_HYBRID,
                "agentAudit", decisionAudit,
                "agenticSimulation", agenticSimulation,
                "decisionReadiness", decisionReadiness));
        return bundle;
    }

    @SuppressWarnings("rawtypes")
    private Map<String, Object> loadRuntimeDecisionAudit(
            PolicyWindTunnelExecutionContext context,
            String runId,
            boolean includeDecisionRows) {
        try {
            ResponseEntity<List> response = restTemplate.exchange(
                    context.agentRuntimeUrl() + "/api/agents/hybrid-decisions?simulationRunId=" + urlEncode(runId)
                            + "&includeDecisions=" + includeDecisionRows,
                    HttpMethod.GET,
                    new HttpEntity<>(null, HttpHeaders.EMPTY),
                    List.class);
            if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return emptyDecisionAudit("agent-runtime-unavailable");
            }
            return buildDecisionAuditPayload(listOfMaps(response.getBody()), "agent-runtime");
        } catch (Exception e) {
            log.debug("Decision audit not available for live clock-driven bundle: {}", e.getMessage());
            return emptyDecisionAudit("agent-runtime-unavailable");
        }
    }

    private boolean shouldIncludeRuntimeDecisionRows(PolicyWindTunnelExecutionContext context, String runId) {
        Map<String, Object> parameters = readPolicyMetadata(context, runId).parameters();
        return AGENT_DECISION_MODE_REAL_LLM.equals(requestedAgentDecisionMode(parameters))
                || isEventOnlyLifecycle(parameters);
    }

    private Map<String, Object> finalizedRuntimeDecisionAudit(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Map<String, Object> compactAudit,
            RuntimeTickDrainResult drainResult) {
        if (!shouldIncludeRuntimeDecisionRows(context, runId)) {
            return compactAudit;
        }
        return withRuntimeTickDrain(
                loadRuntimeDecisionAudit(context, runId, true),
                drainResult);
    }

    private Map<String, Object> waitForRuntimeDecisionAuditToSettle(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        RuntimeTickDrainResult drainResult = waitForRuntimeTickDrain(context, runId);
        Map<String, Object> latest = withRuntimeTickDrain(
                loadRuntimeDecisionAudit(context, runId, false),
                drainResult);
        String lastSignature = decisionAuditSignature(latest);
        int stableSamples = 0;
        long timeoutMillis = Math.max(1_000L, HYBRID_DECISION_AUDIT_SETTLE_TIMEOUT_MILLIS);
        long pollMillis = Math.max(100L, HYBRID_DECISION_AUDIT_SETTLE_POLL_MILLIS);
        int requiredStableSamples = Math.max(1, HYBRID_DECISION_AUDIT_STABLE_SAMPLES);
        long deadlineNanos = System.nanoTime() + (timeoutMillis * 1_000_000L);
        while (System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return latest;
            }
            Map<String, Object> next = withRuntimeTickDrain(
                    loadRuntimeDecisionAudit(context, runId, false),
                    drainResult);
            String nextSignature = decisionAuditSignature(next);
            latest = next;
            if (nextSignature.equals(lastSignature)) {
                stableSamples++;
                if (stableSamples >= requiredStableSamples && decisionAuditHasSettledWindowTelemetry(latest)) {
                    return finalizedRuntimeDecisionAudit(context, runId, latest, drainResult);
                }
            } else {
                stableSamples = 0;
                lastSignature = nextSignature;
            }
        }
        return finalizedRuntimeDecisionAudit(context, runId, latest, drainResult);
    }

    @SuppressWarnings("rawtypes")
    private RuntimeTickDrainResult waitForRuntimeTickDrain(PolicyWindTunnelExecutionContext context, String runId) {
        if (runId == null || runId.isBlank()) {
            return RuntimeTickDrainResult.unverified(
                    "MISSING_RUN_ID",
                    "Runtime tick drain could not be verified because the simulation run id was missing.");
        }
        long timeoutMillis = Math.max(1_000L, RUNTIME_TICK_DRAIN_TIMEOUT_MILLIS);
        long pollMillis = Math.max(100L, HYBRID_DECISION_AUDIT_SETTLE_POLL_MILLIS);
        int requiredStableSamples = Math.max(1, RUNTIME_TICK_DRAIN_STABLE_SAMPLES);
        long deadlineNanos = System.nanoTime() + (timeoutMillis * 1_000_000L);
        String lastSignature = null;
        int stableSamples = 0;
        while (System.nanoTime() < deadlineNanos) {
            try {
                ResponseEntity<Map> response = restTemplate.exchange(
                        context.agentRuntimeUrl() + "/api/agents/runtime-state?simulationRunId=" + urlEncode(runId),
                        HttpMethod.GET,
                        new HttpEntity<>(null, HttpHeaders.EMPTY),
                        Map.class);
                Map<String, Object> payload = response != null && response.getBody() != null
                        ? objectMap(response.getBody())
                        : Map.of();
                if (!runtimeTickDrainResponseMatchesRun(payload, runId)) {
                    log.warn(
                            "Runtime tick drain response for run {} was not scoped to the requested run; skipping drain readiness sample.",
                            runId);
                    return RuntimeTickDrainResult.unverified(
                            "UNVERIFIED_SCOPE",
                            "Runtime tick drain could not be verified because agent-runtime returned an unscoped or mismatched runtime-state payload.");
                }
                String signature = runtimeTickDrainSignature(payload);
                if (runtimeTickDrainComplete(payload)) {
                    if (signature.equals(lastSignature)) {
                        stableSamples++;
                    } else {
                        lastSignature = signature;
                        stableSamples = 1;
                    }
                    if (stableSamples >= requiredStableSamples) {
                        return RuntimeTickDrainResult.drained();
                    }
                } else {
                    lastSignature = signature;
                    stableSamples = 0;
                }
            } catch (Exception e) {
                log.debug("Runtime tick telemetry unavailable while finalizing audit: {}", e.getMessage());
                return RuntimeTickDrainResult.unverified(
                        "UNAVAILABLE",
                        "Runtime tick drain could not be verified because agent-runtime runtime-state telemetry was unavailable.");
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RuntimeTickDrainResult.unverified(
                        "INTERRUPTED",
                        "Runtime tick drain verification was interrupted before a stable drained sample was observed.");
            }
        }
        return RuntimeTickDrainResult.unverified(
                "TIMEOUT",
                "Runtime tick drain could not be verified before the finalization drain timeout.");
    }

    private Map<String, Object> withRuntimeTickDrain(
            Map<String, Object> decisionAudit,
            RuntimeTickDrainResult drainResult) {
        Map<String, Object> decorated = new LinkedHashMap<>(decisionAudit == null ? Map.of() : decisionAudit);
        decorated.put("runtimeTickDrain", drainResult.toMap());
        return decorated;
    }

    private boolean runtimeTickDrainResponseMatchesRun(Map<String, Object> telemetry, String runId) {
        if (telemetry == null || telemetry.isEmpty() || runId == null || runId.isBlank()) {
            return false;
        }
        String scope = stringValue(telemetry.get("scope"));
        String responseRunId = stringValue(telemetry.get("simulationRunId"));
        return "simulationRun".equals(scope)
                && responseRunId != null
                && responseRunId.trim().equals(runId.trim());
    }

    private boolean runtimeTickDrainComplete(Map<String, Object> telemetry) {
        Map<String, Object> tickExecution = objectMap(telemetry.get("tickExecution"));
        if (tickExecution.isEmpty()) {
            return intValue(telemetry.get("activeTickTasks"), 0) <= 0
                    && intValue(telemetry.get("runningAgents"), 0) <= 0
                    && intValue(telemetry.get("pendingAgents"), 0) <= 0;
        }
        int submittedTasks = intValue(tickExecution.get("submittedTasks"), 0);
        int completedTasks = intValue(tickExecution.get("completedTasks"), 0);
        return intValue(tickExecution.get("activeTasks"), 0) <= 0
                && intValue(tickExecution.get("runningAgents"), 0) <= 0
                && intValue(tickExecution.get("pendingAgents"), 0) <= 0
                && completedTasks >= submittedTasks;
    }

    private String runtimeTickDrainSignature(Map<String, Object> telemetry) {
        Map<String, Object> tickExecution = objectMap(telemetry.get("tickExecution"));
        if (tickExecution.isEmpty()) {
            return String.join(":",
                    String.valueOf(intValue(telemetry.get("activeTickTasks"), 0)),
                    String.valueOf(intValue(telemetry.get("runningAgents"), 0)),
                    String.valueOf(intValue(telemetry.get("pendingAgents"), 0)),
                    String.valueOf(intValue(telemetry.get("activeAgents"), 0)),
                    String.valueOf(intValue(telemetry.get("oldestBacklogMs"), 0)));
        }
        return String.join(":",
                String.valueOf(intValue(tickExecution.get("activeTasks"), 0)),
                String.valueOf(intValue(tickExecution.get("runningAgents"), 0)),
                String.valueOf(intValue(tickExecution.get("pendingAgents"), 0)),
                String.valueOf(intValue(tickExecution.get("submittedTasks"), 0)),
                String.valueOf(intValue(tickExecution.get("completedTasks"), 0)),
                String.valueOf(intValue(tickExecution.get("failedTasks"), 0)));
    }

    private String decisionAuditSignature(Map<String, Object> decisionAudit) {
        Map<String, Object> summary = objectMap(decisionAudit.get("summary"));
        Map<String, Object> totals = objectMap(decisionAudit.get("totals"));
        return String.join(":",
                String.valueOf(intValue(summary.get("agentCount"), intValue(totals.get("agentCount"), 0))),
                String.valueOf(intValue(summary.get("decisionCount"), intValue(totals.get("decisionCount"), 0))),
                String.valueOf(intValue(
                        summary.get("decisionWindowCount"),
                        intValue(totals.get("decisionWindowCount"), 0))),
                String.valueOf(intValue(
                        summary.get("decisionOpportunityCount"),
                        intValue(totals.get("decisionOpportunityCount"), 0))),
                String.valueOf(intValue(
                        summary.get("decisionAdmittedCount"),
                        intValue(totals.get("decisionAdmittedCount"), 0))),
                String.valueOf(intValue(
                        summary.get("decisionDeferredCount"),
                        intValue(totals.get("decisionDeferredCount"), 0))),
                String.valueOf(intValue(summary.get("fallbackCount"), intValue(totals.get("fallbackCount"), 0))));
    }

    private boolean decisionAuditHasSettledWindowTelemetry(Map<String, Object> decisionAudit) {
        Map<String, Object> summary = objectMap(decisionAudit.get("summary"));
        Map<String, Object> totals = objectMap(decisionAudit.get("totals"));
        int decisionCount = intValue(summary.get("decisionCount"), intValue(totals.get("decisionCount"), 0));
        if (decisionCount <= 0) {
            return true;
        }
        int opportunityCount = intValue(
                summary.get("decisionOpportunityCount"),
                intValue(totals.get("decisionOpportunityCount"), 0));
        int admittedCount = intValue(
                summary.get("decisionAdmittedCount"),
                intValue(totals.get("decisionAdmittedCount"), 0));
        int windowCount = intValue(
                summary.get("decisionWindowCount"),
                intValue(totals.get("decisionWindowCount"), 0));
        if (opportunityCount <= 0 && admittedCount <= 0 && windowCount <= 0) {
            return false;
        }
        return admittedCount >= decisionCount;
    }

    private Map<String, Object> emptyDecisionAudit(String source) {
        return buildDecisionAuditPayload(List.of(), source);
    }

    private Map<String, Object> buildDecisionAuditPayload(List<Map<String, Object>> rawAgents, String source) {
        List<Map<String, Object>> agents = new ArrayList<>();
        Map<String, Integer> actionCounts = new LinkedHashMap<>();
        Map<String, Integer> responseFormatCounts = new LinkedHashMap<>();
        Map<String, Integer> deferredDecisionReasonCounts = new LinkedHashMap<>();
        Set<String> contractSchemaVersions = new HashSet<>();
        int decisionCount = 0;
        int fallbackCount = 0;
        int decisionWindowCount = 0;
        int decisionOpportunityCount = 0;
        int decisionAdmittedCount = 0;
        int decisionDeferredCount = 0;

        for (Map<String, Object> rawAgent : rawAgents) {
            Map<String, Object> agent = new LinkedHashMap<>(rawAgent);
            List<Map<String, Object>> decisions = listOfMaps(agent.get("decisions"));
            int agentDecisionCount = intValue(agent.get("totalDecisionCount"), intValue(agent.get("eventCount"), decisions.size()));
            int agentFallbackCount = intValue(agent.get("fallbackCount"), 0);
            Map<String, Object> decisionWindowSummary = objectMap(agent.get("decisionWindowSummary"));
            int agentWindowCount = intValue(agent.get("decisionWindowCount"), intValue(decisionWindowSummary.get("windowCount"), 0));
            int agentOpportunityCount = intValue(agent.get("decisionOpportunityCount"), intValue(decisionWindowSummary.get("opportunityCount"), 0));
            int agentAdmittedCount = intValue(agent.get("decisionAdmittedCount"), intValue(decisionWindowSummary.get("admittedCount"), agentDecisionCount));
            int agentDeferredCount = intValue(agent.get("decisionDeferredCount"), intValue(decisionWindowSummary.get("deferredCount"), Math.max(0, agentOpportunityCount - agentAdmittedCount)));
            Map<String, Object> agentDeferredReasons = objectMap(firstNonNull(
                    agent.get("decisionDeferredByReason"),
                    decisionWindowSummary.get("deferredByReason")));
            Map<String, Integer> agentActionCounts = intMap(agent.get("actionCounts"));
            Map<String, Integer> agentResponseFormatCounts = intMap(agent.get("responseFormatCounts"));
            for (Object schemaVersion : listValue(agent.get("contractSchemaVersions"))) {
                String text = stringValue(schemaVersion);
                if (!text.isBlank()) {
                    contractSchemaVersions.add(text);
                }
            }

            for (Map<String, Object> decision : decisions) {
                incrementCount(actionCounts, decision.get("action"));
                incrementCount(agentActionCounts, decision.get("action"));
                incrementCount(responseFormatCounts, decision.get("responseFormat"));
                incrementCount(agentResponseFormatCounts, decision.get("responseFormat"));
                String schemaVersion = stringValue(decision.get("contractSchemaVersion"));
                if (!schemaVersion.isBlank()) {
                    contractSchemaVersions.add(schemaVersion);
                }
            }
            if (decisions.isEmpty()) {
                mergeCounts(actionCounts, intMap(agent.get("actionCounts")));
                mergeCounts(responseFormatCounts, intMap(agent.get("responseFormatCounts")));
            }

            decisionCount += agentDecisionCount;
            fallbackCount += agentFallbackCount;
            decisionWindowCount += agentWindowCount;
            decisionOpportunityCount += agentOpportunityCount;
            decisionAdmittedCount += agentAdmittedCount;
            decisionDeferredCount += agentDeferredCount;
            for (Map.Entry<String, Object> entry : agentDeferredReasons.entrySet()) {
                incrementCount(deferredDecisionReasonCounts, entry.getKey(), intValue(entry.getValue(), 0));
            }
            agent.put("totalDecisionCount", agentDecisionCount);
            agent.put("fallbackRate", agentDecisionCount == 0 ? 0.0d : agentFallbackCount / (double) agentDecisionCount);
            agent.put("actionCounts", agentActionCounts);
            agent.put("responseFormatCounts", agentResponseFormatCounts);
            agent.put("dominantResponseFormat", dominantCountKey(agentResponseFormatCounts));
            agent.put("referenceReplicateDecisions", decisions.isEmpty()
                    ? listOfMaps(agent.get("referenceReplicateDecisions"))
                    : List.of(decisions.getFirst()));
            if (Boolean.TRUE.equals(agent.get("decisionsOmitted"))) {
                agent.remove("decisions");
            }
            agents.add(agent);
        }

        Map<String, Object> summary = linkedMap(
                "agentCount", agents.size(),
                "decisionCount", decisionCount,
                "fallbackCount", fallbackCount,
                "fallbackRateP50", decisionCount == 0 ? 0.0d : fallbackCount / (double) decisionCount,
                "structuredDecisionRateP50", responseFormatCounts.containsKey("structured") ? 1.0d : 0.0d,
                "legacyTextRateP50", responseFormatCounts.containsKey("legacy_text") ? 1.0d : 0.0d,
                "dominantDecisionAction", dominantCountKey(actionCounts),
                "dominantResponseFormat", dominantCountKey(responseFormatCounts),
                "responseFormatCounts", responseFormatCounts,
                "contractSchemaVersions", new ArrayList<>(contractSchemaVersions),
                "decisionWindowCount", decisionWindowCount,
                "decisionOpportunityCount", decisionOpportunityCount,
                "decisionAdmittedCount", decisionAdmittedCount,
                "decisionDeferredCount", decisionDeferredCount,
                "decisionDeferredByReason", deferredDecisionReasonCounts,
                "opportunityLedger", buildOpportunityLedgerTotals(agents));

        Map<String, Object> opportunityLedger = objectMap(summary.get("opportunityLedger"));

        return linkedMap(
                "schemaVersion", "hybrid_decision_audit_live.v1",
                "source", source,
                "totals", linkedMap(
                        "agentCount", agents.size(),
                        "decisionCount", decisionCount,
                        "fallbackCount", fallbackCount,
                        "decisionWindowCount", decisionWindowCount,
                        "decisionOpportunityCount", decisionOpportunityCount,
                        "decisionAdmittedCount", decisionAdmittedCount,
                        "decisionDeferredCount", decisionDeferredCount,
                        "decisionDeferredByReason", deferredDecisionReasonCounts,
                        "opportunityLedger", opportunityLedger),
                "summary", summary,
                "opportunityLedger", opportunityLedger,
                "actionCounts", actionCounts,
                "responseFormatCounts", responseFormatCounts,
                "contractSchemaVersions", new ArrayList<>(contractSchemaVersions),
                "agents", agents);
    }

    private Map<String, Object> buildOpportunityLedgerTotals(List<Map<String, Object>> agents) {
        Map<String, Long> statusCounts = emptyOpportunityStatusCounts();
        Map<String, Long> deferredReasonCounts = new TreeMap<>();
        Map<String, Long> historicalDeferralReasonCounts = new TreeMap<>();
        Set<String> agentIds = new HashSet<>();
        Set<String> simulationRunIds = new HashSet<>();
        long generated = 0L;
        long terminal = 0L;
        long backlog = 0L;
        long admitted = 0L;
        long attempts = 0L;
        long validDecisions = 0L;
        long recoverableDecisions = 0L;
        long succeededWithoutEvidence = 0L;
        long evidenceGaps = 0L;
        long historicalDeferralCount = 0L;
        int invalidAgentAuditCount = 0;

        for (Map<String, Object> agent : agents) {
            if (!(agent.get("opportunityAudit") instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> audit = objectMap(agent.get("opportunityAudit"));
            if (!opportunityAgentAuditIsValid(audit)) {
                invalidAgentAuditCount++;
                continue;
            }
            String agentId = stringValue(audit.get("agentId"));
            String simulationRunId = stringValue(audit.get("simulationRunId"));
            if (!agentIds.add(agentId)) {
                invalidAgentAuditCount++;
                continue;
            }
            simulationRunIds.add(simulationRunId);
            generated = Math.addExact(generated, strictNonNegativeLong(audit.get("generatedOpportunities")));
            terminal = Math.addExact(terminal, strictNonNegativeLong(audit.get("terminalOpportunities")));
            backlog = Math.addExact(backlog, strictNonNegativeLong(audit.get("remainingBacklog")));
            admitted = Math.addExact(admitted, strictNonNegativeLong(audit.get("admittedOpportunities")));
            attempts = Math.addExact(attempts, strictNonNegativeLong(audit.get("totalAttempts")));
            validDecisions = Math.addExact(validDecisions, strictNonNegativeLong(audit.get("validDecisions")));
            recoverableDecisions = Math.addExact(
                    recoverableDecisions,
                    strictNonNegativeLong(audit.get("recoverableRecordedDecisions")));
            succeededWithoutEvidence = Math.addExact(
                    succeededWithoutEvidence,
                    strictNonNegativeLong(audit.get("succeededWithoutDecisionEvidence")));
            evidenceGaps = Math.addExact(
                    evidenceGaps,
                    strictNonNegativeLong(audit.get("decisionEvidenceGaps")));
            historicalDeferralCount = Math.addExact(
                    historicalDeferralCount,
                    strictNonNegativeLong(audit.get("historicalDeferralCount")));
            for (String status : OPPORTUNITY_LIFECYCLE_STATUSES) {
                statusCounts.merge(
                        status,
                        strictNonNegativeLong(objectMap(audit.get("statusCounts")).get(status)),
                        Math::addExact);
            }
            for (Map.Entry<String, Object> reason : objectMap(audit.get("deferredReasonCounts")).entrySet()) {
                deferredReasonCounts.merge(
                        reason.getKey(),
                        strictNonNegativeLong(reason.getValue()),
                        Math::addExact);
            }
            for (Map.Entry<String, Object> reason
                    : objectMap(audit.get("historicalDeferralReasonCounts")).entrySet()) {
                historicalDeferralReasonCounts.merge(
                        reason.getKey(),
                        strictNonNegativeLong(reason.getValue()),
                        Math::addExact);
            }
        }

        return linkedMap(
                "schemaVersion", OPPORTUNITY_AUDIT_TOTALS_SCHEMA_VERSION,
                "agentCount", agentIds.size(),
                "invalidAgentAuditCount", invalidAgentAuditCount,
                "simulationRunIds", simulationRunIds.stream().sorted().toList(),
                "consistentRunScope", simulationRunIds.size() <= 1,
                "generatedOpportunities", generated,
                "terminalOpportunities", terminal,
                "remainingBacklog", backlog,
                "admittedOpportunities", admitted,
                "totalAttempts", attempts,
                "validDecisions", validDecisions,
                "recoverableRecordedDecisions", recoverableDecisions,
                "succeededWithoutDecisionEvidence", succeededWithoutEvidence,
                "decisionEvidenceGaps", evidenceGaps,
                "statusCounts", statusCounts,
                "deferredReasonCounts", deferredReasonCounts,
                "historicalDeferralCount", historicalDeferralCount,
                "historicalDeferralReasonCounts", historicalDeferralReasonCounts);
    }

    private static boolean opportunityAgentAuditIsValid(Map<String, Object> audit) {
        if (!OPPORTUNITY_AGENT_AUDIT_SCHEMA_VERSION.equals(stringValue(audit.get("schemaVersion")))
                || stringValue(audit.get("simulationRunId")).isBlank()
                || stringValue(audit.get("agentId")).isBlank()) {
            return false;
        }
        Long generated = strictNonNegativeLong(audit.get("generatedOpportunities"));
        Long terminal = strictNonNegativeLong(audit.get("terminalOpportunities"));
        Long backlog = strictNonNegativeLong(audit.get("remainingBacklog"));
        Long admitted = strictNonNegativeLong(audit.get("admittedOpportunities"));
        Long attempts = strictNonNegativeLong(audit.get("totalAttempts"));
        Long validDecisions = strictNonNegativeLong(audit.get("validDecisions"));
        Long recoverable = strictNonNegativeLong(audit.get("recoverableRecordedDecisions"));
        Long succeededWithoutEvidence = strictNonNegativeLong(audit.get("succeededWithoutDecisionEvidence"));
        Long evidenceGaps = strictNonNegativeLong(audit.get("decisionEvidenceGaps"));
        Long historicalDeferralCount = strictNonNegativeLong(audit.get("historicalDeferralCount"));
        Map<String, Object> rawStatusCounts = objectMap(audit.get("statusCounts"));
        if (generated == null
                || terminal == null
                || backlog == null
                || admitted == null
                || attempts == null
                || validDecisions == null
                || recoverable == null
                || succeededWithoutEvidence == null
                || evidenceGaps == null
                || historicalDeferralCount == null
                || !rawStatusCounts.keySet().equals(OPPORTUNITY_LIFECYCLE_STATUSES)) {
            return false;
        }
        Map<String, Long> statusCounts = emptyOpportunityStatusCounts();
        for (String status : OPPORTUNITY_LIFECYCLE_STATUSES) {
            Long count = strictNonNegativeLong(rawStatusCounts.get(status));
            if (count == null) {
                return false;
            }
            statusCounts.put(status, count);
        }
        long statusTotal = statusCounts.values().stream().mapToLong(Long::longValue).sum();
        long expectedBacklog = statusCounts.get("PENDING")
                + statusCounts.get("RUNNING")
                + statusCounts.get("DEFERRED");
        Map<String, Long> deferredReasonCounts = strictOpportunityReasonCounts(
                audit.get("deferredReasonCounts"));
        Map<String, Long> historicalDeferralReasonCounts = strictOpportunityReasonCounts(
                audit.get("historicalDeferralReasonCounts"));
        if (deferredReasonCounts == null || historicalDeferralReasonCounts == null) {
            return false;
        }
        long deferredReasonTotal = deferredReasonCounts.values().stream()
                .mapToLong(Long::longValue)
                .reduce(0L, Math::addExact);
        long historicalDeferralReasonTotal = historicalDeferralReasonCounts.values().stream()
                .mapToLong(Long::longValue)
                .reduce(0L, Math::addExact);
        return generated == statusTotal
                && terminal == generated - expectedBacklog
                && backlog == expectedBacklog
                && admitted <= generated
                && attempts >= admitted
                && succeededWithoutEvidence <= statusCounts.get("SUCCEEDED")
                && validDecisions == statusCounts.get("SUCCEEDED") - succeededWithoutEvidence
                && recoverable <= backlog
                && evidenceGaps == statusCounts.get("BLOCKED")
                        + statusCounts.get("FAILED")
                        + statusCounts.get("CANCELLED")
                        + succeededWithoutEvidence
                && deferredReasonTotal == statusCounts.get("DEFERRED")
                && historicalDeferralReasonTotal == historicalDeferralCount;
    }

    private static Map<String, Long> strictOpportunityReasonCounts(Object value) {
        if (!(value instanceof Map<?, ?> rawCounts)) {
            return null;
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawCounts.entrySet()) {
            if (!(entry.getKey() instanceof String reasonCode)) {
                return null;
            }
            Long count = strictNonNegativeLong(entry.getValue());
            boolean proofSafeReason = OPPORTUNITY_DEFERRED_REASON_CODES_V1.contains(reasonCode)
                    || (LEGACY_UNSPECIFIED_DEFERRED_REASON_CODE.equals(reasonCode)
                            && Long.valueOf(0L).equals(count));
            if (count == null || !proofSafeReason) {
                return null;
            }
            counts.put(reasonCode, count);
        }
        return counts;
    }

    private static Map<String, Long> emptyOpportunityStatusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : List.of(
                "PENDING", "RUNNING", "SUCCEEDED", "DEFERRED", "BLOCKED", "FAILED", "CANCELLED")) {
            counts.put(status, 0L);
        }
        return counts;
    }

    private Map<String, Object> buildAgenticSimulationSummary(
            Map<String, Object> parameters,
            int cohortSize,
            Map<String, Object> decisionAudit,
            Map<String, Object> artifactDownloads,
            Map<String, Object> simulationWindow,
            String simulationRunId,
            Map<String, Object> opportunityReconciliation,
            boolean finalized) {
        String decisionMode = firstNonBlank(
                stringValue(parameters.get("agentDecisionMode")),
                AGENT_DECISION_MODE_DETERMINISTIC).toUpperCase(Locale.ROOT);
        String cadence = firstNonBlank(
                stringValue(parameters.get("agentDecisionCadence")),
                "NOT_APPLICABLE").toUpperCase(Locale.ROOT);
        int intervalMinutes = intervalMinutesForCadence(
                cadence,
                intValue(parameters.get("agentDecisionIntervalMinutes"), 0));
        int horizonDays = Math.max(0, intValue(parameters.get("simulationHorizonDays"), 0));
        double playbackRate = toDouble(parameters.get("playbackRate"), 0.0d);
        Map<String, Object> auditSummary = objectMap(decisionAudit.get("summary"));
        Map<String, Object> auditTotals = objectMap(decisionAudit.get("totals"));
        int auditedAgentCount = intValue(
                auditSummary.get("agentCount"),
                intValue(auditTotals.get("agentCount"), 0));
        int actualDecisionCount = intValue(
                auditSummary.get("decisionCount"),
                intValue(auditTotals.get("decisionCount"), 0));
        int fallbackCount = intValue(
                auditSummary.get("fallbackCount"),
                intValue(auditTotals.get("fallbackCount"), 0));
        int observedDecisionOpportunities = intValue(
                auditSummary.get("decisionOpportunityCount"),
                intValue(auditTotals.get("decisionOpportunityCount"), 0));
        int admittedDecisionOpportunities = intValue(
                auditSummary.get("decisionAdmittedCount"),
                intValue(auditTotals.get("decisionAdmittedCount"), 0));
        int deferredDecisionOpportunities = intValue(
                auditSummary.get("decisionDeferredCount"),
                intValue(auditTotals.get("decisionDeferredCount"),
                        Math.max(0, observedDecisionOpportunities - admittedDecisionOpportunities)));
        Map<String, Object> deferredDecisionReasons = objectMap(firstNonNull(
                auditSummary.get("decisionDeferredByReason"),
                auditTotals.get("decisionDeferredByReason")));
        List<Map<String, Object>> evidenceIntegrityWarnings = agenticEvidenceIntegrityWarnings(
                observedDecisionOpportunities,
                admittedDecisionOpportunities,
                deferredDecisionOpportunities,
                actualDecisionCount,
                objectMap(decisionAudit.get("runtimeTickDrain")),
                simulationWindow);
        int expectedOpportunities = expectedDecisionOpportunities(
                decisionMode,
                cohortSize,
                horizonDays,
                intervalMinutes);
        double coverageRate = expectedOpportunities <= 0
                ? 0.0d
                : round4(actualDecisionCount / (double) expectedOpportunities);
        double opportunityCoverageRate = expectedOpportunities <= 0
                ? 0.0d
                : round4(observedDecisionOpportunities / (double) expectedOpportunities);
        double admissionRate = observedDecisionOpportunities <= 0
                ? 0.0d
                : round4(admittedDecisionOpportunities / (double) observedDecisionOpportunities);
        String coverageStatus = agenticCoverageStatus(
                decisionMode,
                expectedOpportunities,
                actualDecisionCount,
                finalized);
        Map<String, Object> summary = linkedMap(
                "schemaVersion", "agentic_customer_simulation_summary.v1",
                "executionMode", EXECUTION_MODE_CLOCK_DRIVEN_HYBRID,
                "agentDecisionMode", decisionMode,
                "decisionCadence", cadence,
                "decisionIntervalMinutes", intervalMinutes,
                "simulationHorizonDays", horizonDays,
                "configuredDurationDays", firstNonNull(simulationWindow.get("configuredDurationDays"), horizonDays),
                "actualDurationDays", simulationWindow.get("actualDurationDays"),
                "horizonOvershootDays", simulationWindow.get("horizonOvershootDays"),
                "durationStatus", simulationWindow.get("durationStatus"),
                "durationProofVerified", simulationWindow.get("durationProofVerified"),
                "durationUnverifiedReason", simulationWindow.get("durationUnverifiedReason"),
                "playbackRate", playbackRate,
                "realLlmOptIn", AGENT_DECISION_MODE_REAL_LLM.equals(decisionMode)
                        && booleanValue(parameters.get("realLlmOptIn"), false),
                "realLlmBudget", AGENT_DECISION_MODE_REAL_LLM.equals(decisionMode)
                        ? realLlmBudget(parameters)
                        : Map.of(),
                "cohortSize", cohortSize,
                "expectedDecisionOpportunities", expectedOpportunities,
                "observedDecisionOpportunities", observedDecisionOpportunities,
                "admittedDecisionOpportunities", admittedDecisionOpportunities,
                "deferredDecisionOpportunities", deferredDecisionOpportunities,
                "deferredDecisionReasons", deferredDecisionReasons,
                "actualDecisionCount", actualDecisionCount,
                "auditedAgentCount", auditedAgentCount,
                "fallbackCount", fallbackCount,
                "evidenceIntegrityWarnings", evidenceIntegrityWarnings,
                "decisionCoverageRate", coverageRate,
                "decisionOpportunityCoverageRate", opportunityCoverageRate,
                "decisionAdmissionRate", admissionRate,
                "decisionCoverageStatus", coverageStatus,
                "decisionCoverageExplanation", agenticCoverageExplanation(
                        decisionMode,
                        cadence,
                        expectedOpportunities,
                        observedDecisionOpportunities,
                        admittedDecisionOpportunities,
                        actualDecisionCount,
                        deferredDecisionOpportunities,
                        deferredDecisionReasons,
                        evidenceIntegrityWarnings,
                        finalized),
                "evidenceRefs", linkedMap(
                        "hybridAgentAuditJson", artifactDownloads.get("hybridAgentAuditJson"),
                        "simulationRunEvidencePack", artifactDownloads.get("simulationRunEvidence")));
        applyOpportunityReconciliationToAgenticSummary(
                summary,
                decisionAudit,
                simulationWindow,
                simulationRunId,
                opportunityReconciliation,
                finalized);
        return summary;
    }

    private void applyOpportunityReconciliationToAgenticSummary(
            Map<String, Object> summary,
            Map<String, Object> decisionAudit,
            Map<String, Object> simulationWindow,
            String simulationRunId,
            Map<String, Object> opportunityReconciliation,
            boolean finalized) {
        summary.put("opportunityReconciliation", opportunityReconciliation);
        if (!finalized
                || !opportunityReconciliationIsValid(opportunityReconciliation, simulationRunId)
                || !OPPORTUNITY_GENERATION_MODE.equals(opportunityReconciliation.get("generationMode"))) {
            summary.put("decisionOpportunitySource", "LEGACY_CADENCE_AUDIT");
            return;
        }

        Map<String, Object> statusCounts = objectMap(opportunityReconciliation.get("statusCounts"));
        Map<String, Object> opportunityLedger = opportunityLedgerFromDecisionAudit(decisionAudit);
        boolean opportunityAuditVerified = opportunityAuditMatchesReconciliation(
                decisionAudit,
                simulationRunId,
                opportunityReconciliation);
        long expected = longValue(opportunityReconciliation.get("expectedGeneratedOpportunities"), 0L);
        long generated = longValue(opportunityReconciliation.get("generatedOpportunities"), 0L);
        long terminal = longValue(opportunityReconciliation.get("terminalOpportunities"), 0L);
        long backlog = longValue(opportunityReconciliation.get("remainingBacklog"), 0L);
        long validDecisions = longValue(opportunityReconciliation.get("validDecisions"), 0L);
        long legacyCadenceDecisions = legacyCadenceDecisionCount(decisionAudit);
        String decisionMode = stringValue(summary.get("agentDecisionMode")).toUpperCase(Locale.ROOT);
        boolean agentDecisionEvidenceRequired = agentDecisionEvidenceRequired(decisionMode);
        boolean decisionModeEvidenceConflict = decisionModeEvidenceConflict(
                decisionMode,
                decisionAudit,
                opportunityReconciliation);
        boolean evidenceModelSplit = opportunityEvidenceModelSplit(
                decisionMode,
                legacyCadenceDecisions);
        long deferred = longValue(statusCounts.get("DEFERRED"), 0L);
        long admitted = opportunityAuditVerified
                ? longValue(opportunityLedger.get("admittedOpportunities"), 0L)
                : longValue(statusCounts.get("SUCCEEDED"), 0L)
                        + deferred
                        + longValue(statusCounts.get("RUNNING"), 0L)
                        + longValue(statusCounts.get("BLOCKED"), 0L)
                        + longValue(statusCounts.get("FAILED"), 0L);
        long totalAttempts = opportunityAuditVerified
                ? longValue(opportunityLedger.get("totalAttempts"), admitted)
                : admitted;
        Map<String, Object> deferredReasons = opportunityAuditVerified
                ? objectMap(opportunityLedger.get("deferredReasonCounts"))
                : Map.of();
        long historicalDeferrals = opportunityAuditVerified
                ? longValue(opportunityLedger.get("historicalDeferralCount"), 0L)
                : 0L;
        Map<String, Object> historicalDeferralReasons = opportunityAuditVerified
                ? objectMap(opportunityLedger.get("historicalDeferralReasonCounts"))
                : Map.of();
        double decisionCoverage = expected <= 0L ? 0.0d : round4(validDecisions / (double) expected);
        double opportunityCoverage = expected <= 0L ? 0.0d : round4(generated / (double) expected);
        double admissionRate = generated <= 0L ? 0.0d : round4(admitted / (double) generated);

        List<Map<String, Object>> warnings = new ArrayList<>();
        for (Map<String, Object> warning : listOfMaps(summary.get("evidenceIntegrityWarnings"))) {
            String code = stringValue(warning.get("code"));
            if ("SIMULATION_DURATION_UNVERIFIED".equals(code)
                    || "RUNTIME_TICK_DRAIN_UNVERIFIED".equals(code)) {
                warnings.add(warning);
            }
        }
        if (agentDecisionEvidenceRequired && !opportunityAuditVerified) {
            warnings.add(linkedMap(
                    "code", "OPPORTUNITY_AGENT_AUDIT_UNVERIFIED",
                    "message", "Per-agent opportunity audit totals were unavailable or did not match the run reconciliation snapshot. Admission and attempt counts are conservative lower bounds."));
        }
        if (!Boolean.TRUE.equals(opportunityReconciliation.get("ledgerReconciled")) || backlog > 0L) {
            warnings.add(linkedMap(
                    "code", "OPPORTUNITY_LEDGER_NOT_RECONCILED",
                    "message", "The simulation horizon completed before the customer decision opportunity ledger reconciled.",
                    "status", opportunityReconciliation.get("status"),
                    "remainingBacklog", backlog));
        }
        long evidenceGaps = longValue(opportunityReconciliation.get("decisionEvidenceGaps"), 0L);
        if (evidenceGaps > 0L) {
            warnings.add(linkedMap(
                    "code", "OPPORTUNITY_DECISION_EVIDENCE_GAPS",
                    "message", "Terminal customer decision opportunities include explicit blocked, failed, cancelled, or missing-evidence outcomes.",
                    "decisionEvidenceGaps", evidenceGaps));
        }
        if (evidenceModelSplit) {
            warnings.add(linkedMap(
                    "code", "EVIDENCE_MODEL_SPLIT",
                    "message", "Legacy cadence telemetry recorded customer decisions, but the finalized durable opportunity ledger cannot reconcile those decisions. The cadence count is preserved as observed telemetry and is not promoted to durable review evidence.",
                    "legacyCadenceDecisionCount", legacyCadenceDecisions,
                    "durableGeneratedOpportunities", generated,
                    "durableValidDecisionCount", validDecisions));
        }
        if (decisionModeEvidenceConflict) {
            warnings.add(linkedMap(
                    "code", "DECISION_MODE_EVIDENCE_CONFLICT",
                    "message", "The deterministic decision mode recorded non-zero agent decision or durable opportunity evidence. The contradictory evidence cannot support review readiness.",
                    "agentDecisionMode", decisionMode,
                    "observedDecisionCount", observedDecisionCount(decisionAudit),
                    "durableGeneratedOpportunities", generated,
                    "durableValidDecisionCount", validDecisions));
        }

        String reconciliationStatus = stringValue(opportunityReconciliation.get("status"));
        String coverageStatus = decisionModeEvidenceConflict
                ? "DECISION_MODE_EVIDENCE_CONFLICT"
                : evidenceModelSplit
                        ? "EVIDENCE_MODEL_SPLIT"
                        : switch (reconciliationStatus) {
                    case "EMPTY" -> "NO_CUSTOMER_DECISIONS_TRIGGERED";
                    case "RECONCILED" -> evidenceGaps > 0L ? "RECONCILED_WITH_EVIDENCE_GAPS" : "CAPTURED";
                    case "DRAINING" -> "PARTIAL_COVERAGE";
                    case "INCONSISTENT" -> "EVIDENCE_INCONSISTENT";
                    case "GENERATION_OPEN" -> "GENERATION_OPEN";
                    default -> "OPPORTUNITY_LEDGER_UNVERIFIED";
                };
        summary.put("decisionOpportunitySource", "CUSTOMER_DECISION_OPPORTUNITY_LEDGER");
        summary.put("legacyCadenceDecisionCount", legacyCadenceDecisions);
        summary.put("durableValidDecisionCount", validDecisions);
        summary.put("expectedDecisionOpportunities", expected);
        summary.put("observedDecisionOpportunities", generated);
        summary.put("admittedDecisionOpportunities", admitted);
        summary.put("deferredDecisionOpportunities", deferred);
        summary.put("deferredDecisionReasons", deferredReasons);
        summary.put("historicalDeferredDecisionTransitions", historicalDeferrals);
        summary.put("historicalDeferredDecisionReasons", historicalDeferralReasons);
        summary.put("actualDecisionCount", validDecisions);
        summary.put("auditedAgentCount", opportunityAuditVerified
                ? longValue(opportunityLedger.get("agentCount"), 0L)
                : 0L);
        summary.put("opportunityTerminalCount", terminal);
        summary.put("opportunityRemainingBacklog", backlog);
        summary.put("opportunityStatusCounts", statusCounts);
        summary.put("opportunityTotalAttempts", totalAttempts);
        summary.put("opportunityAdmissionCountVerified", opportunityAuditVerified);
        summary.put("opportunityAgentAuditRequired", agentDecisionEvidenceRequired);
        summary.put("opportunityDecisionEvidenceGaps", evidenceGaps);
        summary.put("evidenceIntegrityWarnings", warnings);
        summary.put("decisionCoverageRate", decisionCoverage);
        summary.put("decisionOpportunityCoverageRate", opportunityCoverage);
        summary.put("decisionAdmissionRate", admissionRate);
        summary.put("decisionCoverageStatus", coverageStatus);
        summary.put(
                "decisionCoverageExplanation",
                decisionModeEvidenceConflict
                        ? "Decision-mode evidence conflict: deterministic mode recorded non-zero agent decision or durable opportunity evidence. The run must be reconciled before it can support review readiness."
                        : evidenceModelSplit
                        ? "Evidence model split: legacy cadence telemetry recorded " + legacyCadenceDecisions
                                + " customer decision(s), while the finalized durable opportunity ledger generated "
                                + generated + " opportunity/opportunities and reconciled " + validDecisions
                                + " valid decision(s). The legacy count remains visible but cannot support review readiness until it is represented in the durable opportunity ledger."
                        : "The finalized event-driven run generated " + generated
                                + " durable customer decision opportunity/opportunities, produced " + validDecisions
                                + " valid decision(s), left " + backlog + " in backlog, and recorded "
                                + evidenceGaps + " explicit evidence gap(s). Ledger status: "
                                + reconciliationStatus + ".");
    }

    private boolean opportunityAuditTotalsMatchReconciliation(
            Map<String, Object> totals,
            String simulationRunId,
            Map<String, Object> reconciliation) {
        long reconciledGenerated = longValue(reconciliation.get("generatedOpportunities"), -1L);
        long expectedInitializationAgents = longValue(
                reconciliation.get("expectedInitializationAgents"), -1L);
        long auditedAgentCount = longValue(totals.get("agentCount"), -1L);
        List<Object> simulationRunIds = listValue(totals.get("simulationRunIds"));
        boolean initializedCohort = expectedInitializationAgents > 0L;
        boolean runScopeMatches = initializedCohort || reconciledGenerated > 0L
                ? simulationRunIds.equals(List.of(simulationRunId))
                : simulationRunIds.isEmpty();
        boolean initializedAgentCoverageMatches = !initializedCohort
                || auditedAgentCount == expectedInitializationAgents;
        if (!OPPORTUNITY_AUDIT_TOTALS_SCHEMA_VERSION.equals(stringValue(totals.get("schemaVersion")))
                || !Boolean.TRUE.equals(totals.get("consistentRunScope"))
                || longValue(totals.get("invalidAgentAuditCount"), -1L) != 0L
                || !runScopeMatches
                || !initializedAgentCoverageMatches) {
            return false;
        }
        return longValue(totals.get("generatedOpportunities"), -1L)
                        == reconciledGenerated
                && longValue(totals.get("terminalOpportunities"), -1L)
                        == longValue(reconciliation.get("terminalOpportunities"), -2L)
                && longValue(totals.get("remainingBacklog"), -1L)
                        == longValue(reconciliation.get("remainingBacklog"), -2L)
                && longValue(totals.get("validDecisions"), -1L)
                        == longValue(reconciliation.get("validDecisions"), -2L)
                && longValue(totals.get("recoverableRecordedDecisions"), -1L)
                        == longValue(reconciliation.get("recoverableRecordedDecisions"), -2L)
                && longValue(totals.get("succeededWithoutDecisionEvidence"), -1L)
                        == longValue(reconciliation.get("succeededWithoutDecisionEvidence"), -2L)
                && longValue(totals.get("decisionEvidenceGaps"), -1L)
                        == longValue(reconciliation.get("decisionEvidenceGaps"), -2L)
                && objectMap(totals.get("statusCounts")).equals(objectMap(reconciliation.get("statusCounts")));
    }

    private Map<String, Object> opportunityLedgerFromDecisionAudit(Map<String, Object> decisionAudit) {
        Map<String, Object> auditSummary = objectMap(decisionAudit.get("summary"));
        return objectMap(firstNonNull(
                auditSummary.get("opportunityLedger"),
                decisionAudit.get("opportunityLedger")));
    }

    private boolean opportunityAuditMatchesReconciliation(
            Map<String, Object> decisionAudit,
            String simulationRunId,
            Map<String, Object> reconciliation) {
        Map<String, Object> totals = opportunityLedgerFromDecisionAudit(decisionAudit);
        if (!opportunityAuditTotalsMatchReconciliation(totals, simulationRunId, reconciliation)) {
            return false;
        }
        Object rawAgents = decisionAudit.get("agents");
        if (!(rawAgents instanceof List<?> agents)) {
            return false;
        }
        Set<String> auditedAgentIds = new HashSet<>();
        Set<String> validDecisionOpportunityIds = new HashSet<>();
        List<Map<String, Object>> validatedAgents = new ArrayList<>();
        for (Object rawAgent : agents) {
            if (!(rawAgent instanceof Map<?, ?> rawAgentMap)) {
                return false;
            }
            Map<String, Object> agent = objectMap(rawAgentMap);
            if (!agent.containsKey("opportunityAudit")) {
                return false;
            }
            Map<String, Object> audit = objectMap(agent.get("opportunityAudit"));
            String auditAgentId = stringValue(audit.get("agentId"));
            if (!opportunityAgentAuditIsValid(audit)
                    || !simulationRunId.equals(stringValue(audit.get("simulationRunId")))
                    || !auditedAgentIds.add(auditAgentId)
                    || !agentEnvelopeOwnsAudit(agent, auditAgentId)
                    || !validDecisionRowsMatchAudit(
                            agent,
                            audit,
                            validDecisionOpportunityIds)) {
                return false;
            }
            validatedAgents.add(agent);
        }
        long expectedAuditedAgents = longValue(totals.get("agentCount"), -1L);
        if (auditedAgentIds.size() != expectedAuditedAgents
                || agents.size() != expectedAuditedAgents) {
            return false;
        }
        try {
            Map<String, Object> recomputedTotals = buildOpportunityLedgerTotals(validatedAgents);
            return totals.equals(recomputedTotals)
                    && opportunityAuditTotalsMatchReconciliation(
                            recomputedTotals,
                            simulationRunId,
                            reconciliation)
                    && validDecisionOpportunityIds.size()
                            == longValue(reconciliation.get("validDecisions"), -1L);
        } catch (ArithmeticException invalidTotals) {
            return false;
        }
    }

    private static boolean agentEnvelopeOwnsAudit(
            Map<String, Object> agent,
            String auditAgentId) {
        if (auditAgentId.isBlank()) {
            return false;
        }
        for (String identityField : List.of("agentId", "runtimeIdentity", "memoryKey")) {
            String identity = stringValue(agent.get(identityField));
            if (!identity.isBlank() && identity.equals(auditAgentId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean validDecisionRowsMatchAudit(
            Map<String, Object> agent,
            Map<String, Object> audit,
            Set<String> validDecisionOpportunityIds) {
        Long auditedValidDecisions = strictNonNegativeLong(audit.get("validDecisions"));
        if (auditedValidDecisions == null) {
            return false;
        }
        Object rawDecisions = agent.get("decisions");
        if (!(rawDecisions instanceof List<?> decisions)) {
            return auditedValidDecisions == 0L;
        }
        long validDecisionRows = 0L;
        for (Object rawDecision : decisions) {
            if (!(rawDecision instanceof Map<?, ?> rawDecisionMap)) {
                return false;
            }
            Map<String, Object> decision = objectMap(rawDecisionMap);
            String opportunityId = stringValue(decision.get("opportunityId"));
            Object contractValidity = decision.get("opportunityDecisionContractValid");
            if (!opportunityId.isBlank() && !(contractValidity instanceof Boolean)) {
                return false;
            }
            if (Boolean.TRUE.equals(contractValidity)) {
                if (opportunityId.isBlank()
                        || !validDecisionOpportunityIds.add(opportunityId)) {
                    return false;
                }
                validDecisionRows++;
            }
        }
        return validDecisionRows == auditedValidDecisions;
    }

    OpportunityProofAssessment assessOpportunityProof(
            Map<String, Object> decisionAudit,
            String simulationRunId,
            Map<String, Object> reconciliation) {
        return assessOpportunityProof(
                decisionAudit,
                simulationRunId,
                reconciliation,
                AGENT_DECISION_MODE_STUB_LLM);
    }

    OpportunityProofAssessment assessOpportunityProof(
            Map<String, Object> decisionAudit,
            String simulationRunId,
            Map<String, Object> reconciliation,
            String decisionMode) {
        boolean agentDecisionEvidenceRequired = agentDecisionEvidenceRequired(decisionMode);
        boolean agentAuditVerified = opportunityAuditMatchesReconciliation(
                decisionAudit,
                simulationRunId,
                reconciliation);
        long legacyCadenceDecisions = legacyCadenceDecisionCount(decisionAudit);
        long durableValidDecisions = longValue(reconciliation.get("validDecisions"), 0L);
        boolean decisionModeEvidenceConflict = decisionModeEvidenceConflict(
                decisionMode,
                decisionAudit,
                reconciliation);
        boolean evidenceModelSplit = opportunityEvidenceModelSplit(
                decisionMode,
                legacyCadenceDecisions);
        return new OpportunityProofAssessment(
                opportunityReconciliationIsPartial(reconciliation, agentDecisionEvidenceRequired)
                        || (agentDecisionEvidenceRequired && !agentAuditVerified)
                        || evidenceModelSplit
                        || decisionModeEvidenceConflict,
                agentAuditVerified,
                agentDecisionEvidenceRequired,
                opportunityProofReason(
                        reconciliation,
                        agentAuditVerified,
                        agentDecisionEvidenceRequired,
                        decisionModeEvidenceConflict,
                        evidenceModelSplit,
                        legacyCadenceDecisions,
                        durableValidDecisions));
    }

    record OpportunityProofAssessment(
            boolean partialEvidence,
            boolean agentAuditVerified,
            boolean agentAuditRequired,
            String reason) {
    }

    private static boolean agentDecisionEvidenceRequired(String decisionMode) {
        String normalized = stringValue(decisionMode)
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        return AGENT_DECISION_MODE_STUB_LLM.equals(normalized)
                || AGENT_DECISION_MODE_REAL_LLM.equals(normalized);
    }

    private static long legacyCadenceDecisionCount(Map<String, Object> decisionAudit) {
        long observedDecisions = observedDecisionCount(decisionAudit);
        long correlatedOpportunityDecisions = 0L;
        for (Map<String, Object> agent : listOfMaps(decisionAudit.get("agents"))) {
            for (Map<String, Object> decision : listOfMaps(agent.get("decisions"))) {
                if (!stringValue(decision.get("opportunityId")).isBlank()) {
                    correlatedOpportunityDecisions++;
                }
            }
        }
        return Math.max(0L, observedDecisions - correlatedOpportunityDecisions);
    }

    private static long observedDecisionCount(Map<String, Object> decisionAudit) {
        Map<String, Object> summary = objectMap(decisionAudit.get("summary"));
        Map<String, Object> totals = objectMap(decisionAudit.get("totals"));
        long aggregateCount = Math.max(
                longValue(summary.get("decisionCount"), 0L),
                longValue(totals.get("decisionCount"), 0L));
        long agentCount = 0L;
        for (Map<String, Object> agent : listOfMaps(decisionAudit.get("agents"))) {
            long declaredCount = Math.max(
                    longValue(agent.get("eventCount"), 0L),
                    longValue(agent.get("totalDecisionCount"), 0L));
            long rowCount = agent.get("decisions") instanceof List<?> decisions
                    ? decisions.size()
                    : 0L;
            try {
                agentCount = Math.addExact(agentCount, Math.max(declaredCount, rowCount));
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }
        return Math.max(0L, Math.max(aggregateCount, agentCount));
    }

    private boolean decisionModeEvidenceConflict(
            String decisionMode,
            Map<String, Object> decisionAudit,
            Map<String, Object> reconciliation) {
        String normalized = stringValue(decisionMode)
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        if (!AGENT_DECISION_MODE_DETERMINISTIC.equals(normalized)) {
            return false;
        }
        Map<String, Object> opportunityLedger = opportunityLedgerFromDecisionAudit(decisionAudit);
        if (observedDecisionCount(decisionAudit) > 0L
                || longValue(opportunityLedger.get("generatedOpportunities"), 0L) > 0L
                || longValue(opportunityLedger.get("validDecisions"), 0L) > 0L
                || longValue(reconciliation.get("generatedOpportunities"), 0L) > 0L
                || longValue(reconciliation.get("validDecisions"), 0L) > 0L) {
            return true;
        }
        for (Map<String, Object> agent : listOfMaps(decisionAudit.get("agents"))) {
            Map<String, Object> audit = objectMap(agent.get("opportunityAudit"));
            if (longValue(audit.get("generatedOpportunities"), 0L) > 0L
                    || longValue(audit.get("validDecisions"), 0L) > 0L) {
                return true;
            }
        }
        return false;
    }

    private static boolean opportunityEvidenceModelSplit(
            String decisionMode,
            long legacyCadenceDecisions) {
        return agentDecisionEvidenceRequired(decisionMode)
                && legacyCadenceDecisions > 0L;
    }

    private int intervalMinutesForCadence(String cadence, int fallback) {
        return switch (stringValue(cadence).toUpperCase(Locale.ROOT)) {
            case "EVENT_ONLY" -> 0;
            case "HOURLY" -> 60;
            case "DAILY" -> 24 * 60;
            case "WEEKLY" -> 7 * 24 * 60;
            default -> Math.max(0, fallback);
        };
    }

    private int expectedDecisionOpportunities(
            String decisionMode,
            int cohortSize,
            int horizonDays,
            int intervalMinutes) {
        if (!AGENT_DECISION_MODE_STUB_LLM.equals(decisionMode)
                && !AGENT_DECISION_MODE_REAL_LLM.equals(decisionMode)) {
            return 0;
        }
        if (cohortSize <= 0 || horizonDays <= 0 || intervalMinutes <= 0) {
            return 0;
        }
        long horizonMinutes = horizonDays * 24L * 60L;
        long slots = Math.max(1L, horizonMinutes / intervalMinutes);
        long expected = slots * cohortSize;
        return expected > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) expected;
    }

    private long expectedDecisionOpportunitiesLong(int cohortSize, int horizonDays, int intervalMinutes) {
        if (cohortSize <= 0 || horizonDays <= 0 || intervalMinutes <= 0) {
            return 0L;
        }
        long horizonMinutes = horizonDays * 24L * 60L;
        long slots = Math.max(1L, horizonMinutes / intervalMinutes);
        return slots * cohortSize;
    }

    private String agenticCoverageStatus(
            String decisionMode,
            int expectedOpportunities,
            int actualDecisionCount,
            boolean finalized) {
        if (!AGENT_DECISION_MODE_STUB_LLM.equals(decisionMode)
                && !AGENT_DECISION_MODE_REAL_LLM.equals(decisionMode)) {
            return "NO_CUSTOMER_DECISIONS_EXPECTED";
        }
        if (expectedOpportunities <= 0) {
            return "NOT_CONFIGURED";
        }
        if (actualDecisionCount <= 0) {
            return finalized ? "NO_DECISIONS_CAPTURED" : "WAITING_FOR_DECISIONS";
        }
        if (actualDecisionCount < expectedOpportunities) {
            return finalized ? "PARTIAL_COVERAGE" : "CAPTURING";
        }
        return "CAPTURED";
    }

    private String agenticCoverageExplanation(
            String decisionMode,
            String cadence,
            int expectedOpportunities,
            int observedDecisionOpportunities,
            int admittedDecisionOpportunities,
            int actualDecisionCount,
            int deferredDecisionOpportunities,
            Map<String, Object> deferredDecisionReasons,
            List<Map<String, Object>> evidenceIntegrityWarnings,
            boolean finalized) {
        if (!AGENT_DECISION_MODE_STUB_LLM.equals(decisionMode)
                && !AGENT_DECISION_MODE_REAL_LLM.equals(decisionMode)) {
            return "Deterministic mode does not schedule customer LLM turns. It exercises activation, scheduled work, remote-domain operations, and evidence capture.";
        }
        String modeLabel = AGENT_DECISION_MODE_STUB_LLM.equals(decisionMode)
                ? "Stub LLM mode schedules no-token customer decision turns"
                : "Real LLM mode schedules provider-backed customer decision turns";
        String integrityClause = "";
        if (!evidenceIntegrityWarnings.isEmpty()) {
            Map<String, Object> firstWarning = evidenceIntegrityWarnings.getFirst();
            integrityClause = " Evidence integrity warning: "
                    + firstNonBlank(
                            stringValue(firstWarning.get("message")),
                            stringValue(firstWarning.get("code")),
                            "raw decision counters disagree");
        }
        if (expectedOpportunities <= 0) {
            return modeLabel + ", but this run did not publish enough horizon/cadence metadata to estimate opportunities."
                    + integrityClause;
        }
        if (observedDecisionOpportunities > 0) {
            String deferredClause = deferredDecisionOpportunities > 0
                    ? " and deferred " + deferredDecisionOpportunities + " because "
                            + deferredReasonSummary(deferredDecisionReasons)
                    : "";
            return modeLabel + " on the " + stringValue(cadence).toLowerCase(Locale.ROOT)
                    + " cadence. The runtime checked " + observedDecisionOpportunities + " of about "
                    + expectedOpportunities + " configured opportunity/opportunities, admitted "
                    + admittedDecisionOpportunities + " customer decision turn(s), and captured "
                    + actualDecisionCount + " audited decision event(s)" + deferredClause + "."
                    + integrityClause;
        }
        if (actualDecisionCount <= 0) {
            return modeLabel + " on the " + stringValue(cadence).toLowerCase(Locale.ROOT)
                    + " cadence, but no audited decisions have been captured"
                    + (finalized ? "." : " yet.")
                    + integrityClause;
        }
        return modeLabel + " on the " + stringValue(cadence).toLowerCase(Locale.ROOT)
                + " cadence. The audit captured " + actualDecisionCount + " of about "
                + expectedOpportunities + " configured decision opportunity/opportunities."
                + integrityClause;
    }

    private List<Map<String, Object>> agenticEvidenceIntegrityWarnings(
            int observedDecisionOpportunities,
            int admittedDecisionOpportunities,
            int deferredDecisionOpportunities,
            int actualDecisionCount,
            Map<String, Object> runtimeTickDrain,
            Map<String, Object> simulationWindow) {
        List<Map<String, Object>> warnings = new ArrayList<>();
        if (Boolean.FALSE.equals(simulationWindow.get("durationProofVerified"))) {
            warnings.add(linkedMap(
                    "code", "SIMULATION_DURATION_UNVERIFIED",
                    "message", firstNonBlank(
                            stringValue(simulationWindow.get("durationUnverifiedReason")),
                            "Simulation duration could not be verified because replay clock ownership was not proven for this run."),
                    "durationStatus", firstNonBlank(
                            stringValue(simulationWindow.get("durationStatus")),
                            "UNVERIFIED_CLOCK_OWNERSHIP")));
        }
        if (!runtimeTickDrain.isEmpty() && !Boolean.TRUE.equals(runtimeTickDrain.get("verified"))) {
            warnings.add(linkedMap(
                    "code", "RUNTIME_TICK_DRAIN_UNVERIFIED",
                    "message", firstNonBlank(
                            stringValue(runtimeTickDrain.get("message")),
                            "Runtime tick drain was not verified for this simulation run before finalization; decision audit may still be catching up."),
                    "drainStatus", firstNonBlank(stringValue(runtimeTickDrain.get("status")), "UNVERIFIED")));
        }
        if (actualDecisionCount > admittedDecisionOpportunities) {
            warnings.add(linkedMap(
                    "code", "AUDITED_DECISIONS_EXCEED_ADMITTED_TURNS",
                    "message", "Audited decision events exceed admitted runtime turns. Decision-window telemetry may be missing, delayed, or inconsistent.",
                    "observedDecisionOpportunities", observedDecisionOpportunities,
                    "admittedDecisionOpportunities", admittedDecisionOpportunities,
                    "actualDecisionCount", actualDecisionCount));
        }
        if (admittedDecisionOpportunities > observedDecisionOpportunities) {
            warnings.add(linkedMap(
                    "code", "ADMITTED_TURNS_EXCEED_OBSERVED_OPPORTUNITIES",
                    "message", "Admitted runtime turns exceed observed decision opportunities. The report preserved raw counters instead of normalizing them.",
                    "observedDecisionOpportunities", observedDecisionOpportunities,
                    "admittedDecisionOpportunities", admittedDecisionOpportunities));
        }
        int expectedDeferred = Math.max(0, observedDecisionOpportunities - admittedDecisionOpportunities);
        if (deferredDecisionOpportunities != expectedDeferred) {
            warnings.add(linkedMap(
                    "code", "DEFERRED_OPPORTUNITY_COUNT_MISMATCH",
                    "message", "Deferred decision opportunities do not match observed minus admitted opportunities. The report preserved raw runtime counters.",
                    "observedDecisionOpportunities", observedDecisionOpportunities,
                    "admittedDecisionOpportunities", admittedDecisionOpportunities,
                    "deferredDecisionOpportunities", deferredDecisionOpportunities,
                    "expectedDeferredDecisionOpportunities", expectedDeferred));
        }
        return warnings;
    }

    private record RuntimeTickDrainResult(boolean verified, String status, String message) {
        private static RuntimeTickDrainResult drained() {
            return new RuntimeTickDrainResult(
                    true,
                    "DRAINED",
                    "Runtime tick drain was verified for this simulation run before finalization.");
        }

        private static RuntimeTickDrainResult unverified(String status, String message) {
            return new RuntimeTickDrainResult(false, status, message);
        }

        private Map<String, Object> toMap() {
            return linkedMap(
                    "verified", verified,
                    "status", status,
                    "message", message);
        }
    }

    private void incrementCount(Map<String, Integer> counts, Object key) {
        String normalized = firstNonBlank(stringValue(key), "none");
        counts.put(normalized, counts.getOrDefault(normalized, 0) + 1);
    }

    private void incrementCount(Map<String, Integer> counts, Object key, int amount) {
        if (amount <= 0) {
            return;
        }
        String normalized = firstNonBlank(stringValue(key), "none");
        counts.put(normalized, counts.getOrDefault(normalized, 0) + amount);
    }

    private void mergeCounts(Map<String, Integer> target, Map<String, Integer> source) {
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            incrementCount(target, entry.getKey(), entry.getValue());
        }
    }

    private Map<String, Integer> intMap(Object value) {
        Map<String, Object> source = objectMap(value);
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            int amount = intValue(entry.getValue(), 0);
            if (amount > 0) {
                result.put(entry.getKey(), amount);
            }
        }
        return result;
    }

    private List<Object> listValue(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String deferredReasonSummary(Map<String, Object> deferredDecisionReasons) {
        if (deferredDecisionReasons == null || deferredDecisionReasons.isEmpty()) {
            return "runtime gating";
        }
        return deferredDecisionReasons.entrySet().stream()
                .sorted((left, right) -> Integer.compare(
                        intValue(right.getValue(), 0),
                        intValue(left.getValue(), 0)))
                .limit(2)
                .map(entry -> stringValue(entry.getKey()).replace('_', ' ') + " (" + intValue(entry.getValue(), 0) + ")")
                .collect(Collectors.joining(", "));
    }

    private String dominantCountKey(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("--");
    }

    private PolicyWindTunnelArtifactPayload buildClockDrivenHybridLiveArtifact(
            PolicyWindTunnelExecutionContext context,
            PolicyWindTunnelStoredRun storedRun,
            String artifactType) throws IOException {
        Map<String, Object> bundle = buildClockDrivenHybridLiveBundle(context, storedRun);
        Map<String, Object> summary = objectMap(bundle.get("summary"));
        String normalizedType = stringValue(artifactType).toLowerCase(Locale.ROOT);
        return switch (normalizedType) {
            case "summary" -> jsonArtifact("summary", "live-hybrid-summary.json", summary);
            case "experiment-card", "markdown", "md" -> new PolicyWindTunnelArtifactPayload(
                    "experiment-card",
                    "text/markdown",
                    "live-hybrid-experiment-card.md",
                    stringValue(bundle.get("experimentCardMarkdown")).getBytes(StandardCharsets.UTF_8));
            case "simulation-timeline", "simulation-timeline-json" -> jsonArtifact(
                    "simulation-timeline-json",
                    "live-hybrid-simulation-timeline.json",
                    summary.get("simulationTimeline"));
            case "side-effect-ledger", "side-effect-ledger-json" -> jsonArtifact(
                    "side-effect-ledger-json",
                    "live-hybrid-side-effect-ledger.json",
                    summary.get("sideEffectLedger"));
            case "branch-state-projection", "branch-state-projection-json" -> jsonArtifact(
                    "branch-state-projection-json",
                    "live-hybrid-branch-state-projection.json",
                    summary.get("branchStateProjection"));
            case "comparison", "comparison-report" -> jsonArtifact(
                    "comparison-report",
                    "live-hybrid-decision-comparison.json",
                    summary.get("decisionComparison"));
            case "hybrid-agent-audit", "hybrid-agent-audit-json" -> jsonArtifact(
                    "hybrid-agent-audit-json",
                    "live-hybrid-agent-audit.json",
                    objectMap(bundle.get("hybrid")).get("agentAudit"));
            default -> throw new IllegalArgumentException(
                    "Unsupported live hybrid artifact type. Supported values: summary, experiment-card, simulation-timeline-json, side-effect-ledger-json, branch-state-projection-json, comparison-report, hybrid-agent-audit-json.");
        };
    }

    private PolicyWindTunnelArtifactMetadata clockDrivenHybridLiveArtifactMetadata(String artifactType) {
        String normalizedType = stringValue(artifactType).toLowerCase(Locale.ROOT);
        return switch (normalizedType) {
            case "summary" -> new PolicyWindTunnelArtifactMetadata(
                    "application/json",
                    "live-hybrid-summary.json");
            case "experiment-card", "markdown", "md" -> new PolicyWindTunnelArtifactMetadata(
                    "text/markdown",
                    "live-hybrid-experiment-card.md");
            case "simulation-timeline", "simulation-timeline-json" -> new PolicyWindTunnelArtifactMetadata(
                    "application/json",
                    "live-hybrid-simulation-timeline.json");
            case "side-effect-ledger", "side-effect-ledger-json" -> new PolicyWindTunnelArtifactMetadata(
                    "application/json",
                    "live-hybrid-side-effect-ledger.json");
            case "branch-state-projection", "branch-state-projection-json" -> new PolicyWindTunnelArtifactMetadata(
                    "application/json",
                    "live-hybrid-branch-state-projection.json");
            case "comparison", "comparison-report" -> new PolicyWindTunnelArtifactMetadata(
                    "application/json",
                    "live-hybrid-decision-comparison.json");
            case "hybrid-agent-audit", "hybrid-agent-audit-json" -> new PolicyWindTunnelArtifactMetadata(
                    "application/json",
                    "live-hybrid-agent-audit.json");
            default -> throw new IllegalArgumentException(
                    "Unsupported live hybrid artifact type. Supported values: summary, experiment-card, simulation-timeline-json, side-effect-ledger-json, branch-state-projection-json, comparison-report, hybrid-agent-audit-json.");
        };
    }

    private PolicyWindTunnelArtifactPayload jsonArtifact(
            String type,
            String filename,
            Object payload) throws IOException {
        return new PolicyWindTunnelArtifactPayload(
                type,
                "application/json",
                filename,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload == null ? Map.of() : payload));
    }

    private PolicyWindTunnelArchivePayload buildClockDrivenHybridEvidencePack(
            PolicyWindTunnelExecutionContext context,
            PolicyWindTunnelStoredRun storedRun) throws IOException {
        Map<String, Object> bundle = buildClockDrivenHybridLiveBundle(context, storedRun);
        Map<String, Object> summary = objectMap(bundle.get("summary"));
        String archiveBaseName = "scenario-lab-live-evidence-" + safeToken(storedRun.runId());
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Set<String> entryNames = new HashSet<>();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            String archiveRoot = archiveBaseName + "/";
            addZipEntry(
                    zipOutputStream,
                    entryNames,
                    archiveRoot + "manifest.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(linkedMap(
                            "schemaVersion", "policy_wind_tunnel_live_evidence_pack.v1",
                            "runId", storedRun.runId(),
                            "domainId", BankingPolicyWindTunnelManifest.DOMAIN_ID,
                            "packId", BankingPolicyWindTunnelManifest.PACK_ID,
                            "exportedAt", OffsetDateTime.now(ZoneOffset.UTC),
                            "decisionReadiness", bundle.get("decisionReadiness"),
                            "simulationRunEvidenceExport", objectMap(bundle.get("artifactDownloads"))
                                    .get("simulationRunEvidence"),
                            "includedFiles", List.of(
                                    "README.md",
                                    "live-hybrid-summary.json",
                                    "live-hybrid-simulation-timeline.json",
                                    "live-hybrid-side-effect-ledger.json",
                                    "live-hybrid-branch-state-projection.json",
                                    "live-hybrid-agent-audit.json"))));
            addZipEntry(
                    zipOutputStream,
                    entryNames,
                    archiveRoot + "README.md",
                    stringValue(bundle.get("experimentCardMarkdown")).getBytes(StandardCharsets.UTF_8));
            addZipEntry(
                    zipOutputStream,
                    entryNames,
                    archiveRoot + "live-hybrid-summary.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary));
            addZipEntry(
                    zipOutputStream,
                    entryNames,
                    archiveRoot + "live-hybrid-simulation-timeline.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary.get("simulationTimeline")));
            addZipEntry(
                    zipOutputStream,
                    entryNames,
                    archiveRoot + "live-hybrid-side-effect-ledger.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary.get("sideEffectLedger")));
            addZipEntry(
                    zipOutputStream,
                    entryNames,
                    archiveRoot + "live-hybrid-branch-state-projection.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary.get("branchStateProjection")));
            addZipEntry(
                    zipOutputStream,
                    entryNames,
                    archiveRoot + "live-hybrid-agent-audit.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(objectMap(bundle.get("hybrid")).get("agentAudit")));
        }
        return new PolicyWindTunnelArchivePayload(archiveBaseName, outputStream.toByteArray());
    }

    private void finalizeClockDrivenHybridRun(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Map<String, Object> payload,
            String opportunityCorrelationCapability) {
        synchronized (lifecycleLock(runId)) {
            FinalizationOperation operation = finalizationOperation(payload);
            String finalizationMode = finalizationMode(readRunMetadata(context, runId));
            requireFinalizationOperation(runId, finalizationMode, operation);
            if (isExplicitLegacyFinalizationMode(finalizationMode)) {
                finalizeClockDrivenHybridRunLegacyV1(context, runId);
                return;
            }
            withFinalizationReceiptLock(
                    context,
                    runId,
                    () -> publishClockDrivenFinalization(
                            context,
                            runId,
                            payload,
                            operation,
                            opportunityCorrelationCapability));
        }
    }

    private void finalizeClockDrivenHybridRunLegacyV1(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        if (runIsTerminal(context, runId)) {
            return;
        }
        BankingPolicyScenarioLabRunMetadata metadata = readPolicyMetadata(context, runId);
        if (!clockDrivenRunCanFinalize(context, runId, metadata)) {
            appendLifecycleIgnoredLog(context, runId, "Finalize");
            return;
        }
        try {
            if (applyPlaybackControlForRun(context, runId, true, 0.0, "Finalize requested")) {
                appendLog(context, runId,
                        "Finalize requested. Simulator playback rate set to 0.0 before report assembly.");
            }
        } catch (Exception pauseError) {
            appendLog(context, runId, "Finalize requested. Clock pause attempt was not confirmed: "
                    + firstNonBlank(pauseError.getMessage(), pauseError.getClass().getSimpleName()));
        }
        updateRunState(
                context,
                runId,
                STATUS_RUNNING,
                PHASE_FINALIZING_REPORT,
                0.92,
                null,
                null,
                null,
                false);
        try {
            PolicyWindTunnelStoredRun storedRun = findStoredRun(context, runId);
            Path summaryDir = finalizationSummaryDirectory(context, runId);
            Map<String, Object> artifactPaths =
                    finalizedHybridArtifactPaths(context.workspaceRoot(), summaryDir, runId);
            Map<String, Object> summary = buildClockDrivenHybridFinalizedSummary(
                    context,
                    storedRun,
                    artifactPaths,
                    OffsetDateTime.now(ZoneOffset.UTC));
            writeClockDrivenHybridFinalizedArtifacts(summaryDir, summary);

            String summaryPath = stringValue(artifactPaths.get("summary"));
            String bundleUrl = "/api/experiment-runs/" + urlEncode(runId) + "/scenario-lab/bundle";
            String evidencePackUrl =
                    "/api/experiment-runs/" + urlEncode(runId) + "/scenario-lab/evidence-pack";
            if (!lifecycleTransitionAllows(context, runId, TRANSITION_FINALIZE_COMPLETE)) {
                appendLog(context, runId,
                        "Finalization output was built but completion was not applied because run state changed.");
                return;
            }
            Map<String, Object> currentMetadata = readRunMetadata(context, runId);
            Map<String, Object> completedMetadata = readPolicyMetadata(currentMetadata)
                    .appendLog("Clock-Driven Simulation evidence finalized into outcome comparison report.")
                    .withLifecycle(PHASE_COMPLETED, 1.0, true, bundleUrl, evidencePackUrl, null)
                    .writeTo(currentMetadata);
            long completedAtEpochMs = System.currentTimeMillis();
            context.updateRun(runId, new PolicyWindTunnelRunUpdate(
                    STATUS_COMPLETED,
                    summaryPath,
                    bundleUrl,
                    completedAtEpochMs,
                    completedMetadata));
            notifyLegacySimulationRunCompleted(
                    context,
                    runId,
                    storedRun,
                    summaryPath,
                    bundleUrl,
                    completedAtEpochMs,
                    summary);
            registerClockDrivenHybridFinalizedArtifacts(context, runId, artifactPaths);
            try {
                releaseHybridReplayContract(context, runId);
            } catch (Exception releaseError) {
                appendLog(context, runId,
                        "Completed run, but simulator clock ownership release was not confirmed: "
                                + firstNonBlank(
                                        releaseError.getMessage(),
                                        releaseError.getClass().getSimpleName()));
            }
            appendLog(context, runId,
                    "Runtime cohort retained for post-run inspection. Use Runtime Overview Reset Run "
                            + "when you want to clear active agents and the live feed.");
        } catch (Exception failure) {
            log.warn(
                    "Clock-Driven Simulation Scenario Lab legacy finalization {} failed: {}",
                    runId,
                    failure.getMessage(),
                    failure);
            Map<String, Object> error = Map.of(
                    "message", firstNonBlank(
                            failure.getMessage(),
                            "Clock-Driven Simulation finalization failed"),
                    "type", failure.getClass().getSimpleName());
            updateRunState(
                    context,
                    runId,
                    STATUS_FAILED,
                    PHASE_FAILED,
                    1.0,
                    null,
                    null,
                    error,
                    false);
            appendLog(context, runId, "Finalization failed: "
                    + firstNonBlank(failure.getMessage(), failure.getClass().getSimpleName()));
        }
    }

    private void publishClockDrivenFinalization(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Map<String, Object> payload,
            FinalizationOperation operation,
            String opportunityCorrelationCapability) throws Exception {
        Map<String, Object> receipt = loadFinalizationReceipt(context, runId);
        String finalizationMode = receipt.isEmpty()
                ? finalizationMode(readRunMetadata(context, runId))
                : stringValue(receipt.get("finalizationMode"));
        requireFinalizationOperation(runId, finalizationMode, operation);
        String inputDigest = operation == null
                ? sha256Hex(canonicalJsonBytes(finalizationRequest(payload)))
                : operation.inputDigest();

        if (!receipt.isEmpty()) {
            validateFinalizationReceipt(receipt, runId, finalizationMode, operation, inputDigest);
            requirePublishedArtifacts(context, objectMap(receipt.get("artifactManifest")));
            PolicyWindTunnelStoredRun currentRun = context.findRun(runId).orElse(null);
            if (currentRun != null && isTerminalStatus(currentRun.status())) {
                String receiptStatus = stringValue(
                        objectMap(receipt.get("runSnapshot")).get("status"));
                if (FINALIZATION_RECEIPT_PREPARED.equals(stringValue(receipt.get("state")))
                        || !currentRun.status().equalsIgnoreCase(receiptStatus)) {
                    receipt = finalizationReceipt(
                            runId,
                            finalizationMode,
                            operation,
                            inputDigest,
                            FINALIZATION_RECEIPT_PUBLISHED,
                            runSnapshot(
                                    currentRun,
                                    currentRun.status(),
                                    currentRun.summaryPath(),
                                    currentRun.bundlePath(),
                                    storedRunCompletionEpochMs(currentRun),
                                    currentRun.metadata()),
                            objectMap(receipt.get("artifactManifest")));
                    attachCompletionNotificationPayload(context, receipt);
                    writeFinalizationReceipt(context, runId, receipt);
                }
                confirmOrScheduleFinalizationNotification(context, receipt);
                return;
            }
            restoreRunFromFinalizationReceipt(context, receipt, true);
            if (FINALIZATION_RECEIPT_PUBLISHED.equals(stringValue(receipt.get("state")))) {
                registerArtifactsFromManifest(
                        context, runId, objectMap(receipt.get("artifactManifest")));
                confirmOrScheduleFinalizationNotification(context, receipt);
                return;
            }
        } else {
            Map<String, Object> currentMetadata = readRunMetadata(context, runId);
            validateFinalizationOperation(currentMetadata, operation);
            if (runIsTerminal(context, runId)) {
                if (isExplicitLegacyFinalizationMode(finalizationMode) && operation == null) {
                    return;
                }
                PolicyWindTunnelStoredRun terminalRun = findStoredRun(context, runId);
                if (FINALIZATION_MODE_PLATFORM_OWNED_V2.equals(finalizationMode)
                        && operation != null
                        && (STATUS_FAILED.equalsIgnoreCase(terminalRun.status())
                                || STATUS_CANCELLED.equalsIgnoreCase(terminalRun.status()))) {
                    publishTerminalReceiptWithoutPrepared(
                            context,
                            runId,
                            finalizationMode,
                            operation,
                            inputDigest,
                            terminalRun,
                            opportunityCorrelationCapability);
                    return;
                }
                throw new IllegalStateException(
                        "A platform-owned terminal run has no durable keyed finalization receipt");
            }
            BankingPolicyScenarioLabRunMetadata metadata = readPolicyMetadata(currentMetadata);
            Map<String, Object> correlationAudit = prefetchRateChangeCorrelationAudit(
                    context,
                    runId,
                    objectMap(metadata.parameters()),
                    opportunityCorrelationCapability);
            if (!clockDrivenRunCanFinalize(context, runId, metadata)) {
                appendLifecycleIgnoredLog(context, runId, "Finalize");
                return;
            }
            Map<String, Object> reconciliation = fetchDecisionOpportunityReconciliation(context, runId);
            if (!opportunityReconciliationIsValid(reconciliation, runId)
                    || !Boolean.TRUE.equals(reconciliation.get("generationSealed"))) {
                appendLog(context, runId,
                        "Finalize deferred because platform-owned opportunity generation sealing is not verified.");
                return;
            }

            PolicyWindTunnelStoredRun storedRun = findStoredRun(context, runId);
            Path summaryDir = finalizationSummaryDirectory(context, runId);
            Map<String, Object> artifactPaths =
                    finalizedHybridArtifactPaths(context.workspaceRoot(), summaryDir, runId);
            Map<String, Object> finalizingMetadata = metadata
                    .withLifecycle(PHASE_FINALIZING_REPORT, 0.92, true, null, null, null, null)
                    .writeTo(currentMetadata);
            if (operation != null) {
                finalizingMetadata = withFinalizationOperation(finalizingMetadata, operation);
            }
            Map<String, Object> summary = buildClockDrivenHybridFinalizedSummary(
                    context,
                    storedRun,
                    artifactPaths,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    opportunityCorrelationCapability,
                    correlationAudit);
            writeClockDrivenHybridFinalizedArtifacts(summaryDir, summary);
            Map<String, Object> artifactManifest = finalizationArtifactManifest(context, artifactPaths);
            requirePublishedArtifacts(context, artifactManifest);
            receipt = finalizationReceipt(
                    runId,
                    finalizationMode,
                    operation,
                    inputDigest,
                    FINALIZATION_RECEIPT_PREPARED,
                    runSnapshot(storedRun, STATUS_RUNNING, null, null, null, finalizingMetadata),
                    artifactManifest);
            writeFinalizationReceipt(context, runId, receipt);
            afterFinalizationReceiptPrepared.accept(
                    runId,
                    operation == null ? "" : operation.operationId());
            context.updateRun(runId, new PolicyWindTunnelRunUpdate(
                    STATUS_RUNNING, null, null, null, finalizingMetadata));
        }

        PolicyWindTunnelStoredRun storedRun = findStoredRun(context, runId);
        Map<String, Object> artifactPaths =
                finalizedHybridArtifactPaths(
                        context.workspaceRoot(), finalizationSummaryDirectory(context, runId), runId);
        Map<String, Object> artifactManifest = objectMap(receipt.get("artifactManifest"));
        requireMatchingArtifactManifest(context, runId, receipt);
        requirePublishedArtifacts(context, artifactManifest);
        Map<String, Object> summary = readFinalizedSummary(context, artifactManifest);

        PolicyWindTunnelStoredRun publicationState = findStoredRun(context, runId);
        if (!lifecycleTransitionAllows(publicationState, TRANSITION_FINALIZE_COMPLETE)) {
            Map<String, Object> terminalReceipt = finalizationReceipt(
                    runId,
                    finalizationMode,
                    operation,
                    inputDigest,
                    FINALIZATION_RECEIPT_PUBLISHED,
                    runSnapshot(
                            publicationState,
                            publicationState.status(),
                            publicationState.summaryPath(),
                            publicationState.bundlePath(),
                            storedRunCompletionEpochMs(publicationState),
                            publicationState.metadata()),
                    artifactManifest);
            attachCompletionNotificationPayload(context, terminalReceipt);
            writeFinalizationReceipt(context, runId, terminalReceipt);
            registerArtifactsFromManifest(context, runId, artifactManifest);
            confirmOrScheduleFinalizationNotification(context, terminalReceipt);
            return;
        }

        String summaryPath = stringValue(artifactPaths.get("summary"));
        String bundleUrl = "/api/experiment-runs/" + urlEncode(runId) + "/scenario-lab/bundle";
        String evidencePackUrl =
                "/api/experiment-runs/" + urlEncode(runId) + "/scenario-lab/evidence-pack";
        Map<String, Object> completionMetadata = readRunMetadata(context, runId);
        Map<String, Object> completedMetadata = new LinkedHashMap<>(readPolicyMetadata(completionMetadata)
                .appendLog("Clock-Driven Simulation evidence finalized into outcome comparison report.")
                .appendLog("Runtime cohort retained for post-run inspection. Use Runtime Overview Reset Run "
                        + "when you want to clear active agents and the live feed.")
                .withLifecycle(PHASE_COMPLETED, 1.0, true, bundleUrl, evidencePackUrl, null)
                .writeTo(completionMetadata));
        Map<String, Object> completedParameters = objectMap(readPolicyMetadata(completedMetadata).parameters());
        Map<String, Object> completedScenarioLab = new LinkedHashMap<>(
                objectMap(completedMetadata.get("scenarioLab")));
        completedScenarioLab.put("summaryMode", stringValue(summary.get("summaryMode")));
        completedScenarioLab.put("agenticSimulation", objectMap(summary.get("agenticSimulation")));
        completedMetadata.put("scenarioLab", completedScenarioLab);
        completedMetadata.put("source", "java-spring-banking");
        completedMetadata.put("domainId", BankingPolicyWindTunnelManifest.DOMAIN_ID);
        completedMetadata.put("cohortId", firstNonBlank(
                stringValue(completedParameters.get("cohortId")),
                stringValue(objectMap(completedParameters.get("cohortSelection")).get("cohortId"))));
        long completedAtEpochMs = System.currentTimeMillis();
        Map<String, Object> publishedReceipt = finalizationReceipt(
                runId,
                finalizationMode,
                operation,
                inputDigest,
                FINALIZATION_RECEIPT_PUBLISHED,
                runSnapshot(
                        storedRun,
                        STATUS_COMPLETED,
                        summaryPath,
                        bundleUrl,
                        completedAtEpochMs,
                        completedMetadata),
                artifactManifest);
        attachCompletionNotificationPayload(context, publishedReceipt);
        writeFinalizationReceipt(context, runId, publishedReceipt);
        context.updateRun(runId, new PolicyWindTunnelRunUpdate(
                STATUS_COMPLETED,
                summaryPath,
                bundleUrl,
                completedAtEpochMs,
                completedMetadata));
        registerArtifactsFromManifest(context, runId, artifactManifest);
        confirmOrScheduleFinalizationNotification(context, publishedReceipt);
    }

    private void publishTerminalRunState(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String status,
            String phase,
            Map<String, Object> error,
            String logLine) {
        if (!STATUS_CANCELLED.equals(status) && !STATUS_FAILED.equals(status)) {
            throw new IllegalArgumentException("Only failed or cancelled runs may publish terminal precedence");
        }
        withFinalizationReceiptLock(context, runId, () -> {
            Map<String, Object> receipt = loadFinalizationReceipt(context, runId);
            if (receipt.isEmpty()) {
                updateRunStateAndAppendLog(
                        context,
                        runId,
                        status,
                        phase,
                        1.0,
                        null,
                        null,
                        error,
                        false,
                        logLine);
                return;
            }
            if (FINALIZATION_RECEIPT_PUBLISHED.equals(stringValue(receipt.get("state")))) {
                String publishedStatus = stringValue(objectMap(receipt.get("runSnapshot")).get("status"));
                if (!status.equals(publishedStatus)) {
                    throw new IllegalStateException("Published finalization receipt conflicts with terminal state");
                }
                restoreRunFromFinalizationReceipt(context, receipt, true);
                return;
            }

            PolicyWindTunnelStoredRun currentRun = context.findRun(runId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown runId: " + runId));
            Map<String, Object> currentMetadata = objectMap(currentRun.metadata());
            BankingPolicyScenarioLabRunMetadata terminalPolicyMetadata = readPolicyMetadata(currentMetadata)
                    .withLifecycle(phase, 1.0, false, null, null, error, null);
            if (!blank(logLine)) {
                terminalPolicyMetadata = terminalPolicyMetadata.appendLog(logLine);
            }
            Map<String, Object> terminalMetadata = terminalPolicyMetadata.writeTo(currentMetadata);
            long completedAtEpochMs = System.currentTimeMillis();
            Map<String, Object> artifactManifest = objectMap(receipt.get("artifactManifest"));
            requirePublishedArtifacts(context, artifactManifest);
            context.updateRun(runId, new PolicyWindTunnelRunUpdate(
                    status,
                    currentRun.summaryPath(),
                    currentRun.bundlePath(),
                    completedAtEpochMs,
                    terminalMetadata));
        });
    }

    private void publishTerminalReceiptWithoutPrepared(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String finalizationMode,
            FinalizationOperation operation,
            String inputDigest,
            PolicyWindTunnelStoredRun terminalRun,
            String opportunityCorrelationCapability) throws IOException {
        if (!STATUS_CANCELLED.equalsIgnoreCase(terminalRun.status())
                && !STATUS_FAILED.equalsIgnoreCase(terminalRun.status())) {
            throw new IllegalStateException(
                    "A terminal finalization receipt requires FAILED or CANCELLED state");
        }
        Path summaryDirectory = finalizationSummaryDirectory(context, runId);
        Map<String, Object> correlationAudit = prefetchRateChangeCorrelationAudit(
                context,
                runId,
                objectMap(readPolicyMetadata(terminalRun.metadata()).parameters()),
                opportunityCorrelationCapability);
        Map<String, Object> artifactPaths = finalizedHybridArtifactPaths(
                context.workspaceRoot(), summaryDirectory, runId);
        Map<String, Object> summary = buildClockDrivenHybridFinalizedSummary(
                context,
                terminalRun,
                artifactPaths,
                OffsetDateTime.now(ZoneOffset.UTC),
                opportunityCorrelationCapability,
                correlationAudit);
        writeClockDrivenHybridFinalizedArtifacts(summaryDirectory, summary);
        Map<String, Object> artifactManifest = finalizationArtifactManifest(context, artifactPaths);
        requirePublishedArtifacts(context, artifactManifest);
        Map<String, Object> terminalReceipt = finalizationReceipt(
                runId,
                finalizationMode,
                operation,
                inputDigest,
                FINALIZATION_RECEIPT_PUBLISHED,
                runSnapshot(
                        terminalRun,
                        terminalRun.status(),
                        terminalRun.summaryPath(),
                        terminalRun.bundlePath(),
                        storedRunCompletionEpochMs(terminalRun),
                        terminalRun.metadata()),
                artifactManifest);
        writeFinalizationReceipt(context, runId, terminalReceipt);
        registerArtifactsFromManifest(context, runId, artifactManifest);
    }

    private void withFinalizationReceiptLock(
            PolicyWindTunnelExecutionContext context,
            String runId,
            FinalizationReceiptWork work) {
        Path lockPath = finalizationRunDirectory(context, runId)
                .resolve(FINALIZATION_RECEIPT_LOCK_FILE)
                .toAbsolutePath()
                .normalize();
        Object processLock = FINALIZATION_RECEIPT_LOCKS.computeIfAbsent(lockPath, ignored -> new Object());
        synchronized (processLock) {
            try {
                createDirectoriesDurably(lockPath.getParent());
                try (FileChannel channel = FileChannel.open(
                                lockPath,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                        FileLock ignored = channel.lock()) {
                    work.execute();
                }
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(
                        "Clock-Driven Simulation finalization publication failed",
                        failure);
            }
        }
    }

    private Path finalizationRunDirectory(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        Path artifactRoot = context.workspaceRoot()
                .resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT)
                .toAbsolutePath()
                .normalize();
        Path runRoot = artifactRoot.resolve(runId).normalize();
        if (!runRoot.startsWith(artifactRoot)) {
            throw new IllegalArgumentException("Scenario Lab runId escapes the artifact root");
        }
        return runRoot;
    }

    private Path finalizationSummaryDirectory(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        return finalizationRunDirectory(context, runId).resolve("summary");
    }

    private Path finalizationReceiptPath(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        return finalizationRunDirectory(context, runId).resolve(FINALIZATION_RECEIPT_FILE);
    }

    private Map<String, Object> loadFinalizationReceipt(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        Path receiptPath = finalizationReceiptPath(context, runId);
        if (Files.notExists(receiptPath)) {
            return Map.of();
        }
        try {
            byte[] content = Files.readAllBytes(receiptPath);
            if (content.length == 0 || content.length > 1024 * 1024) {
                throw new IllegalStateException("Scenario Lab finalization receipt size is invalid");
            }
            Map<String, Object> receipt = objectMap(objectMapper.readValue(content, Map.class));
            validateStoredFinalizationReceipt(receipt, runId);
            requireMatchingArtifactManifest(context, runId, receipt);
            return receipt;
        } catch (IOException | JacksonException invalidReceipt) {
            throw new IllegalStateException(
                    "Scenario Lab finalization receipt could not be read",
                    invalidReceipt);
        }
    }

    private void writeFinalizationReceipt(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Map<String, Object> receipt) throws IOException {
        validateStoredFinalizationReceipt(receipt, runId);
        requireMatchingArtifactManifest(context, runId, receipt);
        writeJson(finalizationReceiptPath(context, runId), receipt);
    }

    private void validateStoredFinalizationReceipt(
            Map<String, Object> receipt,
            String expectedRunId) {
        String state = stringValue(receipt.get("state"));
        String mode = stringValue(receipt.get("finalizationMode"));
        String operationId = stringValue(receipt.get("operationId"));
        String inputDigest = stringValue(receipt.get("inputDigest"));
        String providerRevision = stringValue(receipt.get("providerRevision"));
        String notificationState = stringValue(receipt.get("completionNotificationState"));
        Map<String, Object> runSnapshot = objectMap(receipt.get("runSnapshot"));
        Map<String, Object> notificationPayload = objectMap(receipt.get("completionNotificationPayload"));
        if (!receipt.keySet().equals(FINALIZATION_RECEIPT_FIELDS)
                || !FINALIZATION_RECEIPT_SCHEMA.equals(receipt.get("schemaVersion"))
                || !expectedRunId.equals(receipt.get("runId"))
                || !validReceiptRunId(receipt.get("runId"))
                || (!FINALIZATION_RECEIPT_PREPARED.equals(state)
                        && !FINALIZATION_RECEIPT_PUBLISHED.equals(state))
                || !state.equals(receipt.get("state"))
                || !FINALIZATION_MODE_PLATFORM_OWNED_V2.equals(receipt.get("finalizationMode"))
                || !BankingRemotePolicyWindTunnelService.PROVIDER_REVISION.equals(receipt.get("providerRevision"))
                || !(receipt.get("operationId") instanceof String)
                || !(receipt.get("inputDigest") instanceof String)
                || !inputDigest.matches("[0-9a-f]{64}")
                || (!FINALIZATION_NOTIFICATION_PENDING.equals(notificationState)
                        && !FINALIZATION_NOTIFICATION_CONFIRMED.equals(notificationState)
                        && !FINALIZATION_NOTIFICATION_NOT_REQUIRED.equals(notificationState))
                || !notificationState.equals(receipt.get("completionNotificationState"))
                || !(receipt.get("completionNotificationPayload") instanceof Map<?, ?>)
                || !(receipt.get("runSnapshot") instanceof Map<?, ?>)
                || !validFinalizationRunSnapshot(runSnapshot, state)
                || !(receipt.get("artifactManifest") instanceof Map<?, ?>)
                || !validFinalizationArtifactManifest(objectMap(receipt.get("artifactManifest")))
                || !validIsoDateTime(receipt.get("updatedAt"))) {
            throw new IllegalStateException("Scenario Lab finalization receipt is invalid");
        }
        if (FINALIZATION_MODE_PLATFORM_OWNED_V2.equals(mode)
                && !expectedFinalizationOperationId(expectedRunId).equals(operationId)) {
            throw new IllegalStateException("Scenario Lab V2 finalization receipt is not operation-keyed");
        }
        boolean published = FINALIZATION_RECEIPT_PUBLISHED.equals(state);
        boolean completed = STATUS_COMPLETED.equalsIgnoreCase(stringValue(runSnapshot.get("status")));
        if (!published && (!FINALIZATION_NOTIFICATION_PENDING.equals(notificationState)
                || !notificationPayload.isEmpty())) {
            throw new IllegalStateException("Scenario Lab PREPARED receipt notification state is invalid");
        }
        if (published && completed) {
            if (FINALIZATION_NOTIFICATION_NOT_REQUIRED.equals(notificationState)
                    || !validCompletionNotificationPayload(
                            notificationPayload,
                            expectedRunId,
                            operationId,
                            inputDigest,
                            providerRevision,
                            runSnapshot)) {
                throw new IllegalStateException("Scenario Lab completion notification receipt is invalid");
            }
        } else if (published && (!FINALIZATION_NOTIFICATION_NOT_REQUIRED.equals(notificationState)
                || !notificationPayload.isEmpty())) {
            throw new IllegalStateException("Scenario Lab terminal receipt notification state is invalid");
        }
    }

    private boolean validFinalizationRunSnapshot(Map<String, Object> snapshot, String receiptState) {
        if (!snapshot.keySet().containsAll(Set.of("status", "metadata"))
                || !FINALIZATION_RUN_SNAPSHOT_FIELDS.containsAll(snapshot.keySet())
                || !(snapshot.get("status") instanceof String status)
                || !(snapshot.get("metadata") instanceof Map<?, ?>)) {
            return false;
        }
        if (FINALIZATION_RECEIPT_PREPARED.equals(receiptState)) {
            if (!STATUS_RUNNING.equals(status) || snapshot.containsKey("completedAtEpochMs")) {
                return false;
            }
        } else if (!Set.of(STATUS_COMPLETED, STATUS_FAILED, STATUS_CANCELLED).contains(status)
                || !snapshot.containsKey("completedAtEpochMs")) {
            return false;
        }
        for (String pathField : List.of("summaryPath", "bundlePath")) {
            Object value = snapshot.get(pathField);
            if (value != null && !(value instanceof String)) {
                return false;
            }
        }
        try {
            strictNotificationEpochMs(snapshot.get("startedAtEpochMs"));
            if (snapshot.containsKey("completedAtEpochMs")) {
                strictNotificationEpochMs(snapshot.get("completedAtEpochMs"));
            }
        } catch (IllegalStateException invalidTimestamp) {
            return false;
        }
        return true;
    }

    private boolean validReceiptRunId(Object value) {
        if (!(value instanceof String text) || text.isEmpty() || text.length() > 256) {
            return false;
        }
        return text.chars().allMatch(character -> character >= 0x20 && character <= 0x7e);
    }

    private boolean validIsoDateTime(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            return OffsetDateTime.parse(text).getOffset() != null;
        } catch (RuntimeException invalidTimestamp) {
            return false;
        }
    }

    private void validateFinalizationReceipt(
            Map<String, Object> receipt,
            String runId,
            String finalizationMode,
            FinalizationOperation operation,
            String inputDigest) {
        validateStoredFinalizationReceipt(receipt, runId);
        String requestedOperationId = operation == null ? "" : operation.operationId();
        if (!finalizationMode.equals(stringValue(receipt.get("finalizationMode")))
                || !requestedOperationId.equals(stringValue(receipt.get("operationId")))
                || !inputDigest.equals(stringValue(receipt.get("inputDigest")))) {
            throw new IllegalArgumentException(
                    "Scenario Lab finalization operationId was reused with contradictory input");
        }
    }

    private Map<String, Object> finalizationReceipt(
            String runId,
            String finalizationMode,
            FinalizationOperation operation,
            String inputDigest,
            String state,
            Map<String, Object> runSnapshot,
            Map<String, Object> artifactManifest) {
        boolean notificationRequired = FINALIZATION_RECEIPT_PUBLISHED.equals(state)
                && STATUS_COMPLETED.equalsIgnoreCase(stringValue(runSnapshot.get("status")));
        return linkedMap(
                "schemaVersion", FINALIZATION_RECEIPT_SCHEMA,
                "runId", runId,
                "finalizationMode", finalizationMode,
                "providerRevision", BankingRemotePolicyWindTunnelService.PROVIDER_REVISION,
                "operationId", operation == null ? "" : operation.operationId(),
                "inputDigest", inputDigest,
                "state", state,
                "completionNotificationState", notificationRequired
                        ? FINALIZATION_NOTIFICATION_PENDING
                        : FINALIZATION_RECEIPT_PUBLISHED.equals(state)
                                ? FINALIZATION_NOTIFICATION_NOT_REQUIRED
                                : FINALIZATION_NOTIFICATION_PENDING,
                "completionNotificationPayload", Map.of(),
                "runSnapshot", runSnapshot,
                "artifactManifest", artifactManifest,
                "updatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
    }

    private Map<String, Object> runSnapshot(
            PolicyWindTunnelStoredRun storedRun,
            String status,
            String summaryPath,
            String bundlePath,
            Long completedAtEpochMs,
            Map<String, Object> metadata) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", status);
        snapshot.put("summaryPath", firstNonBlank(summaryPath, storedRun.summaryPath()));
        snapshot.put("bundlePath", firstNonBlank(bundlePath, storedRun.bundlePath()));
        snapshot.put("startedAtEpochMs", storedRun.startedAtEpochMs());
        if (completedAtEpochMs != null) {
            snapshot.put("completedAtEpochMs", completedAtEpochMs);
        }
        snapshot.put("metadata", metadata);
        return snapshot;
    }

    private long storedRunCompletionEpochMs(PolicyWindTunnelStoredRun storedRun) {
        if (storedRun.updatedAt() != null) {
            return storedRun.updatedAt().toInstant().toEpochMilli();
        }
        if (storedRun.createdAt() != null) {
            return storedRun.createdAt().toInstant().toEpochMilli();
        }
        return Math.max(1L, storedRun.startedAtEpochMs() == null ? 1L : storedRun.startedAtEpochMs());
    }

    private void restoreFinalizationReceiptIfPresent(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        synchronized (lifecycleLock(runId)) {
            Map<String, Object> receipt = loadFinalizationReceipt(context, runId);
            if (!receipt.isEmpty()) {
                requirePublishedArtifacts(context, objectMap(receipt.get("artifactManifest")));
                restoreRunFromFinalizationReceipt(context, receipt, false);
            }
        }
    }

    private void restoreRunFromFinalizationReceipt(
            PolicyWindTunnelExecutionContext context,
            Map<String, Object> receipt,
            boolean replaceExisting) {
        String runId = stringValue(receipt.get("runId"));
        Map<String, Object> snapshot = objectMap(receipt.get("runSnapshot"));
        Map<String, Object> metadata = objectMap(snapshot.get("metadata"));
        PolicyWindTunnelStoredRun currentRun = context.findRun(runId).orElse(null);
        if (currentRun != null && isTerminalStatus(currentRun.status())) {
            return;
        }
        if (currentRun == null) {
            ensureExperimentRegistered(context);
            Map<String, Object> parameters = objectMap(readPolicyMetadata(metadata).parameters());
            context.createRun(new PolicyWindTunnelRunCreateRequest(
                    runId,
                    BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                    scenarioIdForPreset(firstNonBlank(stringValue(parameters.get("presetId")), "custom")),
                    "CANDIDATE",
                    stringValue(parameters.get("compareMode")),
                    stringValue(parameters.get("presetId")),
                    stringValue(snapshot.get("status")),
                    longValue(snapshot.get("startedAtEpochMs"), System.currentTimeMillis()),
                    stringValue(snapshot.get("summaryPath")),
                    stringValue(snapshot.get("bundlePath")),
                    metadata));
        } else if (replaceExisting
                || FINALIZATION_RECEIPT_PUBLISHED.equals(stringValue(receipt.get("state")))) {
            context.updateRun(runId, new PolicyWindTunnelRunUpdate(
                    stringValue(snapshot.get("status")),
                    stringValue(snapshot.get("summaryPath")),
                    stringValue(snapshot.get("bundlePath")),
                    snapshot.containsKey("completedAtEpochMs")
                            ? longValue(snapshot.get("completedAtEpochMs"), System.currentTimeMillis())
                            : null,
                    metadata));
        }
        if (FINALIZATION_RECEIPT_PUBLISHED.equals(stringValue(receipt.get("state")))) {
            registerArtifactsFromManifest(
                    context,
                    runId,
                    objectMap(receipt.get("artifactManifest")));
        }
    }

    private String finalizationMode(Map<String, Object> metadata) {
        Object directValue = metadata.get(FINALIZATION_MODE_FIELD);
        Object parameterValue = objectMap(readPolicyMetadata(metadata).parameters())
                .get(FINALIZATION_MODE_FIELD);
        String direct = directValue == null ? "" : requireExactFinalizationMode(directValue);
        String parameter = parameterValue == null ? "" : requireExactFinalizationMode(parameterValue);
        if (!direct.isBlank() && !parameter.isBlank() && !direct.equals(parameter)) {
            throw new IllegalStateException(
                    "Scenario Lab finalization mode metadata contradicts the pinned run parameters");
        }
        return firstNonBlank(direct, parameter);
    }

    private static boolean isExplicitLegacyFinalizationMode(String mode) {
        return FINALIZATION_MODE_PLATFORM_OWNED_V1.equals(mode)
                || FINALIZATION_MODE_LEGACY_V1.equals(mode);
    }

    private static void requireFinalizationOperation(
            String runId,
            String finalizationMode,
            FinalizationOperation operation) {
        if (isExplicitLegacyFinalizationMode(finalizationMode)) {
            if (operation != null) {
                throw new IllegalArgumentException(
                        "Legacy V1 finalization recovery does not accept a V2 operationId");
            }
            return;
        }
        if (!FINALIZATION_MODE_PLATFORM_OWNED_V2.equals(finalizationMode)) {
            throw new IllegalArgumentException(
                    "Scenario Lab finalization requires an explicitly pinned finalization mode");
        }
        if (operation == null) {
            throw new IllegalArgumentException(
                    "PLATFORM_OWNED_V2 finalization requires an operationId before publication");
        }
        if (!expectedFinalizationOperationId(runId).equals(operation.operationId())) {
            throw new IllegalArgumentException(
                    "PLATFORM_OWNED_V2 finalization operationId did not match this run and provider revision");
        }
    }

    private Map<String, Object> finalizationRequest(Map<String, Object> payload) {
        Map<String, Object> request = new LinkedHashMap<>(objectMap(payload));
        request.put("action", LIFECYCLE_ACTION_FINALIZE);
        return request;
    }

    private Map<String, Object> withFinalizationOperation(
            Map<String, Object> metadata,
            FinalizationOperation operation) {
        Map<String, Object> updated = new LinkedHashMap<>(metadata);
        updated.put(FINALIZATION_OPERATION_METADATA, Map.of(
                "operationId", operation.operationId(),
                "inputDigest", operation.inputDigest()));
        return updated;
    }

    private Map<String, Object> finalizationArtifactManifest(
            PolicyWindTunnelExecutionContext context,
            Map<String, Object> artifactPaths) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        addArtifactManifestEntry(context, manifest, "summary", artifactPaths, "summary", "application/json");
        addArtifactManifestEntry(context, manifest, "impactedCohortsCsv", artifactPaths, "impactedCohortsCsv", "text/csv");
        addArtifactManifestEntry(context, manifest, "experimentCard", artifactPaths, "experimentCard", "text/markdown");
        addArtifactManifestEntry(context, manifest, "comparisonReport", artifactPaths, "comparisonReport", "application/json");
        addArtifactManifestEntry(context, manifest, "checkpointsJson", artifactPaths, "checkpointsJson", "application/json");
        addArtifactManifestEntry(context, manifest, "agentDiffsJson", artifactPaths, "agentDiffsJson", "application/json");
        addArtifactManifestEntry(context, manifest, "notableActionsJson", artifactPaths, "notableActionsJson", "application/json");
        addArtifactManifestEntry(context, manifest, "scheduledWorkFailuresJson", artifactPaths, "scheduledWorkFailuresJson", "application/json");
        addArtifactManifestEntry(context, manifest, "simulationTimelineJson", artifactPaths, "simulationTimelineJson", "application/json");
        addArtifactManifestEntry(context, manifest, "sideEffectLedgerJson", artifactPaths, "sideEffectLedgerJson", "application/json");
        addArtifactManifestEntry(context, manifest, "domainOutcomeScoreJson", artifactPaths, "domainOutcomeScoreJson", "application/json");
        addArtifactManifestEntry(context, manifest, "domainOutcomeScoresJson", artifactPaths, "domainOutcomeScoresJson", "application/json");
        addArtifactManifestEntry(context, manifest, "branchStateProjectionJson", artifactPaths, "branchStateProjectionJson", "application/json");
        addArtifactManifestEntry(context, manifest, "hybridAgentAuditJson", artifactPaths, "hybridAgentAuditJson", "application/json");
        return manifest;
    }

    private void addArtifactManifestEntry(
            PolicyWindTunnelExecutionContext context,
            Map<String, Object> manifest,
            String artifactType,
            Map<String, Object> paths,
            String pathKey,
            String contentType) {
        String artifactRef = stringValue(paths.get(pathKey));
        Path artifactPath = resolveFinalizationArtifact(context, artifactRef);
        try {
            byte[] content = Files.readAllBytes(artifactPath);
            manifest.put(artifactType, Map.of(
                    "artifactRef", artifactRef,
                    "contentType", contentType,
                    "sha256", sha256Hex(content),
                    "byteLength", (long) content.length));
        } catch (IOException unavailableArtifact) {
            throw new IllegalStateException(
                    "Scenario Lab finalization artifact could not be frozen: " + artifactRef,
                    unavailableArtifact);
        }
    }

    private void requireMatchingArtifactManifest(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Map<String, Object> receipt) {
        Map<String, Object> artifactPaths = finalizedHybridArtifactPaths(
                context.workspaceRoot(), finalizationSummaryDirectory(context, runId), runId);
        Map<String, Object> expectedManifest = finalizationArtifactManifest(context, artifactPaths);
        if (!sha256Hex(canonicalJsonBytes(expectedManifest)).equals(
                sha256Hex(canonicalJsonBytes(objectMap(receipt.get("artifactManifest")))))) {
            throw new IllegalStateException(
                    "Scenario Lab finalization receipt artifact manifest is contradictory");
        }
    }

    private void requirePublishedArtifacts(
            PolicyWindTunnelExecutionContext context,
            Map<String, Object> artifactManifest) {
        for (Object rawEntry : artifactManifest.values()) {
            Map<String, Object> entry = objectMap(rawEntry);
            String artifactRef = stringValue(entry.get("artifactRef"));
            Path artifactPath = resolveFinalizationArtifact(context, artifactRef);
            try {
                byte[] content = Files.readAllBytes(artifactPath);
                if (content.length != strictArtifactByteLength(entry.get("byteLength"))
                        || !sha256Hex(content).equals(stringValue(entry.get("sha256")))) {
                    throw new IllegalStateException(
                            "Scenario Lab finalization artifact integrity did not match its receipt: "
                                    + artifactRef);
                }
            } catch (IOException unavailableArtifact) {
                throw new IllegalStateException(
                        "Scenario Lab finalization artifact was not durably published: " + artifactRef,
                        unavailableArtifact);
            }
        }
    }

    private boolean validFinalizationArtifactManifest(Map<String, Object> artifactManifest) {
        if (!artifactManifest.keySet().equals(FINALIZATION_ARTIFACT_CONTENT_TYPES.keySet())) {
            return false;
        }
        for (Map.Entry<String, String> expected : FINALIZATION_ARTIFACT_CONTENT_TYPES.entrySet()) {
            Object rawEntry = artifactManifest.get(expected.getKey());
            Map<String, Object> entry = objectMap(rawEntry);
            if (!validRelativeArtifactRef(entry.get("artifactRef"))
                    || !expected.getValue().equals(stringValue(entry.get("contentType")))
                    || !entry.keySet().equals(FINALIZATION_ARTIFACT_FIELDS)
                    || !stringValue(entry.get("sha256")).matches("[0-9a-f]{64}")) {
                return false;
            }
            try {
                strictArtifactByteLength(entry.get("byteLength"));
            } catch (IllegalStateException invalidLength) {
                return false;
            }
        }
        return true;
    }

    private boolean validRelativeArtifactRef(Object value) {
        if (!(value instanceof String artifactRef)
                || artifactRef.isBlank()
                || artifactRef.length() > 1024
                || artifactRef.contains("\\")) {
            return false;
        }
        try {
            Path path = Path.of(artifactRef);
            return !path.isAbsolute()
                    && path.normalize().equals(path)
                    && !path.startsWith("..");
        } catch (RuntimeException invalidPath) {
            return false;
        }
    }

    private Path resolveFinalizationArtifact(
            PolicyWindTunnelExecutionContext context,
            String artifactRef) {
        Path root = context.workspaceRoot().toAbsolutePath().normalize();
        Path artifactPath = root.resolve(artifactRef).toAbsolutePath().normalize();
        if (artifactRef.isBlank()
                || !artifactPath.startsWith(root)
                || !Files.isRegularFile(artifactPath)) {
            throw new IllegalStateException(
                    "Scenario Lab finalization artifact was not durably published: " + artifactRef);
        }
        return artifactPath;
    }

    private long strictArtifactByteLength(Object value) {
        if (!(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger)) {
            throw new IllegalStateException("Scenario Lab finalization artifact byte length is invalid");
        }
        long resolved = ((Number) value).longValue();
        if (resolved < 0L || resolved > 9_007_199_254_740_991L) {
            throw new IllegalStateException("Scenario Lab finalization artifact byte length is invalid");
        }
        return resolved;
    }

    private Map<String, Object> readFinalizedSummary(
            PolicyWindTunnelExecutionContext context,
            Map<String, Object> artifactManifest) {
        Map<String, Object> summaryEntry = objectMap(artifactManifest.get("summary"));
        Path summaryPath = resolveFinalizationArtifact(
                context, stringValue(summaryEntry.get("artifactRef")));
        try {
            return objectMap(objectMapper.readValue(Files.readAllBytes(summaryPath), Map.class));
        } catch (IOException invalidSummary) {
            throw new IllegalStateException(
                    "Scenario Lab finalized summary could not be read from immutable evidence",
                    invalidSummary);
        }
    }

    private void attachCompletionNotificationPayload(
            PolicyWindTunnelExecutionContext context,
            Map<String, Object> receipt) {
        Map<String, Object> snapshot = objectMap(receipt.get("runSnapshot"));
        if (!FINALIZATION_RECEIPT_PUBLISHED.equals(stringValue(receipt.get("state")))
                || !STATUS_COMPLETED.equalsIgnoreCase(stringValue(snapshot.get("status")))) {
            return;
        }
        Map<String, Object> artifactManifest = objectMap(receipt.get("artifactManifest"));
        Map<String, Object> policyMetadata = objectMap(snapshot.get("metadata"));
        String runId = stringValue(receipt.get("runId"));
        String summaryPath = firstNonBlank(
                stringValue(snapshot.get("summaryPath")),
                stringValue(objectMap(artifactManifest.get("summary")).get("artifactRef")));
        String bundlePath = firstNonBlank(
                stringValue(snapshot.get("bundlePath")),
                "/api/experiment-runs/" + urlEncode(runId) + "/scenario-lab/bundle");
        long completedAtEpochMs = strictNotificationEpochMs(snapshot.get("completedAtEpochMs"));
        receipt.put("completionNotificationPayload", linkedMap(
                "runId", runId,
                "status", STATUS_COMPLETED,
                "source", "java-spring-banking",
                "summaryPath", summaryPath,
                "bundlePath", bundlePath,
                "operationId", stringValue(receipt.get("operationId")),
                "inputDigest", stringValue(receipt.get("inputDigest")),
                "providerRevision", stringValue(receipt.get("providerRevision")),
                "completedAtEpochMs", completedAtEpochMs,
                "metadata", policyMetadata));
    }

    private boolean validCompletionNotificationPayload(
            Map<String, Object> payload,
            String runId,
            String operationId,
            String inputDigest,
            String providerRevision,
            Map<String, Object> runSnapshot) {
        try {
            return payload.keySet().equals(FINALIZATION_NOTIFICATION_FIELDS)
                    && runId.equals(payload.get("runId"))
                    && STATUS_COMPLETED.equals(payload.get("status"))
                    && payload.get("source") instanceof String source
                    && !source.isBlank()
                    && operationId.equals(payload.get("operationId"))
                    && inputDigest.equals(payload.get("inputDigest"))
                    && providerRevision.equals(payload.get("providerRevision"))
                    && payload.get("summaryPath") instanceof String
                    && payload.get("bundlePath") instanceof String
                    && firstNonBlank(
                                    stringValue(runSnapshot.get("summaryPath")),
                                    stringValue(payload.get("summaryPath")))
                            .equals(stringValue(payload.get("summaryPath")))
                    && firstNonBlank(
                                    stringValue(runSnapshot.get("bundlePath")),
                                    stringValue(payload.get("bundlePath")))
                            .equals(stringValue(payload.get("bundlePath")))
                    && strictNotificationEpochMs(runSnapshot.get("completedAtEpochMs"))
                            == strictNotificationEpochMs(payload.get("completedAtEpochMs"))
                    && payload.get("metadata") instanceof Map<?, ?> metadata
                    && !metadata.isEmpty();
        } catch (IllegalStateException invalidPayload) {
            return false;
        }
    }

    private long strictNotificationEpochMs(Object value) {
        if (!(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger)) {
            throw new IllegalStateException("Scenario Lab completion timestamp is invalid");
        }
        long resolved = ((Number) value).longValue();
        if (resolved < 1L || resolved > 9_007_199_254_740_991L) {
            throw new IllegalStateException("Scenario Lab completion timestamp is invalid");
        }
        return resolved;
    }

    private void confirmOrScheduleFinalizationNotification(
            PolicyWindTunnelExecutionContext context,
            Map<String, Object> receipt) throws IOException {
        String runId = stringValue(receipt.get("runId"));
        if (confirmFinalizationNotification(context, receipt)) {
            finalizationNotificationRetryAttempts.remove(runId);
        } else {
            scheduleFinalizationNotificationRetry(context, runId);
        }
    }

    private boolean confirmFinalizationNotification(
            PolicyWindTunnelExecutionContext context,
            Map<String, Object> receipt) throws IOException {
        if (!FINALIZATION_RECEIPT_PUBLISHED.equals(stringValue(receipt.get("state")))
                || FINALIZATION_NOTIFICATION_CONFIRMED.equals(
                        stringValue(receipt.get("completionNotificationState")))
                || FINALIZATION_NOTIFICATION_NOT_REQUIRED.equals(
                        stringValue(receipt.get("completionNotificationState")))) {
            return true;
        }
        String runId = stringValue(receipt.get("runId"));
        Map<String, Object> updated = new LinkedHashMap<>(receipt);
        Map<String, Object> payload = objectMap(receipt.get("completionNotificationPayload"));
        if (notifySimulationRunCompleted(context, runId, payload)) {
            updated.put("completionNotificationState", FINALIZATION_NOTIFICATION_CONFIRMED);
            updated.put("updatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
            writeFinalizationReceipt(context, runId, updated);
            return true;
        }
        return false;
    }

    private void scheduleFinalizationNotificationRetry(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        if (!pendingFinalizationNotificationRetries.add(runId)) {
            return;
        }
        long delayMillis = finalizationNotificationRetryDelayMillis(runId);
        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS, executor).execute(() -> {
            boolean retry = false;
            try {
                retry = retryPendingFinalizationNotification(context, runId);
            } catch (RuntimeException retryFailure) {
                retry = true;
                log.warn(
                        "Scenario Lab completion notification retry failed for run {}: {}",
                        runId,
                        retryFailure.getMessage());
            } finally {
                pendingFinalizationNotificationRetries.remove(runId);
            }
            if (retry) {
                scheduleFinalizationNotificationRetry(context, runId);
            } else {
                finalizationNotificationRetryAttempts.remove(runId);
            }
        });
    }

    private boolean retryPendingFinalizationNotification(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        boolean[] retry = {false};
        withFinalizationReceiptLock(context, runId, () -> {
            Map<String, Object> receipt = loadFinalizationReceipt(context, runId);
            if (!receipt.isEmpty()) {
                retry[0] = !confirmFinalizationNotification(context, receipt);
            }
        });
        return retry[0];
    }

    private long finalizationNotificationRetryDelayMillis(String runId) {
        int attempt = finalizationNotificationRetryAttempts.merge(
                runId,
                1,
                (current, ignored) -> Math.min(current + 1, 31));
        long exponential = FINALIZATION_NOTIFICATION_RETRY_BASE_MILLIS;
        for (int index = 1; index < attempt && exponential < FINALIZATION_NOTIFICATION_RETRY_MAX_MILLIS; index++) {
            exponential = Math.min(
                    FINALIZATION_NOTIFICATION_RETRY_MAX_MILLIS,
                    exponential * 2L);
        }
        long jitterLimit = Math.min(
                Math.max(1L, exponential / 4L),
                FINALIZATION_NOTIFICATION_RETRY_MAX_MILLIS - exponential);
        long jitter = jitterLimit > 0L
                ? ThreadLocalRandom.current().nextLong(jitterLimit + 1L)
                : 0L;
        return exponential + jitter;
    }

    private static String expectedFinalizationOperationId(String runId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (runId + BankingRemotePolicyWindTunnelService.PROVIDER_REVISION)
                            .getBytes(StandardCharsets.UTF_8));
            return FINALIZATION_OPERATION_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void registerArtifactsFromManifest(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Map<String, Object> artifactManifest) {
        artifactManifest.forEach((artifactType, rawEntry) -> {
            Map<String, Object> entry = objectMap(rawEntry);
            context.registerArtifact(
                    runId,
                    artifactType,
                    stringValue(entry.get("artifactRef")),
                    stringValue(entry.get("contentType")));
        });
    }

    @FunctionalInterface
    private interface FinalizationReceiptWork {
        void execute() throws Exception;
    }

    private FinalizationOperation finalizationOperation(Map<String, Object> payload) {
        Map<String, Object> request = new LinkedHashMap<>(objectMap(payload));
        String operationId = stringValue(request.get("operationId")).trim();
        if (operationId.isBlank()) {
            return null;
        }
        if (!operationId.matches("scenario-lab-finalize:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Scenario Lab finalization operationId must use scenario-lab-finalize:<sha256>");
        }
        request.put("action", LIFECYCLE_ACTION_FINALIZE);
        return new FinalizationOperation(operationId, sha256Hex(canonicalJsonBytes(request)));
    }

    private void validateFinalizationOperation(
            Map<String, Object> metadata,
            FinalizationOperation requested) {
        Map<String, Object> existing = objectMap(metadata.get(FINALIZATION_OPERATION_METADATA));
        if (existing.isEmpty()) {
            return;
        }
        if (requested == null
                || !stringValue(existing.get("operationId")).equals(requested.operationId())
                || !stringValue(existing.get("inputDigest")).equals(requested.inputDigest())) {
            throw new IllegalArgumentException(
                    "Scenario Lab finalization operationId was reused with contradictory input");
        }
    }

    private void persistFinalizationOperation(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Map<String, Object> metadata,
            FinalizationOperation operation,
            PolicyWindTunnelRunUpdate update) {
        if (operation == null || metadata.containsKey(FINALIZATION_OPERATION_METADATA)) {
            if (update != null) {
                context.updateRun(runId, update);
            }
            return;
        }
        Map<String, Object> updatedMetadata = new LinkedHashMap<>(metadata);
        updatedMetadata.put(FINALIZATION_OPERATION_METADATA, Map.of(
                "operationId", operation.operationId(),
                "inputDigest", operation.inputDigest()));
        PolicyWindTunnelRunUpdate resolved = update == null
                ? new PolicyWindTunnelRunUpdate(null, null, null, null, updatedMetadata)
                : new PolicyWindTunnelRunUpdate(
                        update.status(),
                        update.summaryPath(),
                        update.bundlePath(),
                        update.completedAtEpochMs(),
                        updatedMetadata);
        context.updateRun(runId, resolved);
    }

    private byte[] canonicalJsonBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(canonicalJsonValue(value));
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("Scenario Lab finalization input is not valid JSON", failure);
        }
    }

    private Object canonicalJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonicalJsonValue(nested)));
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            iterable.forEach(nested -> values.add(canonicalJsonValue(nested)));
            return values;
        }
        return value;
    }

    private record FinalizationOperation(String operationId, String inputDigest) {
    }

    private boolean clockDrivenRunCanFinalize(
            PolicyWindTunnelExecutionContext context,
            String runId,
            BankingPolicyScenarioLabRunMetadata metadata) {
        return context.findRun(runId)
                .map(storedRun -> lifecycleTransitionAllows(storedRun, TRANSITION_FINALIZE_START)
                        && (!STATUS_PAUSED.equalsIgnoreCase(stringValue(storedRun.status()))
                        || PHASE_CLOCK_RUNNING.equalsIgnoreCase(metadata.pausedFromPhase())))
                .orElse(false);
    }

    private Map<String, Object> finalizedHybridArtifactPaths(Path workspaceRoot, Path summaryDir, String runId) {
        return linkedMap(
                "summary", relativizeToWorkspace(workspaceRoot, summaryDir.resolve("scenario-lab-summary.json")),
                "impactedCohortsCsv", relativizeToWorkspace(workspaceRoot, summaryDir.resolve("impacted-cohorts.csv")),
                "experimentCard", relativizeToWorkspace(workspaceRoot, summaryDir.resolve("experiment-card.md")),
                "comparisonReport", relativizeToWorkspace(workspaceRoot, summaryDir.resolve("decision-comparison.json")),
                "checkpointsJson", relativizeToWorkspace(workspaceRoot, summaryDir.resolve("checkpoints.json")),
                "agentDiffsJson", relativizeToWorkspace(workspaceRoot, summaryDir.resolve("agent-diffs.json")),
                "notableActionsJson", relativizeToWorkspace(workspaceRoot, summaryDir.resolve("notable-actions.json")),
                "scheduledWorkFailuresJson",
                relativizeToWorkspace(workspaceRoot, summaryDir.resolve("scheduled-work-failures.json")),
                "simulationTimelineJson",
                relativizeToWorkspace(workspaceRoot, summaryDir.resolve("simulation-timeline.json")),
                "sideEffectLedgerJson",
                relativizeToWorkspace(workspaceRoot, summaryDir.resolve("side-effect-ledger.json")),
                "domainOutcomeScoreJson",
                relativizeToWorkspace(workspaceRoot, summaryDir.resolve("domain-outcome-score.json")),
                "domainOutcomeScoresJson",
                relativizeToWorkspace(workspaceRoot, summaryDir.resolve("domain-outcome-scores.json")),
                "branchStateProjectionJson",
                relativizeToWorkspace(workspaceRoot, summaryDir.resolve("branch-state-projection.json")),
                "hybridAgentAuditJson",
                relativizeToWorkspace(workspaceRoot, summaryDir.resolve("hybrid-agent-audit.json")),
                "simulationRunEvidence", "output/simulation-runs/" + runId);
    }

    RateChangeProofAssessment assessClockDrivenRateChangeProof(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String presetId,
            Map<String, Object> parameters,
            String opportunityCorrelationCapability) {
        return assessClockDrivenRateChangeProof(
                context,
                runId,
                presetId,
                parameters,
                opportunityCorrelationCapability,
                null);
    }

    private RateChangeProofAssessment assessClockDrivenRateChangeProof(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String presetId,
            Map<String, Object> parameters,
            String opportunityCorrelationCapability,
            Map<String, Object> prefetchedCorrelationAudit) {
        String storedPlanDigest = stringValue(parameters.get(
                CLOCK_DRIVEN_INTERVENTION_PLAN_DIGEST_FIELD));
        if (storedPlanDigest.isBlank()) {
            if (BankingClockDrivenRateChangePlan.SUPPORTED_PRESET_ID.equals(presetId)) {
                return new RateChangeProofAssessment(
                        true,
                        false,
                        false,
                        "The rate-change run is missing its launch-pinned intervention plan digest.",
                        linkedMap(
                                "schemaVersion", RATE_CHANGE_PROOF_SCHEMA_VERSION,
                                "status", "UNVERIFIED",
                                "chainIntegrityVerified", false,
                                "realizedReactionVerified", false,
                                "generatedOpportunities", 0,
                                "correlatedDecisions", 0,
                                "realizedCustomerReactions", 0,
                                "technicalValidationDecisions", 0));
            }
            return new RateChangeProofAssessment(
                    false,
                    true,
                    false,
                    "No launch-pinned rate-change plan applies to this run.",
                    linkedMap(
                            "schemaVersion", RATE_CHANGE_PROOF_SCHEMA_VERSION,
                            "status", "NOT_APPLICABLE",
                            "chainIntegrityVerified", true,
                            "realizedReactionVerified", false));
        }
        try {
            BankingClockDrivenRateChangePlan.Snapshot plan =
                    BankingClockDrivenRateChangePlan.compileForLaunch(runId, presetId, parameters)
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Run metadata cannot reproduce the pinned rate-change plan"));
            if (!storedPlanDigest.equals(plan.planDigest())
                    || !BankingClockDrivenRateChangePlan.PLAN_SCHEMA_VERSION.equals(
                            stringValue(parameters.get(CLOCK_DRIVEN_INTERVENTION_PLAN_SCHEMA_FIELD)))) {
                throw new IllegalArgumentException("Run metadata contradicts the pinned rate-change plan");
            }
            BankingClockDrivenRateChangePlan.Snapshot persistedPlan =
                    BankingClockDrivenRateChangePlan.requireSnapshot(Files.readAllBytes(
                            clockDrivenInterventionPlanPath(context, runId)));
            if (!plan.equals(persistedPlan)) {
                throw new IllegalArgumentException("Persisted rate-change plan is contradictory");
            }
            BankingClockDrivenRateChangePlan.InstallationManifest installation =
                    BankingClockDrivenRateChangePlan.requireInstallationManifest(
                            Files.readAllBytes(clockDrivenInterventionInstallationPath(context, runId)),
                            plan);
            String pinnedCohortArtifactDigest = exactProofText(
                    parameters.get("cohortArtifactDigest"), "cohortArtifactDigest");
            String pinnedIdentitySetDigest = exactProofText(
                    parameters.get("cohortAgentIdentitySetDigest"), "cohortAgentIdentitySetDigest");
            long pinnedAgentCount = exactProofLong(
                    parameters.get("cohortTargetSize"), "cohortTargetSize");
            CohortArtifactSnapshot cohortSnapshot = loadCohortArtifactSnapshot(
                    context, runId, parameters);
            Map<String, Integer> pinnedEligibility =
                    BankingClockDrivenRateChangePlan.eligibleAccountCountsFromCohortArtifact(
                            cohortSnapshot.content());
            Map<String, Integer> installedEligibility = installation.customers().stream()
                    .collect(Collectors.toMap(
                            BankingClockDrivenRateChangePlan.InstallationCustomer::cohortAgentId,
                            BankingClockDrivenRateChangePlan.InstallationCustomer::eligibleAccountCount,
                            (left, right) -> {
                                throw new IllegalArgumentException(
                                        "Duplicate installed cohort identity");
                            },
                            TreeMap::new));
            if (!installation.cohortArtifactDigest().equals(pinnedCohortArtifactDigest)
                    || !installation.agentIdentitySetDigest().equals(pinnedIdentitySetDigest)
                    || installation.installedAgentCount() != pinnedAgentCount
                    || !pinnedEligibility.equals(installedEligibility)
                    || !BankingClockDrivenRateChangePlan.agentIdentitySetDigest(
                                    List.copyOf(pinnedEligibility.keySet()))
                            .equals(pinnedIdentitySetDigest)) {
                throw new IllegalArgumentException(
                        "Rate-change installation manifest contradicts the launch-pinned cohort");
            }
            Map<String, Object> correlationAudit = prefetchedCorrelationAudit != null
                    ? prefetchedCorrelationAudit
                    : fetchDecisionOpportunityCorrelationAudit(
                            context,
                            runId,
                            RATE_CHANGE_TRIGGER_TYPE,
                            opportunityCorrelationCapability);
            return verifyClockDrivenRateChangeProof(
                    plan,
                    installation,
                    correlationAudit,
                    agentDecisionMode(parameters));
        } catch (OpportunityCorrelationAuditUnavailableException unavailableAudit) {
            throw unavailableAudit;
        } catch (Exception invalidProof) {
            log.warn("Clock-driven rate-change proof is incomplete for run {}: {}",
                    runId,
                    invalidProof.getMessage());
            return new RateChangeProofAssessment(
                    true,
                    false,
                    false,
                    "The launch-pinned rate-change mutation, opportunity, and decision chain was not fully verified.",
                    linkedMap(
                            "schemaVersion", RATE_CHANGE_PROOF_SCHEMA_VERSION,
                            "status", "UNVERIFIED",
                            "chainIntegrityVerified", false,
                            "realizedReactionVerified", false,
                            "planDigest", storedPlanDigest,
                            "generatedOpportunities", 0,
                            "correlatedDecisions", 0,
                            "realizedCustomerReactions", 0,
                            "technicalValidationDecisions", 0));
        }
    }

    RateChangeProofAssessment verifyClockDrivenRateChangeProof(
            BankingClockDrivenRateChangePlan.Snapshot plan,
            BankingClockDrivenRateChangePlan.InstallationManifest installation,
            Map<String, Object> audit,
            String decisionMode) {
        if (!audit.keySet().equals(OPPORTUNITY_CORRELATION_AUDIT_FIELDS)) {
            throw new IllegalArgumentException("Opportunity correlation audit shape is invalid");
        }
        exactProofText(audit.get("asOf"), "asOf");
        String runId = exactProofText(audit.get("simulationRunId"), "simulationRunId");
        String triggerType = exactProofText(audit.get("triggerType"), "triggerType");
        if (!OPPORTUNITY_CORRELATION_AUDIT_SCHEMA_VERSION.equals(
                        exactProofText(audit.get("schemaVersion"), "schemaVersion"))
                || !runId.equals(plan.plan().get("simulationRunId"))
                || !RATE_CHANGE_TRIGGER_TYPE.equals(triggerType)) {
            throw new IllegalArgumentException("Opportunity correlation audit ownership is invalid");
        }
        List<Map<String, Object>> opportunities = exactProofObjectList(
                audit.get("opportunities"),
                "opportunities");
        if (exactProofInteger(audit.get("opportunityCount"), "opportunityCount")
                != opportunities.size()) {
            throw new IllegalArgumentException("Opportunity correlation audit count is inconsistent");
        }

        Map<String, BankingClockDrivenRateChangePlan.InstallationCustomer> expectedCustomers =
                installation.customers().stream().collect(Collectors.toMap(
                        BankingClockDrivenRateChangePlan.InstallationCustomer::opportunityAgentId,
                        customer -> customer,
                        (left, right) -> {
                            throw new IllegalArgumentException("Duplicate installed customer identity");
                        },
                        TreeMap::new));
        Set<String> observedCustomers = new HashSet<>();
        Set<String> opportunityIds = new HashSet<>();
        int terminalOpportunities = 0;
        int correlatedDecisions = 0;
        String mutationOperationId = exactProofText(
                plan.plan().get("mutationOperationId"),
                "mutationOperationId");
        long scheduledForEpochMs = exactProofLong(
                plan.plan().get("scheduledForEpochMs"),
                "scheduledForEpochMs");
        for (Map<String, Object> opportunity : opportunities) {
            if (!opportunity.keySet().equals(OPPORTUNITY_CORRELATION_ROW_FIELDS)) {
                throw new IllegalArgumentException("Opportunity correlation row shape is invalid");
            }
            String opportunityId = exactProofUuid(opportunity.get("opportunityId"), "opportunityId");
            String opportunityAgentId = exactProofText(opportunity.get("agentId"), "agentId");
            BankingClockDrivenRateChangePlan.InstallationCustomer installed =
                    expectedCustomers.get(opportunityAgentId);
            if (installed == null
                    || installed.eligibleAccountCount() <= 0
                    || !observedCustomers.add(opportunityAgentId)
                    || !opportunityIds.add(opportunityId)) {
                throw new IllegalArgumentException(
                        "Rate-change opportunity ownership is missing, duplicated, or ineligible");
            }
            String triggerRef = mutationOperationId + "|customer:"
                    + installed.cohortAgentId();
            long opportunityScheduledForEpochMs = exactProofLong(
                    opportunity.get("scheduledForEpochMs"), "scheduledForEpochMs");
            if (!BankingPolicyWindTunnelManifest.DOMAIN_ID.equals(
                            exactProofText(opportunity.get("domainId"), "domainId"))
                    || !RATE_CHANGE_TRIGGER_TYPE.equals(
                            exactProofText(opportunity.get("triggerType"), "triggerType"))
                    || !(RATE_CHANGE_TRIGGER_TYPE + ":" + triggerRef).equals(
                            exactProofText(opportunity.get("triggerId"), "triggerId"))
                    || !triggerRef.equals(exactProofText(opportunity.get("triggerRef"), "triggerRef"))
                    || opportunityScheduledForEpochMs < scheduledForEpochMs
                    || !"NORMAL".equals(exactProofText(opportunity.get("priority"), "priority"))
                    || !"Rate or policy changed".equals(
                            exactProofText(opportunity.get("reason"), "reason"))
                    || !exactProofStringList(opportunity.get("evidenceRefs"), "evidenceRefs")
                            .equals(List.of(triggerRef))
                    || !exactProofText(opportunity.get("triggerDigest"), "triggerDigest")
                            .matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Rate-change trigger correlation is contradictory");
            }
            verifyRateChangeTriggerPayload(
                    exactProofObject(opportunity.get("payload"), "payload"),
                    plan,
                    installed.cohortAgentId(),
                    installed.eligibleAccountCount(),
                    triggerRef,
                    opportunityScheduledForEpochMs);

            int attemptCount = exactProofInteger(opportunity.get("attemptCount"), "attemptCount");
            String status = exactProofText(opportunity.get("status"), "status");
            if ("SUCCEEDED".equals(status)) {
                terminalOpportunities += 1;
            }
            if (!"SUCCEEDED".equals(status)
                    || attemptCount < 1
                    || !decisionMode.equals(exactProofText(opportunity.get("decisionMode"), "decisionMode"))
                    || !Boolean.TRUE.equals(opportunity.get("decisionContractValid"))
                    || exactProofInteger(opportunity.get("decisionAttempt"), "decisionAttempt")
                            != attemptCount
                    || exactProofUuid(opportunity.get("decisionEventId"), "decisionEventId").isBlank()) {
                throw new IllegalArgumentException(
                        "Rate-change opportunity does not have fenced valid decision evidence");
            }
            correlatedDecisions += 1;
        }

        long expectedEligibleCustomers = installation.customers().stream()
                .filter(customer -> customer.eligibleAccountCount() > 0)
                .count();
        long expectedAffectedAccounts = installation.customers().stream()
                .mapToLong(BankingClockDrivenRateChangePlan.InstallationCustomer::eligibleAccountCount)
                .sum();
        if (expectedEligibleCustomers <= 0
                || expectedAffectedAccounts <= 0
                || opportunities.isEmpty()
                || terminalOpportunities <= 0
                || correlatedDecisions <= 0
                || opportunities.size() != expectedEligibleCustomers
                || observedCustomers.size() != expectedEligibleCustomers) {
            throw new IllegalArgumentException(
                    "Rate-change opportunity coverage does not match installed eligibility");
        }
        boolean realLlm = AGENT_DECISION_MODE_REAL_LLM.equals(decisionMode);
        String reactionEvidenceType = realLlm
                ? "REMOTE_AUTHORIZED_REAL_LLM"
                : "TECHNICAL_NO_TOKEN_VALIDATION";
        Map<String, Object> evidence = linkedMap(
                "schemaVersion", RATE_CHANGE_PROOF_SCHEMA_VERSION,
                "status", realLlm ? "VERIFIED" : "TECHNICAL_VALIDATION_ONLY",
                "chainIntegrityVerified", true,
                "realizedReactionVerified", realLlm,
                "planDigest", plan.planDigest(),
                "installationManifestDigest", installation.manifestDigest(),
                "interventionId", plan.plan().get("interventionId"),
                "mutationOperationId", mutationOperationId,
                "scheduledForEpochMs", scheduledForEpochMs,
                "installedCustomers", installation.installedAgentCount(),
                "eligibleCustomers", installation.eligibleCustomerCount(),
                "generatedOpportunities", opportunities.size(),
                "terminalOpportunities", terminalOpportunities,
                "correlatedDecisions", correlatedDecisions,
                "reactionEvidenceType", reactionEvidenceType,
                "realizedCustomerReactions", realLlm ? correlatedDecisions : 0,
                "technicalValidationDecisions", realLlm ? 0 : correlatedDecisions);
        return new RateChangeProofAssessment(
                true,
                true,
                realLlm,
                realLlm
                        ? "Every eligible customer has a fenced Real LLM decision correlated to the committed day-10 rate change."
                        : "Every eligible customer has a fenced no-token decision for technical validation; no realized customer reaction is claimed.",
                evidence);
    }

    private void verifyRateChangeTriggerPayload(
            Map<String, Object> payload,
            BankingClockDrivenRateChangePlan.Snapshot plan,
            String cohortAgentId,
            int affectedAccountCount,
            String triggerRef,
            long opportunityScheduledForEpochMs) {
        if (!payload.keySet().equals(RATE_CHANGE_TRIGGER_PAYLOAD_FIELDS)
                || !"banking.customer-decision-trigger-policy.v1".equals(
                        exactProofText(payload.get("policyVersion"), "policyVersion"))
                || !"canonical_decimal_string.v1".equals(
                        exactProofText(payload.get("numericEncoding"), "numericEncoding"))
                || !RATE_CHANGE_TRIGGER_TYPE.equals(
                        exactProofText(payload.get("normalizedEventType"), "normalizedEventType"))
                || !"remote_domain_rate_changed".equals(
                        exactProofText(payload.get("sourceEventType"), "sourceEventType"))
                || !"remote_domain_rate_changed".equals(
                        exactProofText(payload.get("eventType"), "eventType"))
                || !triggerRef.equals(exactProofText(payload.get("businessEventId"), "businessEventId"))
                || !plan.plan().get("simulationRunId").equals(
                        exactProofText(payload.get("simulationRunId"), "simulationRunId"))
                || !cohortAgentId.equals(exactProofText(payload.get("customerId"), "customerId"))
                || !plan.plan().get("interventionId").equals(
                        exactProofText(payload.get("interventionId"), "interventionId"))
                || !plan.plan().get("mutationOperationId").equals(
                        exactProofText(payload.get("operationId"), "operationId"))
                || !plan.planDigest().equals(exactProofText(payload.get("planDigest"), "planDigest"))
                || !("banking|clock-driven-rate-change|" + plan.plan().get("mutationOperationId"))
                        .equals(exactProofText(payload.get("occurrenceKey"), "occurrenceKey"))
                || !plan.plan().get("workId").equals(exactProofText(payload.get("workId"), "workId"))
                || !plan.plan().get("productCode").equals(
                        exactProofText(payload.get("productCode"), "productCode"))
                || !Long.toString(exactProofLong(plan.plan().get("scheduledForEpochMs"), "scheduledForEpochMs"))
                        .equals(exactProofText(payload.get("scheduledForEpochMs"), "scheduledForEpochMs"))
                || !Long.toString(exactProofLong(plan.plan().get("effectiveAtEpochMs"), "effectiveAtEpochMs"))
                        .equals(exactProofText(payload.get("effectiveAtEpochMs"), "effectiveAtEpochMs"))
                || !Long.toString(opportunityScheduledForEpochMs)
                        .equals(exactProofText(payload.get("appliedAtEpochMs"), "appliedAtEpochMs"))
                || !Integer.toString(affectedAccountCount).equals(
                        exactProofText(payload.get("affectedAccountCount"), "affectedAccountCount"))
                || !plan.plan().get("expectedPreviousRate").equals(
                        exactProofText(payload.get("oldRate"), "oldRate"))
                || !plan.plan().get("newRate").equals(
                        exactProofText(payload.get("newRate"), "newRate"))
                || !"SUCCESS".equals(exactProofText(payload.get("outcome"), "outcome"))) {
            throw new IllegalArgumentException("Rate-change trigger payload is contradictory");
        }
    }

    private Map<String, Object> prefetchRateChangeCorrelationAudit(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Map<String, Object> parameters,
            String opportunityCorrelationCapability) {
        if (stringValue(parameters.get(CLOCK_DRIVEN_INTERVENTION_PLAN_DIGEST_FIELD)).isBlank()) {
            return null;
        }
        return fetchDecisionOpportunityCorrelationAudit(
                context,
                runId,
                RATE_CHANGE_TRIGGER_TYPE,
                opportunityCorrelationCapability);
    }

    private Map<String, Object> fetchDecisionOpportunityCorrelationAudit(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String triggerType,
            String capability) {
        String providerRevision = BankingRemotePolicyWindTunnelService.PROVIDER_REVISION;
        if (capability == null || capability.isBlank() || !capability.equals(capability.trim())) {
            throw new OpportunityCorrelationAuditUnavailableException(
                    "Finalization request does not contain a private opportunity correlation capability");
        }
        String url = context.agentRuntimeUrl()
                + "/api/agents/simulation-runs/"
                + urlEncode(runId)
                + "/decision-opportunities/correlation-audit?triggerType="
                + urlEncode(triggerType)
                + "&domainId="
                + urlEncode(BankingPolicyWindTunnelManifest.DOMAIN_ID)
                + "&providerRevision="
                + urlEncode(providerRevision);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-Astraform-Opportunity-Correlation-Capability", capability);
        byte[] body;
        try {
            body = restTemplate.execute(
                    URI.create(url),
                    HttpMethod.GET,
                    request -> request.getHeaders().putAll(headers),
                    response -> {
                        MediaType contentType = response.getHeaders().getContentType();
                        if (response.getStatusCode() != HttpStatus.OK
                                || !isStrictJsonContentType(contentType)) {
                            throw new OpportunityCorrelationAuditUnavailableException(
                                    "Opportunity correlation audit transport is not an exact JSON proof response");
                        }
                        return readBoundedCorrelationAudit(response.getBody());
                    });
        } catch (HttpStatusCodeException unavailable) {
            throw new OpportunityCorrelationAuditUnavailableException(
                    "Opportunity correlation audit failed with status "
                            + unavailable.getStatusCode().value(),
                    unavailable);
        } catch (ResourceAccessException unavailable) {
            throw new OpportunityCorrelationAuditUnavailableException(
                    "Opportunity correlation audit is unavailable",
                    unavailable);
        }
        if (body == null || body.length == 0) {
            throw new OpportunityCorrelationAuditUnavailableException(
                    "Opportunity correlation audit transport is not an exact JSON proof response");
        }
        try {
            Object decoded = objectMapper.readValue(body, Object.class);
            return exactProofObject(decoded, "correlationAudit");
        } catch (JacksonException invalidJson) {
            throw new OpportunityCorrelationAuditUnavailableException(
                    "Opportunity correlation audit is not strict UTF-8 JSON",
                    invalidJson);
        }
    }

    private static boolean isStrictJsonContentType(MediaType contentType) {
        if (contentType == null
                || !"application".equalsIgnoreCase(contentType.getType())
                || !"json".equalsIgnoreCase(contentType.getSubtype())) {
            return false;
        }
        Map<String, String> parameters = contentType.getParameters();
        if (parameters.isEmpty()) {
            return true;
        }
        return parameters.size() == 1
                && parameters.keySet().stream().allMatch("charset"::equalsIgnoreCase)
                && StandardCharsets.UTF_8.equals(contentType.getCharset());
    }

    private static byte[] readBoundedCorrelationAudit(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            int remainingWithOverflowByte = MAX_CORRELATION_AUDIT_BYTES - output.size() + 1;
            int read = input.read(buffer, 0, Math.min(buffer.length, remainingWithOverflowByte));
            if (read < 0) {
                return output.toByteArray();
            }
            if (output.size() + read > MAX_CORRELATION_AUDIT_BYTES) {
                throw new OpportunityCorrelationAuditUnavailableException(
                        "Opportunity correlation audit exceeds the 4 MiB transport limit");
            }
            output.write(buffer, 0, read);
        }
    }

    static final class OpportunityCorrelationAuditUnavailableException extends RuntimeException {
        OpportunityCorrelationAuditUnavailableException(String message) {
            super(message);
        }

        OpportunityCorrelationAuditUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String exactProofText(Object raw, String field) {
        if (!(raw instanceof String value)
                || value.isBlank()
                || !value.equals(value.trim())
                || value.length() > 512) {
            throw new IllegalArgumentException(field + " must be exact bounded text");
        }
        return value;
    }

    private static String exactProofUuid(Object raw, String field) {
        String value = exactProofText(raw, field);
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException(field + " must be a canonical UUID");
            }
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + " must be a canonical UUID", invalid);
        }
    }

    private static int exactProofInteger(Object raw, String field) {
        long value = exactProofLong(raw, field);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " exceeds the supported integer bound");
        }
        return Math.toIntExact(value);
    }

    private static long exactProofLong(Object raw, String field) {
        if (!(raw instanceof Number number) || raw instanceof Double || raw instanceof Float) {
            throw new IllegalArgumentException(field + " must be an exact I-JSON integer");
        }
        try {
            BigDecimal decimal = new BigDecimal(number.toString());
            long value = decimal.longValueExact();
            if (value < 0 || value > BankingClockDrivenRateChangePlan.MAX_IJSON_INTEGER) {
                throw new ArithmeticException("outside I-JSON range");
            }
            return value;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IllegalArgumentException(field + " must be an exact I-JSON integer", invalid);
        }
    }

    private static Map<String, Object> exactProofObject(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + " keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static List<Map<String, Object>> exactProofObjectList(Object raw, String field) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return list.stream()
                .map(value -> exactProofObject(value, field + "[]"))
                .toList();
    }

    private static List<String> exactProofStringList(Object raw, String field) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return list.stream().map(value -> exactProofText(value, field + "[]")).toList();
    }

    private Map<String, Object> buildClockDrivenHybridFinalizedSummary(
            PolicyWindTunnelExecutionContext context,
            PolicyWindTunnelStoredRun storedRun,
            Map<String, Object> artifactPaths,
            OffsetDateTime finalizedAt) {
        return buildClockDrivenHybridFinalizedSummary(
                context, storedRun, artifactPaths, finalizedAt, "", null);
    }

    private Map<String, Object> buildClockDrivenHybridFinalizedSummary(
            PolicyWindTunnelExecutionContext context,
            PolicyWindTunnelStoredRun storedRun,
            Map<String, Object> artifactPaths,
            OffsetDateTime finalizedAt,
            String opportunityCorrelationCapability,
            Map<String, Object> prefetchedCorrelationAudit) {
        BankingPolicyScenarioLabRunMetadata policyMetadata = readPolicyMetadata(storedRun.metadata());
        Map<String, Object> parameters = objectMap(policyMetadata.parameters());
        String runId = storedRun.runId();
        String presetId = firstNonBlank(stringValue(parameters.get("presetId")), "custom");
        String cohortId = selectedCohortId(parameters);
        int cohortSize = countGeneratedCohortCustomers(
                context,
                cohortId,
                intValue(parameters.get("cohortTargetSize"), 0));
        String productCode = firstNonBlank(stringValue(parameters.get("productCode")), "TS001");
        String policyType = policyTypeForPreset(presetId);
        Map<String, Object> simulationWindow = clockDrivenSimulationWindow(context, runId, parameters);
        Map<String, Object> simulationTimeline = buildFinalizedHybridTimeline(policyMetadata, finalizedAt, simulationWindow);
        Map<String, Object> decisionAudit = waitForRuntimeDecisionAuditToSettle(context, runId);
        Map<String, Object> opportunityReconciliation =
                fetchDecisionOpportunityReconciliation(context, runId);
        OpportunityProofAssessment opportunityProof = assessOpportunityProof(
                decisionAudit,
                runId,
                opportunityReconciliation,
                requestedAgentDecisionMode(parameters));
        boolean opportunityAgentAuditVerified = opportunityProof.agentAuditVerified();
        boolean opportunityAgentAuditRequired = opportunityProof.agentAuditRequired();
        RateChangeProofAssessment rateChangeProof = assessClockDrivenRateChangeProof(
                context,
                runId,
                presetId,
                parameters,
                opportunityCorrelationCapability,
                prefetchedCorrelationAudit);
        boolean partialOpportunityEvidence = opportunityProof.partialEvidence()
                || (rateChangeProof.required() && !rateChangeProof.verified());
        String opportunityProofReason = rateChangeProof.required() && !rateChangeProof.verified()
                ? rateChangeProof.reason()
                : opportunityProof.reason();
        int timelineEventCount = listOfMaps(simulationTimeline.get("events")).size();
        Map<String, Object> simulationRunRecord = fetchSimulationRunRecord(runId);
        Map<String, Object> ledgerCounts = linkedMap(
                "agentCount", intValue(simulationRunRecord.get("agentCount"), cohortSize),
                "eventCount", intValue(simulationRunRecord.get("eventCount"), 0),
                "checkpointCount", intValue(simulationRunRecord.get("checkpointCount"), 0));

        Map<String, Object> sideEffectLedger = linkedMap(
                "schemaVersion", "customer_outcome_side_effect_ledger.v1",
                "mode", "CLOCK_DRIVEN_HYBRID_FINALIZED",
                "entries", List.of(
                        linkedMap(
                                "effectId", "e24-evidence-spine-" + runId,
                                "effectType", "EVIDENCE_CAPTURE",
                                "operation", "E24 run ledger export",
                                "status", "FINALIZED",
                                "evidenceRef", "simulationRunEvidencePack",
                                "note", "Core-mediated runtime activity is durable in experiment-service."),
                        linkedMap(
                                "effectId", "scenario-lab-finalize-" + runId,
                                "effectType", "REPORT_ASSEMBLY",
                                "operation", "Finalize Clock-Driven Simulation report",
                                "status", "COMPLETED",
                                "evidenceRef", "comparisonReport",
                                "note", "This report summarizes captured evidence without inventing unobserved domain metrics.")));
        List<Map<String, Object>> stateMutations = buildClockDrivenHybridStateMutations(context, runId);
        int evidenceSignalCount = intValue(ledgerCounts.get("eventCount"), 0)
                + intValue(ledgerCounts.get("checkpointCount"), 0)
                + timelineEventCount
                + listOfMaps(sideEffectLedger.get("entries")).size()
                + stateMutations.size();
        int rejectedWorkCount = countRejectedCheckpointWork(context, runId, stateMutations);
        List<String> stateMutationIds = stateMutations.stream()
                .map(mutation -> stringValue(mutation.get("mutationId")))
                .filter(id -> !id.isBlank())
                .limit(12)
                .toList();

        Map<String, Object> branchStateProjection = linkedMap(
                "schemaVersion", "customer_outcome_branch_state_projection.v1",
                "branches", List.of(
                        linkedMap(
                                "branchId", "baseline-current-policy",
                                "branchRole", "baseline",
                                "agentCount", cohortSize,
                                "status", "REFERENCE_ONLY"),
                        linkedMap(
                                "branchId", "clock-driven-hybrid-run",
                                "branchRole", "proposed",
                                "agentCount", cohortSize,
                                "status", "OBSERVED")),
                "mutations", stateMutations,
                "metricTraces", List.of(
                        linkedMap(
                                "metricKey", "activatedSyntheticCustomers",
                                "supportLevel", "RUN_LEDGER_LINKED",
                                "sourceMutationIds", stateMutationIds,
                                "evidenceChain", linkedMap(
                                        "sourceMutationIds", stateMutationIds,
                                        "sourceEffectIds", List.of("e24-evidence-spine-" + runId),
                                        "artifactRefs", linkedMap(
                                                "simulationTimelineJson", artifactPaths.get("simulationTimelineJson"),
                                                "simulationRunEvidencePack", "/api/dashboard/simulation-runs/"
                                                        + urlEncode(runId) + "/export"))),
                        linkedMap(
                                "metricKey", "runtimeEvidenceSignals",
                                "supportLevel", "RUN_LEDGER_LINKED",
                                "sourceMutationIds", stateMutationIds,
                                "evidenceChain", linkedMap(
                                        "sourceMutationIds", stateMutationIds,
                                        "sourceEffectIds", List.of(
                                                "e24-evidence-spine-" + runId,
                                                "scenario-lab-finalize-" + runId),
                                        "artifactRefs", linkedMap(
                                                "sideEffectLedgerJson", artifactPaths.get("sideEffectLedgerJson"),
                                                "comparisonReport", artifactPaths.get("comparisonReport"))))));

        Map<String, Object> scoreContextMetrics = linkedMap(
                "cohortSize", cohortSize,
                "timelineEventCount", timelineEventCount,
                "sideEffectCount", listOfMaps(sideEffectLedger.get("entries")).size(),
                "stateChangeCount", stateMutations.size(),
                "evidenceSignalCount", evidenceSignalCount,
                "rejectedWorkCount", rejectedWorkCount);
        Map<String, Object> scoreContextSignals = linkedMap(
                "hasRuntimeEvidence", evidenceSignalCount > 0,
                "hasStateEvidence", true,
                "hasStateChanges", !stateMutations.isEmpty(),
                "hasRejectedWork", rejectedWorkCount > 0);
        List<Map<String, Object>> domainOutcomeScores = evaluateDomainOutcomeScorecards(
                context,
                runId,
                presetId,
                parameters,
                artifactPaths,
                scoreContextMetrics,
                scoreContextSignals);
        Map<String, Object> domainOutcomeScore = primaryDomainOutcomeScore(domainOutcomeScores);
        String scoredRecommendation = scoredRecommendation(domainOutcomeScore);
        String scoredReason = scoredReason(domainOutcomeScore);
        boolean technicalValidationOnly = rateChangeProof.required()
                && rateChangeProof.chainIntegrityVerified()
                && !rateChangeProof.realizedReactionVerified();
        String effectiveRecommendation = partialOpportunityEvidence || technicalValidationOnly
                ? "WATCH"
                : scoredRecommendation;

        Map<String, Object> decisionReadiness = linkedMap(
                "status", partialOpportunityEvidence
                        ? "PARTIAL_EVIDENCE"
                        : technicalValidationOnly
                                ? "TECHNICAL_VALIDATION_ONLY"
                                : "READY_FOR_HUMAN_REVIEW",
                "recommendation", effectiveRecommendation,
                "phase", "COMPLETED",
                "reason", partialOpportunityEvidence
                        ? opportunityProofReason
                        : technicalValidationOnly
                                ? "The no-token run verifies the intervention, opportunity, and decision machinery, but it contains no realized customer reaction evidence."
                                : firstNonBlank(
                                scoredReason,
                                "The clock-driven run has been frozen into a replay-linked outcome report. This is review-ready evidence, not an automatic launch approval."),
                "scorecard", linkedMap(
                        "scorecardId", domainOutcomeScore.get("scorecardId"),
                        "version", domainOutcomeScore.get("version"),
                        "status", domainOutcomeScore.get("status")),
                "scorecards", domainOutcomeScores,
                "nextStep", partialOpportunityEvidence
                        ? "Resolve the opportunity ledger and regenerate a complete per-agent audit before review."
                        : technicalValidationOnly
                                ? "Rerun with an authorized Real LLM worker before using customer reactions or launch readiness as business evidence."
                                : nextStepForRecommendation(scoredRecommendation, domainOutcomeScore));
        Map<String, Object> decisionComparison = linkedMap(
                "schemaVersion", "customer_outcome_decision_comparison.v1",
                "comparisonId", presetId + "-clock-driven-simulation-finalized",
                "scenarioId", scenarioIdForPreset(presetId),
                "title", "Clock-Driven Simulation Outcome Review",
                "decisionQuestion", "What did the selected synthetic customer cohort do under clock-driven execution, and is the evidence ready for a launch decision?",
                "baseline", linkedMap(
                        "branchId", "baseline-current-policy",
                        "status", "REFERENCE_ONLY",
                        "evidenceLinks", linkedMap(
                                "note", "Clock-driven baseline branch scoring is not yet produced in this slice.")),
                "proposed", linkedMap(
                        "branchId", "clock-driven-simulation-run",
                        "status", partialOpportunityEvidence
                                ? "PARTIAL"
                                : technicalValidationOnly
                                        ? "TECHNICAL_VALIDATION_ONLY"
                                        : "FINALIZED",
                        "evidenceLinks", linkedMap(
                                "simulationRunEvidencePack", "/api/dashboard/simulation-runs/"
                                        + urlEncode(runId) + "/export",
                                "simulationTimelineJson", artifactPaths.get("simulationTimelineJson"),
                                "sideEffectLedgerJson", artifactPaths.get("sideEffectLedgerJson"))),
                "metricDeltas", List.of(
                        linkedMap(
                                "metricKey", "activatedSyntheticCustomers",
                                "label", "Activated synthetic customers",
                                "baselineValue", 0,
                                "proposedValue", cohortSize,
                                "deltaValue", cohortSize,
                                "unit", "count",
                                "evidenceRef", "simulationRunEvidencePack"),
                        linkedMap(
                                "metricKey", "runtimeEvidenceSignals",
                                "label", "Runtime evidence signals captured",
                                "baselineValue", 0,
                                "proposedValue", evidenceSignalCount,
                                "deltaValue", evidenceSignalCount,
                                "unit", "count",
                                "evidenceRef", "sideEffectLedgerJson")),
                "replayEvidence", linkedMap(
                        "requiredEvidence", List.of(
                                "customerProfile",
                                "simulationTimeline",
                                "sideEffectLedger",
                                "domainCheckpoints",
                                "simulationRunEvidencePack"),
                        "shared", linkedMap(
                                "simulationTimelineJson", artifactPaths.get("simulationTimelineJson"),
                                "sideEffectLedgerJson", artifactPaths.get("sideEffectLedgerJson"),
                                "branchStateProjectionJson", artifactPaths.get("branchStateProjectionJson"),
                                "comparisonReport", artifactPaths.get("comparisonReport"),
                                "simulationRunEvidencePack", "/api/dashboard/simulation-runs/"
                                        + urlEncode(runId) + "/export")));

        Map<String, Object> evaluator = linkedMap(
                "status", effectiveRecommendation,
                "verdict", effectiveRecommendation,
                "breachCount", scoreGateBreachCount(domainOutcomeScore, cohortSize),
                "failBreaches", scoreGateFailCount(domainOutcomeScore, cohortSize),
                "domainOutcomeScore", domainOutcomeScore,
                "domainOutcomeScores", domainOutcomeScores,
                "decisionGates", linkedMap(
                        "breachedGateCount", scoreGateBreachCount(domainOutcomeScore, cohortSize),
                        "failGateCount", scoreGateFailCount(domainOutcomeScore, cohortSize),
                        "gates", scoreDecisionGates(domainOutcomeScore, cohortSize)));

        Map<String, Object> runConfig = new LinkedHashMap<>(parameters);
        runConfig.put("executionMode", EXECUTION_MODE_CLOCK_DRIVEN_HYBRID);
        runConfig.put("lifecycleState", "COMPLETED");
        runConfig.put("phase", "COMPLETED");
        runConfig.put("cohortSize", cohortSize);
        runConfig.put("cohortId", cohortId);
        runConfig.put("ledgerCounts", ledgerCounts);
        runConfig.put("opportunityReconciliationStatus", opportunityReconciliation.get("status"));
        runConfig.put("opportunityLedgerReconciled", opportunityReconciliation.get("ledgerReconciled"));
        runConfig.put("opportunityRemainingBacklog", opportunityReconciliation.get("remainingBacklog"));
        runConfig.put("opportunityAgentAuditVerified", opportunityAgentAuditVerified);
        runConfig.put("opportunityAgentAuditRequired", opportunityAgentAuditRequired);
        runConfig.put(
                "clockDrivenInterventionProofVerified",
                rateChangeProof.realizedReactionVerified());
        runConfig.put("clockDrivenInterventionChainIntegrityVerified",
                rateChangeProof.chainIntegrityVerified());
        runConfig.put("clockDrivenInterventionRealizedReactionVerified",
                rateChangeProof.realizedReactionVerified());
        runConfig.putAll(simulationWindow);
        runConfig.put("finalizedAt", finalizedAt.toString());

        Map<String, Object> policyToggle = linkedMap(
                "productCode", productCode,
                "productName", productNameFor(productCode),
                "policyType", policyType,
                "baselineRate", parameters.get("baselineRate"),
                "candidateRate", parameters.get("candidateRate"));
        Map<String, Object> artifactDownloads = liveHybridArtifactDownloads(runId);
        Map<String, Object> agenticSimulation = buildAgenticSimulationSummary(
                parameters,
                cohortSize,
                decisionAudit,
                artifactDownloads,
                simulationWindow,
                runId,
                opportunityReconciliation,
                true);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", BankingPolicyWindTunnelManifest.SUMMARY_SCHEMA_VERSION);
        summary.put("summaryMode", "CLOCK_DRIVEN_HYBRID_FINALIZED");
        summary.put("runId", runId);
        summary.put("scenarioId", scenarioIdForPreset(presetId));
        summary.put("demoPreset", Map.of("presetId", presetId));
        summary.put("decisionReadiness", decisionReadiness);
        summary.put("decisionComparison", decisionComparison);
        summary.put("simulationTimeline", simulationTimeline);
        summary.put("sideEffectLedger", sideEffectLedger);
        summary.put("branchStateProjection", branchStateProjection);
        summary.put("domainOutcomeScore", domainOutcomeScore);
        summary.put("domainOutcomeScores", domainOutcomeScores);
        summary.put("runConfig", runConfig);
        summary.put("policyToggle", policyToggle);
        summary.put("hybridDecisionAuditSummary", decisionAudit.get("summary"));
        summary.put("hybridAgentAudit", decisionAudit);
        summary.put("agenticSimulation", agenticSimulation);
        summary.put("opportunityReconciliation", opportunityReconciliation);
        summary.put("clockDrivenInterventionProof", rateChangeProof.evidence());
        summary.put("checkpointSummaries", buildCheckpointSummaries(stateMutations));
        summary.put("headline", linkedMap(
                "title", "Clock-Driven Simulation Outcome Review",
                "summary", partialOpportunityEvidence
                        ? "The simulation horizon completed, but customer decision opportunity evidence remains partial."
                        : technicalValidationOnly
                                ? "The no-token run verified the day-10 intervention chain, but it did not observe realized customer reactions and is not launch-ready business evidence."
                                : "The selected synthetic customer cohort ran under simulator-clock control and was finalized into replay-linked evidence for human review.",
                "verdict", effectiveRecommendation,
                "simulatedSubjects", cohortSize,
                "impactedAgents", 0,
                "phase", "COMPLETED"));
        summary.put("candidate", linkedMap(
                "activeAgents", cohortSize,
                "impactedAgents", 0,
                "exposureValue", cohortSize,
                "exposureUnit", "count",
                "evidenceState", partialOpportunityEvidence
                        ? "PARTIAL"
                        : technicalValidationOnly
                                ? "TECHNICAL_VALIDATION_ONLY"
                                : "FINALIZED"));
        summary.put("delta", linkedMap(
                "primaryOutcomeDelta", evidenceSignalCount,
                "primaryOutcomeUnit", "count",
                "counterMetricDelta", 0,
                "counterMetricUnit", "count"));
        summary.put("evaluator", evaluator);
        summary.put("exposureSummary", linkedMap(
                "scopeLabel", cohortId.isBlank() ? "Generated customer cohort" : cohortId,
                "cohortAgents", cohortSize,
                "eligibleAgents", cohortSize,
                "nonEligibleAgents", 0,
                "impactedAgents", 0,
                "exposureValue", cohortSize,
                "exposureUnit", "count"));
        summary.put("artifacts", artifactPaths);
        summary.put("artifactDownloads", linkedMap(
                "simulationRunEvidence", "/api/dashboard/simulation-runs/" + urlEncode(runId) + "/export",
                "domainOutcomeScoreJson", "/api/dashboard/scenario-lab/artifact?runId="
                        + urlEncode(runId) + "&type=domain-outcome-score-json",
                "domainOutcomeScoresJson", "/api/dashboard/scenario-lab/artifact?runId="
                        + urlEncode(runId) + "&type=domain-outcome-scores-json",
                "hybridAgentAuditJson", "/api/dashboard/scenario-lab/artifact?runId="
                        + urlEncode(runId) + "&type=hybrid-agent-audit-json"));
        List<String> limitations = new ArrayList<>(List.of(
                "This finalized clock-driven report summarizes captured platform evidence; it does not claim to predict production outcomes.",
                "The platform captures core-mediated activity. Domain-internal external calls require domain-emitted evidence events.",
                "The domain-owned scorecard converts captured evidence into review guidance; it is still not an automatic production approval."));
        if (partialOpportunityEvidence) {
            if (rateChangeProof.required() && !rateChangeProof.verified()) {
                limitations.add(
                        "The day-10 rate-change mutation, customer trigger, opportunity, and fenced decision chain was not fully verified; no business reaction claim is permitted.");
            } else {
                limitations.add(opportunityAgentAuditVerified
                        ? "The simulation horizon completed before the customer decision opportunity ledger was fully reconciled; backlog and evidence gaps remain explicit in opportunityReconciliation."
                        : "The run-level opportunity ledger and durable per-agent audit were not jointly verified; this report is partial and cannot support human review readiness.");
            }
        }
        if (technicalValidationOnly) {
            limitations.add(
                    "No-token decisions validate execution plumbing only. They do not prove realized customer behavior and cannot support a LAUNCH recommendation.");
        }
        summary.put("limitations", List.copyOf(limitations));
        return summary;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchDecisionOpportunityReconciliation(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        try {
            String reconciliationBaseUrl = context.agentRuntimeUrl()
                    + "/api/agents/simulation-runs/"
                    + urlEncode(runId)
                    + "/decision-opportunities";
            Map<String, Object> response = restTemplate.getForObject(
                    reconciliationBaseUrl + "/reconciliation",
                    Map.class);
            if (!opportunityReconciliationIsValid(response, runId)) {
                return unavailableOpportunityReconciliation(runId);
            }
            return new LinkedHashMap<>(response);
        } catch (Exception error) {
            log.warn(
                    "Customer decision opportunity reconciliation unavailable for run {} ({})",
                    runId,
                    error.getClass().getSimpleName());
            return unavailableOpportunityReconciliation(runId);
        }
    }

    private static Map<String, Object> unavailableOpportunityReconciliation(String runId) {
        return linkedMap(
                "schemaVersion", OPPORTUNITY_RECONCILIATION_SCHEMA_VERSION,
                "simulationRunId", runId,
                "status", "UNAVAILABLE",
                "ledgerReconciled", false,
                "generationMode", null,
                "generationSealed", false,
                "observedWorkWindows", 0,
                "generationObservedAt", null,
                "generationSealedAt", null,
                "generationSealedReplayContractVersion", null,
                "generationSealedReplayFenceDigest", null,
                "generationSealedReplayFenceLeaseOwner", null,
                "generationSealedReplayFenceExpiresAtEpochMs", null,
                "initializationManifestStatus", null,
                "expectedInitializationAgents", null,
                "registeredInitializationAgents", null,
                "resolvedInitializationAgents", null,
                "tickFailureCount", null,
                "tickFailureLedgerCount", null,
                "expectedGeneratedOpportunities", null,
                "rejectedTriggers", null,
                "migrationAnomalies", null,
                "generatedOpportunities", null,
                "terminalOpportunities", null,
                "remainingBacklog", null,
                "validDecisions", null,
                "recoverableRecordedDecisions", null,
                "succeededWithoutDecisionEvidence", null,
                "decisionEvidenceGaps", null,
                "statusCounts", Map.of());
    }

    private static boolean opportunityReconciliationIsPartial(Map<String, Object> reconciliation) {
        return opportunityReconciliationIsPartial(reconciliation, true);
    }

    private static boolean opportunityReconciliationIsPartial(
            Map<String, Object> reconciliation,
            boolean agentDecisionEvidenceRequired) {
        String runId = reconciliation != null
                ? stringValue(reconciliation.get("simulationRunId"))
                : null;
        boolean valid = opportunityReconciliationIsValid(reconciliation, runId);
        return !valid
                || !booleanValue(reconciliation.get("ledgerReconciled"), false)
                || longValue(reconciliation.get("remainingBacklog"), 0L) > 0L
                || longValue(reconciliation.get("decisionEvidenceGaps"), 0L) > 0L
                || longValue(reconciliation.get("succeededWithoutDecisionEvidence"), 0L) > 0L
                || (agentDecisionEvidenceRequired
                        && valid
                        && booleanValue(reconciliation.get("generationSealed"), false)
                        && (longValue(reconciliation.get("generatedOpportunities"), 0L) == 0L
                                || longValue(reconciliation.get("validDecisions"), 0L) == 0L));
    }

    static boolean opportunityReconciliationIsValid(
            Map<String, Object> reconciliation,
            String expectedRunId) {
        if (reconciliation == null
                || expectedRunId == null
                || !OPPORTUNITY_RECONCILIATION_SCHEMA_VERSION.equals(
                        stringValue(reconciliation.get("schemaVersion")))
                || !expectedRunId.equals(stringValue(reconciliation.get("simulationRunId")))) {
            return false;
        }
        String status = stringValue(reconciliation.get("status"));
        if (!OPPORTUNITY_RECONCILIATION_STATUSES.contains(status)
                || !(reconciliation.get("ledgerReconciled") instanceof Boolean ledgerReconciled)
                || !(reconciliation.get("generationSealed") instanceof Boolean generationSealed)) {
            return false;
        }
        Long observedWindows = strictNonNegativeLong(reconciliation.get("observedWorkWindows"));
        Long expectedInitializationAgents =
                strictNonNegativeLong(reconciliation.get("expectedInitializationAgents"));
        Long registeredInitializationAgents =
                strictNonNegativeLong(reconciliation.get("registeredInitializationAgents"));
        Long resolvedInitializationAgents =
                strictNonNegativeLong(reconciliation.get("resolvedInitializationAgents"));
        Long tickFailureCount = strictNonNegativeLong(reconciliation.get("tickFailureCount"));
        Long tickFailureLedgerCount = strictNonNegativeLong(
                reconciliation.get("tickFailureLedgerCount"));
        Long expectedGenerated = strictNonNegativeLong(reconciliation.get("expectedGeneratedOpportunities"));
        Long rejectedTriggers = strictNonNegativeLong(reconciliation.get("rejectedTriggers"));
        Long migrationAnomalies = strictNonNegativeLong(reconciliation.get("migrationAnomalies"));
        Long generated = strictNonNegativeLong(reconciliation.get("generatedOpportunities"));
        Long terminal = strictNonNegativeLong(reconciliation.get("terminalOpportunities"));
        Long backlog = strictNonNegativeLong(reconciliation.get("remainingBacklog"));
        Long validDecisions = strictNonNegativeLong(reconciliation.get("validDecisions"));
        Long recoverable = strictNonNegativeLong(reconciliation.get("recoverableRecordedDecisions"));
        Long succeededWithoutEvidence =
                strictNonNegativeLong(reconciliation.get("succeededWithoutDecisionEvidence"));
        Long evidenceGaps = strictNonNegativeLong(reconciliation.get("decisionEvidenceGaps"));
        if (observedWindows == null
                || expectedInitializationAgents == null
                || registeredInitializationAgents == null
                || resolvedInitializationAgents == null
                || tickFailureCount == null
                || tickFailureLedgerCount == null
                || expectedGenerated == null
                || rejectedTriggers == null
                || migrationAnomalies == null
                || generated == null
                || terminal == null
                || backlog == null
                || validDecisions == null
                || recoverable == null
                || succeededWithoutEvidence == null
                || evidenceGaps == null
                || !strictInstant(reconciliation.get("asOf"), false)) {
            return false;
        }
        Object rawStatusCounts = reconciliation.get("statusCounts");
        if (!(rawStatusCounts instanceof Map<?, ?> statusCounts)
                || !statusCounts.keySet().stream().map(String::valueOf).collect(Collectors.toSet())
                        .equals(OPPORTUNITY_LIFECYCLE_STATUSES)) {
            return false;
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String lifecycleStatus : OPPORTUNITY_LIFECYCLE_STATUSES) {
            Long count = strictNonNegativeLong(statusCounts.get(lifecycleStatus));
            if (count == null) {
                return false;
            }
            counts.put(lifecycleStatus, count);
        }
        try {
            long statusTotal = 0L;
            for (long count : counts.values()) {
                statusTotal = Math.addExact(statusTotal, count);
            }
            long expectedBacklog = Math.addExact(
                    Math.addExact(counts.get("PENDING"), counts.get("RUNNING")),
                    counts.get("DEFERRED"));
            long expectedTerminal = Math.subtractExact(statusTotal, expectedBacklog);
            long expectedValidDecisions = Math.subtractExact(
                    counts.get("SUCCEEDED"), succeededWithoutEvidence);
            long expectedEvidenceGaps = Math.addExact(
                    Math.addExact(
                            Math.addExact(counts.get("BLOCKED"), counts.get("FAILED")),
                            counts.get("CANCELLED")),
                    succeededWithoutEvidence);
            if (generated != statusTotal
                    || backlog != expectedBacklog
                    || terminal != expectedTerminal
                    || succeededWithoutEvidence > counts.get("SUCCEEDED")
                    || validDecisions != expectedValidDecisions
                    || recoverable > backlog
                    || evidenceGaps != expectedEvidenceGaps) {
                return false;
            }
        } catch (ArithmeticException invalidCount) {
            return false;
        }

        String generationMode = stringValue(reconciliation.get("generationMode"));
        String initializationManifestStatus =
                stringValue(reconciliation.get("initializationManifestStatus"));
        boolean observedAtPresent = strictInstant(reconciliation.get("generationObservedAt"), false);
        boolean sealedAtPresent = strictInstant(reconciliation.get("generationSealedAt"), false);
        Long sealedReplayContractVersion = strictNonNegativeLong(
                reconciliation.get("generationSealedReplayContractVersion"));
        String sealedFenceDigest = stringValue(
                reconciliation.get("generationSealedReplayFenceDigest"));
        String sealedFenceLeaseOwner = stringValue(
                reconciliation.get("generationSealedReplayFenceLeaseOwner"));
        Long sealedFenceExpiresAt = strictNonNegativeLong(
                reconciliation.get("generationSealedReplayFenceExpiresAtEpochMs"));
        boolean unknown = "UNKNOWN_RUN".equals(status);
        if (unknown) {
            return !ledgerReconciled
                    && !generationSealed
                    && observedWindows == 0L
                    && expectedGenerated == 0L
                    && rejectedTriggers == 0L
                    && migrationAnomalies == 0L
                    && generated == 0L
                    && initializationManifestStatus.isEmpty()
                    && expectedInitializationAgents == 0L
                    && registeredInitializationAgents == 0L
                    && resolvedInitializationAgents == 0L
                    && tickFailureCount == 0L
                    && tickFailureLedgerCount == 0L
                    && generationMode.isEmpty()
                    && reconciliation.get("generationObservedAt") == null
                    && reconciliation.get("generationSealedAt") == null
                    && reconciliation.get("generationSealedReplayContractVersion") == null
                    && reconciliation.get("generationSealedReplayFenceDigest") == null
                    && reconciliation.get("generationSealedReplayFenceLeaseOwner") == null
                    && reconciliation.get("generationSealedReplayFenceExpiresAtEpochMs") == null;
        }
        if (!OPPORTUNITY_GENERATION_MODE.equals(generationMode) || !observedAtPresent) {
            return false;
        }
        boolean sealProofAbsent = !generationSealed
                && reconciliation.get("generationSealedAt") == null
                && reconciliation.get("generationSealedReplayContractVersion") == null
                && reconciliation.get("generationSealedReplayFenceDigest") == null
                && reconciliation.get("generationSealedReplayFenceLeaseOwner") == null
                && reconciliation.get("generationSealedReplayFenceExpiresAtEpochMs") == null;
        boolean sealProofPresent = generationSealed
                && sealedAtPresent
                && sealedReplayContractVersion != null
                && sealedReplayContractVersion > 0L
                && sealedFenceDigest.matches("[0-9a-f]{64}")
                && !sealedFenceLeaseOwner.isBlank()
                && sealedFenceExpiresAt != null
                && sealedFenceExpiresAt > 0L;
        boolean closedInitializationComplete = "CLOSED".equals(initializationManifestStatus)
                && expectedInitializationAgents > 0L
                && registeredInitializationAgents.equals(expectedInitializationAgents)
                && resolvedInitializationAgents.equals(expectedInitializationAgents);
        boolean tickFailureLedgerConsistent = tickFailureCount.equals(tickFailureLedgerCount);
        if ("TICK_FAILURE_LEDGER_INCONSISTENT".equals(status)) {
            return !ledgerReconciled
                    && !tickFailureLedgerConsistent
                    && (generationSealed ? sealProofPresent : sealProofAbsent);
        }
        if (!tickFailureLedgerConsistent) {
            return false;
        }
        if (generationSealed
                && "INITIALIZATION_MANIFEST_UNVERIFIED".equals(status)
                && "LEGACY_UNVERIFIED".equals(initializationManifestStatus)) {
            return !ledgerReconciled
                    && expectedInitializationAgents == 0L
                    && registeredInitializationAgents == 0L
                    && resolvedInitializationAgents == 0L
                    && tickFailureCount == 0L
                    && sealProofPresent;
        }
        if (!generationSealed) {
            if (!sealProofAbsent || ledgerReconciled) {
                return false;
            }
            return switch (status) {
                case "GENERATION_FAILED" -> switch (initializationManifestStatus) {
                    case "UNDECLARED", "LEGACY_UNVERIFIED" -> expectedInitializationAgents == 0L
                            && registeredInitializationAgents == 0L
                            && resolvedInitializationAgents == 0L;
                    case "OPEN", "CLOSED" -> expectedInitializationAgents > 0L
                            && resolvedInitializationAgents <= registeredInitializationAgents;
                    default -> false;
                };
                case "INITIALIZATION_MANIFEST_UNVERIFIED" ->
                    ("UNDECLARED".equals(initializationManifestStatus)
                                    || "LEGACY_UNVERIFIED".equals(initializationManifestStatus))
                            && expectedInitializationAgents == 0L
                            && registeredInitializationAgents == 0L
                            && resolvedInitializationAgents == 0L
                            && tickFailureCount == 0L;
                case "INITIALIZATION_MANIFEST_OPEN" -> "OPEN".equals(initializationManifestStatus)
                        && expectedInitializationAgents > 0L
                        && registeredInitializationAgents < expectedInitializationAgents
                        && resolvedInitializationAgents == 0L
                        && tickFailureCount == 0L;
                case "INITIALIZATION_MANIFEST_INCONSISTENT" -> "CLOSED".equals(initializationManifestStatus)
                        && expectedInitializationAgents > 0L
                        && resolvedInitializationAgents <= registeredInitializationAgents;
                case "INITIALIZATION_PENDING" -> "CLOSED".equals(initializationManifestStatus)
                        && expectedInitializationAgents > 0L
                        && registeredInitializationAgents.equals(expectedInitializationAgents)
                        && resolvedInitializationAgents < expectedInitializationAgents
                        && tickFailureCount == 0L;
                case "TICK_FAILURE_DEBT" -> closedInitializationComplete
                        && tickFailureCount > 0L;
                case "GENERATION_OPEN" -> closedInitializationComplete && tickFailureCount == 0L;
                default -> false;
            };
        }
        if (!closedInitializationComplete
                || tickFailureCount != 0L
                || !sealProofPresent) {
            return false;
        }
        boolean inconsistentGeneration = !expectedGenerated.equals(generated)
                || rejectedTriggers > 0L
                || migrationAnomalies > 0L;
        return switch (status) {
            case "EMPTY" -> ledgerReconciled
                    && generated == 0L
                    && !inconsistentGeneration
                    && succeededWithoutEvidence == 0L;
            case "DRAINING" -> !ledgerReconciled
                    && backlog > 0L
                    && !inconsistentGeneration
                    && succeededWithoutEvidence == 0L;
            case "INCONSISTENT" -> !ledgerReconciled
                    && (inconsistentGeneration || succeededWithoutEvidence > 0L);
            case "RECONCILED" -> ledgerReconciled
                    && generated > 0L
                    && backlog == 0L
                    && !inconsistentGeneration
                    && succeededWithoutEvidence == 0L;
            default -> false;
        };
    }

    private static Long strictNonNegativeLong(Object value) {
        if (!(value instanceof Number)) {
            return null;
        }
        try {
            long parsed = new BigDecimal(String.valueOf(value)).longValueExact();
            return parsed >= 0L ? parsed : null;
        } catch (ArithmeticException | NumberFormatException invalid) {
            return null;
        }
    }

    private static boolean strictInstant(Object value, boolean nullable) {
        if (value == null) {
            return nullable;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return false;
        }
        try {
            Instant.parse(text);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String opportunityReconciliationReason(Map<String, Object> reconciliation) {
        String status = firstNonBlank(stringValue(reconciliation.get("status")), "UNAVAILABLE");
        if ("UNAVAILABLE".equalsIgnoreCase(status)) {
            return "The simulation horizon completed, but the customer decision opportunity ledger could not be verified.";
        }
        long generated = longValue(reconciliation.get("generatedOpportunities"), 0L);
        long validDecisions = longValue(reconciliation.get("validDecisions"), 0L);
        if (booleanValue(reconciliation.get("generationSealed"), false)
                && (generated == 0L || validDecisions == 0L)) {
            return "The finalized durable opportunity ledger generated " + generated
                    + " opportunity/opportunities and reconciled " + validDecisions
                    + " valid decision(s), so this run cannot support human review readiness.";
        }
        long backlog = longValue(reconciliation.get("remainingBacklog"), 0L);
        long gaps = longValue(reconciliation.get("decisionEvidenceGaps"), 0L);
        long rejectedTriggers = longValue(reconciliation.get("rejectedTriggers"), 0L);
        long migrationAnomalies = longValue(reconciliation.get("migrationAnomalies"), 0L);
        return "The simulation horizon completed, but opportunity reconciliation is "
                + status
                + " with "
                + backlog
                + " remaining and "
                + gaps
                + " explicit evidence gap(s) and "
                + rejectedTriggers
                + " rejected trigger(s) and "
                + migrationAnomalies
                + " migration anomaly/anomalies.";
    }

    private static String opportunityProofReason(
            Map<String, Object> reconciliation,
            boolean opportunityAgentAuditVerified,
            boolean agentDecisionEvidenceRequired,
            boolean decisionModeEvidenceConflict,
            boolean evidenceModelSplit,
            long legacyCadenceDecisions,
            long durableValidDecisions) {
        String reconciliationReason = opportunityReconciliationIsPartial(
                reconciliation,
                agentDecisionEvidenceRequired)
                ? opportunityReconciliationReason(reconciliation)
                : "";
        String splitReason = evidenceModelSplit
                ? "Legacy cadence telemetry recorded " + legacyCadenceDecisions
                        + " customer decision(s), but the durable opportunity ledger reconciled "
                        + durableValidDecisions
                        + "; the two evidence models must be reconciled before human review."
                : "";
        String auditReason = agentDecisionEvidenceRequired && !opportunityAgentAuditVerified
                ? "The run-level opportunity ledger could not be matched to a complete, run-scoped per-agent audit artifact."
                : "";
        String decisionModeReason = decisionModeEvidenceConflict
                ? "Deterministic decision mode recorded non-zero agent decision or durable opportunity evidence; the contradictory evidence must be reconciled before human review."
                : "";
        List<String> reasons = new ArrayList<>();
        if (!decisionModeReason.isBlank()) {
            reasons.add(decisionModeReason);
        }
        if (!splitReason.isBlank()) {
            reasons.add(splitReason);
        }
        if (!reconciliationReason.isBlank()) {
            reasons.add(reconciliationReason);
        }
        if (!auditReason.isBlank()) {
            reasons.add(auditReason);
        }
        return String.join(" ", reasons);
    }

    private List<Map<String, Object>> evaluateDomainOutcomeScorecards(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String presetId,
            Map<String, Object> parameters,
            Map<String, Object> artifactPaths,
            Map<String, Object> metrics,
            Map<String, Object> signals) {
        PolicyExpressionContext scoreContext = PolicyExpressionContext.create(
                BankingPolicyWindTunnelManifest.DOMAIN_ID,
                BankingPolicyWindTunnelManifest.PACK_ID,
                PolicyExpressionStage.DECISION_GATE,
                "CANDIDATE",
                linkedMap(
                        "runId", runId,
                        "presetId", presetId,
                        "artifactRefs", artifactPaths),
                parameters,
                linkedMap("artifactRefs", artifactPaths),
                metrics,
                signals);
        List<Map<String, Object>> scorecards = scorecardsFromPackMetadata(remotePolicyWindTunnelPackMetadata(context));
        if (scorecards.isEmpty()) {
            scorecards = scorecardsFromPackMetadata(context.packMetadata(packId()));
        }
        if (scorecards.isEmpty()) {
            return scorecardEvaluator.evaluateAll(List.of(), scoreContext);
        }
        List<Map<String, Object>> scores = new ArrayList<>();
        for (Map<String, Object> scorecard : scorecards) {
            try {
                scores.add(scorecardEvaluator.evaluate(scorecard, scoreContext));
            } catch (RuntimeException error) {
                log.warn("Domain outcome scorecard {} evaluation failed for run {}: {}",
                        firstNonBlank(stringValue(scorecard.get("scorecardId")), "<unknown>"),
                        runId,
                        error.getMessage(),
                        error);
                scores.add(failedDomainOutcomeScorecard(scorecard, error));
            }
        }
        return List.copyOf(scores);
    }

    private Map<String, Object> remotePolicyWindTunnelPackMetadata(PolicyWindTunnelExecutionContext context) {
        String dependencyUrl = context.dependencyUrl(BankingPolicyWindTunnelManifest.BANKING_PRODUCT_ENDPOINT_DEPENDENCY);
        if (dependencyUrl == null || dependencyUrl.isBlank()) {
            return Map.of();
        }
        String endpoint = trimTrailingSlash(dependencyUrl) + "/policy-wind-tunnel/pack";
        try {
            ResponseEntity<Object> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    Object.class);
            return response == null ? Map.of() : objectMap(response.getBody());
        } catch (RuntimeException error) {
            log.debug("Remote banking scorecard metadata unavailable at {}: {}",
                    endpoint,
                    error.getMessage());
            return Map.of();
        }
    }

    private static List<Map<String, Object>> scorecardsFromPackMetadata(Map<String, Object> packMetadata) {
        List<Map<String, Object>> scorecards = listOfMaps(objectMap(packMetadata).get("outcomeScorecards"));
        if (!scorecards.isEmpty()) {
            return scorecards;
        }
        Map<String, Object> scorecard = objectMap(objectMap(packMetadata).get("outcomeScorecard"));
        return scorecard.isEmpty() ? List.of() : List.of(scorecard);
    }

    private static String trimTrailingSlash(String value) {
        String normalized = stringValue(value);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private Map<String, Object> clockDrivenSimulationWindow(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Map<String, Object> parameters) {
        int configuredDays = Math.max(1, intValue(parameters.get("simulationHorizonDays"), 30));
        long simulationStartEpochMs = simulationStartEpochMs(parameters);
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("configuredDurationDays", configuredDays);
        window.put("horizonDays", configuredDays);
        window.put("simulationStartEpochMs", simulationStartEpochMs);
        Map<String, Object> replay = fetchSimulatorReplay(context);
        String activeRunId = stringValue(objectMap(replay.get("executionContext")).get("runId"));
        if (!runId.equals(activeRunId)) {
            window.put("durationStatus", "UNVERIFIED_CLOCK_OWNERSHIP");
            window.put("durationProofVerified", false);
            window.put("durationUnverifiedReason", activeRunId.isBlank()
                    ? "Simulator replay ownership was missing or unavailable."
                    : "Simulator replay is owned by " + activeRunId + ".");
            return window;
        }
        Long simulationTimeEpochMs = fetchSimulationTimeEpochMs(context);
        if (simulationTimeEpochMs == null) {
            window.put("durationStatus", "UNVERIFIED_CLOCK_TIME");
            window.put("durationProofVerified", false);
            window.put("durationUnverifiedReason", "Simulator clock time was unavailable after replay ownership was verified.");
            return window;
        }
        long elapsedMs = Math.max(0L, simulationTimeEpochMs - simulationStartEpochMs);
        double actualDays = round4(elapsedMs / (double) MILLIS_PER_SIMULATION_DAY);
        double overshootDays = round4(Math.max(0.0d, actualDays - configuredDays));
        window.put("simulationTimeEpochMs", simulationTimeEpochMs);
        window.put("actualDurationDays", actualDays);
        window.put("elapsedDays", actualDays);
        window.put("horizonOvershootDays", overshootDays);
        window.put("durationStatus", overshootDays > 0.05d ? "HORIZON_OVERRUN" : "WITHIN_HORIZON");
        window.put("durationProofVerified", true);
        return window;
    }

    private long simulationStartEpochMs(Map<String, Object> parameters) {
        long value = longValue(objectMap(parameters).get("simulationStartEpochMs"), DEFAULT_SIMULATION_START_EPOCH_MS);
        return value > 0L ? value : DEFAULT_SIMULATION_START_EPOCH_MS;
    }

    private Map<String, Object> fetchSimulationRunRecord(String runId) {
        String experimentServiceUrl = trimTrailingSlash(System.getenv("EXPERIMENT_SERVICE_URL"));
        if (experimentServiceUrl.isBlank()) {
            experimentServiceUrl = "http://experiment-service:7457";
        }
        try {
            Object body = restTemplate.getForObject(
                    experimentServiceUrl + "/api/simulation-runs/" + urlEncode(runId),
                    Object.class);
            return objectMap(body);
        } catch (Exception e) {
            log.debug("Simulation run ledger record unavailable for {}: {}",
                    runId,
                    firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
            return Map.of();
        }
    }

    private boolean notifySimulationRunCompleted(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Map<String, Object> payload) {
        String experimentServiceUrl = trimTrailingSlash(System.getenv("EXPERIMENT_SERVICE_URL"));
        if (experimentServiceUrl.isBlank()) {
            experimentServiceUrl = "http://experiment-service:7457";
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String operationId = stringValue(payload.get("operationId"));
            if (!operationId.isBlank()) {
                headers.set("Idempotency-Key", operationId);
            }
            ResponseEntity<Object> response = restTemplate.exchange(
                    experimentServiceUrl + "/api/simulation-runs/" + urlEncode(runId),
                    HttpMethod.PATCH,
                    new HttpEntity<>(payload, headers),
                    Object.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            appendLog(context, runId, "Completed run, but E24 simulation-run ledger closure was not confirmed: "
                    + firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
            return false;
        }
    }

    private void notifyLegacySimulationRunCompleted(
            PolicyWindTunnelExecutionContext context,
            String runId,
            PolicyWindTunnelStoredRun storedRun,
            String summaryPath,
            String bundleUrl,
            long completedAtEpochMs,
            Map<String, Object> summary) {
        BankingPolicyScenarioLabRunMetadata policyMetadata = readPolicyMetadata(storedRun.metadata());
        Map<String, Object> parameters = objectMap(policyMetadata.parameters());
        Map<String, Object> payload = linkedMap(
                "runId", runId,
                "status", STATUS_COMPLETED,
                "source", "java-spring-banking",
                "summaryPath", summaryPath,
                "bundlePath", bundleUrl,
                "completedAtEpochMs", completedAtEpochMs,
                "metadata", linkedMap(
                        "source", "java-spring-banking",
                        "domainId", BankingPolicyWindTunnelManifest.DOMAIN_ID,
                        "cohortId", firstNonBlank(
                                stringValue(parameters.get("cohortId")),
                                stringValue(objectMap(parameters.get("cohortSelection")).get("cohortId"))),
                        "scenarioLab", linkedMap(
                                "phase", STATUS_COMPLETED,
                                "progress", 1.0,
                                "bundleReady", true,
                                "summaryMode", stringValue(summary.get("summaryMode")),
                                "parameters", parameters,
                                "agenticSimulation", objectMap(summary.get("agenticSimulation")))));
        notifySimulationRunCompleted(context, runId, payload);
    }

    private Map<String, Object> failedDomainOutcomeScorecard(
            Map<String, Object> scorecard,
            RuntimeException error) {
        return linkedMap(
                "schemaVersion", DomainOutcomeScorecardEvaluator.SCORE_SCHEMA_VERSION,
                "status", "SCORING_FAILED",
                "recommendation", "REVIEW",
                "scorecardId", firstNonBlank(stringValue(scorecard.get("scorecardId")), "unknown-scorecard"),
                "label", firstNonBlank(stringValue(scorecard.get("label")), "Unknown scorecard"),
                "version", firstNonBlank(stringValue(scorecard.get("version")), "0.0.0"),
                "reason", firstNonBlank(error.getMessage(), "Domain scoring failed."),
                "metrics", List.of(),
                "gates", List.of(),
                "evidenceRefs", List.of("simulationRunEvidencePack"),
                "limitations", List.of("Domain scorecard evaluation failed; review raw evidence instead."));
    }

    private Map<String, Object> primaryDomainOutcomeScore(List<Map<String, Object>> scores) {
        return scores.stream()
                .filter(score -> score != null && !score.isEmpty())
                .max(Comparator.comparingInt(this::recommendationSeverity))
                .orElseGet(() -> linkedMap(
                        "schemaVersion", DomainOutcomeScorecardEvaluator.SCORE_SCHEMA_VERSION,
                        "status", "UNSCORED",
                        "recommendation", "REVIEW",
                        "reason", "Domain scoring not supplied yet."));
    }

    private int recommendationSeverity(Map<String, Object> score) {
        return switch (stringValue(score.get("recommendation")).toUpperCase(Locale.ROOT)) {
            case "BLOCK" -> 4;
            case "WATCH" -> 3;
            case "REVIEW" -> 2;
            case "LAUNCH" -> 1;
            default -> 0;
        };
    }

    private String scoredRecommendation(Map<String, Object> domainOutcomeScore) {
        return switch (stringValue(domainOutcomeScore.get("recommendation")).toUpperCase(Locale.ROOT)) {
            case "LAUNCH" -> "LAUNCH";
            case "BLOCK" -> "BLOCK";
            case "WATCH" -> "WATCH";
            default -> "REVIEW";
        };
    }

    private String scoredReason(Map<String, Object> domainOutcomeScore) {
        Map<String, Object> selectedRule = objectMap(domainOutcomeScore.get("selectedRule"));
        return firstNonBlank(
                stringValue(selectedRule.get("reason")),
                stringValue(domainOutcomeScore.get("reason")));
    }

    private String nextStepForRecommendation(
            String recommendation,
            Map<String, Object> domainOutcomeScore) {
        String scorecardLabel = firstNonBlank(stringValue(domainOutcomeScore.get("label")), "domain scorecard");
        return switch (recommendation) {
            case "LAUNCH" -> "Review the linked evidence and obtain the required human approval before launch.";
            case "BLOCK" -> "Treat this candidate as blocked until the domain owner resolves the scored failure and reruns the simulation.";
            case "WATCH" -> "Inspect the affected subjects and evidence linked by " + scorecardLabel + " before making a launch decision.";
            default -> "Review the raw evidence export because the domain scorecard did not produce a stronger recommendation.";
        };
    }

    private int scoreGateBreachCount(Map<String, Object> domainOutcomeScore, int cohortSize) {
        return scoreGateFailCount(domainOutcomeScore, cohortSize);
    }

    private int scoreGateFailCount(Map<String, Object> domainOutcomeScore, int cohortSize) {
        int count = 0;
        for (Map<String, Object> gate : scoreDecisionGates(domainOutcomeScore, cohortSize)) {
            if (!"PASS".equalsIgnoreCase(stringValue(gate.get("status")))) {
                count += 1;
            }
        }
        return count;
    }

    private List<Map<String, Object>> scoreDecisionGates(
            Map<String, Object> domainOutcomeScore,
            int cohortSize) {
        List<Map<String, Object>> gates = new ArrayList<>();
        gates.add(linkedMap(
                "gateId", "cohort-activated",
                "label", "Cohort activated",
                "status", cohortSize > 0 ? "PASS" : "WATCH",
                "narrative", "Generated customers were activated into agent-runtime."));
        for (Map<String, Object> gate : listOfMaps(domainOutcomeScore.get("gates"))) {
            gates.add(linkedMap(
                    "gateId", gate.get("gateId"),
                    "label", gate.get("label"),
                    "status", gate.get("status"),
                    "narrative", firstNonBlank(
                            stringValue(gate.get("description")),
                            "Domain-owned scorecard gate."),
                    "evidenceRef", gate.get("evidenceRef"),
                    "supportLevel", gate.get("supportLevel")));
        }
        if (!"SCORED".equalsIgnoreCase(stringValue(domainOutcomeScore.get("status")))) {
            gates.add(linkedMap(
                    "gateId", "domain-scorecard",
                    "label", "Domain scorecard",
                    "status", "WATCH",
                    "narrative", "Domain scoring was not available for this report."));
        }
        return List.copyOf(gates);
    }

    private List<Map<String, Object>> buildClockDrivenHybridStateMutations(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        Path checkpointDir = context.workspaceRoot()
                .resolve("output")
                .resolve("simulation-runs")
                .resolve(runId)
                .resolve("checkpoints");
        if (!Files.isDirectory(checkpointDir)) {
            return List.of();
        }
        try (var paths = Files.list(checkpointDir)) {
            List<Path> checkpointFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(HYBRID_STATE_MUTATION_SAMPLE_LIMIT)
                    .toList();
            List<Map<String, Object>> mutations = new ArrayList<>();
            for (int index = 0; index < checkpointFiles.size(); index++) {
                Map<String, Object> checkpoint = objectMap(objectMapper.readValue(checkpointFiles.get(index).toFile(), Map.class));
                mutations.add(buildClockDrivenHybridStateMutation(runId, checkpointFiles.get(index), checkpoint, index));
            }
            return mutations;
        } catch (Exception e) {
            log.warn("Unable to derive state mutations from clock-driven checkpoints for run {}", runId, e);
            return List.of();
        }
    }

    private int countRejectedCheckpointWork(
            PolicyWindTunnelExecutionContext context,
            String runId,
            List<Map<String, Object>> sampledStateMutations) {
        Path checkpointDir = context.workspaceRoot()
                .resolve("output")
                .resolve("simulation-runs")
                .resolve(runId)
                .resolve("checkpoints");
        if (!Files.isDirectory(checkpointDir)) {
            return (int) sampledStateMutations.stream()
                    .filter(mutation -> "REMOTE_DOMAIN_WORK_REJECTED".equals(stringValue(mutation.get("mutationType"))))
                    .count();
        }
        try (var paths = Files.list(checkpointDir)) {
            Map<String, Set<String>> rejectedWorkKeysBySubject = new LinkedHashMap<>();
            Map<String, Integer> rejectedTransactionCountBySubject = new LinkedHashMap<>();
            List<Path> checkpointFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path checkpointFile : checkpointFiles) {
                Map<String, Object> checkpoint = objectMap(objectMapper.readValue(checkpointFile.toFile(), Map.class));
                Map<String, Object> nextState = objectMap(checkpoint.get("nextState"));
                Map<String, Object> nextStateData = objectMap(nextState.get("data"));
                String subjectKey = rejectedWorkSubjectKey(checkpoint, nextStateData);
                List<Map<String, Object>> executionLog = listOfMaps(nextStateData.get("executionLog"));
                int rejectedTransactions = intValue(nextStateData.get("rejectedTransactionCount"), 0);
                if (rejectedTransactions > 0) {
                    rejectedTransactionCountBySubject.merge(subjectKey, rejectedTransactions, Math::max);
                }
                for (int index = 0; index < executionLog.size(); index++) {
                    Map<String, Object> entry = executionLog.get(index);
                    if (!"REJECTED".equalsIgnoreCase(stringValue(entry.get("status")))) {
                        continue;
                    }
                    rejectedWorkKeysBySubject
                            .computeIfAbsent(subjectKey, ignored -> new HashSet<>())
                            .add(rejectedWorkKey(entry, checkpointFile.getFileName().toString(), index));
                }
            }
            Set<String> subjects = new HashSet<>();
            subjects.addAll(rejectedWorkKeysBySubject.keySet());
            subjects.addAll(rejectedTransactionCountBySubject.keySet());
            int rejected = 0;
            for (String subject : subjects) {
                int keyedRejectedWork = rejectedWorkKeysBySubject.getOrDefault(subject, Set.of()).size();
                int cumulativeRejectedTransactions = rejectedTransactionCountBySubject.getOrDefault(subject, 0);
                rejected += Math.max(keyedRejectedWork, cumulativeRejectedTransactions);
            }
            return rejected;
        } catch (Exception e) {
            log.warn("Unable to count rejected checkpoint work for run {}", runId, e);
            return (int) sampledStateMutations.stream()
                    .filter(mutation -> "REMOTE_DOMAIN_WORK_REJECTED".equals(stringValue(mutation.get("mutationType"))))
                    .count();
        }
    }

    private String rejectedWorkSubjectKey(Map<String, Object> checkpoint, Map<String, Object> nextStateData) {
        Map<String, Object> runtimeMetadata = objectMap(checkpoint.get("runtimeMetadata"));
        Map<String, Object> statusView = objectMap(checkpoint.get("statusView"));
        Map<String, Object> customerState = objectMap(statusView.get("customerState"));
        return firstNonBlank(
                stringValue(nextStateData.get("domainId")),
                stringValue(runtimeMetadata.get("domainId")),
                BankingPolicyWindTunnelManifest.DOMAIN_ID)
                + ":"
                + firstNonBlank(
                stringValue(nextStateData.get("agentId")),
                stringValue(customerState.get("agentId")),
                stringValue(nextStateData.get("personaName")),
                stringValue(objectMap(nextStateData.get("customerProfile")).get("fullName")),
                "run");
    }

    private String rejectedWorkKey(Map<String, Object> executionLogEntry, String checkpointRef, int entryIndex) {
        String workId = stringValue(executionLogEntry.get("workId"));
        String scheduledFor = firstNonBlank(
                stringValue(executionLogEntry.get("occurrencePeriod")),
                stringValue(executionLogEntry.get("scheduledForEpochMs")),
                stringValue(executionLogEntry.get("scheduledAtEpochMs")),
                stringValue(executionLogEntry.get("appliedAtEpochMs")));
        String scheduledWorkId = (!workId.isBlank() && !scheduledFor.isBlank()) ? workId + "@" + scheduledFor : "";
        String workDescriptionKey = firstNonBlank(
                stringValue(executionLogEntry.get("workKind")),
                stringValue(executionLogEntry.get("description")),
                stringValue(executionLogEntry.get("failureMessage")));
        return firstNonBlank(
                stringValue(executionLogEntry.get("occurrenceKey")),
                stringValue(executionLogEntry.get("idempotencyKey")),
                stringValue(executionLogEntry.get("operationId")),
                scheduledWorkId,
                workId,
                workDescriptionKey.isBlank()
                        ? ""
                        : "anonymous:" + checkpointRef + ":" + entryIndex + ":" + workDescriptionKey,
                "anonymous-rejected-entry-" + checkpointRef + ":" + entryIndex);
    }

    private Map<String, Object> buildClockDrivenHybridStateMutation(
            String runId,
            Path checkpointPath,
            Map<String, Object> checkpoint,
            int index) {
        Map<String, Object> nextState = objectMap(checkpoint.get("nextState"));
        Map<String, Object> nextStateData = objectMap(nextState.get("data"));
        Map<String, Object> runtimeMetadata = objectMap(checkpoint.get("runtimeMetadata"));
        Map<String, Object> statusView = objectMap(checkpoint.get("statusView"));
        Map<String, Object> customerState = objectMap(statusView.get("customerState"));
        List<Map<String, Object>> executionLog = listOfMaps(nextStateData.get("executionLog"));
        int appliedTransactions = intValue(nextStateData.get("appliedTransactionCount"), 0);
        int rejectedTransactions = intValue(nextStateData.get("rejectedTransactionCount"), 0);
        int executedWork = intValue(nextStateData.get("executedWorkCount"), executionLog.size());
        long rejectedWork = executionLog.stream()
                .filter(entry -> "REJECTED".equalsIgnoreCase(stringValue(entry.get("status"))))
                .count();
        int actionCount = Math.max(1, Math.max(executedWork, appliedTransactions + rejectedTransactions));
        String personaName = firstNonBlank(
                stringValue(nextStateData.get("personaName")),
                stringValue(objectMap(nextStateData.get("customerProfile")).get("fullName")),
                stringValue(nextStateData.get("agentId")),
                stringValue(customerState.get("agentId")),
                "Synthetic customer " + (index + 1));
        String domainId = firstNonBlank(
                stringValue(nextStateData.get("domainId")),
                stringValue(runtimeMetadata.get("domainId")),
                BankingPolicyWindTunnelManifest.DOMAIN_ID);
        String mutationType = mutationType(appliedTransactions, rejectedTransactions, rejectedWork, executedWork);
        String description = mutationDescription(personaName, appliedTransactions, rejectedTransactions, executedWork);
        return linkedMap(
                "mutationId", "state-" + (index + 1) + "-" + safeToken(personaName),
                "mutationType", mutationType,
                "label", humanMutationLabel(mutationType),
                "description", description,
                "branchId", "clock-driven-hybrid-run",
                "branchRole", "proposed",
                "personaName", personaName,
                "segment", firstNonBlank(stringValue(nextStateData.get("segment")), domainId + "-runtime-state"),
                "deltaValue", actionCount,
                "unit", "events",
                "severity", rejectedTransactions > 0 || rejectedWork > 0 ? "Review" : "Observed",
                "status", rejectedTransactions > 0 || rejectedWork > 0 ? "REVIEW" : "OBSERVED",
                "sourceEffectId", "e24-evidence-spine-" + runId,
                "evidenceRef", "simulationRunEvidencePack",
                "artifactRef", "/api/dashboard/simulation-runs/" + urlEncode(runId) + "/export",
                "checkpointRef", checkpointPath.getFileName().toString(),
                "stateSummary", linkedMap(
                        "domainId", domainId,
                        "operation", firstNonBlank(stringValue(nextStateData.get("lastAction")), stringValue(checkpoint.get("operation")), "checkpoint"),
                        "executedWorkCount", executedWork,
                        "appliedTransactionCount", appliedTransactions,
                        "rejectedTransactionCount", rejectedTransactions,
                        "currentBalance", customerState.get("currentBalance")));
    }

    private String mutationType(
            int appliedTransactions,
            int rejectedTransactions,
            long rejectedWork,
            int executedWork) {
        if (rejectedTransactions > 0 || rejectedWork > 0) {
            return "REMOTE_DOMAIN_WORK_REJECTED";
        }
        if (appliedTransactions > 0) {
            return "REMOTE_DOMAIN_TRANSACTION_POSTED";
        }
        if (executedWork > 0) {
            return "REMOTE_DOMAIN_WORK_EXECUTED";
        }
        return "REMOTE_DOMAIN_STATE_CHECKPOINT";
    }

    private String humanMutationLabel(String mutationType) {
        return switch (mutationType) {
            case "REMOTE_DOMAIN_WORK_REJECTED" -> "Remote-domain work rejected";
            case "REMOTE_DOMAIN_TRANSACTION_POSTED" -> "Remote-domain transaction posted";
            case "REMOTE_DOMAIN_WORK_EXECUTED" -> "Remote-domain work executed";
            default -> "Remote-domain state checkpoint";
        };
    }

    private String mutationDescription(
            String personaName,
            int appliedTransactions,
            int rejectedTransactions,
            int executedWork) {
        if (rejectedTransactions > 0) {
            return "Captured " + rejectedTransactions + " rejected transaction(s) for " + personaName
                    + " during clock-driven scheduled work.";
        }
        if (appliedTransactions > 0) {
            return "Captured " + appliedTransactions + " posted transaction(s) for " + personaName
                    + " during clock-driven scheduled work.";
        }
        if (executedWork > 0) {
            return "Captured " + executedWork + " executed scheduled work item(s) for " + personaName + ".";
        }
        return "Captured remote-domain state checkpoint for " + personaName + ".";
    }

    private List<Map<String, Object>> buildCheckpointSummaries(List<Map<String, Object>> stateMutations) {
        return stateMutations.stream()
                .map(mutation -> linkedMap(
                        "checkpointRef", mutation.get("checkpointRef"),
                        "personaName", mutation.get("personaName"),
                        "mutationType", mutation.get("mutationType"),
                        "description", mutation.get("description"),
                        "stateSummary", mutation.get("stateSummary"),
                        "evidenceRef", mutation.get("evidenceRef")))
                .toList();
    }

    private Map<String, Object> buildFinalizedHybridTimeline(
            BankingPolicyScenarioLabRunMetadata policyMetadata,
            OffsetDateTime finalizedAt,
            Map<String, Object> simulationWindow) {
        Map<String, Object> timeline = buildLiveHybridTimeline(policyMetadata, simulationWindow);
        List<Map<String, Object>> events = new ArrayList<>(listOfMaps(timeline.get("events")));
        events.add(linkedMap(
                "eventId", "finalize-report",
                "eventType", "REPORT_FINALIZED",
                "label", "Outcome report finalized",
                "status", "COMPLETED",
                "completedAt", finalizedAt.toString(),
                "evidenceRef", "comparisonReport"));
        Map<String, Object> finalizedTimeline = new LinkedHashMap<>(simulationWindow);
        finalizedTimeline.put("schemaVersion", "customer_outcome_simulation_timeline.v1");
        finalizedTimeline.put("mode", "CLOCK_DRIVEN_HYBRID_FINALIZED");
        finalizedTimeline.put("events", events);
        return finalizedTimeline;
    }

    private void writeClockDrivenHybridFinalizedArtifacts(
            Path summaryDir,
            Map<String, Object> summary) throws IOException {
        writeJson(summaryDir.resolve("scenario-lab-summary.json"), summary);
        writeJson(summaryDir.resolve("decision-comparison.json"), objectMap(summary.get("decisionComparison")));
        writeJson(summaryDir.resolve("simulation-timeline.json"), objectMap(summary.get("simulationTimeline")));
        writeJson(summaryDir.resolve("side-effect-ledger.json"), objectMap(summary.get("sideEffectLedger")));
        writeJson(summaryDir.resolve("branch-state-projection.json"), objectMap(summary.get("branchStateProjection")));
        writeJson(summaryDir.resolve("domain-outcome-score.json"), objectMap(summary.get("domainOutcomeScore")));
        writeJson(summaryDir.resolve("domain-outcome-scores.json"), summary.get("domainOutcomeScores"));
        writeJson(summaryDir.resolve("hybrid-agent-audit.json"), objectMap(summary.get("hybridAgentAudit")));
        writeJson(summaryDir.resolve("checkpoints.json"), summary.get("checkpointSummaries"));
        writeJson(summaryDir.resolve("agent-diffs.json"), List.of());
        writeJson(summaryDir.resolve("notable-actions.json"), List.of());
        writeJson(summaryDir.resolve("scheduled-work-failures.json"), List.of());
        writeBytesAtomically(
                summaryDir.resolve("impacted-cohorts.csv"),
                "personaName,segment,status,evidenceRef\n".getBytes(StandardCharsets.UTF_8));
        writeBytesAtomically(
                summaryDir.resolve("experiment-card.md"),
                buildClockDrivenHybridFinalizedExperimentCard(summary).getBytes(StandardCharsets.UTF_8));
    }

    private void registerClockDrivenHybridFinalizedArtifacts(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Map<String, Object> artifactPaths) {
        registerArtifactsFromManifest(
                context, runId, finalizationArtifactManifest(context, artifactPaths));
    }

    private String buildClockDrivenHybridFinalizedExperimentCard(Map<String, Object> summary) {
        Map<String, Object> readiness = objectMap(summary.get("decisionReadiness"));
        Map<String, Object> runConfig = objectMap(summary.get("runConfig"));
        Map<String, Object> decisionComparison = objectMap(summary.get("decisionComparison"));
        Map<String, Object> exposureSummary = objectMap(summary.get("exposureSummary"));
        List<String> lines = new ArrayList<>();
        lines.add("# Clock-Driven Simulation Outcome Review");
        lines.add("");
        lines.add("## Decision Status");
        lines.add("- Run ID: `" + stringValue(summary.get("runId")) + "`");
        lines.add("- Status: `" + stringValue(readiness.get("status")) + "`");
        lines.add("- Recommendation: `" + stringValue(readiness.get("recommendation")) + "`");
        lines.add("- Reason: " + stringValue(readiness.get("reason")));
        lines.add("");
        lines.add("## Scope");
        lines.add("- Cohort ID: `" + stringValue(runConfig.get("cohortId")) + "`");
        lines.add("- Cohort size: `" + stringValue(exposureSummary.get("cohortAgents")) + "`");
        lines.add("- Execution mode: `" + stringValue(runConfig.get("executionMode")) + "`");
        lines.add("");
        lines.add("## Decision Under Test");
        lines.add("- Comparison ID: `" + stringValue(decisionComparison.get("comparisonId")) + "`");
        lines.add("- Question: " + stringValue(decisionComparison.get("decisionQuestion")));
        lines.add("");
        lines.add("## Evidence");
        lines.add("- E24 simulation run evidence: `/api/dashboard/simulation-runs/"
                + urlEncode(stringValue(summary.get("runId"))) + "/export`");
        lines.add("- Timeline, side-effect ledger, and branch-state projection are included beside this card.");
        lines.add("");
        lines.add("## Limitations");
        lines.add("This is finalized stress-test evidence. It is not a production forecast and not an automatic launch approval.");
        return String.join("\n", lines) + "\n";
    }

    private Map<String, Object> liveHybridDecisionReadiness(String status, String phase) {
        String normalizedStatus = stringValue(status).toUpperCase(Locale.ROOT);
        String readinessStatus = switch (normalizedStatus) {
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> "NOT_READY";
        };
        String reason = switch (readinessStatus) {
            case "FAILED" -> "The clock-driven run failed before a finalized outcome report was produced.";
            case "CANCELLED" -> "The run was cancelled. Evidence captured before cancellation remains exportable, but the launch decision is incomplete.";
            default -> "The simulator clock is still gathering evidence. Do not treat this as a launch recommendation until final outcome scoring is produced.";
        };
        return linkedMap(
                "status", readinessStatus,
                "recommendation", "WATCH",
                "phase", firstNonBlank(phase, normalizedStatus),
                "reason", reason,
                "nextStep", "Continue or pause the clock-driven run, then finalize it into an outcome report when enough evidence has accumulated.");
    }

    private Map<String, Object> buildLiveHybridTimeline(
            BankingPolicyScenarioLabRunMetadata policyMetadata,
            Map<String, Object> simulationWindow) {
        List<Map<String, Object>> events = new ArrayList<>();
        List<Map<String, Object>> timings = listOfMaps(policyMetadata.phaseTimings());
        for (int index = 0; index < timings.size(); index++) {
            Map<String, Object> timing = timings.get(index);
            events.add(linkedMap(
                    "eventId", "phase-" + (index + 1),
                    "eventType", "RUN_PHASE",
                    "label", stringValue(timing.get("phase")),
                    "status", stringValue(timing.get("status")),
                    "startedAt", timing.get("startedAt"),
                    "completedAt", timing.get("completedAt"),
                    "evidenceRef", "simulationRunEvidencePack"));
        }
        List<String> logTail = stringList(policyMetadata.logTail());
        for (int index = 0; index < logTail.size(); index++) {
            events.add(linkedMap(
                    "eventId", "log-" + (index + 1),
                    "eventType", "RUN_LOG",
                    "message", logTail.get(index),
                    "evidenceRef", "simulationRunEvidencePack"));
        }
        Map<String, Object> timeline = new LinkedHashMap<>(simulationWindow);
        timeline.put("schemaVersion", "customer_outcome_simulation_timeline.v1");
        timeline.put("mode", "CLOCK_DRIVEN_HYBRID_LIVE");
        timeline.put("events", events);
        return timeline;
    }

    private Map<String, Object> buildLiveHybridSideEffectLedger(
            String runId,
            String status,
            String phase,
            Map<String, Object> artifactDownloads) {
        return linkedMap(
                "schemaVersion", "customer_outcome_side_effect_ledger.v1",
                "mode", "CLOCK_DRIVEN_HYBRID_LIVE",
                "entries", List.of(linkedMap(
                        "effectId", "e24-evidence-spine-" + runId,
                        "effectType", "EVIDENCE_CAPTURE",
                        "operation", "E24 run ledger",
                        "status", firstNonBlank(phase, status),
                        "evidenceRef", "simulationRunEvidencePack",
                        "downloadUrl", artifactDownloads.get("simulationRunEvidence"),
                        "note", "Platform-mediated runtime activity is captured by experiment-service. Domain-internal private I/O requires domain-emitted evidence events.")));
    }

    private String buildClockDrivenHybridExperimentCard(Map<String, Object> summary) {
        Map<String, Object> readiness = objectMap(summary.get("decisionReadiness"));
        Map<String, Object> runConfig = objectMap(summary.get("runConfig"));
        Map<String, Object> decisionComparison = objectMap(summary.get("decisionComparison"));
        Map<String, Object> artifacts = objectMap(summary.get("artifacts"));
        List<String> lines = new ArrayList<>();
        lines.add("# Live Clock-Driven Simulation Evidence");
        lines.add("");
        lines.add("## Decision Status");
        lines.add("- Run ID: `" + stringValue(summary.get("runId")) + "`");
        lines.add("- Status: `" + stringValue(readiness.get("status")) + "`");
        lines.add("- Phase: `" + stringValue(readiness.get("phase")) + "`");
        lines.add("- Recommendation: `" + stringValue(readiness.get("recommendation")) + "`");
        lines.add("- Reason: " + stringValue(readiness.get("reason")));
        lines.add("");
        lines.add("## Scope");
        lines.add("- Preset: `" + stringValue(objectMap(summary.get("demoPreset")).get("presetId")) + "`");
        lines.add("- Execution mode: `" + stringValue(runConfig.get("executionMode")) + "`");
        lines.add("- Cohort ID: `" + stringValue(runConfig.get("cohortId")) + "`");
        lines.add("- Cohort size: `" + stringValue(runConfig.get("cohortSize")) + "`");
        lines.add("");
        lines.add("## Decision Under Test");
        lines.add("- Comparison ID: `" + stringValue(decisionComparison.get("comparisonId")) + "`");
        lines.add("- Question: " + stringValue(decisionComparison.get("decisionQuestion")));
        lines.add("");
        lines.add("## Evidence");
        lines.add("- Scenario Lab live bundle: `live-generated`");
        lines.add("- E24 simulation run evidence: `" + stringValue(artifacts.get("simulationRunEvidence")) + "`");
        lines.add("");
        lines.add("## Limitations");
        lines.add("This is live operational evidence. It is not a completed baseline-vs-proposed outcome report, and it should not be used as a launch approval artifact.");
        return String.join("\n", lines) + "\n";
    }

    private Map<String, Object> liveHybridArtifactDownloads(String runId) {
        String encodedRunId = urlEncode(runId);
        return linkedMap(
                "evidencePack", "/api/dashboard/scenario-lab/export?runId=" + encodedRunId,
                "simulationRunEvidence", "/api/dashboard/simulation-runs/" + encodedRunId + "/export",
                "summary", "/api/dashboard/scenario-lab/artifact?runId=" + encodedRunId + "&type=summary",
                "experimentCard", "/api/dashboard/scenario-lab/artifact?runId=" + encodedRunId + "&type=experiment-card",
                "simulationTimelineJson", "/api/dashboard/scenario-lab/artifact?runId=" + encodedRunId + "&type=simulation-timeline-json",
                "sideEffectLedgerJson", "/api/dashboard/scenario-lab/artifact?runId=" + encodedRunId + "&type=side-effect-ledger-json",
                "branchStateProjectionJson", "/api/dashboard/scenario-lab/artifact?runId=" + encodedRunId + "&type=branch-state-projection-json",
                "hybridAgentAuditJson", "/api/dashboard/scenario-lab/artifact?runId=" + encodedRunId + "&type=hybrid-agent-audit-json",
                "comparisonReport", "/api/dashboard/scenario-lab/artifact?runId=" + encodedRunId + "&type=comparison-report");
    }

    private int countGeneratedCohortCustomers(
            PolicyWindTunnelExecutionContext context,
            String cohortId,
            int fallback) {
        if (cohortId.isBlank()) {
            return fallback;
        }
        Path customersFile = context.workspaceRoot()
                .resolve("output/cohorts")
                .resolve(cohortId)
                .resolve("customers.json")
                .normalize();
        if (!Files.exists(customersFile)) {
            return fallback;
        }
        try {
            List<?> customers = objectMapper.readValue(Files.readString(customersFile), List.class);
            return customers.size();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String productNameFor(String productCode) {
        return switch (stringValue(productCode)) {
            case "TS001" -> "Standard Saving Product";
            case "CC001" -> "Standard Credit Card";
            default -> firstNonBlank(productCode, "Banking product");
        };
    }

    private String policyTypeForPreset(String presetId) {
        String normalized = stringValue(presetId).toLowerCase(Locale.ROOT);
        if (normalized.contains("hardship")) {
            return "HARDSHIP";
        }
        if (normalized.contains("fraud")) {
            return "FRAUD";
        }
        if (normalized.contains("credit") || normalized.contains("limit")) {
            return "LIMIT";
        }
        if (normalized.contains("repricing") || normalized.contains("rate")) {
            return "RATE";
        }
        return "POLICY";
    }

    private void addZipEntry(
            ZipOutputStream zipOutputStream,
            Set<String> entryNames,
            String entryName,
            byte[] content) throws IOException {
        String normalizedEntryName = entryName.replace('\\', '/');
        if (!entryNames.add(normalizedEntryName)) {
            return;
        }
        ZipEntry entry = new ZipEntry(normalizedEntryName);
        zipOutputStream.putNextEntry(entry);
        zipOutputStream.write(content);
        zipOutputStream.closeEntry();
    }

    private String safeToken(String value) {
        String safe = stringValue(value)
                .replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        return safe.isBlank() ? "run" : safe;
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String platformOwnedRunId(Object requestedRunId) {
        String normalized = stringValue(requestedRunId).trim();
        if (normalized.isBlank()) {
            return "policy-wt-" + OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond() + "-"
                    + UUID.randomUUID().toString().substring(0, 8);
        }
        if (normalized.length() > 255
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(
                    "simulationRunId is not a valid platform run identifier");
        }
        return normalized;
    }

    private void writeJson(Path path, Object value) throws IOException {
        writeBytesAtomically(
                path,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    private void writeBytesAtomically(Path path, byte[] content) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        createDirectoriesDurably(parent);
        Path pending = Files.createTempFile(parent, path.getFileName().toString() + ".", ".pending");
        try {
            Files.write(
                    pending,
                    content,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(
                    pending,
                    path.toAbsolutePath().normalize(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(parent);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static RestTemplate patchCapableRestTemplate() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return new RestTemplate(requestFactory);
    }

    private boolean isTerminalStatus(String status) {
        String normalized = stringValue(status).toUpperCase(Locale.ROOT);
        return "COMPLETED".equals(normalized)
                || "FAILED".equals(normalized)
                || "CANCELLED".equals(normalized);
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (blank(value)) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double doubleEnv(String name, double defaultValue) {
        String value = System.getenv(name);
        if (blank(value)) {
            return defaultValue;
        }
        try {
            return Math.max(0.0001d, Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String stringEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        if (blank(value)) {
            return defaultValue;
        }
        return value.trim();
    }
}
