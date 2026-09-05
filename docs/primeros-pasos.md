# Primeros pasos

Esta guía construye, paso a paso, la validación de un caso de uso real: la
confirmación de una reserva. Al final tendrás una política compuesta que reporta
todos los motivos de rechazo y una parte de sus condiciones vive en
configuración.

## 1. El contexto

Una política decide sobre un **contexto**: el objeto que ya tiene tu caso de uso.
No hace falta ningún tipo especial, ni anotaciones, ni heredar de nada.

```java
record Booking(
        String customer,
        int age,
        int weeklyBookings,
        String country,
        boolean active
) {}
```

Un consejo: pásale a la política lo que necesita para decidir, ya cargado. Si una
condición requiere consultar la base de datos, haz la consulta antes y mete el
resultado en el contexto (`weeklyBookings` en el ejemplo). Así las reglas siguen
siendo funciones puras, se pueden probar sin infraestructura y evaluarlas no
dispara consultas sorpresa.

## 2. La primera política

`Policies.require` construye una política que permite el contexto **solo si** la
regla se cumple:

```java
import com.policyrules.policy.Policies;
import com.policyrules.policy.Policy;

Policy<Booking> mustBeActive = Policies.require(
        "booking-must-be-active",       // identificador estable
        Booking::active,                // la condición
        "BOOKING_INACTIVE",             // código del motivo
        "La reserva debe estar activa"  // mensaje del motivo
);
```

Los cuatro argumentos tienen papeles distintos:

- El **identificador** nombra a la política en tests, logs y métricas. No se
  muestra al usuario.
- La **condición** es un `Rule<Booking>`, que al ser una interfaz funcional
  acepta cualquier lambda o referencia a método.
- El **código** es lo que consumen tus clientes (una API, un front que traduce).
  Trátalo como un enum que solo crece.
- El **mensaje** es para personas y puede cambiar cuando quieras.

## 3. Evaluar

```java
PolicyResult result = mustBeActive.evaluate(booking);

result.allowed();        // true si no hay violaciones
result.denied();         // lo contrario
result.violations();     // lista inmutable de motivos
result.codes();          // ["BOOKING_INACTIVE"]
```

`evaluate` nunca lanza por una denegación: devolver el motivo es el resultado
normal. Cuando la operación no puede continuar, el borde de la aplicación usa
`enforce`:

```java
mustBeActive.enforce(booking);   // PolicyViolationException si deniega
```

## 4. Explicar el rechazo con datos

Cuando el motivo depende del contexto, usa la variante con factoría. Solo se
invoca si la regla falla, así que puede ser todo lo detallada que haga falta:

```java
Policy<Booking> withinWeeklyLimit = Policies.require(
        "weekly-limit",
        booking -> booking.weeklyBookings() < 3,
        booking -> new PolicyViolation(
                "WEEKLY_LIMIT_REACHED",
                "Límite semanal alcanzado",
                Map.of("current", booking.weeklyBookings(), "maximum", 3)
        )
);
```

Los metadatos permiten que quien recibe el rechazo lo explique sin reimplementar
la lógica: *"llevas 3 de 3 reservas esta semana"*.

## 5. Componer

Una política compuesta es también una política, así que el caso de uso expone
una sola aunque por dentro tenga cinco condiciones:

```java
Policy<Booking> canBeConfirmed = Policies.allOf("booking-can-be-confirmed", List.of(
        mustBeActive,
        withinWeeklyLimit
));
```

Hay dos estrategias, y la elección depende de qué hace quien recibe la respuesta:

| Factoría | Comportamiento | Cuándo |
|----------|----------------|--------|
| `Policies.allOf` | evalúa todas y acumula todos los motivos | formularios y APIs: el usuario quiere arreglarlo todo de una vez |
| `Policies.firstFailureOf` | para en el primer rechazo | rutas calientes, o cuando las comprobaciones siguientes son caras |

En ambos casos se respeta el orden de declaración, tanto al evaluar como al
reportar.

## 6. Mover una condición a configuración

Hasta aquí todo vive en el código. Supongamos que la edad mínima y los países
admitidos cambian por campaña. Primero se declara **qué tipos de regla acepta la
aplicación**:

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

Después se compila el documento que venga de donde venga:

```java
Map<String, Object> document = ruleStore.load("booking-eligibility");

Rule<Booking> eligible = compiler.compile(RuleDefinitionCodec.read(document));

Policy<Booking> eligibility = Policies.require(
        "booking-eligibility", eligible,
        "NOT_ELIGIBLE", "La reserva no cumple la regla de elegibilidad");
```

Con este documento:

```json
{
  "operator": "and",
  "rules": [
    { "type": "minimum-age", "parameters": { "minimum": 18 } },
    { "type": "country-in",  "parameters": { "countries": ["ES", "PT"] } }
  ]
}
```

La política resultante se combina con las demás igual que cualquier otra:

```java
Policy<Booking> canBeConfirmed = Policies.allOf("booking-can-be-confirmed", List.of(
        mustBeActive,
        withinWeeklyLimit,
        eligibility
));
```

## 7. Dónde construir cada cosa

| Se construye | Cuándo | Se reutiliza |
|--------------|--------|--------------|
| `RuleRegistry` | una vez, al arrancar | siempre |
| `RuleCompiler` | una vez, al arrancar | siempre |
| Políticas de código | una vez, al arrancar | siempre |
| `RuleDefinition` compilada | al leer la configuración | hasta que la configuración cambie (ver [caché](integracion.md#cachear-reglas-compiladas)) |
| `PolicyResult` | en cada evaluación | no |

Todo lo que devuelve la librería es inmutable y seguro entre hilos, así que
guardar políticas y reglas compiladas en campos `static final` o en beans
singleton es correcto.

## 8. Probarlo

Las políticas se prueban sin arrancar nada:

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

El ejemplo completo, ejecutable, está en
`src/test/java/com/policyrules/examples/BookingExampleTest.java`.

## Siguiente paso

- [Políticas y resultados](politicas.md) para el detalle del modelo.
- [Reglas dinámicas](reglas-dinamicas.md) para exprimir la configuración.
- [Integración](integracion.md) para conectarlo con tu infraestructura.
