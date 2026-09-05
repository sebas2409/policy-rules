# Integración

La librería no trae observabilidad, persistencia ni caché. No es una carencia:
son las tres cosas que cada proyecto ya tiene resueltas a su manera, y meterlas
aquí obligaría a acatar la decisión de la librería y arrastraría dependencias.

Como `Policy` y `Rule` son interfaces pequeñas, añadirlas donde se usan es un
decorador de pocas líneas. Aquí están los patrones listos para copiar.

- [Observabilidad](#observabilidad)
- [Cachear reglas compiladas](#cachear-reglas-compiladas)
- [Cargar definiciones](#cargar-definiciones)
- [Spring](#spring)
- [Manejo de errores en el borde](#manejo-de-errores-en-el-borde)
- [Tests](#tests)

---

## Observabilidad

Un decorador que mide y registra, sin tocar la decisión:

```java
public final class ObservedPolicy<T> implements Policy<T> {

    private final Policy<T> delegate;
    private final MeterRegistry meters;          // tu librería de métricas
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

Se aplica donde se construye la política, sin tocar el resto del código:

```java
Policy<Booking> observed = new ObservedPolicy<>(canBeConfirmed, meters);
```

Tres criterios que conviene respetar:

- **Registra códigos, no mensajes ni contexto.** Los códigos son estables y
  agregables; los mensajes y los metadatos pueden contener datos personales.
- **Un fallo de telemetría no debe cambiar una decisión de negocio.** Si tu
  cliente de métricas puede lanzar, captura la excepción dentro del decorador.
- **Distingue permitido de denegado en los niveles.** Denegar es normal
  (`INFO`/`DEBUG`); solo una excepción inesperada merece `ERROR`.

Para las trazas, el patrón es el mismo con un `Span` alrededor de `evaluate`,
usando `id()` como nombre de la operación.

---

## Cachear reglas compiladas

Compilar en cada petición es un desperdicio. Como las reglas compiladas son
inmutables, se comparten sin más:

```java
public final class CachedRuleSource {

    private final RuleStore store;                     // tu acceso a datos
    private final RuleCompiler<Booking> compiler;
    private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(long version, Rule<Booking> rule) {}

    public Rule<Booking> rule(String ruleId) {
        var stored = store.load(ruleId);               // documento + versión
        return cache.compute(ruleId, (id, cached) ->
                cached != null && cached.version() == stored.version()
                        ? cached
                        : new Entry(stored.version(),
                                    compiler.compile(RuleDefinitionCodec.read(stored.document())))
        ).rule();
    }
}
```

La clave está en **cómo se invalida**, y eso depende de tu almacén:

| Estrategia | Cuándo |
|------------|--------|
| versión o `updatedAt` en el documento | lo más simple; una lectura barata por petición |
| TTL corto (`Caffeine.expireAfterWrite`) | cuando un retraso de segundos es aceptable |
| evento de invalidación | cuando el cambio debe propagarse ya y tienes bus de eventos |
| recarga programada | pocas reglas, cambios poco frecuentes |

Si el documento no cambia nunca en caliente, lo más simple es compilar al
arrancar y guardar la regla en un campo `final`.

---

## Cargar definiciones

La librería solo necesita un `Map<String, Object>`.

### MongoDB

```java
Document stored = collection.find(eq("_id", "booking-eligibility")).first();
if (stored == null) {
    throw new IllegalStateException("Regla no configurada: booking-eligibility");
}

RuleDefinition definition = RuleDefinitionCodec.read(stored.get("rule", Document.class));
```

`Document` implementa `Map<String, Object>`, así que se pasa directamente. Ojo con
los tipos que devuelve el driver: un entero puede llegar como `Integer` o `Long`,
y un decimal como `Decimal128`. `RuleParameters` normaliza los `Number`; para
`Decimal128` (que no lo es) usa `.toBigDecimal()` al guardar o registra un
conversor propio.

### JPA / columna JSON

```java
@Entity
class StoredRule {
    @Id String id;
    long version;
    @Column(columnDefinition = "jsonb") String rule;   // el árbol serializado
}
```

```java
Map<String, Object> document = objectMapper.readValue(
        stored.getRule(), new TypeReference<Map<String, Object>>() {});

RuleDefinition definition = RuleDefinitionCodec.read(document);
```

### Fichero JSON o YAML

```java
try (var input = Files.newInputStream(Path.of("rules/booking-eligibility.json"))) {
    Map<String, Object> document =
            objectMapper.readValue(input, new TypeReference<Map<String, Object>>() {});
    return RuleDefinitionCodec.read(document);
}
```

### Serializar hacia el almacén

```java
Map<String, Object> document = RuleDefinitionCodec.write(definition);
collection.replaceOne(eq("_id", ruleId), new Document(document), new ReplaceOptions().upsert(true));
```

---

## Spring

Las políticas son objetos inmutables: encajan como beans singleton.

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
                        "BOOKING_INACTIVE", "La reserva debe estar activa"),
                Policies.forbid("booking-must-not-be-cancelled", Booking::cancelled,
                        "BOOKING_CANCELLED", "La reserva fue cancelada")
        ));
    }
}
```

Si una política depende de una regla configurable, inyecta la fuente cacheada y
compón en el momento de evaluar, no en el de crear el bean:

```java
@Service
class BookingConfirmationService {

    private final Policy<Booking> staticPolicy;
    private final CachedRuleSource rules;

    PolicyResult check(Booking booking) {
        var eligibility = Policies.require(
                "booking-eligibility", rules.rule("booking-eligibility"),
                "NOT_ELIGIBLE", "No cumple la regla de elegibilidad");

        return staticPolicy.evaluate(booking)
                .combine(eligibility.evaluate(booking));
    }
}
```

Al arrancar conviene comprobar que la configuración almacenada es compilable, y
fallar el arranque si no lo es, en vez de descubrirlo con la primera petición:

```java
@EventListener(ApplicationReadyEvent.class)
void verifyStoredRules() {
    store.ids().forEach(id -> compiler.compile(RuleDefinitionCodec.read(store.load(id))));
}
```

---

## Manejo de errores en el borde

Hay dos familias de fallo bien distintas, y conviene mapearlas a respuestas
distintas:

```java
@RestControllerAdvice
class PolicyExceptionHandler {

    // La petición no cumple las reglas de negocio: culpa del cliente.
    @ExceptionHandler(PolicyViolationException.class)
    ResponseEntity<List<PolicyViolation>> denied(PolicyViolationException exception) {
        return ResponseEntity.unprocessableEntity().body(exception.violations());
    }

    // La configuración almacenada está rota: culpa nuestra.
    @ExceptionHandler(RuleConfigurationException.class)
    ResponseEntity<String> misconfigured(RuleConfigurationException exception) {
        log.error("Regla mal configurada", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Configuración de reglas inválida");
    }
}
```

No devuelvas el mensaje de una `RuleConfigurationException` a un cliente final:
incluye nombres de tipo y valores de configuración.

---

## Tests

**Políticas de código:** no hace falta infraestructura.

```java
@Test
void reportsEveryReason() {
    var result = canBeConfirmed.evaluate(new Booking("Leo", 16, 3, "US", false));

    assertEquals(List.of("BOOKING_INACTIVE", "NOT_ELIGIBLE"), result.codes());
}
```

Asserta sobre `codes()` y no sobre los mensajes: los códigos son el contrato, los
textos cambian.

**Tipos de regla configurables:** pruébalos a través del compilador, que es como
se usarán de verdad.

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

**Configuración real:** un test que compile todos los documentos guardados en el
repositorio (o en el entorno de pruebas) detecta un tipo mal escrito antes de que
llegue a producción.

```java
@Test
void everyStoredRuleCompiles() {
    for (var path : Files.list(Path.of("src/main/resources/rules")).toList()) {
        assertDoesNotThrow(() -> compiler.compile(RuleDefinitionCodec.read(read(path))));
    }
}
```
