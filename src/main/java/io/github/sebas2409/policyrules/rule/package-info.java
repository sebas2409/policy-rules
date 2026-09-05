/**
 * Composable business conditions.
 *
 * <p>{@link io.github.sebas2409.policyrules.rule.Rule} is the smallest unit of the library: a
 * side-effect free question about a context object, combinable with the usual
 * boolean operators. {@link io.github.sebas2409.policyrules.rule.Rules} adds the factories and
 * collection combinators that do not fit on the interface itself.</p>
 *
 * <p>Rules defined in code live here. Rules whose shape is decided by external
 * configuration are built by {@link io.github.sebas2409.policyrules.rule.definition} and end up
 * being ordinary {@code Rule} instances, indistinguishable from the ones
 * written by hand.</p>
 */
package io.github.sebas2409.policyrules.rule;
