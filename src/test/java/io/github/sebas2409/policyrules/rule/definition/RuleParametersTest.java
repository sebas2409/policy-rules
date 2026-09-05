package io.github.sebas2409.policyrules.rule.definition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuleParameters")
class RuleParametersTest {

    private enum Channel {
        WEB,
        MOBILE
    }

    @Test
    @DisplayName("reads the same number whichever type the source produced")
    void readsNumbersFromEveryUsualRepresentation() {
        var parameters = RuleParameters.of(Map.of(
                "asInteger", 18,
                "asLong", 18L,
                "asDouble", 18.0,
                "asDecimal", new BigDecimal("18"),
                "asText", "18"
        ));

        assertEquals(18, parameters.intValue("asInteger"));
        assertEquals(18, parameters.intValue("asLong"));
        assertEquals(18, parameters.intValue("asDouble"));
        assertEquals(18, parameters.intValue("asDecimal"));
        assertEquals(18, parameters.intValue("asText"));
        assertEquals(18L, parameters.longValue("asInteger"));
        assertEquals(18.0, parameters.doubleValue("asDouble"));
        assertEquals(new BigDecimal("18"), parameters.decimal("asDecimal"));
    }

    @Test
    @DisplayName("keeps decimal precision instead of going through double")
    void keepsDecimalPrecision() {
        var parameters = RuleParameters.of(Map.of("amount", "10.10"));

        assertEquals(new BigDecimal("10.10"), parameters.decimal("amount"));
    }

    @Test
    @DisplayName("rejects a number that would lose information")
    void rejectsLossyNumbers() {
        var parameters = RuleParameters.of(Map.of(
                "fractional", 18.5,
                "tooLarge", Long.MAX_VALUE
        ));

        assertThrows(RuleParameterException.class, () -> parameters.intValue("fractional"));
        assertThrows(RuleParameterException.class, () -> parameters.intValue("tooLarge"));
        assertEquals(Long.MAX_VALUE, parameters.longValue("tooLarge"));
    }

    @Test
    @DisplayName("reads text, booleans and enums, including their text form")
    void readsTextBooleansAndEnums() {
        var parameters = RuleParameters.of(Map.of(
                "country", "ES",
                "enabled", true,
                "enabledAsText", "TRUE",
                "channel", "mobile"
        ));

        assertEquals("ES", parameters.string("country"));
        assertTrue(parameters.booleanValue("enabled"));
        assertTrue(parameters.booleanValue("enabledAsText"));
        assertEquals(Channel.MOBILE, parameters.value("channel", Channel.class));
    }

    @Test
    @DisplayName("names the offending parameter when a value is missing or unusable")
    void failsWithTheParameterName() {
        var parameters = RuleParameters.of(Map.of("minimum", "abc"));

        var missing = assertThrows(RuleParameterException.class,
                () -> parameters.intValue("maximum"));
        var unusable = assertThrows(RuleParameterException.class,
                () -> parameters.intValue("minimum"));
        var unknownConstant = assertThrows(RuleParameterException.class,
                () -> parameters.value("minimum", Channel.class));

        assertEquals("maximum", missing.parameter());
        assertTrue(missing.getMessage().contains("maximum"));
        assertEquals("minimum", unusable.parameter());
        assertTrue(unusable.getMessage().contains("abc"));
        assertTrue(unknownConstant.getMessage().contains("MOBILE"));
    }

    @Test
    @DisplayName("falls back to a default only when the parameter is absent")
    void appliesDefaultsOnlyWhenAbsent() {
        var parameters = RuleParameters.of(Map.of("minimum", 5, "broken", "abc"));

        assertEquals(5, parameters.intValue("minimum", 1));
        assertEquals(1, parameters.intValue("maximum", 1));
        assertEquals("EUR", parameters.string("currency", "EUR"));
        assertTrue(parameters.booleanValue("enabled", true));
        assertThrows(RuleParameterException.class, () -> parameters.intValue("broken", 1));
    }

    @Test
    @DisplayName("reads lists, converting every element")
    void readsLists() {
        var parameters = RuleParameters.of(Map.of(
                "countries", List.of("ES", "PT"),
                "limits", List.of(1, "2", 3.0)
        ));

        assertEquals(List.of("ES", "PT"), parameters.list("countries", String.class));
        assertEquals(List.of(1, 2, 3), parameters.list("limits", Integer.class));
        assertEquals(List.of(), parameters.list("missing", String.class, List.of()));
        assertThrows(RuleParameterException.class, () -> parameters.list("missing", String.class));
        assertThrows(RuleParameterException.class,
                () -> parameters.list("countries", Integer.class));
    }

    @Test
    @DisplayName("reads nested groups of parameters")
    void readsNestedGroups() {
        var parameters = RuleParameters.of(Map.of(
                "window", Map.of("days", 7, "maximum", 3)
        ));

        var window = parameters.group("window");

        assertEquals(7, window.intValue("days"));
        assertEquals(3, window.intValue("maximum"));
        assertTrue(parameters.group("missing", RuleParameters.empty()).isEmpty());
        assertThrows(RuleParameterException.class, () -> parameters.group("missing"));
    }

    @Test
    @DisplayName("is an immutable snapshot of the source map")
    void isAnImmutableSnapshot() {
        var source = new LinkedHashMap<String, Object>();
        source.put("minimum", 18);
        var parameters = RuleParameters.of(source);

        source.put("minimum", 21);

        assertEquals(18, parameters.intValue("minimum"));
        assertEquals(java.util.Set.of("minimum"), parameters.names());
        assertTrue(parameters.contains("minimum"));
        assertFalse(parameters.contains("maximum"));
        assertTrue(parameters.find("maximum").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> parameters.asMap().clear());
        assertEquals(RuleParameters.of(Map.of("minimum", 18)), parameters);
    }
}
