package io.github.sebas2409.policyrules.rule.definition;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Explaining a rule built from configuration")
class RuleCompilerExplainTest {

    private static RuleCompiler<Integer> compiler() {
        var registry = new RuleRegistry<Integer>()
                .register("greater-than", parameters -> {
                    var limit = parameters.intValue("limit");
                    return value -> value > limit;
                })
                .register("even", parameters -> value -> value % 2 == 0);
        return new RuleCompiler<>(registry);
    }

    @Test
    @DisplayName("labels every node with the type and parameters of the definition")
    void labelsNodesFromTheDefinition() {
        var definition = RuleDefinitions.and(
                RuleDefinitions.atomic("greater-than", Map.of("limit", 10)),
                RuleDefinitions.atomic("even")
        );

        var trace = compiler().compileExplainable(definition).explain(12);

        assertEquals(ExplainableRules.AND_LABEL, trace.label());
        assertEquals(
                List.of("greater-than", "even"),
                trace.children().stream().map(RuleTrace::label).toList()
        );
        assertEquals(Map.of("limit", 10), trace.children().getFirst().parameters());
        assertTrue(trace.matched());
    }

    @Test
    @DisplayName("registered factories stay plain rules and are never asked to explain")
    void registeredFactoriesNeedNoChange() {
        var builds = new AtomicInteger();
        var registry = new RuleRegistry<Integer>().register("counted", parameters -> {
            builds.incrementAndGet();
            return value -> value > 0;
        });

        var rule = new RuleCompiler<>(registry).compileExplainable(
                RuleDefinitions.atomic("counted")
        );
        rule.matches(1);
        rule.explain(1);
        rule.explain(2);

        assertEquals(1, builds.get());
    }

    @Test
    @DisplayName("the trace mirrors the shape of the definition, including negations")
    void theTraceMirrorsTheDefinition() {
        var definition = RuleDefinitions.or(
                RuleDefinitions.atomic("greater-than", Map.of("limit", 100)),
                RuleDefinitions.not(RuleDefinitions.atomic("even"))
        );

        var trace = compiler().compileExplainable(definition).explain(7);

        assertEquals(ExplainableRules.OR_LABEL, trace.label());
        assertEquals(Outcome.NOT_MATCHED, trace.children().getFirst().outcome());
        var negation = trace.children().getLast();
        assertEquals(ExplainableRules.NOT_LABEL, negation.label());
        assertEquals(Outcome.MATCHED, negation.outcome());
        assertEquals("even", negation.children().getFirst().label());
        assertTrue(trace.matched());
    }

    @Test
    @DisplayName("points at the atomic node that decided a denial")
    void reportsTheNodeThatDecided() {
        var definition = RuleDefinitions.and(
                RuleDefinitions.atomic("even"),
                RuleDefinitions.atomic("greater-than", Map.of("limit", 100))
        );

        var culprit = compiler().compileExplainable(definition).explain(12).culprit().orElseThrow();

        assertEquals("greater-than", culprit.label());
        assertEquals(Map.of("limit", 100), culprit.parameters());
        assertEquals(Outcome.NOT_MATCHED, culprit.outcome());
    }

    @Test
    @DisplayName("answers exactly like the rule compiled without traces")
    void agreesWithThePlainCompiledRule() {
        var definition = RuleDefinitions.and(
                RuleDefinitions.atomic("greater-than", Map.of("limit", 10)),
                RuleDefinitions.not(RuleDefinitions.atomic("even"))
        );
        var compiler = compiler();

        var plain = compiler.compile(definition);
        var explainable = compiler.compileExplainable(definition);

        for (var value : List.of(-1, 4, 11, 12, 13)) {
            assertEquals(plain.matches(value), explainable.matches(value),
                    "the two compilations disagree for " + value);
            assertEquals(plain.matches(value), explainable.explain(value).matched(),
                    "the trace disagrees with the plain rule for " + value);
        }
    }

    @Test
    @DisplayName("reads a document and explains it, which is the whole loop")
    void compilesAndExplainsADocument() {
        var document = Map.<String, Object>of(
                "operator", "and",
                "rules", List.of(
                        Map.of("type", "greater-than", "parameters", Map.of("limit", 10)),
                        Map.of("type", "even")
                )
        );

        var rule = compiler().compileExplainable(RuleDefinitionCodec.read(document));
        var trace = rule.explain(11);

        assertFalse(trace.matched());
        assertEquals("even", trace.culprit().orElseThrow().label());
    }

    @Test
    @DisplayName("still reports configuration problems while compiling")
    void rejectsInvalidConfigurationAtCompileTime() {
        var compiler = compiler();

        assertThrows(UnknownRuleTypeException.class,
                () -> compiler.compileExplainable(RuleDefinitions.atomic("unknown")));
        assertThrows(RuleParameterException.class,
                () -> compiler.compileExplainable(
                        RuleDefinitions.atomic("greater-than", Map.of("limit", "ten"))));
        assertThrows(NullPointerException.class, () -> compiler.compileExplainable(null));
    }
}
