# policy-rules

[![CI](https://github.com/sebas2409/policy-rules/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sebas2409/policy-rules/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.sebas2409/policy-rules)](https://central.sonatype.com/artifact/io.github.sebas2409/policy-rules)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
![Java](https://img.shields.io/badge/Java-25-orange)

A Java library for expressing **business rules** and **policies** as composable
objects, including rules whose shape is decided by **configuration** rather than
by code.

- **No dependencies.** Only the Java 25 standard library.
- **No infrastructure.** It does not read from a database, write logs, or depend
  on a framework. That is left to the project using it.
- **Immutable and thread-safe.** Everything the library returns is built once and
  can be shared.

---

## Contents

- [The problem](#the-problem)
- [Installation](#installation)
- [One-minute example](#one-minute-example)
- [The four concepts](#the-four-concepts)
- [Configurable rules](#configurable-rules)
- [API map](#api-map)
- [What it leaves out, and why](#what-it-leaves-out-and-why)
- [Documentation](#documentation)
- [Build and publish](#build-and-publish)

---

## The problem

Business conditions tend to end up tangled inside service methods:

```java
public void confirm(Booking booking) {
    if (!booking.active()) {
        throw new IllegalStateException("The booking is not active");
    }
    if (booking.customer().age() < 18) {
        throw new IllegalStateException("Underage customer");
    }
    if (booking.weeklyBookings() >= 3) {
        throw new IllegalStateException("Weekly limit reached");
    }
    // ...
}
```

That has three concrete problems:

1. **The condition and its meaning are welded together.** You cannot reuse
   `age() >= 18` in another use case without dragging along its message and its
   exception.
2. **Only the first failure is reported.** A form or an API usually needs *every*
   reason at once.
3. **Changing a threshold means a deployment.** The `18` and the `3` are business
   data, yet they live in the code.

`policy-rules` separates those three things: the condition (`Rule`), its meaning
(`Policy` → `PolicyResult`) and its origin (code or configuration).

---

## Installation

Published on **Maven Central**: no token and no extra repository needed.

```xml
<dependency>
    <groupId>io.github.sebas2409</groupId>
    <artifactId>policy-rules</artifactId>
    <version>1.0.1</version>
</dependency>
```

Gradle:

```kotlin
implementation("io.github.sebas2409:policy-rules:1.0.1")
```

Requires **Java 25**. The library declares the automatic module name
`io.github.sebas2409.policyrules`, so it works on the classpath and on the module
path alike.

---

## One-minute example

```java
import io.github.sebas2409.policyrules.policy.*;
import java.util.List;
import java.util.Map;

record Booking(String customer, int age, int weeklyBookings, boolean active) {}

Policy<Booking> canBeConfirmed = Policies.allOf("booking-can-be-confirmed", List.of(

        Policies.require("booking-must-be-active",
                Booking::active,
                "BOOKING_INACTIVE", "The booking must be active"),

        Policies.require("customer-must-be-adult",
                booking -> booking.age() >= 18,
                "CUSTOMER_UNDERAGE", "The customer must be of age"),

        Policies.require("weekly-limit",
                booking -> booking.weeklyBookings() < 3,
                booking -> new PolicyViolation(
                        "WEEKLY_LIMIT_REACHED",
                        "Weekly booking limit reached",
                        Map.of("current", booking.weeklyBookings(), "maximum", 3)))
));

PolicyResult result = canBeConfirmed.evaluate(booking);

if (result.allowed()) {
    bookings.confirm(booking);
} else {
    return unprocessableEntity(result.violations());   // every reason
}
```

And at the boundary of the application, where the operation cannot continue:

```java
canBeConfirmed.enforce(booking);   // throws PolicyViolationException when denied
```

---

## The four concepts

| Type | Answers | Holds |
|------|---------|-------|
| `Rule<T>` | *does the condition hold?* | a `boolean`, nothing else |
| `Policy<T>` | *may this happen?* | a condition plus the reason for denying it |
| `PolicyResult` | *what happened?* | the list of reasons (empty means allowed) |
| `PolicyViolation` | *why not?* | a stable code, a message and metadata |

Two design decisions worth understanding before using it:

**A denial is a value, not an exception.** Denying is an expected business
outcome, so `evaluate` returns a `PolicyResult` carrying *every* reason. Only the
boundary of the application turns it into an exception, through `enforce` or
`requireAllowed`.

**State is derived, not stored.** `PolicyResult.allowed()` is
`violations().isEmpty()`, which makes the contradictory states "denied without a
reason" and "allowed with violations" impossible to build.

Full detail in [docs/policies.md](docs/policies.md).

---

## Configurable rules

When a threshold changes more often than the software, the condition can live
outside the code. The application declares **which rule types it accepts**, and
configuration can only combine and parameterize those:

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

The stored document — loaded by whoever loads it — has this shape:

```json
{
  "operator": "and",
  "rules": [
    { "type": "minimum-age", "parameters": { "minimum": 18 } },
    { "type": "country-in",  "parameters": { "countries": ["ES", "PT"] } }
  ]
}
```

And it compiles into an ordinary rule:

```java
Map<String, Object> document = ruleStore.load("booking-eligibility");

Rule<Booking> eligible = compiler.compile(RuleDefinitionCodec.read(document));

Policy<Booking> eligibility = Policies.require(
        "booking-eligibility", eligible,
        "NOT_ELIGIBLE", "The booking does not meet the eligibility rule");
```

A compiled rule is indistinguishable from a hand-written one: it composes with
`and`/`or`/`not` and any policy can use it.

Three guarantees make this safe:

- **Configuration introduces no behavior.** It can only use the registered types;
  anything else is rejected with `UnknownRuleTypeException`.
- **Errors surface at compile time, not at decision time.** A missing parameter or
  an unknown type fails in `compile(...)`. A compiled rule can no longer fail
  because of configuration.
- **The library does not know where the document came from.** MongoDB,
  PostgreSQL, a JSON file or a remote service: all of them hand over a
  `Map<String, Object>`.

Full guide in [docs/dynamic-rules.md](docs/dynamic-rules.md), format spec in
[docs/rule-definition-format.md](docs/rule-definition-format.md).

---

## API map

```
io.github.sebas2409.policyrules.policy            Business decisions
    Policy<T>                       id() + evaluate(ctx) + enforce(ctx)
    Policies                        require, forbid, allOf, firstFailureOf, adapt, allow, deny
    PolicyResult                    allowed/denied, violations, codes, combine, requireAllowed
    PolicyViolation                 code + message + metadata
    PolicyViolationException        what the application boundary throws

io.github.sebas2409.policyrules.rule              Conditions
    Rule<T>                         matches + and/or/not + adapt + asPredicate
    Rules                           alwaysTrue, alwaysFalse, of, not, allOf, anyOf

io.github.sebas2409.policyrules.rule.definition   Rules from configuration
    RuleDefinition                  sealed model: atomic, and, or, not
    RuleDefinitions                 factories plus typesOf/sizeOf for validation
    RuleDefinitionCodec             Map <-> RuleDefinition
    RuleRegistry<T>                 catalog of accepted types
    RuleFactory<T>                  builds a rule from its parameters
    RuleParameters                  type-safe parameter reading
    RuleConfigurationException      + UnknownRuleType / RuleParameter / RuleDefinitionFormat
```

---

## What it leaves out, and why

The library ships no **observability**, **persistence** or **caching**. That is
not an omission: those are precisely the three things every project already
solves its own way, and bundling them here would force the library's choice on
everyone.

Since `Policy` and `Rule` are small interfaces, adding them in your project is a
decorator of a few lines. [docs/integration.md](docs/integration.md) has the
ready-to-copy patterns:

- decorating a policy with metrics, logs or traces,
- caching compiled rules and invalidating them when configuration changes,
- loading definitions from MongoDB, JPA or a JSON file,
- registering policies as Spring beans,
- validating stored configuration before it reaches production.

---

## Documentation

| Document | Content |
|----------|---------|
| [docs/getting-started.md](docs/getting-started.md) | From nothing to a composed policy, step by step |
| [docs/policies.md](docs/policies.md) | Policies, results, violations and composition |
| [docs/dynamic-rules.md](docs/dynamic-rules.md) | Registry, factories, parameters and compilation |
| [docs/rule-definition-format.md](docs/rule-definition-format.md) | Specification of the configuration document |
| [docs/integration.md](docs/integration.md) | Observability, persistence, caching, Spring and tests |
| [docs/design.md](docs/design.md) | Design decisions and the alternatives that were discarded |
| [docs/publishing.md](docs/publishing.md) | Release flow, workflows and how to consume the artifact |

The Javadoc is the detailed per-type reference; it is generated with
`mvn -Prelease javadoc:javadoc` into `target/site/apidocs`.

---

## Build and publish

```bash
mvn verify                 # compiles and runs the tests
mvn -Prelease install      # also builds the sources and Javadoc jars
```

The `release` profile attaches `-sources.jar` and `-javadoc.jar`. Javadoc is
generated with strict `doclint`: incomplete documentation breaks the build.

Publishing is automatic and **the version in `pom.xml` drives it**: merging a
`<version>` change into `main` makes the `Release` workflow sign and publish the
artifact to Maven Central, create the `vX.Y.Z` tag and open the release with the
jars attached. A `-SNAPSHOT` version, or one whose tag already exists, publishes
nothing.

```bash
# bump <version> in pom.xml (e.g. 1.1.0) and:
git commit -am "Version 1.1.0" && git push origin main
```

Full detail in [docs/publishing.md](docs/publishing.md).

---

## License

MIT. See [LICENSE](LICENSE).
