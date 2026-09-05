/**
 * Rules that report how they reached their answer.
 *
 * <p>A rule answers yes or no. When the rule is a tree of operators that came
 * from configuration, and a person edits that configuration through a screen,
 * the next question is always the same: <em>which node decided it</em>. This
 * package answers it without turning rules into something else.</p>
 *
 * <h2>The trace is data, not an effect</h2>
 * <p>{@link io.github.sebas2409.policyrules.rule.trace.ExplainableRule#explain(java.lang.Object)}
 * returns a {@link io.github.sebas2409.policyrules.rule.trace.RuleTrace}. Nothing here writes a
 * log, opens a span, reads a thread-local or knows about a framework, so the
 * same rule explains itself identically inside a servlet, a reactive handler,
 * a batch job or a test.</p>
 *
 * {@snippet lang = "java":
 * ExplainableRule<Application> eligible = compiler.compileExplainable(definition);
 *
 * RuleTrace trace = eligible.explain(application);
 * if (!trace.matched()) {
 *     RuleTrace culprit = trace.culprit().orElse(trace);
 * }
 *}
 *
 * <h2>Nothing is asked of the rules themselves</h2>
 * <p>A node is named by whoever builds it, not by the condition inside it. A
 * {@code RuleCompiler} already knows the type and the parameters of every
 * atomic node it compiles, so rule types registered in a
 * {@code io.github.sebas2409.policyrules.rule.definition.RuleRegistry} keep being plain lambdas
 * and start appearing in traces without a single change.</p>
 *
 * <h2>Cost</h2>
 * <p>{@code matches} and {@code explain} are separate evaluations of the same
 * logic: the first allocates nothing, the second builds the tree. Code that
 * never calls {@code explain} pays nothing for it.</p>
 */
package io.github.sebas2409.policyrules.rule.trace;
