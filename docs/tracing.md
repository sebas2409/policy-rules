# Explaining a decision

Reference for the `io.github.sebas2409.policyrules.rule.trace` package: how a
rule reports **which node decided** an evaluation, so that a denial can be shown,
audited or debugged without a debugger.

Added in **1.1.0**. Everything here is additive: rules, policies and compiled
definitions that do not ask for a trace behave exactly as before.

## The question it answers

A rule answers `true` or `false`. That is enough when the condition is a line of
Java you can read. It stops being enough when the condition is a tree of
operators that someone edited through a screen:

```json
{
  "operator": "and",
  "rules": [
    { "type": "minimum-age", "parameters": { "minimum": 18 } },
    { "operator": "or", "rules": [
        { "type": "country-in", "parameters": { "countries": ["ES", "PT"] } },
        { "operator": "not", "rule": { "type": "blocked" } }
    ]},
    { "type": "weekly-bookings-below", "parameters": { "maximum": 3 } }
  ]
}
```

When this returns `false`, the next question is always the same: *which part?*
Without an answer, the person who wrote the configuration has to reproduce the
case locally, and the support agent has nothing to tell the customer.

## What a trace is

A `RuleTrace` is a tree that mirrors the rule that produced it: one node per
operator, one leaf per atomic rule, each with its own outcome.

| Component | Holds |
|-----------|-------|
| `label` | the operator (`and`, `or`, `not`) or the registered rule type |
| `outcome` | `MATCHED`, `NOT_MATCHED` or `SKIPPED` |
| `parameters` | the configuration the atomic rule was built with; empty for operators |
| `children` | the nodes below, in evaluation order; empty for leaves |

It is plain, immutable data. The library never logs it, never serializes it and
never decides what it is for. That is what makes it work the same everywhere: a
trace is a return value, not an effect, so nothing here reads a thread-local,
opens a span or knows whether it is running inside a servlet, a reactive
handler, a batch job or a test.

## 1. Getting a rule that explains itself

For rules built from configuration, ask the compiler for one:

```java
RuleCompiler<Booking> compiler = new RuleCompiler<>(registry);

ExplainableRule<Booking> eligible = compiler.compileExplainable(definition);
```

`ExplainableRule<T>` **is** a `Rule<T>`, so it goes anywhere a rule goes.

> **The registry does not change.** A leaf is named by the compiler, from the
> definition it is compiling, not by the factory that built it. Every type
> already registered starts appearing in traces without a single edit, and
> adding a new type never involves thinking about this package.

## 2. Reading the trace

```java
RuleTrace trace = eligible.explain(booking);

if (!trace.matched()) {
    log.warn("Booking rejected:\n{}", trace.format());
}
```

`format()` renders the tree, one node per line:

```
and -> NOT_MATCHED
  minimum-age {minimum=18} -> MATCHED
  or -> NOT_MATCHED
    country-in {countries=[ES, PT]} -> NOT_MATCHED
    not -> NOT_MATCHED
      blocked -> MATCHED
  weekly-bookings-below {maximum=3} -> SKIPPED
```

The generated `toString()` is left alone, so a trace stays usable as a
single-line value inside the metadata of a violation.

### Why `SKIPPED` exists

Rules short-circuit. In the example above, the conjunction stopped at the failing
`or`, so `weekly-bookings-below` was never evaluated. Reporting it as failed
would be a lie, and reporting it as satisfied would be another.

A trace therefore describes **what actually happened**, not what a full
evaluation would have produced. Skipped subtrees keep their shape, so the reader
still sees the rule that exists, greyed out.

## 3. Finding the node that decided

`culprit()` walks down to the deepest node that accounts for a failure:

```java
RuleTrace culprit = trace.culprit().orElse(trace);

// culprit.label()       -> "or"
// culprit.parameters()  -> {}
```

The search descends while exactly one child failed, which is the shape a
short-circuiting conjunction leaves behind, and stops as soon as the reason stops
being a single node:

| Node | Explained by |
|------|--------------|
| an atomic rule that did not hold | itself |
| a conjunction | its one failing child, recursively: the deepest single cause |
| a disjunction whose alternatives all failed | itself — there is no single cause |
| a negation | itself, since its child *did* hold |

In the trace above the culprit is the `or`, not one of its leaves, and that is
the honest answer: the booking failed because **neither** alternative held. The
subtree below it is what tells the reader which one came closest.

A node that held, or that was skipped, has nothing to explain and returns an
empty `Optional`.

## 4. Rules written in code

Configuration is the common case, but a hand-written rule can join the same tree
by being given a name:

