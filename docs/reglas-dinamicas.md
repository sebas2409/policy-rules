# Reglas configurables

Referencia del paquete `com.policyrules.rule.definition`: cómo mover una
condición fuera del código sin perder seguridad de tipos y sin permitir que la
configuración introduzca comportamiento arbitrario.

## Cuándo merece la pena

Mover una condición a configuración tiene un coste: hay que registrar el tipo,
documentarlo y validarlo. Compensa cuando **el valor cambia más a menudo que el
software**: umbrales de campaña, listas de países, límites por segmento.

No compensa para invariantes del dominio ("una reserva cancelada no se
confirma"). Eso es código: si cambia, es un cambio de software y quieres que pase
por revisión, tests y despliegue.

Lo normal es una mezcla: la mayoría de condiciones en código, unas pocas
configurables.

## Las cuatro piezas

```
documento (Map)  --RuleDefinitionCodec-->  RuleDefinition  --RuleCompiler-->  Rule<T>
                                                                  ^
                                                            RuleRegistry
                                                       (tipos que la app acepta)
```

1. **`RuleRegistry<T>`** — el catálogo de tipos de regla que la aplicación
   implementa y, por tanto, acepta.
2. **`RuleDefinition`** — el dato: un árbol de operadores booleanos sobre tipos
   parametrizados.
3. **`RuleDefinitionCodec`** — traduce entre ese árbol y un `Map<String, Object>`.
4. **`RuleCompiler<T>`** — recorre el árbol y produce una `Rule<T>` normal.

## 1. Registrar los tipos aceptados

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

El registro es **la frontera de seguridad**: la configuración solo puede combinar
y parametrizar estos tipos. Un tipo no registrado se rechaza con
`UnknownRuleTypeException`, que además lleva la lista de los que sí existen para
poder mostrar un mensaje útil.

Otras operaciones:

```java
registry.contains("minimum-age");   // true
registry.types();                   // conjunto inmutable y ordenado
registry.create("minimum-age", Map.of("minimum", 18));   // regla suelta, sin árbol
```

Un tipo solo se puede registrar una vez: un duplicado lanza en el arranque en vez
de sustituir comportamiento en silencio. El registro es seguro entre hilos, pero
lo habitual es construirlo una vez y no volver a tocarlo.

### Nombrar los tipos

El nombre es contrato con quien escribe la configuración. Un par de criterios que
funcionan bien:

- Describe **qué comprueba**, no cómo: `minimum-age`, no `check-age-gte`.
- Un solo estilo para todos (`kebab-case` es el más habitual en configuración).
- Evita meter el valor en el nombre: `minimum-age` con parámetro, nunca
  `age-over-18`.

## 2. Leer los parámetros con `RuleParameters`

Los valores llegan de fuera, así que su tipo real depende del parser o del driver
que los produjo: un `18` puede ser `Integer`, `Long`, `Double` o `"18"`.
`RuleParameters` normaliza eso y falla con un mensaje que nombra el parámetro:

```java
parameters.intValue("minimum");                     // obligatorio
parameters.intValue("minimum", 18);                 // con valor por defecto
parameters.string("currency");
parameters.booleanValue("enabled", false);
parameters.decimal("amount");                       // sin pasar por double
parameters.list("countries", String.class);
parameters.value("channel", Channel.class);         // enum por nombre
parameters.group("window").intValue("days");        // parámetros anidados
```

Conversiones aceptadas:

| Tipo pedido | Se acepta |
|-------------|-----------|
| `String` | cualquier `CharSequence` |
| `int`, `long` | cualquier `Number` sin decimales, o texto que lo parsee, dentro del rango |
| `double`, `BigDecimal` | cualquier `Number`, o texto numérico |
| `boolean` | `Boolean`, o el texto `true`/`false` sin distinguir mayúsculas |
| un `enum` | una instancia, o el nombre de la constante sin distinguir mayúsculas |
| cualquier otro | una instancia de ese tipo |

Los números pasan por `BigDecimal`, así que `18`, `18.0` y `"18"` dan el mismo
`int`, y un valor que perdería precisión o se saldría de rango se rechaza en vez
de truncarse.

> **Lee los parámetros al construir, no al evaluar.** Como en los ejemplos: saca
> los valores a variables locales y captúralas en la lambda. Si los leyeras dentro
> de `matches`, repetirías la conversión en cada evaluación y un error de
> configuración estallaría en mitad de una decisión de negocio en vez de al
> compilar.

## 3. Cargar el documento

La librería no lee de ninguna parte. Tu aplicación entrega un
`Map<String, Object>` —que es lo que devuelven todos los parsers y drivers— y el
codec lo convierte:

```java
Map<String, Object> document = ruleStore.load("booking-eligibility");
RuleDefinition definition = RuleDefinitionCodec.read(document);
```

El formato está especificado en [formato-de-reglas.md](formato-de-reglas.md).
Ejemplos de carga desde MongoDB, JPA o un fichero JSON en
[integracion.md](integracion.md#cargar-definiciones).

También puedes construir el árbol a mano, lo que va bien para tests y para datos
semilla:

```java
RuleDefinition definition = RuleDefinitions.and(
        RuleDefinitions.atomic("minimum-age", Map.of("minimum", 18)),
        RuleDefinitions.or(
                RuleDefinitions.atomic("country-in", Map.of("countries", List.of("ES"))),
                RuleDefinitions.not(RuleDefinitions.atomic("blocked"))
        )
);
```

Y volver a serializarlo con `RuleDefinitionCodec.write(definition)`, que produce
exactamente la forma canónica: leer y escribir es un viaje de ida y vuelta sin
pérdidas.

## 4. Compilar

```java
RuleCompiler<Booking> compiler = new RuleCompiler<>(registry);

Rule<Booking> eligible = compiler.compile(definition);
```

Compilar ejecuta **todas** las factorías, así que cualquier problema de
configuración sale aquí:

| Excepción | Causa |
|-----------|-------|
| `UnknownRuleTypeException` | el tipo no está registrado |
| `RuleParameterException` | falta un parámetro o su valor no sirve |
| `RuleDefinitionFormatException` | el documento no sigue el formato |

Las tres heredan de `RuleConfigurationException`, que a su vez es una
`IllegalArgumentException`. Eso permite distinguir en el borde "la configuración
está rota" (un 5xx o un aviso a operaciones) de "la petición no cumple las
reglas" (un 4xx):

```java
try {
    Rule<Booking> rule = compiler.compile(definition);
} catch (RuleConfigurationException brokenConfiguration) {
    alerts.notify("Regla mal configurada", brokenConfiguration);
    throw brokenConfiguration;
}
```

Una vez compilada, la regla **no puede volver a fallar por configuración**: es
una función pura sobre el contexto.

## 5. Usarla como cualquier otra regla

La regla compilada no arrastra ningún rastro de su origen:

```java
Policy<Booking> eligibility = Policies.require(
        "booking-eligibility",
        eligible.and(Booking::active),        // se compone con reglas de código
        "NOT_ELIGIBLE", "No cumple la regla de elegibilidad");
```

## Rendimiento y caché

Compilar es barato, pero no gratis: recorre el árbol e invoca una factoría por
nodo atómico. Evaluar una regla compilada es tan rápido como el código
equivalente escrito a mano.

Compilar en cada petición es un desperdicio. Como las reglas compiladas son
inmutables, se pueden cachear y compartir entre hilos sin más; el patrón está en
[integracion.md](integracion.md#cachear-reglas-compiladas).

## Validar la configuración antes de producción

`RuleDefinitions` permite inspeccionar un árbol sin compilarlo, lo que sirve para
un endpoint de validación o una comprobación al arrancar:

```java
Set<String> unsupported = new TreeSet<>(RuleDefinitions.typesOf(definition));
unsupported.removeIf(registry::contains);

if (!unsupported.isEmpty()) {
    throw new IllegalStateException("Tipos de regla no soportados: " + unsupported);
}

if (RuleDefinitions.sizeOf(definition) > 100) {
    throw new IllegalStateException("La regla es demasiado grande");
}
```

Compilar es aún más estricto que esto (también valida los parámetros), así que si
puedes permitirte compilar, compila: es la mejor validación posible.

## Notas de seguridad

- La configuración **no puede introducir comportamiento**: solo elige entre los
  tipos registrados y les pasa parámetros. No hay expresiones, ni scripts, ni
  reflexión.
- `RuleDefinitionCodec` rechaza documentos anidados más allá de
  `RuleDefinitionCodec.MAX_DEPTH` (50 niveles), de modo que un documento hostil
  no puede agotar la pila del lector recursivo.
- Un árbol grande sigue siendo caro de evaluar: si el documento viene de una
  fuente poco fiable, limita su tamaño con `RuleDefinitions.sizeOf`.
- Los mensajes de error incluyen nombres de tipo y de parámetro, y valores de
  configuración. Son útiles para operaciones, pero no los devuelvas tal cual a un
  cliente final.
