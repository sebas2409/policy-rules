package com.policyrules.rule.definition;

import com.policyrules.rule.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuleCompiler")
class RuleCompilerTest {

    private static RuleRegistry<Integer> registryWithGreaterThan() {
        return new RuleRegistry<Integer>().register(
                "greater-than",
                parameters -> {
                    var limit = parameters.intValue("limit");
                    return value -> value > limit;
                }
        );
    }

    @Test
    @DisplayName("compiles a nested definition into an executable rule")
    void compilesNestedDefinition() {
        var compiler = new RuleCompiler<>(registryWithGreaterThan());
        var definition = RuleDefinitions.and(
                RuleDefinitions.atomic("greater-than", Map.of("limit", 10)),
                RuleDefinitions.not(RuleDefinitions.atomic("greater-than", Map.of("limit", 20)))
        );

        var rule = compiler.compile(definition);

        assertTrue(rule.matches(15));
        assertFalse(rule.matches(5));
        assertFalse(rule.matches(25));
    }

    @Test
    @DisplayName("keeps declaration order and short-circuits compiled composites")
    void compiledCompositesShortCircuit() {
        var evaluations = new AtomicInteger();
        var registry = new RuleRegistry<Integer>()
                .register("greater-than", parameters -> {
                    var limit = parameters.intValue("limit");
                    return value -> value > limit;
                })
                .register("counted", parameters -> value -> {
                    evaluations.incrementAndGet();
                    return true;
                });
        var rule = new RuleCompiler<>(registry).compile(RuleDefinitions.and(
                RuleDefinitions.atomic("greater-than", Map.of("limit", 10)),
                RuleDefinitions.atomic("counted")
        ));

        assertFalse(rule.matches(1));
        assertEquals(0, evaluations.get());
    }

    @Test
    @DisplayName("builds each rule once, at compile time")
    void factoriesRunAtCompileTimeOnly() {
        var builds = new AtomicInteger();
        var registry = new RuleRegistry<Integer>().register("counted", parameters -> {
            builds.incrementAndGet();
            return value -> true;
        });

        var rule = new RuleCompiler<>(registry).compile(RuleDefinitions.atomic("counted"));
        rule.matches(1);
        rule.matches(2);

        assertEquals(1, builds.get());
    }

    @Test
    @DisplayName("reports an unregistered type with the types it does know")
    void rejectsUnknownTypes() {
        var compiler = new RuleCompiler<>(registryWithGreaterThan());

        var exception = assertThrows(
                UnknownRuleTypeException.class,
                () -> compiler.compile(RuleDefinitions.atomic("unknown"))
        );

        assertEquals("unknown", exception.type());
        assertEquals(java.util.Set.of("greater-than"), exception.knownTypes());
        assertTrue(exception instanceof RuleConfigurationException);
    }

    @Test
    @DisplayName("reports an invalid parameter while compiling, not while evaluating")
    void rejectsInvalidParametersAtCompileTime() {
        var compiler = new RuleCompiler<>(registryWithGreaterThan());

        var exception = assertThrows(
                RuleParameterException.class,
                () -> compiler.compile(RuleDefinitions.atomic("greater-than", Map.of("limit", "ten")))
        );

        assertEquals("limit", exception.parameter());
    }

    @Test
    @DisplayName("rejects invalid configuration early")
    void rejectsInvalidConfigurationEarly() {
        var registry = new RuleRegistry<Integer>().register("known", parameters -> value -> true);

        assertThrows(IllegalArgumentException.class,
                () -> registry.register("known", parameters -> value -> false));
        assertThrows(IllegalArgumentException.class,
                () -> RuleDefinitions.and(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AtomicRuleDefinition(" "));
    }

    @Test
    @DisplayName("rejects a factory that returns nothing")
    void rejectsNullRules() {
        var registry = new RuleRegistry<Integer>().register("broken", parameters -> null);

        assertThrows(RuleConfigurationException.class,
                () -> new RuleCompiler<>(registry).compile(RuleDefinitions.atomic("broken")));
    }

    @Test
    @DisplayName("exposes the catalog of accepted types")
    void exposesRegisteredTypes() {
        var registry = registryWithGreaterThan();

        assertTrue(registry.contains("greater-than"));
        assertFalse(registry.contains("unknown"));
        assertEquals(java.util.Set.of("greater-than"), registry.types());
        assertThrows(UnsupportedOperationException.class, () -> registry.types().clear());
    }

    @Test
    @DisplayName("a compiled rule is a plain rule, usable by any policy")
    void compiledRulesComposeWithHandWrittenOnes() {
        Rule<Integer> even = value -> value % 2 == 0;
        var compiled = new RuleCompiler<>(registryWithGreaterThan())
                .compile(RuleDefinitions.atomic("greater-than", Map.of("limit", 10)));

        assertTrue(compiled.and(even).matches(12));
        assertFalse(compiled.and(even).matches(11));
    }
}
