package ai.astraform.samples.marketingops.creativeagency.service;

import ai.astraform.samples.marketingops.creativeagency.model.CreativeAgencyScenarioKey;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DeterministicCreativeAgencyResponseGenerator implements CreativeAgencyResponseGenerator {

    public static final String BINDING_REVISION = "astraform.marketing-creative-deterministic.v3";

    @Override
    public GenerationResult generate(GenerationRequest request) {
        CreativeAgencyScenarioKey scenario = resolveScenario(request);
        String disposition = resolveDisposition(scenario, request);
        return switch (scenario) {
            case CREATIVE_REWORK -> new GenerationResult(
                    scenario, disposition,
                    request.followUp()
                            ? "Update captured for the rework brief."
                            : "We are treating this as a real rework, not a cosmetic polish pass.",
                    List.of(
                            "Collapse the brief to one audience, one promise, and one CTA.",
                            "Strip vague claims and rebuild the asset around the conversion path.",
                            "Return revised copy and layout notes by next business day."),
                    List.of(
                            "target channel and format",
                            "any mandatory disclaimers or legal copy",
                            "the non-negotiable launch date"),
                    "deterministic",
                    "creative-agency-rules",
                    BINDING_REVISION,
                "deterministic",
                    Map.of());
            case ASSET_REVIEW -> assetReview(disposition);
            case LAUNCH_BLOCKER -> launchBlocker(disposition);
        };
    }

    private GenerationResult assetReview(String disposition) {
        boolean requestChanges = "REQUEST_CHANGES".equals(disposition);
        return new GenerationResult(
                CreativeAgencyScenarioKey.ASSET_REVIEW, disposition,
                requestChanges
                        ? "Brand/compliance risk is too high to approve as-is."
                        : "Directionally usable, but it still needs cleanup before release.",
                requestChanges
                        ? List.of(
                                "Remove absolute or unsupported claims.",
                                "Add the missing disclaimer and tighten the CTA language.",
                                "Re-submit with one clean hero message instead of stacked promises.")
                        : List.of(
                                "Keep the concept, but tighten headline hierarchy.",
                                "Standardize tone against the campaign brief before publish.",
                                "Ship only after the final disclaimer check."),
                List.of(),
                "deterministic",
                "creative-agency-rules",
                BINDING_REVISION,
                "deterministic",
                Map.of());
    }

    private GenerationResult launchBlocker(String disposition) {
        if ("AWAIT_REVIEW".equals(disposition)) {
            return new GenerationResult(
                    CreativeAgencyScenarioKey.LAUNCH_BLOCKER, disposition,
                    "The campaign review is still pending; an external launch blocker has not been established.",
                    List.of("Share the campaign review findings when available so we can assess any external dependency."),
                    List.of("the campaign readiness review findings"),
                    "deterministic", "creative-agency-rules", BINDING_REVISION, "deterministic", Map.of());
        }
        if ("NO_EXTERNAL_BLOCKER".equals(disposition)) {
            return new GenerationResult(
                    CreativeAgencyScenarioKey.LAUNCH_BLOCKER, disposition,
                    "The reported campaign state does not identify an external launch blocker.",
                    List.of("Share a new confirmed external dependency if the campaign situation changes."),
                    List.of(),
                    "deterministic", "creative-agency-rules", BINDING_REVISION, "deterministic", Map.of());
        }
        boolean escalate = "ESCALATE".equals(disposition);
        return new GenerationResult(
                CreativeAgencyScenarioKey.LAUNCH_BLOCKER, disposition,
                escalate
                        ? "This blocker touches an external dependency, so we are escalating instead of pretending we can brute-force it."
                        : "This looks operationally recoverable with a tighter execution plan.",
                escalate
                        ? List.of(
                                "Escalate to the owning vendor or legal approver now.",
                                "Lock the launch clock and communicate the dependency risk.",
                                "Re-plan asset delivery for end-of-day confirmation instead of wishful same-hour promises.")
                        : List.of(
                                "Narrow the launch scope to the critical asset set.",
                                "Replace missing dependencies with approved fallback creative.",
                                "Confirm readiness in 30 minutes and publish the revised ETA."),
                List.of(),
                "deterministic",
                "creative-agency-rules",
                BINDING_REVISION,
                "deterministic",
                Map.of());
    }

    private CreativeAgencyScenarioKey resolveScenario(GenerationRequest request) {
        if (request.requestedScenario() != null) return request.requestedScenario();
        if (request.previousScenario() != null) return request.previousScenario();
        if (containsAny(request.userInput(), "blocker", "blocked", "launch", "vendor", "legal", "deadline", "escalate")) {
            return CreativeAgencyScenarioKey.LAUNCH_BLOCKER;
        }
        if (containsAny(request.userInput(), "review", "approval", "approve", "brand", "compliance", "claim", "disclaimer")) {
            return CreativeAgencyScenarioKey.ASSET_REVIEW;
        }
        return CreativeAgencyScenarioKey.CREATIVE_REWORK;
    }

    private String resolveDisposition(CreativeAgencyScenarioKey scenario, GenerationRequest request) {
        Object queueState = request.facts().get("queueState");
        if (scenario == CreativeAgencyScenarioKey.LAUNCH_BLOCKER && queueState != null) {
            if ("review_pending".equals(queueState)) return "AWAIT_REVIEW";
            if (!"blocked".equals(queueState)) return "NO_EXTERNAL_BLOCKER";
        }
        return switch (scenario) {
            case CREATIVE_REWORK -> "REVISE";
            case ASSET_REVIEW -> containsAny(request.userInput(), "guarantee", "guaranteed", "regulated", "legal", "disclaimer", "claim", "urgent")
                    ? "REQUEST_CHANGES" : "APPROVE_WITH_NOTES";
            case LAUNCH_BLOCKER -> containsAny(request.userInput(), "vendor", "legal", "compliance", "brand", "approval", "external")
                    ? "ESCALATE" : "UNBLOCK_PLAN";
        };
    }

    private boolean containsAny(String text, String... keywords) {
        String haystack = text.toLowerCase(Locale.ROOT);
        return Arrays.stream(keywords).anyMatch(haystack::contains);
    }
}
