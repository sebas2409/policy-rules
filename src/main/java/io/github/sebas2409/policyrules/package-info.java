/**
 * A small, dependency-free model for business rules and policies.
 *
 * <p>The library separates three concerns that tend to be tangled inside
 * service methods:</p>
 *
 * <ul>
 *   <li><strong>The condition.</strong> {@link io.github.sebas2409.policyrules.rule.Rule} is a
 *       composable yes/no question about a context object.</li>
 *   <li><strong>The meaning of failing it.</strong>
 *       {@link io.github.sebas2409.policyrules.policy.Policy} pairs a condition with the reason
 *       to report, and answers with a
 *       {@link io.github.sebas2409.policyrules.policy.PolicyResult} that carries every
 *       violation.</li>
 *   <li><strong>Where the condition comes from.</strong>
 *       {@link io.github.sebas2409.policyrules.rule.definition} builds rules out of external
 *       configuration, using only the rule types the application registered.</li>
 * </ul>
 *
 * <p>Nothing here reads from a database, writes a log, or depends on a
 * framework: a policy is a plain object, and integrating it with the
 * infrastructure of a project is left to that project.</p>
 *
 * @see io.github.sebas2409.policyrules.policy.Policies
 * @see io.github.sebas2409.policyrules.rule.Rule
 * @see io.github.sebas2409.policyrules.rule.definition.RuleCompiler
 */
package io.github.sebas2409.policyrules;
