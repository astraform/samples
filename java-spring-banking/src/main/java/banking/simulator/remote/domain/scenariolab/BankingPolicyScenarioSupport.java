package banking.simulator.remote.domain.scenariolab;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BankingPolicyScenarioSupport {

    static final long DEFAULT_SIMULATION_START_EPOCH_MS = 1_767_225_600_000L;
    static final int DEFAULT_COHORT_SIZE = 50;
    static final double HIGH_UTILISATION_THRESHOLD = 0.90;
    static final int LOW_LIQUIDITY_THRESHOLD = 100;

    private BankingPolicyScenarioSupport() {
    }

    static void requireNativeScenarioLabExecution(String operation) {
        throw nativeScenarioLabExecutionUnavailable(operation);
    }

    static ai.astraform.remote.domain.sdk.RemoteDomainProtocolException nativeScenarioLabExecutionUnavailable(
            String operation) {
        return ai.astraform.remote.domain.sdk.RemoteDomainProtocolException.invalidRequest(
                java.util.UUID.randomUUID(), operation, "scenario_lab_native_execution_unavailable",
                "This historical Scenario Lab execution requires the retired global clock. "
                        + "Native Scenario Lab execution is not available yet. "
                        + "Stored run status and saved evidence remain readable.",
                Map.of("requiredCapability", "nativeScenarioLabExecution"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, entryValue) -> normalized.put(String.valueOf(key), entryValue));
            return normalized;
        }
        return new LinkedHashMap<>();
    }

    static Map<String, Object> linkedMap(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("linkedMap requires an even number of arguments.");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            normalized.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                normalized.add(objectMap(map));
            }
        }
        return normalized;
    }

    static List<Object> listValue(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    static Object nestedValue(Object payload, String dottedPath) {
        if (payload == null || dottedPath == null || dottedPath.isBlank()) {
            return null;
        }
        Object current = payload;
        for (String segment : dottedPath.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list && segment.matches("\\d+")) {
                int index = Integer.parseInt(segment);
                current = index >= 0 && index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    static boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    static double toDouble(Object value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    static double requiredFiniteDoubleValue(Map<String, Object> values, String fieldName, String label) {
        Object value = requiredField(values, fieldName, label);
        double parsed;
        if (value instanceof Number number) {
            parsed = number.doubleValue();
        } else {
            try {
                parsed = Double.parseDouble(String.valueOf(value).trim());
            } catch (Exception e) {
                throw new IllegalArgumentException(label + " " + fieldName + " must be a finite number.");
            }
        }
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException(label + " " + fieldName + " must be a finite number.");
        }
        return parsed;
    }

    static int intValue(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    static int requiredIntValue(Map<String, Object> values, String fieldName, String label) {
        Object value = requiredField(values, fieldName, label);
        BigInteger parsed = exactInteger(value, label + " " + fieldName);
        if (parsed.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                || parsed.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(label + " " + fieldName
                    + " must be an integer within 32-bit range.");
        }
        try {
            return parsed.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(label + " " + fieldName
                    + " must be an integer within 32-bit range.");
        }
    }

    private static BigInteger exactInteger(Object value, String label) {
        try {
            if (value instanceof BigInteger bigInteger) {
                return bigInteger;
            }
            if (value instanceof BigDecimal bigDecimal) {
                return bigDecimal.toBigIntegerExact();
            }
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return BigInteger.valueOf(((Number) value).longValue());
            }
            if (value instanceof Number number) {
                double asDouble = number.doubleValue();
                if (!Double.isFinite(asDouble)) {
                    throw new ArithmeticException("non-finite integer");
                }
                return BigDecimal.valueOf(asDouble).toBigIntegerExact();
            }
            return new BigDecimal(String.valueOf(value).trim()).toBigIntegerExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be an integer within 32-bit range.");
        }
    }

    static String requiredStringValue(Map<String, Object> values, String fieldName, String label) {
        return stringValue(requiredField(values, fieldName, label));
    }

    private static Object requiredField(Map<String, Object> values, String fieldName, String label) {
        if (values == null || !values.containsKey(fieldName) || blank(stringValue(values.get(fieldName)))) {
            throw new IllegalArgumentException(label + " " + fieldName + " is required.");
        }
        return values.get(fieldName);
    }

    static long longValue(Object value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    static double round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    static BigDecimal roundAmount(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal round4(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    static double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static String blankToNull(String value) {
        return blank(value) ? null : value;
    }

    static BigDecimal monthlyInterest(BigDecimal balance, BigDecimal rate) {
        return balance.abs().multiply(rate).divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP);
    }

    static String scenarioIdForPreset(String presetId) {
        return "banking_scenario_lab_" + presetId.replace('-', '_');
    }

    static String topSegment(List<Map<String, Object>> impactedSegments) {
        if (impactedSegments.isEmpty()) {
            return "unclassified";
        }
        return stringValue(impactedSegments.getFirst().get("segment"));
    }

    static String relativizeToWorkspace(Path workspaceRoot, Path path) {
        return workspaceRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    static String csv(Object value) {
        String raw = value == null ? "" : String.valueOf(value);
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }
}
