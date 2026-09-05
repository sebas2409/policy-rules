# Policies and results

Reference for the `io.github.sebas2409.policyrules.policy` package: what each
type models, what it guarantees, and when to use each factory.

## Rule and policy: the difference

```java
Rule<Booking>   active = Booking::active;                    // does it hold?
Policy<Booking> policy = Policies.require(                   // may this happen?
        "booking-must-be-active", active,
        "BOOKING_INACTIVE", "The booking must be active");
```

A rule only knows how to say yes or no. A policy also knows **what failing
means**. That separation is what lets the same condition be used by several
policies with different reasons, and lets a condition come from code or from
configuration without the policy caring.

## `PolicyResult`

A result is a list of violations and nothing else:

```java
public record PolicyResult(List<PolicyViolation> violations)
```

`allowed()` is derived from the list (`violations().isEmpty()`), not stored. That
is why there is no "denied without a reason" result and no "allowed with
violations" one: they cannot be built.

| Method | Returns |
|--------|---------|
| `allowed()` / `denied()` | whether the context passed |
| `violations()` | immutable list of reasons, in evaluation order |
| `codes()` | the codes of those reasons |
| `firstViolation()` | an `Optional` with the first reason |
| `hasViolation(code)` | whether a specific reason is present, regardless of order |
| `combine(other)` | a result carrying the reasons of both |
| `requireAllowed()` | throws `PolicyViolationException` when denied |

Building results by hand (useful when writing your own `Policy`):

```java
PolicyResult.allow();
PolicyResult.deny("CODE", "Message");
PolicyResult.deny(new PolicyViolation("CODE", "Message", Map.of("limit", 3)));
PolicyResult.deny(List.of(first, second));   // rejects an empty list
```

## `PolicyViolation`

```java
new PolicyViolation("WEEKLY_LIMIT_REACHED",
                    "Weekly booking limit reached",
                    Map.of("current", 3, "maximum", 3));
```

- **`code`**: stable, machine-readable identifier. It is a contract with your
  clients; treat it as an enum that only grows and never changes meaning.
- **`message`**: text for humans. It can change freely. If you need
  translations, translate at the boundary using the `code` as the key.
- **`metadata`**: the values that explain the decision. They must be non-null and
  serializable by any library. **Do not put personal or sensitive data here**:
  violations end up in logs and in API responses.

`with(key, value)` returns an enriched copy, useful for adding call-site context
to a violation produced by a reusable policy:

```java
violation.with("bookingId", booking.id());
```

## Denial versus exception

A denial is an expected business outcome, so it travels as a value. Only the
boundary of the application turns it into an exception:

```java
// In the domain: decide with the result
PolicyResult result = policy.evaluate(booking);
if (result.denied()) {
    return Rejected.of(result.violations());
}

// At the boundary: when it cannot continue
policy.enforce(booking);   // PolicyViolationException
```

`PolicyViolationException` carries `violations()` and `codes()`. Its message
lists only the **codes**, not the texts, so business messages are not dumped into
logs by accident. The usual approach is to translate it once for the whole
application:

```java
@ExceptionHandler(PolicyViolationException.class)
ResponseEntity<?> handle(PolicyViolationException denied) {
    return ResponseEntity.unprocessableEntity().body(denied.violations());
}
```

## Factory catalog

### `require` — allow only when it holds

```java
Policies.require(id, rule, code, message);
Policies.require(id, rule, context -> violation);   // reason built from the context
```

The violation of the first variant is built once and reused. The second only
invokes the factory when the rule fails, so the allowed path pays nothing.

### `forbid` — deny when it holds

```java
Policies.forbid("booking-must-not-be-cancelled",
        Booking::cancelled,
        "BOOKING_CANCELLED", "The booking was cancelled");
```

Equivalent to `require` with the rule negated. It exists because some conditions
read far better stated as what must **not** happen.

### `allOf` — every reason

```java
Policies.allOf("booking-can-be-confirmed", List.of(active, notCancelled, withinLimit));
Policies.allOf("booking-can-be-confirmed", active, notCancelled, withinLimit);
```

Evaluates every member policy and accumulates their violations in declaration
order. This is what forms and APIs want.

### `firstFailureOf` — stop at the first

```java
Policies.firstFailureOf("booking-can-be-confirmed", List.of(cheapCheck, expensiveCheck));
```

Evaluates nothing after the first rejection. Useful when a later check is
expensive, or when the first reason is already enough.

Since a composite is itself a policy, they nest: an `allOf` of several
`firstFailureOf`, for instance, groups checks by area and yields one reason per
area.

### `adapt` — reuse in a wider context

```java
Policy<Customer> verified = Policies.require("customer-verified",
        Customer::verified, "CUSTOMER_NOT_VERIFIED", "The customer is not verified");

Policy<Order> orderFromVerifiedCustomer = Policies.adapt(verified, Order::customer);
```

This avoids one variant of the same policy per aggregate that needs it. The
adapted policy keeps the identifier of the original. `Rule` has the equivalent
method: `rule.adapt(Order::customer)`.

### `allow` and `deny` — constant policies

```java
Policies.allow("feature-open");
Policies.deny("feature-closed", "FEATURE_DISABLED", "Feature disabled");
```

For disabling an operation without dismantling its wiring (behind a feature flag,
say), and as a neutral element when building lists dynamically.

## Writing your own `Policy`

The factories cover the usual cases, but `Policy` is a two-method interface and
you can implement it whenever a decision does not fit "one rule and one reason":

```java
final class BookingWindowPolicy implements Policy<Booking> {

    @Override
    public String id() {
        return "booking-window";
    }

    @Override
    public PolicyResult evaluate(Booking booking) {
        var violations = new ArrayList<PolicyViolation>();
        if (booking.start().isBefore(LocalDate.now())) {
            violations.add(new PolicyViolation("START_IN_THE_PAST", "The date has passed"));
        }
        if (booking.nights() > 30) {
            violations.add(new PolicyViolation("TOO_LONG", "At most 30 nights"));
        }
        return violations.isEmpty() ? PolicyResult.allow() : PolicyResult.deny(violations);
    }
}
```

The contract every implementation must honor:

- `id()` stable and non-blank.
- `evaluate` never returns `null` and has no observable side effects.
- A denial is reported as a result, not as an exception. Exceptions are for an
  invalid context or a broken dependency.
- It must be thread-safe. The ones in this library are.

## Common mistakes

**Putting the database lookup inside the rule.** It breaks purity and makes
evaluation fire I/O. Load the data first and put it in the context.

**Using the message as the identifier.** Messages change; codes do not. Clients
must branch on `code`.

**Reusing the same code for different reasons.** If two denials are fixed
differently, they are two different codes.

**Building the policy on every request.** Build it once at start-up: policies are
immutable and sharing them is free.
