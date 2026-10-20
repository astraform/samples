package ai.astraform.remote.domain.template;

import ai.astraform.remote.domain.sdk.PopulationCatalogs;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Starter preflight for domain engineers editing the provider-owned population catalog.
 */
public final class PopulationCatalogPreflight {
    private static final String INVALID_CATALOG_PREFIX = "Invalid population catalog:";
    static final Path DEFAULT_CATALOG = Path.of(
            "src/main/resources/population-catalogs/acme-remote.population-catalog.json");

    private PopulationCatalogPreflight() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length > 1) {
            err.println("usage: mvn exec:java [-Dexec.args=path/to/population-catalog.json]");
            return 2;
        }
        Path catalogPath = args.length == 1 ? Path.of(args[0]) : defaultCatalogPath();
        try {
            out.println(summary(PopulationCatalogs.fromPath(catalogPath)));
            return 0;
        } catch (IllegalArgumentException error) {
            err.println("Invalid population catalog: " + readableMessage(error));
            err.println("Fix the field named in the error, then run the population catalog preflight again.");
            return 1;
        }
    }

    static String summary(Map<String, Object> catalog) {
        String domainId = String.valueOf(catalog.getOrDefault("domainId", "unknown-domain"));
        String catalogVersion = String.valueOf(catalog.getOrDefault("catalogVersion", "unknown-version"));
        int archetypeCount = listSize(object(catalog, "archetypeCatalog").get("archetypes"));
        int recipeCount = listSize(object(catalog, "populationRecipes").get("recipes"));
        return "OK: " + domainId + " population catalog " + catalogVersion
                + " (" + archetypeCount + " " + plural("archetype", archetypeCount)
                + ", " + recipeCount + " " + plural("recipe", recipeCount) + ")";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private static String plural(String word, int count) {
        return count == 1 ? word : word + "s";
    }

    private static Path defaultCatalogPath() {
        if (Files.exists(DEFAULT_CATALOG)) {
            return DEFAULT_CATALOG;
        }
        Path moduleRelative = Path.of("starter-java-spring").resolve(DEFAULT_CATALOG);
        return Files.exists(moduleRelative) ? moduleRelative : DEFAULT_CATALOG;
    }

    private static String readableMessage(IllegalArgumentException error) {
        String deepest = deepestMessage(error);
        if (deepest != null && deepest.startsWith(INVALID_CATALOG_PREFIX)) {
            return deepest.substring(INVALID_CATALOG_PREFIX.length()).trim();
        }
        return error.getMessage() == null || error.getMessage().isBlank()
                ? deepest
                : error.getMessage();
    }

    private static String deepestMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? error.getMessage() : message;
    }
}
