package com.policyrules.rule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Rule composition")
class RulesTest {

    @Test
    @DisplayName("binary operators short-circuit like the boolean operators")
    void staticRuleCompositionShortCircuits() {
        var evaluations = new AtomicInteger();
        Rule<Integer> denied = ignored -> false;
        Rule<Integer> counted = ignored -> {
            evaluations.incrementAndGet();
            return true;
        };

        assertFalse(denied.and(counted).matches(1));
        assertTrue(denied.or(counted).matches(1));
        assertTrue(denied.not().matches(1));
        assertEquals(1, evaluations.get());
    }

    @Test
    @DisplayName("allOf stops at the first rule that does not hold")
    void allOfShortCircuits() {
        var evaluations = new AtomicInteger();
        Rule<Integer> positive = value -> value > 0;
        Rule<Integer> counted = ignored -> {
            evaluations.incrementAndGet();
            return true;
        };

        assertFalse(Rules.allOf(positive, counted).matches(-1));
        assertEquals(0, evaluations.get());
        assertTrue(Rules.allOf(positive, counted).matches(1));
        assertEquals(1, evaluations.get());
    }

    @Test
    @DisplayName("anyOf stops at the first rule that holds")
    void anyOfShortCircuits() {
        var evaluations = new AtomicInteger();
        Rule<Integer> positive = value -> value > 0;
        Rule<Integer> counted = ignored -> {
            evaluations.incrementAndGet();
            return true;
        };

        assertTrue(Rules.anyOf(positive, counted).matches(1));
        assertEquals(0, evaluations.get());
    }

    @Test
    @DisplayName("empty combinations use the neutral element of the operator")
    void emptyCombinationsUseNeutralElements() {
        assertTrue(Rules.<Integer>allOf(List.of()).matches(1));
        assertFalse(Rules.<Integer>anyOf(List.of()).matches(1));
        assertTrue(Rules.<Integer>alwaysTrue().matches(1));
        assertFalse(Rules.<Integer>alwaysFalse().matches(1));
    }

    @Test
    @DisplayName("adapt reuses a rule inside a wider context")
    void adaptReusesARuleForAWiderContext() {
        record Customer(int age) {
        }
        record Order(Customer customer) {
        }
        Rule<Customer> adult = customer -> customer.age() >= 18;

        Rule<Order> orderedByAdult = adult.adapt(Order::customer);

        assertTrue(orderedByAdult.matches(new Order(new Customer(20))));
        assertFalse(orderedByAdult.matches(new Order(new Customer(16))));
    }

    @Test
    @DisplayName("interoperates with the standard library")
    void convertsFromAndToPredicate() {
        var rule = Rules.<Integer>of(value -> value > 0);

        assertTrue(rule.matches(1));
        assertEquals(List.of(1, 2), List.of(-1, 1, 2).stream().filter(rule.asPredicate()).toList());
    }
}
