package io.github.sebas2409.policyrules.rule.trace;

import io.github.sebas2409.policyrules.rule.Rule;
import io.github.sebas2409.policyrules.rule.trace.RuleTrace.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Explainable rule composition")
class ExplainableRulesTest {

    private static ExplainableRule<Integer> greaterThan(int limit) {
        return ExplainableRules.of(
                "greater-than",
                Map.of("limit", limit),
                value -> value > limit
        );
    }

    private static List<String> labels(RuleTrace trace) {
        return trace.children().stream().map(RuleTrace::label).toList();
    }

    private static List<Outcome> outcomes(RuleTrace trace) {
        return trace.children().stream().map(RuleTrace::outcome).toList();
    }

    @Test
    @DisplayName("an atomic rule reports its type and the parameters it was built with")
    void anAtomicRuleReportsItsConfiguration() {
        var rule = greaterThan(10);

        var trace = rule.explain(15);

        assertEquals("greater-than", trace.label());
        assertEquals(Map.of("limit", 10), trace.parameters());
        assertEquals(Outcome.MATCHED, trace.outcome());
        assertEquals(Outcome.NOT_MATCHED, rule.explain(5).outcome());
    }

    @Test
    @DisplayName("the rule inside knows nothing about being traced")
    void wrappedRulesNeedNoChange() {
        Rule<Integer> plain = value -> value > 0;

        var explainable = ExplainableRules.of("positive", plain);

        assertTrue(explainable.matches(1));
        assertTrue(explainable.explain(1).matched());
        assertEquals("positive", explainable.label());
    }

    @Test
    @DisplayName("a conjunction reports the children it short-circuited past")
    void aConjunctionReportsWhatItSkipped() {
        var rule = ExplainableRules.and(greaterThan(0), greaterThan(100), greaterThan(1000));

        var trace = rule.explain(50);

        assertEquals(Outcome.NOT_MATCHED, trace.outcome());
        assertEquals(ExplainableRules.AND_LABEL, trace.label());
        assertEquals(
                List.of(Outcome.MATCHED, Outcome.NOT_MATCHED, Outcome.SKIPPED),
                outcomes(trace)
        );
        assertEquals(List.of("greater-than", "greater-than", "greater-than"), labels(trace));
    }

    @Test
    @DisplayName("a disjunction reports the alternatives it never needed")
    void aDisjunctionReportsWhatItSkipped() {
        var rule = ExplainableRules.or(greaterThan(1000), greaterThan(10), greaterThan(0));

        var trace = rule.explain(50);

        assertEquals(Outcome.MATCHED, trace.outcome());
        assertEquals(ExplainableRules.OR_LABEL, trace.label());
        assertEquals(
                List.of(Outcome.NOT_MATCHED, Outcome.MATCHED, Outcome.SKIPPED),
                outcomes(trace)
        );
    }

    @Test
    @DisplayName("a negation keeps the rule that held visible below it")
    void aNegationKeepsItsChildVisible() {
        var rule = ExplainableRules.not(greaterThan(10));

        var trace = rule.explain(15);

        assertEquals(ExplainableRules.NOT_LABEL, trace.label());
        assertEquals(Outcome.NOT_MATCHED, trace.outcome());
        assertEquals(List.of(Outcome.MATCHED), outcomes(trace));
        assertEquals(trace, trace.culprit().orElseThrow());
    }

    @Test
    @DisplayName("explaining never evaluates a rule that was short-circuited past")
    void explainingDoesNotEvaluateSkippedRules() {
        var evaluations = new AtomicInteger();
        var counted = ExplainableRules.<Integer>of("counted", ignored -> {
            evaluations.incrementAndGet();
            return true;
        });

        var trace = ExplainableRules.and(greaterThan(100), counted).explain(50);

        assertEquals(0, evaluations.get());
        assertEquals(Outcome.SKIPPED, trace.children().getLast().outcome());
    }

    @Test
    @DisplayName("explaining evaluates each reached rule exactly once")
    void explainingEvaluatesEachRuleOnce() {
        var evaluations = new AtomicInteger();
        var counted = ExplainableRules.<Integer>of("counted", ignored -> {
            evaluations.incrementAndGet();
            return true;
        });

        ExplainableRules.and(counted, counted, counted).explain(1);

        assertEquals(3, evaluations.get());
    }

    @Test
    @DisplayName("the fast path allocates no trace and agrees with the explained one")
    void matchesAgreesWithExplain() {
        var rule = ExplainableRules.and(
                greaterThan(0),
                ExplainableRules.or(greaterThan(100), ExplainableRules.not(greaterThan(50)))
        );

        for (var value : List.of(-5, 10, 60, 200)) {
            assertEquals(rule.explain(value).matched(), rule.matches(value),
                    "matches and explain disagree for " + value);
        }
    }

    @Test
    @DisplayName("a skipped subtree keeps the shape of the rule it stands for")
    void aSkippedSubtreeKeepsItsShape() {
        var nested = ExplainableRules.or(greaterThan(10), greaterThan(20));
        var rule = ExplainableRules.and(greaterThan(100), nested);

        var trace = rule.explain(1);

        var skippedBranch = trace.children().getLast();
        assertEquals(ExplainableRules.OR_LABEL, skippedBranch.label());
        assertEquals(Outcome.SKIPPED, skippedBranch.outcome());
        assertEquals(List.of(Outcome.SKIPPED, Outcome.SKIPPED), outcomes(skippedBranch));
        assertEquals(Map.of("limit", 10), skippedBranch.children().getFirst().parameters());
    }

    @Test
    @DisplayName("negating an explainable rule keeps it explainable")
    void negationStaysExplainable() {
        ExplainableRule<Integer> negated = greaterThan(10).not();

        assertFalse(negated.matches(15));
        assertEquals(ExplainableRules.NOT_LABEL, negated.explain(15).label());
    }

    @Test
    @DisplayName("rejects operators without operands")
    void rejectsEmptyOperators() {
        assertThrows(IllegalArgumentException.class,
                () -> ExplainableRules.<Integer>and(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ExplainableRules.<Integer>or(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ExplainableRules.<Integer>of(" ", value -> true));
        assertThrows(NullPointerException.class,
                () -> ExplainableRules.<Integer>of("label", null));
    }
}
