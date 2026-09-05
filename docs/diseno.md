# Decisiones de diseño

Por qué la librería es como es, y qué alternativas se descartaron. Útil si vas a
extenderla, o si estás decidiendo si encaja en tu proyecto.

## Una denegación es un valor, no una excepción

`evaluate` devuelve un `PolicyResult` en vez de lanzar. Denegar es un resultado
esperado del negocio, y usar excepciones para el flujo normal tiene tres costes:
solo se reporta el primer motivo, el compilador no obliga a tratarlo, y el
llamante paga el coste de construir la traza.

La excepción existe, pero solo en el borde: `requireAllowed()` y `enforce(...)`
la lanzan cuando la operación no puede continuar. La decisión de si una
denegación es fatal la toma quien llama, no la política.

## El estado se deriva de los datos

```java
public record PolicyResult(List<PolicyViolation> violations) {
    public boolean allowed() { return violations.isEmpty(); }
}
```

Guardar un `boolean allowed` junto a la lista permitiría construir "denegado sin
motivo" o "permitido con violaciones". Derivarlo hace que esos estados no
existan. Es la misma razón por la que `PolicyResult.deny(List.of())` lanza en vez
de crear un resultado incoherente.

## Regla y política separadas

Podrían ser un solo tipo: una condición que devuelve un motivo o nada. Están
separadas porque tienen ciclos de vida distintos:

- Una condición se reutiliza entre casos de uso con **motivos diferentes**
  (`age >= 18` puede ser `CUSTOMER_UNDERAGE` en un sitio y `RESTRICTED_PRODUCT`
  en otro).
- Una condición puede venir de configuración; un motivo, no: los códigos son
  contrato con los clientes y deben vivir en el código.

Ese corte es lo que hace que una regla compilada desde un documento sea
indistinguible de una escrita a mano.

## Interfaces funcionales, no clases por regla

`Rule<T>` es una `@FunctionalInterface`, así que `Booking::active` ya es una
regla. Un jerarquía de clases por condición daría el mismo resultado con mucho
más ruido. Las políticas se construyen con factorías (`Policies.require`) por lo
mismo: son valores, no componentes.

Cuando una decisión no encaja en "una condición y un motivo", `Policy` sigue
siendo una interfaz de dos métodos que puedes implementar (ejemplo en
[politicas.md](politicas.md#escribir-una-policy-propia)).

## Inmutabilidad en todo

Records, copias defensivas en cada constructor y colecciones inmutables al salir.
El resultado es que **todo lo que devuelve la librería se puede compartir entre
hilos y cachear** sin coordinación. Es también lo que hace segura la caché de
reglas compiladas.

El coste es una copia por construcción, que ocurre al arrancar o al compilar una
regla, no en cada evaluación.

## Un modelo sellado para las definiciones

```java
public sealed interface RuleDefinition
        permits AtomicRuleDefinition, AndRuleDefinition, OrRuleDefinition, NotRuleDefinition
```

Sellar la jerarquía permite que el compilador recorra el árbol con un `switch`
exhaustivo, sin rama por defecto. Si algún día se añade un tipo de nodo, todos
los sitios que deben tratarlo pasan a ser errores de compilación en vez de fallos
en tiempo de ejecución.

Descartado: un modelo abierto con visitantes. Más ceremonia y la misma
exhaustividad, pero comprobada peor.

## Los errores de configuración salen al compilar

`RuleCompiler.compile` ejecuta todas las factorías. Un tipo desconocido o un
parámetro inválido fallan ahí, con `RuleConfigurationException`. Una vez
compilada, la regla es una función pura que no puede fallar por configuración.

Esto convierte "la configuración está rota" en un fallo detectable al arrancar o
en un test, en vez de un error en mitad de una decisión de negocio. Es también lo
que permite validar toda la configuración almacenada compilándola.

## La librería no lee de ninguna parte

No hay adaptador de MongoDB, ni de JPA, ni de JSON. Lo único que necesita es un
`Map<String, Object>`, que es exactamente lo que devuelven todos ellos.

La alternativa —incluir adaptadores— habría metido dependencias, versiones de
driver y opiniones sobre el esquema. `RuleDefinitionCodec` cubre la parte
genérica y deja las diez líneas específicas de cada almacén en el proyecto que
las necesita.

## La librería no observa nada

Ni logs, ni métricas, ni trazas: cada proyecto ya tiene su stack. Como `Policy`
es una interfaz de dos métodos, decorarla son quince líneas, y la decoración vive
donde vive el resto de la telemetría del proyecto
([patrón](integracion.md#observabilidad)).

Esto mantiene la librería en cero dependencias, que es lo que hace que se pueda
meter en cualquier proyecto sin negociar versiones.

## La configuración no introduce comportamiento

Solo puede combinar y parametrizar los tipos registrados en el `RuleRegistry`.
No hay expresiones, ni scripting, ni reflexión, ni carga de clases.

Un motor de expresiones sería más flexible, pero convertiría la configuración en
código sin revisión ni tests, con una superficie de ataque que va desde la
inyección hasta el consumo de CPU. El registro es la frontera: si un tipo de
regla no está implementado y registrado, no existe.

## Números a través de `BigDecimal`

`RuleParameters` convierte todo número por `BigDecimal` en vez de castear. Así
`18`, `18L`, `18.0` y `"18"` dan el mismo `int` —vengan del driver que vengan— y
un valor que perdería precisión o se saldría de rango se rechaza en vez de
truncarse en silencio.

## Alcance: lo que no es

No es un motor de reglas al estilo Drools. No hay encadenamiento hacia delante,
ni memoria de trabajo, ni resolución de conflictos, ni DSL propio.

Es un modelo pequeño para el 90% de los casos: condiciones booleanas sobre un
contexto, con motivos explicables y una parte configurable. Si necesitas
inferencia o reglas que se disparan entre sí, esta no es la herramienta.
