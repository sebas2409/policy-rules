# Design decisions

Why the library is the way it is, and which alternatives were discarded. Useful
if you are going to extend it, or if you are deciding whether it fits your
project.

## A denial is a value, not an exception

`evaluate` returns a `PolicyResult` instead of throwing. Denying is an expected
business outcome, and using exceptions for the normal flow costs three things:
only the first reason is reported, the compiler does not force you to handle it,
and the caller pays for building a stack trace.

The exception exists, but only at the boundary: `requireAllowed()` and
`enforce(...)` throw when the operation cannot continue. Whether a denial is
fatal is decided by the caller, not by the policy.

## State is derived from the data

```java
public record PolicyResult(List<PolicyViolation> violations) {
    public boolean allowed() { return violations.isEmpty(); }
}
```

Storing a `boolean allowed` next to the list would allow building "denied without
a reason" or "allowed with violations". Deriving it means those states do not
exist. It is the same reason `PolicyResult.deny(List.of())` throws instead of
creating an incoherent result.

## Rule and policy kept apart

They could be a single type: a condition that returns a reason or nothing. They
are separate because their lifecycles differ:

- A condition is reused across use cases with **different reasons** (`age >= 18`
  may be `CUSTOMER_UNDERAGE` in one place and `RESTRICTED_PRODUCT` in another).
- A condition can come from configuration; a reason cannot: codes are a contract
  with clients and must live in the code.

That cut is what makes a rule compiled from a document indistinguishable from a
hand-written one.

## Functional interfaces, not a class per rule

`Rule<T>` is a `@FunctionalInterface`, so `Booking::active` already is a rule. A
class hierarchy per condition would achieve the same with far more noise.
Policies are built with factories (`Policies.require`) for the same reason: they
are values, not components.

When a decision does not fit "one condition and one reason", `Policy` is still a
two-method interface you can implement (example in
[policies.md](policies.md#writing-your-own-policy)).

## Immutability everywhere

Records, defensive copies in every constructor, immutable collections on the way
out. The result is that **everything the library returns can be shared across
threads and cached** without coordination. It is also what makes the compiled
rule cache safe.

The cost is one copy per construction, which happens at start-up or when a rule
is compiled, not on every evaluation.

## A sealed model for definitions

```java
public sealed interface RuleDefinition
        permits AtomicRuleDefinition, AndRuleDefinition, OrRuleDefinition, NotRuleDefinition
```

Sealing the hierarchy lets the compiler walk the tree with an exhaustive `switch`
and no default branch. If a node type is ever added, every place that must handle
it becomes a compile error instead of a runtime failure.

Discarded: an open model with visitors. More ceremony for the same exhaustiveness,
checked less well.

## Configuration errors surface at compile time

`RuleCompiler.compile` runs every factory. An unknown type or an invalid
parameter fails there, as a `RuleConfigurationException`. Once compiled, the rule
is a pure function that cannot fail because of configuration.

That turns "the configuration is broken" into a failure detectable at start-up or
in a test, instead of an error in the middle of a business decision. It is also
what makes it possible to validate all stored configuration by compiling it.

## The library reads from nowhere

There is no MongoDB adapter, no JPA one, no JSON one. All it needs is a
`Map<String, Object>`, which is exactly what all of them return.

The alternative — shipping adapters — would have brought dependencies, driver
versions and opinions about the schema. `RuleDefinitionCodec` covers the generic
part and leaves the ten store-specific lines in the project that needs them.

## The library observes nothing

No logs, no metrics, no traces: every project already has its own stack. Since
`Policy` is a two-method interface, decorating it is fifteen lines, and the
decoration lives where the rest of the project's telemetry lives
([pattern](integration.md#observability)).

This keeps the library at zero dependencies, which is what lets it drop into any
project without negotiating versions.

## Configuration introduces no behavior

It can only combine and parameterize the types registered in the `RuleRegistry`.
No expressions, no scripting, no reflection, no class loading.

An expression engine would be more flexible, but it would turn configuration into
code with no review and no tests, with an attack surface ranging from injection
to CPU exhaustion. The registry is the boundary: if a rule type is not
implemented and registered, it does not exist.

## Numbers through `BigDecimal`

`RuleParameters` converts every number through `BigDecimal` instead of casting.
That way `18`, `18L`, `18.0` and `"18"` all yield the same `int` — whichever
driver they came from — and a value that would lose precision or overflow is
rejected rather than silently truncated.

## Scope: what it is not

It is not a rules engine in the Drools sense. There is no forward chaining, no
working memory, no conflict resolution, no DSL of its own.

It is a small model for the 90% case: boolean conditions over a context, with
explainable reasons and a configurable part. If you need inference or rules that
trigger one another, this is not the tool.
