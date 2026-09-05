package io.github.sebas2409.policyrules.rule.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The record of how a rule reached its answer for one context.
 *
 * <p>A rule answers {@code true} or {@code false}; when the rule is a tree of
 * operators built from configuration, that answer alone is not enough to act
 * on. The question anyone editing rules asks next is <em>which node decided
 * it</em>, and this type is the answer: a tree that mirrors the rule that
 * produced it, one node per operator and one leaf per atomic rule, each with
 * its own outcome.</p>
 *
 * <p>A trace is plain, immutable data. The library never logs it, never
 * serializes it and never decides what it is for: an application may render it
 * in a screen, attach it to a violation, store it for an audit, or discard
 * it.</p>
 *
 * {@snippet lang = "java":
 * RuleTrace trace = eligible.explain(application);
 *
 * if (!trace.matched()) {
 *     RuleTrace culprit = trace.culprit().orElse(trace);
 *     System.out.println(trace.format());
 * }
 *}
 *
 * <h2>Skipped nodes are part of the answer</h2>
 * <p>Rules short-circuit, so a failing conjunction leaves its remaining
 * children unevaluated. Reporting those as failed would be a lie and reporting
 * them as satisfied would be another, so they appear as
 * {@link Outcome#SKIPPED}. A trace therefore describes what actually happened,
 * not what a full evaluation would have produced.</p>
 *
 * @param label      name of the rule that produced this node: the operator for
 *                   a composite, the registered type for an atomic rule
 * @param outcome    what happened at this node
 * @param parameters configuration the atomic rule was built with; empty for
 *                   composites
 * @param children   nodes below this one, in evaluation order; empty for leaves
 * @see ExplainableRule
 */
public record RuleTrace(
        String label,
        Outcome outcome,
        Map<String, Object> parameters,
        List<RuleTrace> children
) {

    /**
     * What happened at a single node of an evaluation.
     */
    public enum Outcome {

        /** The rule held for the context. */
        MATCHED,

        /** The rule was evaluated and did not hold. */
        NOT_MATCHED,

        /** The rule was never evaluated, because an operator short-circuited. */
        SKIPPED
    }

    /**
     * Validates the node and takes defensive copies of its collections.
     *
     * @throws NullPointerException     if any component is null
     * @throws IllegalArgumentException if {@code label} is blank
     */
    public RuleTrace {
        Objects.requireNonNull(label, "label must not be null");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        Objects.requireNonNull(outcome, "outcome must not be null");
        parameters = Map.copyOf(
                Objects.requireNonNull(parameters, "parameters must not be null")
        );
        children = List.copyOf(
                Objects.requireNonNull(children, "children must not be null")
        );
    }

    /**
     * Creates the trace of an atomic rule.
     *
     * @param label      registered type of the rule
     * @param parameters configuration the rule was built with
     * @param outcome    what happened at the rule
     * @return a leaf node
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if {@code label} is blank
     */
    public static RuleTrace leaf(String label, Map<String, Object> parameters, Outcome outcome) {
        return new RuleTrace(label, outcome, parameters, List.of());
    }

    /**
     * Creates the trace of an operator.
     *
     * @param label    name of the operator
     * @param outcome  what the operator concluded
     * @param children traces of its children, in evaluation order
     * @return a composite node
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if {@code label} is blank
     */
    public static RuleTrace composite(String label, Outcome outcome, List<RuleTrace> children) {
        return new RuleTrace(label, outcome, Map.of(), children);
    }

    /**
     * Tells whether the rule that produced this node held.
     *
     * @return {@code true} when the outcome is {@link Outcome#MATCHED}
     */
    public boolean matched() {
        return outcome == Outcome.MATCHED;
    }

    /**
     * Tells whether this node was reached at all.
     *
     * @return {@code true} when the outcome is {@link Outcome#SKIPPED}
     */
    public boolean skipped() {
        return outcome == Outcome.SKIPPED;
    }

    /**
     * Finds the node that explains why this one did not hold.
     *
     * <p>The search walks down while exactly one child failed, which is the
     * shape a short-circuiting conjunction leaves behind, and stops as soon as
     * the reason stops being a single node:</p>
     *
     * <ul>
     *   <li>An atomic rule that did not hold explains itself.</li>
     *   <li>A conjunction is explained by its one failing child, recursively:
     *       the deepest single cause.</li>
     *   <li>A disjunction whose alternatives all failed has no single cause, so
     *       it explains itself.</li>
     *   <li>A negation is explained by itself, since its child <em>did</em>
     *       hold and the problem is the negation, not the child.</li>
     * </ul>
     *
     * @return the deepest node that accounts for the failure, or empty when
     *         this node held or was skipped
     */
    public Optional<RuleTrace> culprit() {
        if (outcome != Outcome.NOT_MATCHED) {
            return Optional.empty();
        }
        var failed = children.stream()
                .filter(child -> child.outcome == Outcome.NOT_MATCHED)
                .toList();
        if (failed.size() != 1) {
            return Optional.of(this);
        }
        return Optional.of(failed.getFirst().culprit().orElse(failed.getFirst()));
    }

    /**
     * Renders the trace as an indented tree, one node per line.
     *
     * <p>Intended for a log line, a test failure or a diagnostics endpoint. The
     * generated {@code toString()} is left untouched so that a trace stays
     * usable as a single-line value, for instance inside the metadata of a
     * violation.</p>
     *
     * {@snippet :
     * and -> NOT_MATCHED
     *   channel-in {channels=[ONLINE, APP]} -> MATCHED
     *   amount-at-most {max=10000} -> NOT_MATCHED
     *   credit-score-at-least {score=620} -> SKIPPED
     *}
     *
     * @return a multi-line, human-readable rendering of the trace
     */
    public String format() {
        var text = new StringBuilder();
        append(text, 0);
        return text.toString();
    }

    private void append(StringBuilder text, int depth) {
        text.append("  ".repeat(depth)).append(label);
        if (!parameters.isEmpty()) {
            text.append(' ').append(parameters);
        }
        text.append(" -> ").append(outcome);
        for (var child : children) {
            text.append(System.lineSeparator());
            child.append(text, depth + 1);
        }
    }

    /**
     * Returns a copy of this node, and of every node below it, marked as
     * {@link Outcome#SKIPPED}.
     *
     * <p>Used by operators to report the children they short-circuited past,
     * keeping the shape of the rule visible in the trace.</p>
     *
     * @return the same tree with every outcome replaced by {@code SKIPPED}
     */
    public RuleTrace asSkipped() {
        if (children.isEmpty()) {
            return new RuleTrace(label, Outcome.SKIPPED, parameters, List.of());
        }
        var skippedChildren = new ArrayList<RuleTrace>(children.size());
        for (var child : children) {
            skippedChildren.add(child.asSkipped());
        }
        return new RuleTrace(label, Outcome.SKIPPED, parameters, skippedChildren);
    }
}
