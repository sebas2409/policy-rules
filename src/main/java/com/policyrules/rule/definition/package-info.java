/**
 * Rules whose shape is decided by configuration instead of by code.
 *
 * <p>Some conditions change far more often than the software that applies them:
 * a minimum age, a limit per week, the list of accepted countries. This package
 * lets an application move those decisions to an external source without giving
 * up type safety, and without letting configuration introduce arbitrary
 * behavior.</p>
 *
 * <h2>The four pieces</h2>
 * <ol>
 *   <li>{@link com.policyrules.rule.definition.RuleDefinition} is the data: a
 *       tree of boolean operators over named, parameterized rule types.</li>
 *   <li>{@link com.policyrules.rule.definition.RuleRegistry} is the catalog of
 *       the rule types the application implements and therefore accepts.</li>
 *   <li>{@link com.policyrules.rule.definition.RuleCompiler} walks a definition
 *       and turns it into a plain {@link com.policyrules.rule.Rule}.</li>
 *   <li>{@link com.policyrules.rule.definition.RuleDefinitionCodec} reads and
 *       writes definitions as plain maps, which is what parsers and database
 *       drivers hand over.</li>
 * </ol>
 *
 * {@snippet lang = "java":
 * RuleRegistry<Customer> registry = new RuleRegistry<Customer>()
 *         .register("minimum-age", parameters -> {
 *             int minimum = parameters.intValue("minimum");
 *             return customer -> customer.age() >= minimum;
 *         });
 *
 * RuleCompiler<Customer> compiler = new RuleCompiler<>(registry);
 *
 * // The application decides where the document comes from.
 * Rule<Customer> eligible = compiler.compile(
 *         RuleDefinitionCodec.read(ruleStore.load("customer-eligibility"))
 * );
 *}
 *
 * <h2>Where storage fits</h2>
 * <p>Nothing in this package reads or writes anything. Loading a document is
 * left to the application because that is the part that depends on the
 * infrastructure it already has; the library only takes over once the document
 * is in memory.</p>
 *
 * <h2>Failure model</h2>
 * <p>Every configuration problem is reported as a
 * {@link com.policyrules.rule.definition.RuleConfigurationException} while the
 * rule is compiled: an unknown type, a missing parameter, a malformed document.
 * A compiled rule can no longer fail because of configuration.</p>
 */
package com.policyrules.rule.definition;
