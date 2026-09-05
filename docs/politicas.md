# Políticas y resultados

Referencia del paquete `io.github.sebas2409.policyrules.policy`: qué modela cada tipo, qué
garantiza y cuándo usar cada factoría.

## Regla y política: la diferencia

```java
Rule<Booking>   active = Booking::active;                    // ¿se cumple?
Policy<Booking> policy = Policies.require(                   // ¿puede ocurrir?
        "booking-must-be-active", active,
        "BOOKING_INACTIVE", "La reserva debe estar activa");
```

Una regla solo sabe decir sí o no. Una política además sabe **qué significa
fallar**. Esa separación es la que permite que la misma condición la usen varias
políticas con motivos distintos, y que una condición pueda venir del código o de
la configuración sin que a la política le importe.

## `PolicyResult`

Un resultado es una lista de violaciones y nada más:

```java
public record PolicyResult(List<PolicyViolation> violations)
```

`allowed()` se deriva de la lista (`violations().isEmpty()`), no se guarda. Por
eso no existe un resultado "denegado sin motivo" ni uno "permitido con
violaciones": no se pueden construir.

| Método | Devuelve |
|--------|----------|
| `allowed()` / `denied()` | si el contexto pasó o no |
| `violations()` | lista inmutable de motivos, en orden de evaluación |
| `codes()` | los códigos de esos motivos |
| `firstViolation()` | `Optional` con el primer motivo |
| `hasViolation(code)` | si un motivo concreto está presente, sin depender del orden |
| `combine(other)` | un resultado con los motivos de ambos |
| `requireAllowed()` | lanza `PolicyViolationException` si está denegado |

Fabricar resultados a mano (útil al escribir una `Policy` propia):

```java
PolicyResult.allow();
PolicyResult.deny("CODE", "Mensaje");
PolicyResult.deny(new PolicyViolation("CODE", "Mensaje", Map.of("limit", 3)));
PolicyResult.deny(List.of(first, second));   // rechaza la lista vacía
```

## `PolicyViolation`

```java
new PolicyViolation("WEEKLY_LIMIT_REACHED",
                    "Límite semanal alcanzado",
                    Map.of("current", 3, "maximum", 3));
```

- **`code`**: identificador estable y legible por máquina. Es contrato con tus
  clientes; trátalo como un enum que solo crece y nunca cambia de significado.
- **`message`**: texto para personas. Puede cambiar libremente. Si necesitas
  traducciones, tradúcelo en el borde usando el `code` como clave.
- **`metadata`**: los valores que explican la decisión. Deben ser no nulos y
  serializables por cualquier librería. **No metas datos personales ni
  sensibles**: las violaciones acaban en logs y en respuestas de API.

`with(clave, valor)` devuelve una copia enriquecida, útil para añadir contexto
del sitio de llamada a una violación producida por una política reutilizable:

```java
violation.with("bookingId", booking.id());
```

## Denegación frente a excepción

Una denegación es un resultado esperado del negocio, así que viaja como valor.
Solo el borde de la aplicación la convierte en excepción:

```java
// En el dominio: se decide con el resultado
PolicyResult result = policy.evaluate(booking);
if (result.denied()) {
    return Rejected.of(result.violations());
}

// En el borde: si no puede continuar
policy.enforce(booking);   // PolicyViolationException
```

`PolicyViolationException` lleva `violations()` y `codes()`. Su mensaje incluye
solo los **códigos**, no los textos, para no volcar mensajes de negocio en los
logs sin querer. Lo habitual es traducirla una vez para toda la aplicación:

```java
@ExceptionHandler(PolicyViolationException.class)
ResponseEntity<?> handle(PolicyViolationException denied) {
    return ResponseEntity.unprocessableEntity().body(denied.violations());
}
```

## Catálogo de factorías

### `require` — permitir solo si se cumple

```java
Policies.require(id, rule, code, message);
Policies.require(id, rule, context -> violation);   // motivo con datos del contexto
```

La violación de la primera variante se construye una sola vez y se reutiliza. La
segunda solo invoca la factoría cuando la regla falla, así que el camino
permitido no paga nada.

### `forbid` — denegar si se cumple

```java
Policies.forbid("booking-must-not-be-cancelled",
        Booking::cancelled,
        "BOOKING_CANCELLED", "La reserva fue cancelada");
```

Equivale a `require` con la regla negada. Existe porque algunas condiciones se
leen mucho mejor enunciadas como lo que **no** debe pasar.

### `allOf` — todos los motivos

```java
Policies.allOf("booking-can-be-confirmed", List.of(active, notCancelled, withinLimit));
Policies.allOf("booking-can-be-confirmed", active, notCancelled, withinLimit);
```

Evalúa todas las políticas miembro y acumula sus violaciones en orden de
declaración. Es lo que quieren los formularios y las APIs.

### `firstFailureOf` — parar en el primero

```java
Policies.firstFailureOf("booking-can-be-confirmed", List.of(cheapCheck, expensiveCheck));
```

No evalúa nada después del primer rechazo. Útil cuando una comprobación
posterior es cara, o cuando la primera razón ya es suficiente.

Como un compuesto es también una política, se pueden anidar: un `allOf` de
varios `firstFailureOf`, por ejemplo, agrupa comprobaciones por área y da un solo
motivo por área.

### `adapt` — reutilizar en un contexto más amplio

```java
Policy<Customer> verified = Policies.require("customer-verified",
        Customer::verified, "CUSTOMER_NOT_VERIFIED", "Cliente no verificado");

Policy<Order> orderFromVerifiedCustomer = Policies.adapt(verified, Order::customer);
```

Evita tener una variante de la misma política por cada agregado que la necesita.
La política adaptada conserva el identificador de la original. `Rule` tiene el
método equivalente: `rule.adapt(Order::customer)`.

### `allow` y `deny` — políticas constantes

```java
Policies.allow("feature-open");
Policies.deny("feature-closed", "FEATURE_DISABLED", "Función desactivada");
```

Para desactivar una operación sin desmontar su cableado (detrás de un feature
flag, por ejemplo), y como elemento neutro al construir listas dinámicamente.

## Escribir una `Policy` propia

Las factorías cubren lo habitual, pero `Policy` es una interfaz de dos métodos y
puedes implementarla cuando una decisión no encaje en "una regla y un motivo":

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
            violations.add(new PolicyViolation("START_IN_THE_PAST", "La fecha ya pasó"));
        }
        if (booking.nights() > 30) {
            violations.add(new PolicyViolation("TOO_LONG", "Máximo 30 noches"));
        }
        return violations.isEmpty() ? PolicyResult.allow() : PolicyResult.deny(violations);
    }
}
```

El contrato que debe cumplir cualquier implementación:

- `id()` estable y no vacío.
- `evaluate` nunca devuelve `null` y no tiene efectos observables.
- Una denegación se reporta como resultado, no como excepción. Las excepciones
  quedan para un contexto inválido o una dependencia rota.
- Debe ser segura entre hilos. Las de esta librería lo son.

## Errores frecuentes

**Meter la consulta a base de datos dentro de la regla.** Rompe la pureza y hace
que evaluar dispare I/O. Carga el dato antes y mételo en el contexto.

**Usar el mensaje como identificador.** El mensaje cambia; el código no. Los
clientes deben ramificar por `code`.

**Reutilizar el mismo código para motivos distintos.** Si dos denegaciones se
arreglan de forma distinta, son dos códigos distintos.

**Construir la política en cada petición.** Constrúyela una vez al arrancar: son
inmutables y compartirlas es gratis.
