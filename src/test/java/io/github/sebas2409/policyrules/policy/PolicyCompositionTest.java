package io.github.sebas2409.policyrules.policy;

import io.github.sebas2409.policyrules.rule.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Policy composition")
class PolicyCompositionTest {

    private static final Rule<Integer> POSITIVE = value -> value > 0;
    private static final Rule<Integer> EVEN = value -> value % 2 == 0;

    @Test
    @DisplayName("allOf reports every reason in declaration order")
    void accumulatingCompositeReturnsEveryViolation() {
        var positive = Policies.require("positive", POSITIVE, "NOT_POSITIVE", "Must be positive");
        var even = Policies.require("even", EVEN, "NOT_EVEN", "Must be even");

        var result = Policies.allOf("all", List.of(positive, even)).evaluate(-3);

        assertTrue(result.denied());
        assertEquals(List.of("NOT_POSITIVE", "NOT_EVEN"), result.codes());
    }

    @Test
    @DisplayName("firstFailureOf does not evaluate members after the first denial")
    void failFastCompositeStopsAfterFirstDenial() {
        var laterEvaluations = new AtomicInteger();
        var denied = Policies.<Integer>require("denied", value -> false, "DENIED", "Denied");
        var later = Policies.<Integer>require("later", value -> {
            laterEvaluations.incrementAndGet();
            return true;
        }, "LATER", "Later");

        var result = Policies.firstFailureOf("fast", denied, later).evaluate(1);

        assertTrue(result.denied());
        assertEquals(List.of("DENIED"), result.codes());
        assertEquals(0, laterEvaluations.get());
    }

    @Test
    @DisplayName("builds the explanation only when the context is denied")
    void violationFactoryIsLazy() {
        var factoryCalls = new AtomicInteger();
        var policy = Policies.<Integer>require("always", value -> true, value -> {
            factoryCalls.incrementAndGet();
            return new PolicyViolation("NEVER", "Never");
        });

        assertTrue(policy.evaluate(1).allowed());
        assertEquals(0, factoryCalls.get());
    }

    @Test
    @DisplayName("explains a denial with values taken from the context")
    void violationFactoryReceivesTheDeniedContext() {
        var policy = Policies.<Integer>require(
                "weekly-limit",
                value -> value < 3,
                value -> new PolicyViolation(
                        "LIMIT_REACHED",
                        "Limit reached",
                        Map.of("current", value, "maximum", 3)
                )
        );

        var violation = policy.evaluate(5).firstViolation().orElseThrow();

        assertEquals("LIMIT_REACHED", violation.code());
        assertEquals(5, violation.metadata().get("current"));
    }

    @Test
    @DisplayName("forbid denies exactly when the rule holds")
    void forbidInvertsTheRule() {
        var policy = Policies.forbid("not-even", EVEN, "IS_EVEN", "Must not be even");

        assertTrue(policy.evaluate(3).allowed());
        assertTrue(policy.evaluate(4).denied());
    }

    @Test
    @DisplayName("adapt reuses a policy inside a wider context, keeping its id")
    void adaptReusesAPolicyForAWiderContext() {
        record Order(int quantity) {
        }
        var positive = Policies.require("positive", POSITIVE, "NOT_POSITIVE", "Must be positive");

        Policy<Order> orderPolicy = Policies.adapt(positive, Order::quantity);

        assertEquals("positive", orderPolicy.id());
        assertTrue(orderPolicy.evaluate(new Order(2)).allowed());
        assertEquals(List.of("NOT_POSITIVE"), orderPolicy.evaluate(new Order(-1)).codes());
    }

    @Test
    @DisplayName("enforce fails at the boundary and is silent when allowed")
    void enforceThrowsOnlyWhenDenied() {
        var policy = Policies.require("positive", POSITIVE, "NOT_POSITIVE", "Must be positive");

        policy.enforce(1);

        var exception = assertThrows(PolicyViolationException.class, () -> policy.enforce(-1));
        assertEquals(List.of("NOT_POSITIVE"), exception.codes());
    }

    @Test
    @DisplayName("constant policies allow and deny every context")
    void constantPolicies() {
        assertTrue(Policies.<Integer>allow("open").evaluate(1).allowed());
        assertFalse(Policies.<Integer>deny("closed", "CLOSED", "Closed").evaluate(1).allowed());
    }

    @Test
    @DisplayName("rejects an unusable composite instead of failing later")
    void rejectsInvalidCompositesEagerly() {
        assertThrows(IllegalArgumentException.class,
                () -> Policies.allOf("empty", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> Policies.require(" ", POSITIVE, "CODE", "Message"));
    }
}
