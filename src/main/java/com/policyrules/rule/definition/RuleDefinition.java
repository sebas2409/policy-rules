package com.policyrules.rule.definition;

/**
 * Declarative, storage-agnostic description of a rule.
 *
 * <p>A definition is pure data: a tree of atomic rule references and boolean
 * operators, with no behavior and no dependency on where it came from. Loading
 * it is the responsibility of the application (a document store, a relational
 * table, a JSON file, an HTTP call); turning it into executable behavior is the
 * responsibility of {@link RuleCompiler}.</p>
 *
 * <pre>
 *   AND
 *    ├── atomic  minimum-age        { minimum: 18 }
 *    ├── atomic  bookings-below     { maximum: 3 }
 *    └── OR
 *         ├── atomic country-is     { expected: "ES" }
 *         └── atomic country-is     { expected: "PT" }
 * </pre>
 *
 * <p>The hierarchy is {@code sealed}, so {@link RuleCompiler} and any other
 * consumer can exhaustively switch over it without a default branch, and adding
 * a new node type becomes a compile error everywhere it must be handled.</p>
 *
 * <p>Definitions are built either with the factories in {@link RuleDefinitions}
 * or from a raw {@code Map} with {@link RuleDefinitionCodec}. Every
 * implementation is an immutable record, so a definition can be cached and
 * shared across threads.</p>
 *
 * @see RuleDefinitions
 * @see RuleDefinitionCodec
 * @see RuleCompiler
 */
public sealed interface RuleDefinition
        permits AtomicRuleDefinition,
        AndRuleDefinition,
        OrRuleDefinition,
        NotRuleDefinition {
}
