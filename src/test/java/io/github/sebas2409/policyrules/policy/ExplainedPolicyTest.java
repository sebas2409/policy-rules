package io.github.sebas2409.policyrules.policy;

import io.github.sebas2409.policyrules.rule.trace.ExplainableRule;
import io.github.sebas2409.policyrules.rule.trace.ExplainableRules;
import io.github.sebas2409.policyrules.rule.trace.RuleTrace;
import io.github.sebas2409.policyrules.rule.trace.RuleTrace.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Policies that explain a denial")
class ExplainedPolicyTest {

    private static ExplainableRule<Integer> greaterThan(int limit) {
        return ExplainableRules.of("greater-than", Map.of("limit", limit), value -> value > limit);
    }

    private static RuleTrace traceOf(PolicyResult result, String key) {
        return assertInstanceOf(
                RuleTrace.class,
                result.firstViolation().orElseThrow().metadata().get(key)
        );
    }

    @Test
    @DisplayName("an allowed context carries no explanation")
    void allowedContextsCarryNothing() {
        var policy = Policies.requireExplained(
                "above-ten", greaterThan(10), "TOO_SMALL", "The value is too small");

        assertTrue(policy.evaluate(15).allowed());
        assertTrue(policy.evaluate(15).violations().isEmpty());
    }

    @Test
    @DisplayName("a denial carries the whole trace and the node that decided it")
    void deniedContextsCarryTheTrace() {
        var policy = Policies.requireExplained(
                "within-limits",
                ExplainableRules.and(greaterThan(0), greaterThan(100), greaterThan(1000)),
                "OUT_OF_RANGE",
                "The value is outside the accepted range"
        );

        var result = policy.evaluate(50);

        assertTrue(result.denied());
        assertEquals("OUT_OF_RANGE", result.firstViolation().orElseThrow().code());

        var trace = traceOf(result, Policies.TRACE_METADATA_KEY);
        assertEquals(ExplainableRules.AND_LABEL, trace.label());
        assertEquals(
                List.of(Outcome.MATCHED, Outcome.NOT_MATCHED, Outcome.SKIPPED),
                trace.children().stream().map(RuleTrace::outcome).toList()
        );

        var culprit = traceOf(result, Policies.CULPRIT_METADATA_KEY);
        assertEquals("greater-than", culprit.label());
        assertEquals(Map.of("limit", 100), culprit.parameters());
    }

    @Test
    @DisplayName("the rule is evaluated once per decision, not once per method")
    void evaluatesTheRuleOnce() {
        var evaluations = new AtomicInteger();
        var counted = ExplainableRules.<Integer>of("counted", ignored -> {
            evaluations.incrementAndGet();
            return false;
        });
        var policy = Policies.requireExplained("counted", counted, "DENIED", "Denied");

        policy.evaluate(1);

        assertEquals(1, evaluations.get());
    }

    @Test
    @DisplayName("enriches a violation built from the denied context")
    void enrichesACustomViolation() {
        var policy = Policies.<Integer>requireExplained(
                "above-ten",
                greaterThan(10),
                value -> new PolicyViolation("TOO_SMALL", "Too small", Map.of("value", value))
        );

        var violation = policy.evaluate(3).firstViolation().orElseThrow();

        assertEquals(3, violation.metadata().get("value"));
        assertInstanceOf(RuleTrace.class, violation.metadata().get(Policies.TRACE_METADATA_KEY));
    }

    @Test
    @DisplayName("a forbidden condition reports the negation and the rule that held")
    void forbiddenConditionsKeepTheNegationVisible() {
        var policy = Policies.forbidExplained(
                "not-above-ten", greaterThan(10), "TOO_BIG", "The value is too big");

        var result = policy.evaluate(50);

        assertTrue(result.denied());
        var trace = traceOf(result, Policies.TRACE_METADATA_KEY);
        assertEquals(ExplainableRules.NOT_LABEL, trace.label());
        assertEquals(Outcome.MATCHED, trace.children().getFirst().outcome());
        assertEquals("greater-than", trace.children().getFirst().label());
        assertTrue(policy.evaluate(5).allowed());
    }

    @Test
    @DisplayName("composes with the ordinary policies, since it is one of them")
    void composesWithOrdinaryPolicies() {
        var explained = Policies.requireExplained(
                "above-ten", greaterThan(10), "TOO_SMALL", "The value is too small");
        var plain = Policies.<Integer>require(
                "even", value -> value % 2 == 0, "ODD", "The value is odd");

        var combined = Policies.allOf("both", explained, plain);
        var result = combined.evaluate(5);

        assertFalse(result.allowed());
        assertEquals(List.of("TOO_SMALL", "ODD"), result.codes());
    }
}
