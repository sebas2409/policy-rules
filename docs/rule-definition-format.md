# Rule document format

Specification of the shape `RuleDefinitionCodec.read(Map)` accepts and
`RuleDefinitionCodec.write(RuleDefinition)` produces.

The format is described here as JSON because that is the most readable, but the
codec works on `Map<String, Object>`: it applies just as well to MongoDB BSON,
YAML, a JSONB column or a hand-built map.

## Nodes

A document is a tree of nodes. Every node is either **composite** or **atomic**:

- It carries `operator` → composite.
- It carries `type` → atomic.
- It carries both, or neither → rejected.

### Atomic node

```json
{
  "type": "minimum-age",
  "parameters": { "minimum": 18 }
}
```

| Key | Required | Content |
|-----|----------|---------|
| `type` | yes | name registered in the `RuleRegistry`; non-blank text |
| `parameters` | no | map with text keys and non-null values; empty by default |

Parameter **values** are not interpreted here: they are validated by the
`RuleFactory` that consumes them, through `RuleParameters`. They may be numbers,
text, booleans, lists or nested maps.

```json
{ "type": "always-open" }
```

A type without parameters is written without the `parameters` key.

### Composite node

```json
{ "operator": "and", "rules": [ ... ] }
{ "operator": "or",  "rules": [ ... ] }
{ "operator": "not", "rule":  { ... } }
```

| Operator | Children | Semantics |
|----------|----------|-----------|
| `and` | `rules`, at least one | all must hold; evaluated in order, stops at the first that fails |
| `or` | `rules`, at least one | one is enough; evaluated in order, stops at the first that holds |
| `not` | `rule`, exactly one | inverts the result |

The `operator` value is case-insensitive: `and`, `AND` and `And` are equivalent.

`not` also accepts the `"rules": [ ... ]` form with exactly one element, which is
what a generic configuration editor sometimes produces. `write` always emits the
canonical form with `rule`.

Because children are evaluated in order and short-circuit, **put the cheapest or
most discriminating condition first**.

## Complete example

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

Equivalent to:

```
minimum-age(18) AND NOT blocked() AND (country-is(ES) OR country-is(PT))
```

## Round trip

`read` and `write` are inverses: writing a tree that was read produces a document
that reads back as the same tree. The key order `write` emits is stable
(`operator`, `rules` / `rule`; `type`, `parameters`), which keeps configuration
diffs readable in version control.

```java
RuleDefinition definition = RuleDefinitionCodec.read(document);
Map<String, Object> rewritten = RuleDefinitionCodec.write(definition);
// RuleDefinitionCodec.read(rewritten).equals(definition)
```

The maps `write` returns are mutable, ready to hand to a serializer or a database
driver.

## Validation and errors

`read` is deliberately strict: any unexpected shape throws
`RuleDefinitionFormatException` with the path of the offending node.

| Document | Error |
|----------|-------|
| `{}` | the node declares neither `operator` nor `type` |
| `{"type": "a", "operator": "and"}` | it declares both |
| `{"operator": "xor", "rules": [...]}` | unknown operator |
| `{"operator": "and", "rules": []}` | a composite needs at least one child |
| `{"operator": "and", "rules": "x"}` | `rules` must be a list |
| `{"type": " "}` | `type` must not be blank |
| `{"type": "a", "parameters": "x"}` | `parameters` must be a map |
| `{"type": "a", "parameters": {"k": null}}` | a parameter must not be null |
| nesting > `MAX_DEPTH` (50) | too deep |

The path uses dotted notation, starting at the root:

```
Rule definition at $.rules[1].rule must declare either 'operator' or 'type', but has keys [parameters]
```

What `read` does **not** validate is whether the types exist or whether the
parameters are usable; that is checked by `RuleCompiler.compile`, which throws
`UnknownRuleTypeException` or `RuleParameterException`. Both branches share the
`RuleConfigurationException` supertype.

## Constants

Keys and operators are published as constants, so you need not repeat literals
when building queries or validators:

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

## Storing the document

The library imposes nothing about storage. A stored document usually wants some
metadata of its own alongside the tree:

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

Store the tree under a key of its own, `rule` here, and hand the codec only that
part:

```java
@SuppressWarnings("unchecked")
var tree = (Map<String, Object>) stored.get("rule");

RuleDefinition definition = RuleDefinitionCodec.read(tree);
```

Keeping a version and an author is what makes a rule change auditable, which is
exactly what you lose when a condition leaves the code's version control.
