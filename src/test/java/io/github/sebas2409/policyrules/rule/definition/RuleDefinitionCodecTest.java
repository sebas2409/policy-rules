package io.github.sebas2409.policyrules.rule.definition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuleDefinitionCodec")
class RuleDefinitionCodecTest {

    private static Map<String, Object> document() {
        return Map.of(
                "operator", "and",
                "rules", List.of(
                        Map.of("type", "minimum-age", "parameters", Map.of("minimum", 18)),
                        Map.of("operator", "not", "rule", Map.of("type", "blocked")),
                        Map.of("operator", "or", "rules", List.of(
                                Map.of("type", "country-is", "parameters", Map.of("expected", "ES")),
                                Map.of("type", "country-is", "parameters", Map.of("expected", "PT"))
                        ))
                )
        );
    }

    @Test
    @DisplayName("reads the documented shape into a typed definition")
    void readsTheDocumentedShape() {
        var definition = RuleDefinitionCodec.read(document());

        assertEquals(
                java.util.Set.of("minimum-age", "blocked", "country-is"),
                RuleDefinitions.typesOf(definition)
        );
        assertEquals(7, RuleDefinitions.sizeOf(definition));
        assertTrue(definition instanceof AndRuleDefinition);
    }

    @Test
    @DisplayName("survives a write and read round trip unchanged")
    void roundTripsWithoutLosingAnything() {
        var definition = RuleDefinitionCodec.read(document());

        var rewritten = RuleDefinitionCodec.read(RuleDefinitionCodec.write(definition));

        assertEquals(definition, rewritten);
    }

    @Test
    @DisplayName("writes an atomic node without an empty parameters entry")
    void writesCompactAtomicNodes() {
        var written = RuleDefinitionCodec.write(RuleDefinitions.atomic("blocked"));

        assertEquals(Map.of("type", "blocked"), written);
    }

    @Test
    @DisplayName("accepts a negation expressed as a single-element list")
    void acceptsBothShapesOfNegation() {
        var withRule = RuleDefinitionCodec.read(
                Map.of("operator", "NOT", "rule", Map.of("type", "blocked")));
        var withRules = RuleDefinitionCodec.read(
                Map.of("operator", "not", "rules", List.of(Map.of("type", "blocked"))));

        assertEquals(withRule, withRules);
    }

    @Test
    @DisplayName("points at the offending node when the document is malformed")
    void reportsThePathOfTheOffendingNode() {
        var document = Map.<String, Object>of(
                "operator", "and",
                "rules", List.of(
                        Map.of("type", "blocked"),
                        Map.of("parameters", Map.of("minimum", 18))
                )
        );

        var exception = assertThrows(
                RuleDefinitionFormatException.class,
                () -> RuleDefinitionCodec.read(document)
        );

        assertTrue(exception.getMessage().contains("$.rules[1]"), exception.getMessage());
    }

    @Test
    @DisplayName("rejects every shape that is not a valid node")
    void rejectsMalformedDocuments() {
        assertThrows(RuleDefinitionFormatException.class,
                () -> RuleDefinitionCodec.read(Map.of("operator", "xor", "rules", List.of())));
        assertThrows(RuleDefinitionFormatException.class,
                () -> RuleDefinitionCodec.read(Map.of("operator", "and", "rules", List.of())));
        assertThrows(RuleDefinitionFormatException.class,
                () -> RuleDefinitionCodec.read(Map.of("operator", "and", "rules", "not-a-list")));
        assertThrows(RuleDefinitionFormatException.class,
                () -> RuleDefinitionCodec.read(Map.of("type", "blocked", "operator", "and")));
        assertThrows(RuleDefinitionFormatException.class,
                () -> RuleDefinitionCodec.read(Map.of("type", " ")));
        assertThrows(RuleDefinitionFormatException.class,
                () -> RuleDefinitionCodec.read(Map.of("type", "blocked", "parameters", "nope")));
        assertThrows(RuleDefinitionFormatException.class,
                () -> RuleDefinitionCodec.read(Map.of()));
    }

    @Test
    @DisplayName("rejects a document nested beyond the accepted depth")
    void rejectsExcessiveNesting() {
        Map<String, Object> node = new LinkedHashMap<>(Map.of("type", "blocked"));
        for (var depth = 0; depth < RuleDefinitionCodec.MAX_DEPTH + 1; depth++) {
            var parent = new LinkedHashMap<String, Object>();
            parent.put("operator", "not");
            parent.put("rule", node);
            node = parent;
        }
        var deepest = node;

        assertThrows(RuleDefinitionFormatException.class,
                () -> RuleDefinitionCodec.read(deepest));
    }

    @Test
    @DisplayName("produces a document a serializer can consume")
    void producesMutableDocuments() {
        var written = RuleDefinitionCodec.write(RuleDefinitions.and(
                RuleDefinitions.atomic("minimum-age", Map.of("minimum", 18)),
                RuleDefinitions.atomic("blocked")
        ));

        assertEquals(List.of("operator", "rules"), new ArrayList<>(written.keySet()));
        assertEquals("and", written.get("operator"));
        assertTrue(written.get("rules") instanceof List<?> rules && rules.size() == 2);
    }
}
