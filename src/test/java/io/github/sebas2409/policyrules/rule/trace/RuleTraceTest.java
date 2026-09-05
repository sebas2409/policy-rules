package io.github.sebas2409.policyrules.rule.trace;

import io.github.sebas2409.policyrules.rule.trace.RuleTrace.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuleTrace")
class RuleTraceTest {

    private static RuleTrace leaf(String label, Outcome outcome) {
        return RuleTrace.leaf(label, Map.of(), outcome);
    }

    @Test
    @DisplayName("is immutable, whatever it was built from")
    void isImmutable() {
        var trace = RuleTrace.composite("and", Outcome.MATCHED, List.of(
                RuleTrace.leaf("limit", Map.of("max", 10), Outcome.MATCHED)
        ));

        assertThrows(UnsupportedOperationException.class, () -> trace.children().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> trace.children().getFirst().parameters().clear());
    }

    @Test
    @DisplayName("rejects a node without a name")
    void rejectsBlankLabels() {
        assertThrows(IllegalArgumentException.class, () -> leaf(" ", Outcome.MATCHED));
        assertThrows(NullPointerException.class, () -> leaf(null, Outcome.MATCHED));
        assertThrows(NullPointerException.class, () -> RuleTrace.leaf("x", Map.of(), null));
    }

    @Test
    @DisplayName("reports the outcome of the node")
    void reportsTheOutcome() {
        assertTrue(leaf("x", Outcome.MATCHED).matched());
        assertFalse(leaf("x", Outcome.NOT_MATCHED).matched());
        assertFalse(leaf("x", Outcome.SKIPPED).matched());
        assertTrue(leaf("x", Outcome.SKIPPED).skipped());
    }

    @Test
    @DisplayName("a node that held has nothing to explain")
    void satisfiedNodesHaveNoCulprit() {
        assertTrue(leaf("x", Outcome.MATCHED).culprit().isEmpty());
        assertTrue(leaf("x", Outcome.SKIPPED).culprit().isEmpty());
    }

    @Test
    @DisplayName("an atomic rule that did not hold explains itself")
    void anAtomicFailureExplainsItself() {
        var trace = leaf("minimum-score", Outcome.NOT_MATCHED);

        assertEquals(trace, trace.culprit().orElseThrow());
    }

    @Test
    @DisplayName("a conjunction is explained by its one failing child, however deep")
    void aConjunctionIsExplainedByItsFailingChild() {
        var deepest = leaf("amount-at-most", Outcome.NOT_MATCHED);
        var trace = RuleTrace.composite("and", Outcome.NOT_MATCHED, List.of(
                leaf("country-in", Outcome.MATCHED),
                RuleTrace.composite("and", Outcome.NOT_MATCHED, List.of(
                        leaf("channel-in", Outcome.MATCHED),
                        deepest
                )),
                leaf("minimum-score", Outcome.SKIPPED)
        ));

        assertEquals(deepest, trace.culprit().orElseThrow());
    }

    @Test
    @DisplayName("a disjunction whose alternatives all failed has no single cause")
    void aDisjunctionWithoutAlternativesExplainsItself() {
        var trace = RuleTrace.composite("or", Outcome.NOT_MATCHED, List.of(
                leaf("branch-limit", Outcome.NOT_MATCHED),
                leaf("online-limit", Outcome.NOT_MATCHED)
        ));

        assertEquals(trace, trace.culprit().orElseThrow());
    }

    @Test
    @DisplayName("a negation explains itself, since its child is what held")
    void aNegationExplainsItself() {
        var trace = RuleTrace.composite("not", Outcome.NOT_MATCHED, List.of(
                leaf("sanctioned", Outcome.MATCHED)
        ));

        assertEquals(trace, trace.culprit().orElseThrow());
    }

    @Test
    @DisplayName("marks a whole subtree as never evaluated")
    void marksASubtreeAsSkipped() {
        var trace = RuleTrace.composite("and", Outcome.MATCHED, List.of(
                leaf("a", Outcome.MATCHED),
                RuleTrace.composite("or", Outcome.MATCHED, List.of(leaf("b", Outcome.MATCHED)))
        ));

        var skipped = trace.asSkipped();

        assertTrue(skipped.skipped());
        assertTrue(skipped.children().stream().allMatch(RuleTrace::skipped));
        assertTrue(skipped.children().getLast().children().stream().allMatch(RuleTrace::skipped));
    }

    @Test
    @DisplayName("renders as an indented tree, with the parameters of each rule")
    void rendersAnIndentedTree() {
        var trace = RuleTrace.composite("and", Outcome.NOT_MATCHED, List.of(
                RuleTrace.leaf("channel-in", Map.of("channels", List.of("ONLINE")), Outcome.MATCHED),
                RuleTrace.leaf("amount-at-most", Map.of("max", 10000), Outcome.NOT_MATCHED),
                leaf("minimum-score", Outcome.SKIPPED)
        ));

        assertEquals(
                List.of(
                        "and -> NOT_MATCHED",
                        "  channel-in {channels=[ONLINE]} -> MATCHED",
                        "  amount-at-most {max=10000} -> NOT_MATCHED",
                        "  minimum-score -> SKIPPED"
                ),
                trace.format().lines().toList()
        );
    }
}
