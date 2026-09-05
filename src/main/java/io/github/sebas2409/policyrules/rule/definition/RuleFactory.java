package io.github.sebas2409.policyrules.rule.definition;

import io.github.sebas2409.policyrules.rule.Rule;

/**
 * Builds an executable {@link Rule} from the parameters of a configured rule type.
 *
 * <p>A factory is the bridge between a name used in configuration
 * ({@code "minimum-age"}) and the code that implements it. It is registered once
 * in a {@link RuleRegistry} and invoked every time a definition using that name
 * is compiled.</p>
 *
 * {@snippet lang = "java":
 * RuleFactory<Customer> minimumAge = parameters -> {
 *     int minimum = parameters.intValue("minimum");
 *     return customer -> customer.age() >= minimum;
 * };
 *}
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Read and validate every parameter <em>before</em> returning the rule, so
 *       an invalid configuration fails while it is compiled rather than while a
 *       business decision is being taken. {@link RuleParameters} already reports
 *       missing or unusable values this way.</li>
 *   <li>Never return {@code null}; the registry rejects it.</li>
 *   <li>Return a rule that only depends on the captured values, so it can be
 *       cached and shared across threads.</li>
 * </ul>
 *
 * @param <T> context type consumed by the produced rules
 */
@FunctionalInterface
public interface RuleFactory<T> {

    /**
     * Builds a rule from its configured parameters.
     *
     * @param parameters parameters declared for this rule type, never null
     * @return the executable rule, never null
     * @throws RuleParameterException if a parameter is missing or unusable
     */
    Rule<T> create(RuleParameters parameters);
}
