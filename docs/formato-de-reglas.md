# Formato del documento de reglas

Especificación de la forma que `RuleDefinitionCodec.read(Map)` acepta y
`RuleDefinitionCodec.write(RuleDefinition)` produce.

El formato se describe aquí como JSON porque es lo más legible, pero el codec
trabaja sobre `Map<String, Object>`: sirve igual para BSON de MongoDB, YAML, una
columna JSONB o un mapa construido a mano.

## Nodos

Un documento es un árbol de nodos. Cada nodo es **compuesto** o **atómico**:

- Lleva `operator` → es compuesto.
- Lleva `type` → es atómico.
- Lleva los dos, o ninguno → se rechaza.

### Nodo atómico

```json
{
  "type": "minimum-age",
  "parameters": { "minimum": 18 }
}
```

| Clave | Obligatoria | Contenido |
|-------|-------------|-----------|
| `type` | sí | nombre registrado en el `RuleRegistry`; texto no vacío |
| `parameters` | no | mapa de claves de texto con valores no nulos; por defecto, vacío |

Los **valores** de los parámetros no se interpretan aquí: los valida la
`RuleFactory` que los consume, a través de `RuleParameters`. Pueden ser números,
texto, booleanos, listas o mapas anidados.

```json
{ "type": "always-open" }
```

Un tipo sin parámetros se escribe sin la clave `parameters`.

### Nodo compuesto

```json
{ "operator": "and", "rules": [ ... ] }
{ "operator": "or",  "rules": [ ... ] }
{ "operator": "not", "rule":  { ... } }
```

| Operador | Hijos | Semántica |
|----------|-------|-----------|
| `and` | `rules`, al menos uno | todos deben cumplirse; se evalúa en orden y corta en el primero que falla |
| `or` | `rules`, al menos uno | basta con uno; se evalúa en orden y corta en el primero que se cumple |
| `not` | `rule`, exactamente uno | invierte el resultado |

El valor de `operator` no distingue mayúsculas: `and`, `AND` y `And` son
equivalentes.

`not` también acepta la forma `"rules": [ ... ]` con exactamente un elemento, que
es lo que a veces produce un editor genérico de configuración. `write` siempre
emite la forma canónica con `rule`.

Como los hijos se evalúan en orden y con cortocircuito, **coloca primero la
condición más barata o más discriminante**.

## Ejemplo completo

```json
{
  "operator": "and",
  "rules": [
    {
      "type": "minimum-age",
      "parameters": { "minimum": 18 }
    },
    {
      "operator": "not",
      "rule": { "type": "blocked" }
    },
    {
      "operator": "or",
      "rules": [
        { "type": "country-is", "parameters": { "expected": "ES" } },
        { "type": "country-is", "parameters": { "expected": "PT" } }
      ]
    }
  ]
}
```

Equivale a:

```
minimum-age(18) AND NOT blocked() AND (country-is(ES) OR country-is(PT))
```

## Ida y vuelta

`read` y `write` son inversas: escribir un árbol leído produce un documento que
vuelve a leerse como el mismo árbol. El orden de las claves que emite `write` es
estable (`operator`, `rules` / `rule`; `type`, `parameters`), lo que hace que los
diffs de configuración en control de versiones sean legibles.

```java
RuleDefinition definition = RuleDefinitionCodec.read(document);
Map<String, Object> rewritten = RuleDefinitionCodec.write(definition);
// RuleDefinitionCodec.read(rewritten).equals(definition)
```

Los mapas que devuelve `write` son mutables, listos para pasárselos a un
serializador o a un driver de base de datos.

## Validación y errores

`read` es estricto a propósito: cualquier forma inesperada lanza
`RuleDefinitionFormatException` con la ruta del nodo culpable.

| Documento | Error |
|-----------|-------|
| `{}` | ningún nodo declara `operator` ni `type` |
| `{"type": "a", "operator": "and"}` | declara los dos |
| `{"operator": "xor", "rules": [...]}` | operador desconocido |
| `{"operator": "and", "rules": []}` | un compuesto necesita al menos un hijo |
| `{"operator": "and", "rules": "x"}` | `rules` debe ser una lista |
| `{"type": " "}` | `type` no puede estar en blanco |
| `{"type": "a", "parameters": "x"}` | `parameters` debe ser un mapa |
| `{"type": "a", "parameters": {"k": null}}` | un parámetro no puede ser nulo |
| anidamiento > `MAX_DEPTH` (50) | demasiado profundo |

La ruta usa notación de camino, empezando por la raíz:

```
Rule definition at $.rules[1].rule must declare either 'operator' or 'type', but has keys [parameters]
```

Lo que `read` **no** valida es si los tipos existen o si los parámetros sirven;
eso lo comprueba `RuleCompiler.compile`, que lanza `UnknownRuleTypeException` o
`RuleParameterException`. Ambas ramas comparten la superclase
`RuleConfigurationException`.

## Constantes

Las claves y operadores están publicados como constantes, para no repetir
literales al construir consultas o validadores:

```java
RuleDefinitionCodec.OPERATOR_KEY     // "operator"
RuleDefinitionCodec.RULES_KEY        // "rules"
RuleDefinitionCodec.RULE_KEY         // "rule"
RuleDefinitionCodec.TYPE_KEY         // "type"
RuleDefinitionCodec.PARAMETERS_KEY   // "parameters"
RuleDefinitionCodec.AND_OPERATOR     // "and"
RuleDefinitionCodec.OR_OPERATOR      // "or"
RuleDefinitionCodec.NOT_OPERATOR     // "not"
RuleDefinitionCodec.MAX_DEPTH        // 50
```

## Guardar el documento

La librería no impone nada sobre el almacenamiento. Un documento almacenado suele
querer, además del árbol, algunos metadatos propios:

```json
{
  "_id": "booking-eligibility",
  "version": 7,
  "updatedAt": "2026-09-04T10:15:00Z",
  "updatedBy": "ops@example.com",
  "rule": {
    "operator": "and",
    "rules": [ ... ]
  }
}
```

Guarda el árbol bajo una clave propia, como aquí `rule`, y pásale al codec solo
esa parte:

```java
@SuppressWarnings("unchecked")
var tree = (Map<String, Object>) stored.get("rule");

RuleDefinition definition = RuleDefinitionCodec.read(tree);
```

Tener versión y autoría es lo que hace auditable un cambio de regla, que es justo
lo que se pierde al sacar una condición del control de versiones del código.