```java
ExplainableRule<Booking> adult =
        ExplainableRules.of("adult", booking -> booking.age() >= 18);

ExplainableRule<Booking> verified =
        ExplainableRules.of("verified", Booking::verified);

ExplainableRule<Booking> eligible = ExplainableRules.and(adult, verified);
```

| Factory | Produces |
|---------|----------|
| `of(label, rule)` | a leaf with a name |
| `of(label, parameters, rule)` | a leaf that also reports the values it was built with |
| `and(...)`, `or(...)` | operators that report what they short-circuited past |
| `not(rule)` | a negation that keeps the negated rule visible below it |

The labels the operators report are available as `ExplainableRules.AND_LABEL`,
`OR_LABEL` and `NOT_LABEL`.

> **The inherited operators drop the trace.** `Rule.and`, `Rule.or` and
> `Rule.adapt` return plain rules, because that is their contract. Combine
> explainable rules through `ExplainableRules` to keep the trace. `not()` is the
> exception: it is overridden and stays explainable.

## 5. Attaching it to a policy

`Policies.requireExplained` is `require` over a rule that can explain itself:

```java
Policy<Booking> eligibility = Policies.requireExplained(
        "booking-eligibility", eligible,
        "NOT_ELIGIBLE", "The booking does not meet the eligibility rule");

PolicyResult result = eligibility.evaluate(booking);
```

When the context is denied, the violation carries two extra entries:

| Metadata key | Holds |
|--------------|-------|
| `Policies.TRACE_METADATA_KEY` | the whole `RuleTrace` |
| `Policies.CULPRIT_METADATA_KEY` | the node that accounts for the denial, when a single node does |

```java
result.firstViolation()
        .map(violation -> violation.metadata().get(Policies.CULPRIT_METADATA_KEY))
        .map(RuleTrace.class::cast)
        .ifPresent(culprit -> response.setDetail(culprit.label()));
```

`forbidExplained` is the mirror image, for conditions that read better stated as
what must not happen. The negation stays visible: the reported node is the `not`,
and below it the rule that held.

Both overloads of `requireExplained` exist, so an explanation built from the
denied context still works and is enriched with the same metadata:

```java
Policies.requireExplained("weekly-limit", withinLimit,
        booking -> new PolicyViolation(
                "WEEKLY_LIMIT_REACHED", "Weekly booking limit reached",
                Map.of("current", booking.weeklyBookings())));
```

> A violation factory that writes to those two keys will have them overwritten.
> The constants exist so you can avoid the clash.

## Cost

`matches` and `explain` are two evaluations of the same logic. The first
allocates nothing and short-circuits exactly like a plain rule; the second builds
one node per node of the tree.

That is the whole cost model:

- A hot path that never asks for an explanation pays **nothing**. Keep using
  `compile` and `require`.
- A policy whose denials someone reads pays one small allocation per node, and
  only for that policy. The choice is per policy, not per application.

```java
// Same rule, both ways, no duplicated configuration:
boolean ok = eligible.matches(booking);          // nothing allocated
RuleTrace why = eligible.explain(booking);       // the tree
```

## Contract notes

- `matches(c)` and `explain(c).matched()` always agree.
- They are **independent evaluations**, so calling both for the same context
  evaluates the rule twice. Code that wants a trace should call `explain` alone
  and read `RuleTrace.matched()`. This is also the only exact option when a rule
  reads state that can change between calls.
- `Policies.requireExplained` already does that: it calls `explain` once and
  reads the answer from the trace, so the reported explanation always describes
  the very evaluation that produced the denial.
- A rule an operator short-circuited past is **never evaluated**, in either mode.

## Rendering it somewhere else

A trace is data, so it travels wherever the application needs it, the same way a
`RuleDefinition` does through `RuleDefinitionCodec`:

- **A screen.** The trace has the shape of the document the person edited, so it
  can be laid over it: the same tree, each node annotated with its outcome.
- **An API.** Map it with whatever the project already serializes with. The
  library deliberately does not pick Jackson, JSON-B or anything else for you.
- **An audit log.** Stored next to the decision, it explains three months later
  why a context was denied by rules that have since changed. Pair it with the
  version of the configuration that produced it.

Two things worth deciding once, per project:

- **Traces name rules and show configuration values.** Useful for operations,
  fine for an internal screen, usually not something to hand to an end user
  verbatim.
- **Nothing writes them for you.** If a trace should reach the logs, that is a
  decorator around the policy, like every other integration concern. The
  patterns are in [integration.md](integration.md).

## What it is not

Not a profiler: a trace carries no timings, no counters and no identity of the
evaluated context. It answers *which node decided*, and nothing else.

For per-policy metrics and latency, decorate the policy — a `Policy` is a
one-method interface, and [integration.md](integration.md) has the pattern.
