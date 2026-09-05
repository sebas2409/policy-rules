# Configurable rules

Reference for the `io.github.sebas2409.policyrules.rule.definition` package: how
to move a condition out of the code without losing type safety and without
letting configuration introduce arbitrary behavior.

## When it is worth it

Moving a condition to configuration has a cost: the type has to be registered,
documented and validated. It pays off when **the value changes more often than
the software**: campaign thresholds, country lists, per-segment limits.

It does not pay off for domain invariants ("a cancelled booking cannot be
confirmed"). That is code: if it changes, it is a software change and you want it
to go through review, tests and a deployment.

The usual outcome is a mix: most conditions in code, a few configurable.

## The four pieces

```
document (Map)  --RuleDefinitionCodec-->  RuleDefinition  --RuleCompiler-->  Rule<T>
                                                                  ^
                                                            RuleRegistry
                                                     (types the app accepts)
```

1. **`RuleRegistry<T>`** — the catalog of rule types the application implements
   and therefore accepts.
2. **`RuleDefinition`** — the data: a tree of boolean operators over
   parameterized types.
3. **`RuleDefinitionCodec`** — translates between that tree and a
   `Map<String, Object>`.
4. **`RuleCompiler<T>`** — walks the tree and produces an ordinary `Rule<T>`.

## 1. Registering the accepted types

```java
RuleRegistry<Booking> registry = new RuleRegistry<Booking>()
        .register("minimum-age", parameters -> {
            int minimum = parameters.intValue("minimum");
            return booking -> booking.age() >= minimum;
        })
        .register("country-in", parameters -> {
            var allowed = Set.copyOf(parameters.list("countries", String.class));
            return booking -> allowed.contains(booking.country());
        })
        .register("weekly-bookings-below", parameters -> {
            int maximum = parameters.intValue("maximum");
            return booking -> booking.weeklyBookings() < maximum;
        });
```

The registry is **the security boundary**: configuration can only combine and
parameterize these types. An unregistered type is rejected with
`UnknownRuleTypeException`, which also carries the list of the ones that do
exist so you can show a useful message.

Other operations:

```java
registry.contains("minimum-age");   // true
registry.types();                   // immutable, sorted set
registry.create("minimum-age", Map.of("minimum", 18));   // a single rule, no tree
```

A type can only be registered once: a duplicate throws at start-up instead of
silently replacing behavior. The registry is thread-safe, though the usual
pattern is to build it once and never touch it again.

### Naming the types

The name is a contract with whoever writes the configuration. A couple of
criteria that work well:

- Describe **what it checks**, not how: `minimum-age`, not `check-age-gte`.
- Pick one style and keep it (`kebab-case` is the most common in configuration).
- Keep the value out of the name: `minimum-age` with a parameter, never
  `age-over-18`.

## 2. Reading parameters with `RuleParameters`

Values arrive from outside, so their real type depends on the parser or driver
that produced them: an `18` may be an `Integer`, a `Long`, a `Double` or `"18"`.
`RuleParameters` normalizes that and fails with a message naming the parameter:

```java
parameters.intValue("minimum");                     // required
parameters.intValue("minimum", 18);                 // with a default
parameters.string("currency");
parameters.booleanValue("enabled", false);
parameters.decimal("amount");                       // without going through double
parameters.list("countries", String.class);
parameters.value("channel", Channel.class);         // enum by name
parameters.group("window").intValue("days");        // nested parameters
```

Accepted conversions:

| Requested type | Accepted |
|----------------|----------|
| `String` | any `CharSequence` |
| `int`, `long` | any `Number` without decimals, or text that parses as one, within range |
| `double`, `BigDecimal` | any `Number`, or numeric text |
| `boolean` | `Boolean`, or the text `true`/`false`, ignoring case |
| an `enum` | an instance, or the constant name, ignoring case |

Numbers go through `BigDecimal`, so `18`, `18.0` and `"18"` all yield the same
`int`, and a value that would lose precision or overflow is rejected rather than
truncated.

> **Read parameters when building, not when evaluating.** As in the examples:
> pull the values into local variables and capture those in the lambda. Reading
> them inside `matches` would repeat the conversion on every evaluation and would
> turn a configuration mistake into a failure in the middle of a business
> decision instead of at compile time.

## 3. Loading the document

The library reads from nowhere. Your application hands over a
`Map<String, Object>` — which is what every parser and driver returns — and the
codec converts it:

```java
Map<String, Object> document = ruleStore.load("booking-eligibility");
RuleDefinition definition = RuleDefinitionCodec.read(document);
```

The format is specified in [rule-definition-format.md](rule-definition-format.md).
Examples of loading from MongoDB, JPA or a JSON file are in
[integration.md](integration.md#loading-definitions).

You can also build the tree by hand, which works well for tests and seed data:

```java
RuleDefinition definition = RuleDefinitions.and(
        RuleDefinitions.atomic("minimum-age", Map.of("minimum", 18)),
        RuleDefinitions.or(
                RuleDefinitions.atomic("country-in", Map.of("countries", List.of("ES"))),
                RuleDefinitions.not(RuleDefinitions.atomic("blocked"))
        )
);
```

And serialize it back with `RuleDefinitionCodec.write(definition)`, which produces
exactly the canonical shape: reading and writing is a lossless round trip.

## 4. Compiling

```java
RuleCompiler<Booking> compiler = new RuleCompiler<>(registry);

Rule<Booking> eligible = compiler.compile(definition);
```

Compiling runs **every** factory, so any configuration problem surfaces here:

| Exception | Cause |
|-----------|-------|
| `UnknownRuleTypeException` | the type is not registered |
| `RuleParameterException` | a parameter is missing or its value is unusable |
| `RuleDefinitionFormatException` | the document does not follow the format |

All three extend `RuleConfigurationException`, which is itself an
`IllegalArgumentException`. That lets the boundary tell "the configuration is
broken" (a 5xx, or a page to operations) from "the request does not meet the
rules" (a 4xx):

```java
try {
    Rule<Booking> rule = compiler.compile(definition);
} catch (RuleConfigurationException brokenConfiguration) {
    alerts.notify("Misconfigured rule", brokenConfiguration);
    throw brokenConfiguration;
}
```

Once compiled, the rule **can no longer fail because of configuration**: it is a
pure function of the context.

## 5. Using it like any other rule

A compiled rule carries no trace of its origin:

```java
Policy<Booking> eligibility = Policies.require(
        "booking-eligibility",
        eligible.and(Booking::active),        // composes with code rules
        "NOT_ELIGIBLE", "Does not meet the eligibility rule");
```

## Performance and caching

Compiling is cheap, but not free: it walks the tree and invokes one factory per
atomic node. Evaluating a compiled rule is as fast as the equivalent hand-written
code.

Compiling on every request is a waste. Since compiled rules are immutable, they
can be cached and shared across threads; the pattern is in
[integration.md](integration.md#caching-compiled-rules).

## Validating configuration before production

`RuleDefinitions` inspects a tree without compiling it, which is what a validation
endpoint or a start-up check usually needs:

```java
Set<String> unsupported = new TreeSet<>(RuleDefinitions.typesOf(definition));
unsupported.removeIf(registry::contains);

if (!unsupported.isEmpty()) {
    throw new IllegalStateException("Unsupported rule types: " + unsupported);
}

if (RuleDefinitions.sizeOf(definition) > 100) {
    throw new IllegalStateException("The rule is too large");
}
```

Compiling is even stricter than this, since it also validates parameters, so if
you can afford to compile, compile: it is the best validation available.

## Security notes

- Configuration **cannot introduce behavior**: it only picks among the registered
  types and passes them parameters. No expressions, no scripts, no reflection.
- `RuleDefinitionCodec` rejects documents nested deeper than
  `RuleDefinitionCodec.MAX_DEPTH` (50 levels), so a hostile document cannot
  exhaust the stack of the recursive reader.
- A large tree is still expensive to evaluate: if the document comes from an
  untrusted source, cap its size with `RuleDefinitions.sizeOf`.
- Error messages include type names, parameter names and configuration values.
  They are useful for operations, but do not return them verbatim to an end user.
