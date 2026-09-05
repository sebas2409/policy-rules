package io.github.sebas2409.policyrules.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PolicyResult")
class PolicyResultTest {

    @Test
    @DisplayName("derives the allowed state from the violations and reuses the allowed instance")
    void allowedStateIsDerivedAndReused() {
        assertTrue(PolicyResult.allow().allowed());
        assertFalse(PolicyResult.allow().denied());
        assertTrue(PolicyResult.allow().violations().isEmpty());
        assertSame(PolicyResult.allow(), PolicyResult.allow());
    }

    @Test
    @DisplayName("copies violations and metadata so callers cannot mutate a result")
    void resultAndMetadataAreDefensivelyCopied() {
        var metadata = new HashMap<String, Object>();
        var violation = new PolicyViolation("CODE", "Message", metadata);
        var source = new ArrayList<>(List.of(violation));
        var result = new PolicyResult(source);

        metadata.put("changed", true);
        source.clear();

        assertTrue(result.denied());
        assertEquals(1, result.violations().size());
        assertTrue(violation.metadata().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.violations().clear());
    }

    @Test
    @DisplayName("rejects a denial without a reason")
    void denialAndExceptionRequireAtLeastOneViolation() {
        assertThrows(IllegalArgumentException.class, () -> PolicyResult.deny(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PolicyViolationException(List.of()));
    }

    @Test
    @DisplayName("keeps violation order when results are combined")
    void combinesViolationsInOrder() {
        var first = new PolicyViolation("FIRST", "First");
        var second = new PolicyViolation("SECOND", "Second");

        var combined = PolicyResult.deny(first).combine(PolicyResult.deny(second));

        assertEquals(List.of(first, second), combined.violations());
        assertEquals(List.of("FIRST", "SECOND"), combined.codes());
    }

    @Test
    @DisplayName("exposes the reasons for a denial without depending on their order")
    void inspectsViolations() {
        var result = PolicyResult.deny(List.of(
                new PolicyViolation("FIRST", "First"),
                new PolicyViolation("SECOND", "Second")
        ));

        assertTrue(result.hasViolation("SECOND"));
        assertFalse(result.hasViolation("MISSING"));
        assertEquals("FIRST", result.firstViolation().orElseThrow().code());
        assertTrue(PolicyResult.allow().firstViolation().isEmpty());
    }

    @Test
    @DisplayName("fails at the boundary carrying every reason and its codes")
    void requireAllowedThrowsWithEveryReason() {
        var result = PolicyResult.deny(List.of(
                new PolicyViolation("FIRST", "First"),
                new PolicyViolation("SECOND", "Second")
        ));

        var exception = assertThrows(PolicyViolationException.class, result::requireAllowed);

        assertEquals(List.of("FIRST", "SECOND"), exception.codes());
        assertTrue(exception.getMessage().contains("FIRST"));
        assertThrows(UnsupportedOperationException.class,
                () -> exception.violations().clear());
    }

    @Test
    @DisplayName("enriches a violation without mutating the original")
    void violationMetadataCanBeExtended() {
        var violation = new PolicyViolation("CODE", "Message");

        var enriched = violation.with("current", 3);

        assertTrue(violation.metadata().isEmpty());
        assertEquals(3, enriched.metadata().get("current"));
        assertEquals("CODE", enriched.code());
    }

    @Test
    @DisplayName("rejects a violation without a code or a message")
    void violationRequiresCodeAndMessage() {
        assertThrows(IllegalArgumentException.class, () -> new PolicyViolation(" ", "Message"));
        assertThrows(IllegalArgumentException.class, () -> new PolicyViolation("CODE", " "));
        assertThrows(NullPointerException.class, () -> new PolicyViolation(null, "Message"));
    }
}
