package banking.simulator.remote.domain.contract.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compatibility contract retained for runtime code that still depends on the historic
 * banking-owned AgentPersona type.
 *
 * This class intentionally does not inherit the core platform AgentInitPersona anymore.
 * The banking adapter should not transitively own core API classes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentPersona {

    private String domainId = "banking";
    private AgentTypeEnum agentType = AgentTypeEnum.CUSTOMER;
    private String personaName;
    private String personaDescription;
    private String avatarColor;
    private String avatarIcon;
    private LocalDate dateOfBirth;
    private List<Object> initialAccounts = new ArrayList<>();
    private List<Goal> goals = new ArrayList<>();
    private List<String> traits = new ArrayList<>();
    private List<String> tools = new ArrayList<>();
    private String model;
    private ProviderEnum provider = ProviderEnum.OPENAI;
    private MemoryStrategyEnum memoryStrategy = MemoryStrategyEnum.WINDOW;
    private List<Float> busyness = new ArrayList<>();
    private Boolean autonomousDecisionEnabled = true;
    private Integer autonomousDecisionIntervalMinutes = 60;
    private AutonomousDecisionCadenceEnum autonomousDecisionCadence = AutonomousDecisionCadenceEnum.CUSTOM_INTERVAL;
    private Integer autonomousDecisionPhaseOffsetMinutes;
    private InteractionModeEnum interactionMode = InteractionModeEnum.SIMULATION;
    private List<Map<String, Object>> scheduledWork = new ArrayList<>();
    private Map<String, Object> domainConfig;

    public enum AgentTypeEnum implements StringValuedEnum {
        CUSTOMER("Customer"),
        POLICY("Policy");

        private final String value;

        AgentTypeEnum(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }

        @JsonCreator
        public static AgentTypeEnum fromValue(String value) {
            return enumFromValue(values(), value, "agentType");
        }
    }

    public enum ProviderEnum implements StringValuedEnum {
        OPENAI("openai"),
        OLLAMA("ollama"),
        GEMINI("gemini"),
        STUB("stub");

        private final String value;

        ProviderEnum(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }

        @JsonCreator
        public static ProviderEnum fromValue(String value) {
            return enumFromValue(values(), value, "provider");
        }
    }

    public enum MemoryStrategyEnum implements StringValuedEnum {
        WINDOW("window"),
        RAG("rag"),
        NONE("none");

        private final String value;

        MemoryStrategyEnum(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }

        @JsonCreator
        public static MemoryStrategyEnum fromValue(String value) {
            return enumFromValue(values(), value, "memoryStrategy");
        }
    }

    public enum AutonomousDecisionCadenceEnum implements StringValuedEnum {
        CUSTOM_INTERVAL("CUSTOM_INTERVAL"),
        HOURLY("HOURLY"),
        DAILY("DAILY"),
        WEEKLY("WEEKLY"),
        EVENT_ONLY("EVENT_ONLY");

        private final String value;

        AutonomousDecisionCadenceEnum(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }

        @JsonCreator
        public static AutonomousDecisionCadenceEnum fromValue(String value) {
            return enumFromValue(values(), value, "autonomousDecisionCadence");
        }
    }

    public enum InteractionModeEnum implements StringValuedEnum {
        SIMULATION("SIMULATION"),
        HYBRID("HYBRID"),
        A2A_SESSION("A2A_SESSION");

        private final String value;

        InteractionModeEnum(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }

        @JsonCreator
        public static InteractionModeEnum fromValue(String value) {
            return enumFromValue(values(), value, "interactionMode");
        }
    }

    public AgentPersona domainId(String domainId) {
        this.domainId = domainId;
        return this;
    }

    public String getDomainId() {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    public AgentPersona agentType(AgentTypeEnum agentType) {
        this.agentType = agentType;
        return this;
    }

    public AgentTypeEnum getAgentType() {
        return agentType;
    }

    public void setAgentType(AgentTypeEnum agentType) {
        this.agentType = agentType;
    }

    public AgentPersona personaName(String personaName) {
        this.personaName = personaName;
        return this;
    }

    public String getPersonaName() {
        return personaName;
    }

    public void setPersonaName(String personaName) {
        this.personaName = personaName;
    }

    public AgentPersona personaDescription(String personaDescription) {
        this.personaDescription = personaDescription;
        return this;
    }

    public String getPersonaDescription() {
        return personaDescription;
    }

    public void setPersonaDescription(String personaDescription) {
        this.personaDescription = personaDescription;
    }

    public AgentPersona avatarColor(String avatarColor) {
        this.avatarColor = avatarColor;
        return this;
    }

    public String getAvatarColor() {
        return avatarColor;
    }

    public void setAvatarColor(String avatarColor) {
        this.avatarColor = avatarColor;
    }

    public AgentPersona avatarIcon(String avatarIcon) {
        this.avatarIcon = avatarIcon;
        return this;
    }

    public String getAvatarIcon() {
        return avatarIcon;
    }

    public void setAvatarIcon(String avatarIcon) {
        this.avatarIcon = avatarIcon;
    }

    public AgentPersona dateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
        return this;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public AgentPersona initialAccounts(List initialAccounts) {
        setInitialAccounts(initialAccounts);
        return this;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public List getInitialAccounts() {
        return initialAccounts == null ? null : new ArrayList<>(initialAccounts);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void setInitialAccounts(List initialAccounts) {
        this.initialAccounts = initialAccounts == null ? null : new ArrayList<>(initialAccounts);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public AgentPersona goals(List goals) {
        setGoals(goals);
        return this;
    }

    public List<Goal> getGoals() {
        return goals == null ? null : new ArrayList<>(goals);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void setGoals(List goals) {
        this.goals = goals == null
                ? null
                : (List<Goal>) goals.stream()
                        .map(AgentPersona::toGoal)
                        .toList();
    }

    public AgentPersona traits(List<String> traits) {
        this.traits = traits;
        return this;
    }

    public List<String> getTraits() {
        return traits;
    }

    public void setTraits(List<String> traits) {
        this.traits = traits;
    }

    public AgentPersona tools(List<String> tools) {
        this.tools = tools;
        return this;
    }

    public List<String> getTools() {
        return tools;
    }

    public void setTools(List<String> tools) {
        this.tools = tools;
    }

    public AgentPersona model(String model) {
        this.model = model;
        return this;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public AgentPersona provider(ProviderEnum provider) {
        this.provider = provider;
        return this;
    }

    public ProviderEnum getProvider() {
        return provider;
    }

    public void setProvider(ProviderEnum provider) {
        this.provider = provider;
    }

    public AgentPersona memoryStrategy(MemoryStrategyEnum memoryStrategy) {
        this.memoryStrategy = memoryStrategy;
        return this;
    }

    public MemoryStrategyEnum getMemoryStrategy() {
        return memoryStrategy;
    }

    public void setMemoryStrategy(MemoryStrategyEnum memoryStrategy) {
        this.memoryStrategy = memoryStrategy;
    }

    public AgentPersona busyness(List<Float> busyness) {
        this.busyness = busyness;
        return this;
    }

    public List<Float> getBusyness() {
        return busyness;
    }

    public void setBusyness(List<Float> busyness) {
        this.busyness = busyness;
    }

    public AgentPersona autonomousDecisionEnabled(Boolean autonomousDecisionEnabled) {
        this.autonomousDecisionEnabled = autonomousDecisionEnabled;
        return this;
    }

    public Boolean getAutonomousDecisionEnabled() {
        return autonomousDecisionEnabled;
    }

    public void setAutonomousDecisionEnabled(Boolean autonomousDecisionEnabled) {
        this.autonomousDecisionEnabled = autonomousDecisionEnabled;
    }

    public AgentPersona autonomousDecisionIntervalMinutes(Integer autonomousDecisionIntervalMinutes) {
        this.autonomousDecisionIntervalMinutes = autonomousDecisionIntervalMinutes;
        return this;
    }

    public Integer getAutonomousDecisionIntervalMinutes() {
        return autonomousDecisionIntervalMinutes;
    }

    public void setAutonomousDecisionIntervalMinutes(Integer autonomousDecisionIntervalMinutes) {
        this.autonomousDecisionIntervalMinutes = autonomousDecisionIntervalMinutes;
    }

    public AgentPersona autonomousDecisionCadence(AutonomousDecisionCadenceEnum autonomousDecisionCadence) {
        this.autonomousDecisionCadence = autonomousDecisionCadence;
        return this;
    }

    public AutonomousDecisionCadenceEnum getAutonomousDecisionCadence() {
        return autonomousDecisionCadence;
    }

    public void setAutonomousDecisionCadence(AutonomousDecisionCadenceEnum autonomousDecisionCadence) {
        this.autonomousDecisionCadence = autonomousDecisionCadence;
    }

    public AgentPersona autonomousDecisionPhaseOffsetMinutes(Integer autonomousDecisionPhaseOffsetMinutes) {
        this.autonomousDecisionPhaseOffsetMinutes = autonomousDecisionPhaseOffsetMinutes;
        return this;
    }

    public Integer getAutonomousDecisionPhaseOffsetMinutes() {
        return autonomousDecisionPhaseOffsetMinutes;
    }

    public void setAutonomousDecisionPhaseOffsetMinutes(Integer autonomousDecisionPhaseOffsetMinutes) {
        this.autonomousDecisionPhaseOffsetMinutes = autonomousDecisionPhaseOffsetMinutes;
    }

    public AgentPersona interactionMode(InteractionModeEnum interactionMode) {
        this.interactionMode = interactionMode;
        return this;
    }

    public InteractionModeEnum getInteractionMode() {
        return interactionMode;
    }

    public void setInteractionMode(InteractionModeEnum interactionMode) {
        this.interactionMode = interactionMode;
    }

    public AgentPersona scheduledWork(List<Map<String, Object>> scheduledWork) {
        setScheduledWork(scheduledWork);
        return this;
    }

    public List<Map<String, Object>> getScheduledWork() {
        return scheduledWork == null ? null : new ArrayList<>(scheduledWork);
    }

    @SuppressWarnings("unchecked")
    public void setScheduledWork(List<Map<String, Object>> scheduledWork) {
        this.scheduledWork = scheduledWork == null
                ? null
                : scheduledWork.stream()
                        .map(item -> item == null ? Map.<String, Object>of() : new LinkedHashMap<>(item))
                        .toList();
    }

    public AgentPersona domainConfig(Map<String, Object> domainConfig) {
        setDomainConfig(domainConfig);
        return this;
    }

    public Map<String, Object> getDomainConfig() {
        return domainConfig == null ? null : new LinkedHashMap<>(domainConfig);
    }

    public void setDomainConfig(Map<String, Object> domainConfig) {
        this.domainConfig = domainConfig == null ? null : new LinkedHashMap<>(domainConfig);
    }

    private static Goal toGoal(Object value) {
        if (value instanceof Goal goal) {
            return goal;
        }
        if (value instanceof Map<?, ?> map) {
            Goal converted = new Goal()
                    .goalId(asString(map.get("goalId")))
                    .description(asString(map.get("description")))
                    .targetAmount(asDouble(map.get("targetAmount")));
            String priority = asString(map.get("priority"));
            if (priority != null) {
                converted.setPriority(Goal.PriorityEnum.fromValue(priority.toLowerCase()));
            }
            return converted;
        }
        throw new IllegalArgumentException("Unsupported goal value: " + value);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.valueOf(String.valueOf(value));
    }

    private static Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.valueOf(String.valueOf(value));
    }

    private static LocalDate asLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static <E extends Enum<E> & StringValuedEnum> E enumFromValue(E[] values, String value, String label) {
        for (E candidate : values) {
            if (candidate.getValue().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unexpected " + label + " value '" + value + "'");
    }

    private interface StringValuedEnum {
        String getValue();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AgentPersona that = (AgentPersona) o;
        return Objects.equals(domainId, that.domainId)
                && agentType == that.agentType
                && Objects.equals(personaName, that.personaName)
                && Objects.equals(personaDescription, that.personaDescription)
                && Objects.equals(avatarColor, that.avatarColor)
                && Objects.equals(avatarIcon, that.avatarIcon)
                && Objects.equals(dateOfBirth, that.dateOfBirth)
                && Objects.equals(initialAccounts, that.initialAccounts)
                && Objects.equals(goals, that.goals)
                && Objects.equals(traits, that.traits)
                && Objects.equals(tools, that.tools)
                && Objects.equals(model, that.model)
                && provider == that.provider
                && memoryStrategy == that.memoryStrategy
                && Objects.equals(busyness, that.busyness)
                && Objects.equals(autonomousDecisionEnabled, that.autonomousDecisionEnabled)
                && Objects.equals(autonomousDecisionIntervalMinutes, that.autonomousDecisionIntervalMinutes)
                && autonomousDecisionCadence == that.autonomousDecisionCadence
                && Objects.equals(autonomousDecisionPhaseOffsetMinutes, that.autonomousDecisionPhaseOffsetMinutes)
                && interactionMode == that.interactionMode
                && Objects.equals(scheduledWork, that.scheduledWork)
                && Objects.equals(domainConfig, that.domainConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                domainId,
                agentType,
                personaName,
                personaDescription,
                avatarColor,
                avatarIcon,
                dateOfBirth,
                initialAccounts,
                goals,
                traits,
                tools,
                model,
                provider,
                memoryStrategy,
                busyness,
                autonomousDecisionEnabled,
                autonomousDecisionIntervalMinutes,
                autonomousDecisionCadence,
                autonomousDecisionPhaseOffsetMinutes,
                interactionMode,
                scheduledWork,
                domainConfig);
    }

    @Override
    public String toString() {
        return "AgentPersona{"
                + "domainId='" + domainId + '\''
                + ", agentType=" + agentType
                + ", personaName='" + personaName + '\''
                + ", personaDescription='" + personaDescription + '\''
                + ", avatarColor='" + avatarColor + '\''
                + ", avatarIcon='" + avatarIcon + '\''
                + ", dateOfBirth=" + dateOfBirth
                + ", initialAccounts=" + initialAccounts
                + ", goals=" + goals
                + ", traits=" + traits
                + ", tools=" + tools
                + ", model='" + model + '\''
                + ", provider=" + provider
                + ", memoryStrategy=" + memoryStrategy
                + ", busyness=" + busyness
                + ", autonomousDecisionEnabled=" + autonomousDecisionEnabled
                + ", autonomousDecisionIntervalMinutes=" + autonomousDecisionIntervalMinutes
                + ", autonomousDecisionCadence=" + autonomousDecisionCadence
                + ", autonomousDecisionPhaseOffsetMinutes=" + autonomousDecisionPhaseOffsetMinutes
                + ", interactionMode=" + interactionMode
                + ", scheduledWork=" + scheduledWork
                + '}';
    }
}
