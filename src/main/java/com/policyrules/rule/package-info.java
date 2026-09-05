/**
 * Composable business conditions.
 *
 * <p>{@link com.policyrules.rule.Rule} is the smallest unit of the library: a
 * side-effect free question about a context object, combinable with the usual
 * boolean operators. {@link com.policyrules.rule.Rules} adds the factories and
 * collection combinators that do not fit on the interface itself.</p>
 *
 * <p>Rules defined in code live here. Rules whose shape is decided by external
 * configuration are built by {@link com.policyrules.rule.definition} and end up
 * being ordinary {@code Rule} instances, indistinguishable from the ones
 * written by hand.</p>
 */
package com.policyrules.rule;
