# policy-rules

[![CI](https://github.com/sebas2409/policy-rules/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sebas2409/policy-rules/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/sebas2409/policy-rules?label=release)](https://github.com/sebas2409/policy-rules/releases/latest)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
![Java](https://img.shields.io/badge/Java-25-orange)

Librería Java para expresar **reglas de negocio** y **políticas** como objetos
componibles, incluyendo reglas cuya forma se decide **en configuración** y no en
el código.

- **Sin dependencias.** Solo la biblioteca estándar de Java 25.
- **Sin infraestructura.** No lee de una base de datos, no escribe logs, no
  depende de ningún framework. Eso lo pone el proyecto que la usa.
- **Inmutable y segura entre hilos.** Todo lo que devuelve la librería se
  construye una vez y se puede compartir.

---

## Índice

- [El problema](#el-problema)
- [Instalación](#instalación)
- [Ejemplo en un minuto](#ejemplo-en-un-minuto)
- [Los cuatro conceptos](#los-cuatro-conceptos)
- [Reglas configurables](#reglas-configurables)
- [Mapa de la API](#mapa-de-la-api)
- [Qué no incluye y por qué](#qué-no-incluye-y-por-qué)
- [Documentación](#documentación)
- [Compilar y publicar](#compilar-y-publicar)

---

## El problema

Las condiciones de negocio suelen acabar mezcladas dentro de los servicios:

```java
public void confirm(Booking booking) {
    if (!booking.active()) {
        throw new IllegalStateException("La reserva no está activa");
    }
    if (booking.customer().age() < 18) {
        throw new IllegalStateException("Menor de edad");
    }
    if (booking.weeklyBookings() >= 3) {
        throw new IllegalStateException("Límite semanal alcanzado");
    }
    // ...
}
```

Esto tiene tres problemas concretos:

1. **La condición y su significado están pegados.** No se puede reutilizar
   `age() >= 18` en otro caso de uso sin arrastrar el mensaje y la excepción.
2. **Solo se reporta el primer fallo.** Un formulario o una API necesitan
   normalmente *todos* los motivos a la vez.
3. **Cambiar un umbral es un despliegue.** El `18` y el `3` son datos de
   negocio, pero viven en el código.

`policy-rules` separa esas tres cosas: la condición (`Rule`), su significado
(`Policy` → `PolicyResult`) y su origen (código o configuración).

---

## Instalación

La librería se publica en **GitHub Packages** desde el workflow de release.
Maven:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/sebas2409/policy-rules</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.policyrules</groupId>
        <artifactId>policy-rules</artifactId>
        <version>1.0.0</version>
    </dependency>
</dependencies>
```

Gradle:

```kotlin
repositories {
    maven { url = uri("https://maven.pkg.github.com/sebas2409/policy-rules") }
}

dependencies {
    implementation("com.policyrules:policy-rules:1.0.0")
}
```

GitHub Packages pide autenticación incluso para leer: hace falta un token con
`read:packages` en el `settings.xml` del consumidor. Los detalles, y las
alternativas de consumo anónimo (Maven Central y JitPack), están en
[docs/publicacion.md](docs/publicacion.md).

Requiere **Java 25**. La librería publica el nombre de módulo automático
`com.policyrules`, así que funciona tanto en el classpath como en el module path.

---

## Ejemplo en un minuto

```java
import com.policyrules.policy.*;
import java.util.List;
import java.util.Map;

record Booking(String customer, int age, int weeklyBookings, boolean active) {}

Policy<Booking> canBeConfirmed = Policies.allOf("booking-can-be-confirmed", List.of(

        Policies.require("booking-must-be-active",
                Booking::active,
                "BOOKING_INACTIVE", "La reserva debe estar activa"),

        Policies.require("customer-must-be-adult",
                booking -> booking.age() >= 18,
                "CUSTOMER_UNDERAGE", "El cliente debe ser mayor de edad"),

        Policies.require("weekly-limit",
                booking -> booking.weeklyBookings() < 3,
                booking -> new PolicyViolation(
                        "WEEKLY_LIMIT_REACHED",
                        "Límite semanal alcanzado",
                        Map.of("current", booking.weeklyBookings(), "maximum", 3)))
));

PolicyResult result = canBeConfirmed.evaluate(booking);

if (result.allowed()) {
    bookings.confirm(booking);
} else {
    return unprocessableEntity(result.violations());   // todos los motivos
}
```

Y en el borde de la aplicación, cuando la operación no puede continuar:

```java
canBeConfirmed.enforce(booking);   // lanza PolicyViolationException si deniega
```

---

## Los cuatro conceptos

| Tipo              | Responde a                 | Contiene                                |
|-------------------|----------------------------|-----------------------------------------|
| `Rule<T>`         | *¿se cumple la condición?* | un `boolean`, nada más                  |
| `Policy<T>`       | *¿puede ocurrir esto?*     | una condición + el motivo de denegarla  |
| `PolicyResult`    | *¿qué ha pasado?*          | la lista de motivos (vacía = permitido) |
| `PolicyViolation` | *¿por qué no?*             | código estable, mensaje y metadatos     |

Dos decisiones de diseño que conviene entender antes de usarla:

**Una denegación es un valor, no una excepción.** Denegar es un resultado
esperado del negocio, así que `evaluate` devuelve un `PolicyResult` con *todos*
los motivos. Solo el borde de la aplicación lo convierte en excepción, con
`enforce` o `requireAllowed`.

**El estado se deriva, no se guarda.** `PolicyResult.allowed()` es
`violations().isEmpty()`. Así es imposible construir los estados contradictorios
"denegado sin motivo" o "permitido con violaciones".

Detalle completo en [docs/politicas.md](docs/politicas.md).

---

## Reglas configurables

Cuando un umbral cambia más a menudo que el software, la condición puede vivir
fuera del código. La aplicación declara **qué tipos de regla acepta** y la
configuración solo puede combinarlos y parametrizarlos:

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

El documento almacenado —lo cargue quien lo cargue— tiene esta forma:

```json
{
  "operator": "and",
  "rules": [
    { "type": "minimum-age", "parameters": { "minimum": 18 } },
    { "type": "country-in",  "parameters": { "countries": ["ES", "PT"] } }
  ]
}
```

Y se compila en una regla normal y corriente:

```java
Map<String, Object> document = ruleStore.load("booking-eligibility");

Rule<Booking> eligible = compiler.compile(RuleDefinitionCodec.read(document));

Policy<Booking> eligibility = Policies.require(
        "booking-eligibility", eligible,
        "NOT_ELIGIBLE", "La reserva no cumple la regla de elegibilidad");
```

La regla compilada es indistinguible de una escrita a mano: se compone con
`and`/`or`/`not` y la usa cualquier política.

Tres garantías que hacen esto seguro:

- **La configuración no introduce comportamiento.** Solo puede usar los tipos
  registrados; cualquier otro se rechaza con `UnknownRuleTypeException`.
- **Los errores salen al compilar, no al decidir.** Un parámetro que falta o un
  tipo desconocido fallan en `compile(...)`. Una regla ya compilada no puede
  fallar por configuración.
- **La librería no sabe de dónde viene el documento.** Mongo, PostgreSQL, un
  fichero JSON o un servicio remoto: todos entregan un `Map<String, Object>`.

Guía completa en [docs/reglas-dinamicas.md](docs/reglas-dinamicas.md) y
especificación del formato en [docs/formato-de-reglas.md](docs/formato-de-reglas.md).

---

## Mapa de la API

```
com.policyrules.policy              Decisiones de negocio
    Policy<T>                       id() + evaluate(ctx) + enforce(ctx)
    Policies                        require, forbid, allOf, firstFailureOf, adapt, allow, deny
    PolicyResult                    allowed/denied, violations, codes, combine, requireAllowed
    PolicyViolation                 code + message + metadata
    PolicyViolationException        lo que lanza el borde de la aplicación

com.policyrules.rule                Condiciones
    Rule<T>                         matches + and/or/not + adapt + asPredicate
    Rules                           alwaysTrue, alwaysFalse, of, not, allOf, anyOf

com.policyrules.rule.definition     Reglas desde configuración
    RuleDefinition                  modelo sellado: atómica, and, or, not
    RuleDefinitions                 factorías + typesOf/sizeOf para validar
    RuleDefinitionCodec             Map <-> RuleDefinition
    RuleRegistry<T>                 catálogo de tipos aceptados
    RuleFactory<T>                  construye una regla desde sus parámetros
    RuleParameters                  lectura tipada de parámetros
    RuleConfigurationException      + UnknownRuleType / RuleParameter / RuleDefinitionFormat
```

---

## Qué no incluye y por qué

La librería no trae **observabilidad**, **persistencia** ni **caché**. No es una
carencia: son justo las tres cosas que cada proyecto ya tiene resueltas a su
manera, y meterlas aquí obligaría a acatar la decisión de la librería.

Como `Policy` y `Rule` son interfaces pequeñas, añadirlas en tu proyecto es un
decorador de pocas líneas. En [docs/integracion.md](docs/integracion.md) están
los patrones listos para copiar:

- decorar una política con métricas, logs o trazas,
- cachear reglas compiladas e invalidarlas al cambiar la configuración,
- cargar definiciones desde MongoDB, JPA o un fichero JSON,
- registrar las políticas como beans de Spring,
- validar la configuración almacenada antes de que llegue a producción.

---

## Documentación

| Documento                                              | Contenido                                              |
|--------------------------------------------------------|--------------------------------------------------------|
| [docs/primeros-pasos.md](docs/primeros-pasos.md)       | De cero a una política compuesta, paso a paso          |
| [docs/politicas.md](docs/politicas.md)                 | Políticas, resultados, violaciones y composición       |
| [docs/reglas-dinamicas.md](docs/reglas-dinamicas.md)   | Registro, factorías, parámetros y compilación          |
| [docs/formato-de-reglas.md](docs/formato-de-reglas.md) | Especificación del documento de configuración          |
| [docs/integracion.md](docs/integracion.md)             | Observabilidad, persistencia, caché, Spring y tests    |
| [docs/diseno.md](docs/diseno.md)                       | Decisiones de diseño y sus alternativas descartadas    |
| [docs/publicacion.md](docs/publicacion.md)             | Flujo de release, workflows y cómo consumir el paquete |

El JavaDoc es la referencia detallada de cada tipo; se genera con
`mvn -Prelease javadoc:javadoc` y queda en `target/site/apidocs`.

---

## Compilar y publicar

```bash
mvn verify                 # compila y ejecuta los tests
mvn -Prelease install      # además genera los jar de fuentes y JavaDoc
```

El perfil `release` adjunta `-sources.jar` y `-javadoc.jar`. El JavaDoc se genera
con `doclint` estricto: cualquier documentación incompleta rompe la build.

La publicación es automática y **la versión del `pom.xml` manda**: al mergear a
`main` un cambio de `<version>`, el workflow `Release` publica el paquete, crea
el tag `vX.Y.Z` y abre la release con los jar adjuntos. Una versión `-SNAPSHOT`,
o una cuyo tag ya existe, no publica nada.

```bash
# subir <version> en pom.xml (ej. 1.1.0) y:
git commit -am "Versión 1.1.0" && git push origin main
```

Detalle completo en [docs/publicacion.md](docs/publicacion.md).

---

## Licencia

MIT. Ver [LICENSE](LICENSE).
