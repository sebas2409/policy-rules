# Integration

The library ships no observability, persistence or caching. That is not an
omission: those are the three things every project already solves its own way,
and bundling them here would force the library's choice on everyone and drag in
dependencies.

Since `Policy` and `Rule` are small interfaces, adding them where they are used
is a decorator of a few lines. Here are the ready-to-copy patterns.

- [Observability](#observability)
- [Caching compiled rules](#caching-compiled-rules)
- [Loading definitions](#loading-definitions)
- [Spring](#spring)
- [Error handling at the boundary](#error-handling-at-the-boundary)
- [Tests](#tests)

---

## Observability

A decorator that measures and logs without touching the decision:

```java
public final class ObservedPolicy<T> implements Policy<T> {

    private final Policy<T> delegate;
    private final MeterRegistry meters;          // your metrics library
    private static final Logger log = LoggerFactory.getLogger(ObservedPolicy.class);

    public ObservedPolicy(Policy<T> delegate, MeterRegistry meters) {
        this.delegate = Objects.requireNonNull(delegate);
        this.meters = Objects.requireNonNull(meters);
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public PolicyResult evaluate(T context) {
        var startedAt = System.nanoTime();
        var result = delegate.evaluate(context);
        var elapsed = System.nanoTime() - startedAt;

        meters.timer("policy.evaluation",
                     "policy", id(),
                     "allowed", String.valueOf(result.allowed()))
              .record(elapsed, TimeUnit.NANOSECONDS);

        if (result.denied()) {
            log.info("policy={} denied codes={}", id(), result.codes());
        }
        return result;
    }
}
```

It is applied where the policy is built, without touching the rest of the code:

```java
Policy<Booking> observed = new ObservedPolicy<>(canBeConfirmed, meters);
```

Three criteria worth respecting:

- **Log codes, not messages or context.** Codes are stable and aggregatable;
  messages and metadata may contain personal data.
- **A telemetry failure must not change a business decision.** If your metrics
  client can throw, catch it inside the decorator.
- **Tell allowed from denied in the levels.** Denying is normal (`INFO`/`DEBUG`);
  only an unexpected exception deserves `ERROR`.

For traces the pattern is the same, with a `Span` around `evaluate` using `id()`
as the operation name.

---

## Caching compiled rules

Compiling on every request is a waste. Since compiled rules are immutable, they
can be shared as they are:

```java
public final class CachedRuleSource {

    private final RuleStore store;                     // your data access
    private final RuleCompiler<Booking> compiler;
    private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(long version, Rule<Booking> rule) {}

    public Rule<Booking> rule(String ruleId) {
        var stored = store.load(ruleId);               // document plus version
        return cache.compute(ruleId, (id, cached) ->
                cached != null && cached.version() == stored.version()
                        ? cached
                        : new Entry(stored.version(),
                                    compiler.compile(RuleDefinitionCodec.read(stored.document())))
        ).rule();
    }
}
```

The important part is **how it is invalidated**, and that depends on your store:

| Strategy | When |
|----------|------|
| version or `updatedAt` in the document | simplest; one cheap read per request |
| short TTL (`Caffeine.expireAfterWrite`) | when a few seconds of staleness is acceptable |
| invalidation event | when the change must propagate now and you have an event bus |
| scheduled reload | few rules, infrequent changes |

If the document never changes at runtime, the simplest option is to compile at
start-up and keep the rule in a `final` field.

---

## Loading definitions

The library only needs a `Map<String, Object>`.

### MongoDB

```java
Document stored = collection.find(eq("_id", "booking-eligibility")).first();
if (stored == null) {
    throw new IllegalStateException("Rule not configured: booking-eligibility");
}

RuleDefinition definition = RuleDefinitionCodec.read(stored.get("rule", Document.class));
```

`Document` implements `Map<String, Object>`, so it is passed straight through.
Mind the types the driver returns: an integer may arrive as `Integer` or `Long`,
and a decimal as `Decimal128`. `RuleParameters` normalizes anything that is a
`Number`; for `Decimal128` (which is not) call `.toBigDecimal()` when storing, or
register a converter of your own.

### JPA / JSON column

```java
@Entity
class StoredRule {
    @Id String id;
    long version;
    @Column(columnDefinition = "jsonb") String rule;   // the serialized tree
}
```

```java
Map<String, Object> document = objectMapper.readValue(
        stored.getRule(), new TypeReference<Map<String, Object>>() {});

RuleDefinition definition = RuleDefinitionCodec.read(document);
```

### JSON or YAML file

```java
try (var input = Files.newInputStream(Path.of("rules/booking-eligibility.json"))) {
    Map<String, Object> document =
            objectMapper.readValue(input, new TypeReference<Map<String, Object>>() {});
    return RuleDefinitionCodec.read(document);
}
```

### Serializing back to the store

```java
Map<String, Object> document = RuleDefinitionCodec.write(definition);
collection.replaceOne(eq("_id", ruleId), new Document(document), new ReplaceOptions().upsert(true));
```

---

## Spring

Policies are immutable objects: they fit as singleton beans.

```java
@Configuration
class PolicyConfiguration {

    @Bean
    RuleRegistry<Booking> bookingRuleRegistry() {
        return new RuleRegistry<Booking>()
                .register("minimum-age", parameters -> {
                    int minimum = parameters.intValue("minimum");
                    return booking -> booking.age() >= minimum;
                })
                .register("country-in", parameters -> {
                    var allowed = Set.copyOf(parameters.list("countries", String.class));
                    return booking -> allowed.contains(booking.country());
                });
    }

    @Bean
    RuleCompiler<Booking> bookingRuleCompiler(RuleRegistry<Booking> registry) {
        return new RuleCompiler<>(registry);
    }

    @Bean
    Policy<Booking> bookingConfirmationPolicy() {
        return Policies.allOf("booking-can-be-confirmed", List.of(
                Policies.require("booking-must-be-active", Booking::active,
                        "BOOKING_INACTIVE", "The booking must be active"),
                Policies.forbid("booking-must-not-be-cancelled", Booking::cancelled,
                        "BOOKING_CANCELLED", "The booking was cancelled")
        ));
    }
}
```

If a policy depends on a configurable rule, inject the cached source and compose
at evaluation time, not at bean-creation time:

```java
@Service
class BookingConfirmationService {

    private final Policy<Booking> staticPolicy;
    private final CachedRuleSource rules;

    PolicyResult check(Booking booking) {
        var eligibility = Policies.require(
                "booking-eligibility", rules.rule("booking-eligibility"),
                "NOT_ELIGIBLE", "Does not meet the eligibility rule");

        return staticPolicy.evaluate(booking)
                .combine(eligibility.evaluate(booking));
    }
}
```

At start-up it is worth checking that the stored configuration compiles, and
failing the start-up if it does not, rather than discovering it with the first
request:

```java
@EventListener(ApplicationReadyEvent.class)
void verifyStoredRules() {
    store.ids().forEach(id -> compiler.compile(RuleDefinitionCodec.read(store.load(id))));
}
```

---

## Error handling at the boundary

There are two clearly different failure families, and they deserve different
responses:

```java
@RestControllerAdvice
class PolicyExceptionHandler {

    // The request does not meet the business rules: the caller's fault.
    @ExceptionHandler(PolicyViolationException.class)
    ResponseEntity<List<PolicyViolation>> denied(PolicyViolationException exception) {
        return ResponseEntity.unprocessableEntity().body(exception.violations());
    }

    // The stored configuration is broken: our fault.
    @ExceptionHandler(RuleConfigurationException.class)
    ResponseEntity<String> misconfigured(RuleConfigurationException exception) {
        log.error("Misconfigured rule", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Invalid rule configuration");
    }
}
```

Do not return the message of a `RuleConfigurationException` to an end user: it
includes type names and configuration values.

---

## Tests

**Code policies:** no infrastructure needed.

```java
@Test
void reportsEveryReason() {
    var result = canBeConfirmed.evaluate(new Booking("Leo", 16, 3, "US", false));

    assertEquals(List.of("BOOKING_INACTIVE", "NOT_ELIGIBLE"), result.codes());
}
```

Assert on `codes()` rather than on messages: codes are the contract, texts
change.

**Configurable rule types:** test them through the compiler, which is how they
will really be used.

```java
@Test
void compilesMinimumAge() {
    var rule = new RuleCompiler<>(registry).compile(
            RuleDefinitions.atomic("minimum-age", Map.of("minimum", 18)));

    assertTrue(rule.matches(bookingAged(20)));
    assertFalse(rule.matches(bookingAged(16)));
}

@Test
void rejectsAMissingParameter() {
    assertThrows(RuleParameterException.class,
            () -> new RuleCompiler<>(registry).compile(RuleDefinitions.atomic("minimum-age")));
}
```

**Real configuration:** a test that compiles every document stored in the
repository (or in the test environment) catches a mistyped type before it reaches
production.

```java
@Test
void everyStoredRuleCompiles() {
    for (var path : Files.list(Path.of("src/main/resources/rules")).toList()) {
        assertDoesNotThrow(() -> compiler.compile(RuleDefinitionCodec.read(read(path))));
    }
}
```
