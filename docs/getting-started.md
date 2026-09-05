# Getting started

This guide builds, step by step, the validation of a real use case: confirming a
booking. By the end you will have a composed policy that reports every reason for
a rejection, with part of its conditions living in configuration.

## 1. The context

A policy decides on a **context**: the object your use case already has. No
special type is needed, no annotations, nothing to extend.

```java
record Booking(
        String customer,
        int age,
        int weeklyBookings,
        String country,
        boolean active
) {}
```

One piece of advice: hand the policy everything it needs to decide, already
loaded. If a condition requires a database lookup, do the lookup first and put
the result in the context (`weeklyBookings` in the example). That keeps rules
pure, testable without infrastructure, and makes sure evaluating one never fires
a surprise query.

## 2. The first policy

`Policies.require` builds a policy that allows the context **only if** the rule
holds:

```java
import io.github.sebas2409.policyrules.policy.Policies;
import io.github.sebas2409.policyrules.policy.Policy;

Policy<Booking> mustBeActive = Policies.require(
        "booking-must-be-active",        // stable identifier
        Booking::active,                 // the condition
        "BOOKING_INACTIVE",              // code of the reason
        "The booking must be active"     // message of the reason
);
```

The four arguments play different roles:

- The **identifier** names the policy in tests, logs and metrics. It is never
  shown to a user.
- The **condition** is a `Rule<Booking>`, and since that is a functional
  interface it accepts any lambda or method reference.
- The **code** is what your clients consume (an API, a front end that translates
  it). Treat it as an enum that only grows.
- The **message** is for humans and can change whenever you like.

## 3. Evaluating

```java
PolicyResult result = mustBeActive.evaluate(booking);

result.allowed();        // true when there are no violations
result.denied();         // the opposite
result.violations();     // immutable list of reasons
result.codes();          // ["BOOKING_INACTIVE"]
```

`evaluate` never throws because of a denial: reporting the reason is the normal
outcome. When the operation cannot continue, the boundary of the application uses
`enforce`:

```java
mustBeActive.enforce(booking);   // PolicyViolationException when denied
```

## 4. Explaining a rejection with data

When the reason depends on the context, use the factory variant. It is only
invoked if the rule fails, so it can be as detailed as needed:

```java
Policy<Booking> withinWeeklyLimit = Policies.require(
        "weekly-limit",
        booking -> booking.weeklyBookings() < 3,
        booking -> new PolicyViolation(
                "WEEKLY_LIMIT_REACHED",
                "Weekly booking limit reached",
                Map.of("current", booking.weeklyBookings(), "maximum", 3)
        )
);
```

Metadata lets whoever receives the rejection explain it without reimplementing
the logic: *"you already have 3 of 3 bookings this week"*.

## 5. Composing

A composed policy is itself a policy, so the use case exposes a single one even
when it is made of five conditions:

```java
Policy<Booking> canBeConfirmed = Policies.allOf("booking-can-be-confirmed", List.of(
        mustBeActive,
        withinWeeklyLimit
));
```

There are two strategies, and the choice depends on what the caller does with the
answer:

| Factory | Behavior | When |
|---------|----------|------|
| `Policies.allOf` | evaluates every member and accumulates every reason | forms and APIs: the user wants to fix everything at once |
| `Policies.firstFailureOf` | stops at the first rejection | hot paths, or when later checks are expensive |

Both respect declaration order, when evaluating and when reporting.

## 6. Moving a condition to configuration

So far everything lives in the code. Suppose the minimum age and the accepted
countries change per campaign. First, declare **which rule types the application
accepts**:

```java
RuleRegistry<Booking> registry = new RuleRegistry<Booking>()
        .register("minimum-age", parameters -> {
            int minimum = parameters.intValue("minimum");
            return booking -> booking.age() >= minimum;
        })
        .register("country-in", parameters -> {
            var allowed = Set.copyOf(parameters.list("countries", String.class));
            return booking -> allowed.contains(booking.country());
        });

RuleCompiler<Booking> compiler = new RuleCompiler<>(registry);
```

Then compile the document, wherever it comes from:

```java
Map<String, Object> document = ruleStore.load("booking-eligibility");

Rule<Booking> eligible = compiler.compile(RuleDefinitionCodec.read(document));

Policy<Booking> eligibility = Policies.require(
        "booking-eligibility", eligible,
        "NOT_ELIGIBLE", "The booking does not meet the eligibility rule");
```

With this document:

```json
{
  "operator": "and",
  "rules": [
    { "type": "minimum-age", "parameters": { "minimum": 18 } },
    { "type": "country-in",  "parameters": { "countries": ["ES", "PT"] } }
  ]
}
```

The resulting policy combines with the others like any of them:

```java
Policy<Booking> canBeConfirmed = Policies.allOf("booking-can-be-confirmed", List.of(
        mustBeActive,
        withinWeeklyLimit,
        eligibility
));
```

## 7. Where to build each thing

| Built | When | Reused |
|-------|------|--------|
| `RuleRegistry` | once, at start-up | always |
| `RuleCompiler` | once, at start-up | always |
| Code policies | once, at start-up | always |
| A compiled `RuleDefinition` | when configuration is read | until configuration changes (see [caching](integration.md#caching-compiled-rules)) |
| `PolicyResult` | on every evaluation | no |

Everything the library returns is immutable and thread-safe, so keeping policies
and compiled rules in `static final` fields or singleton beans is correct.

## 8. Testing it

Policies are tested without starting anything:

```java
@Test
void reportsEveryReason() {
    var leo = new Booking("Leo", 16, 3, "US", false);

    var result = canBeConfirmed.evaluate(leo);

    assertEquals(
            List.of("BOOKING_INACTIVE", "WEEKLY_LIMIT_REACHED", "NOT_ELIGIBLE"),
            result.codes());
}
```

The complete, runnable example lives in
`src/test/java/io/github/sebas2409/policyrules/examples/BookingExampleTest.java`.

## Next

- [Policies and results](policies.md) for the detail of the model.
- [Configurable rules](dynamic-rules.md) to get the most out of configuration.
- [Integration](integration.md) to wire it into your infrastructure.
