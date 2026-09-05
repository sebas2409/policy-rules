package com.policyrules.examples;

import com.policyrules.policy.Policies;
import com.policyrules.policy.Policy;
import com.policyrules.policy.PolicyViolation;
import com.policyrules.policy.PolicyViolationException;
import com.policyrules.rule.Rule;
import com.policyrules.rule.definition.RuleCompiler;
import com.policyrules.rule.definition.RuleDefinitionCodec;
import com.policyrules.rule.definition.RuleRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end example: a booking use case whose eligibility rule is configured
 * outside the code while the rest of its policies stay in it.
 *
 * <p>This is the worked example of the documentation, kept as a test so it can
 * never drift from the library.</p>
 */
@DisplayName("Example: booking confirmation")
class BookingExampleTest {

    /** The context a policy decides on: whatever the use case already has. */
    private record Booking(String customer, int age, int weeklyBookings, String country, boolean active) {
    }

    /** Rule types the application accepts in its configuration. */
    private static RuleRegistry<Booking> bookingRules() {
        return new RuleRegistry<Booking>()
                .register("minimum-age", parameters -> {
                    var minimum = parameters.intValue("minimum");
                    return booking -> booking.age() >= minimum;
                })
                .register("weekly-bookings-below", parameters -> {
                    var maximum = parameters.intValue("maximum");
                    return booking -> booking.weeklyBookings() < maximum;
                })
                .register("country-in", parameters -> {
                    var allowed = Set.copyOf(parameters.list("countries", String.class));
                    return booking -> allowed.contains(booking.country());
                });
    }

    /**
     * Stands in for whatever the application uses as a rule store: a document
     * database, a configuration service, a JSON file. The library only sees the
     * map it returns.
     */
    private static Map<String, Object> storedEligibilityRule(int minimumAge) {
        return Map.of(
                "operator", "and",
                "rules", List.of(
                        Map.of("type", "minimum-age",
                                "parameters", Map.of("minimum", minimumAge)),
                        Map.of("type", "weekly-bookings-below",
                                "parameters", Map.of("maximum", 3)),
                        Map.of("type", "country-in",
                                "parameters", Map.of("countries", List.of("ES", "PT")))
                )
        );
    }

    private static Policy<Booking> eligibilityPolicy(
            RuleCompiler<Booking> compiler,
            int minimumAge
    ) {
        Rule<Booking> eligible = compiler.compile(
                RuleDefinitionCodec.read(storedEligibilityRule(minimumAge))
        );
        return Policies.require(
                "booking-eligibility",
                eligible,
                booking -> new PolicyViolation(
                        "NOT_ELIGIBLE",
                        "The booking does not meet the eligibility rule",
                        Map.of("customer", booking.customer())
                )
        );
    }

    /** A condition that belongs in the code: changing it is a deployment. */
    private static final Policy<Booking> MUST_BE_ACTIVE = Policies.require(
            "booking-must-be-active",
            Booking::active,
            "BOOKING_INACTIVE",
            "The booking must be active"
    );

    @Test
    @DisplayName("allows a booking that satisfies the code and the configuration")
    void allowsAnEligibleBooking() {
        var compiler = new RuleCompiler<>(bookingRules());
        var ana = new Booking("Ana", 20, 1, "ES", true);

        var canBeConfirmed = Policies.allOf("booking-can-be-confirmed", List.of(
                MUST_BE_ACTIVE,
                eligibilityPolicy(compiler, 18)
        ));

        assertTrue(canBeConfirmed.evaluate(ana).allowed());
    }

    @Test
    @DisplayName("changing the stored rule changes the decision without a deployment")
    void configurationDrivesTheDecision() {
        var compiler = new RuleCompiler<>(bookingRules());
        var ana = new Booking("Ana", 20, 1, "ES", true);

        assertTrue(eligibilityPolicy(compiler, 18).evaluate(ana).allowed());
        assertTrue(eligibilityPolicy(compiler, 21).evaluate(ana).denied());
    }

    @Test
    @DisplayName("reports every reason at once, in declaration order")
    void reportsEveryReason() {
        var compiler = new RuleCompiler<>(bookingRules());
        var leo = new Booking("Leo", 16, 3, "US", false);

        var result = Policies.allOf("booking-can-be-confirmed", List.of(
                MUST_BE_ACTIVE,
                eligibilityPolicy(compiler, 18)
        )).evaluate(leo);

        assertEquals(List.of("BOOKING_INACTIVE", "NOT_ELIGIBLE"), result.codes());
        assertEquals("Leo", result.violations().getLast().metadata().get("customer"));
    }

    @Test
    @DisplayName("stops at the first reason when later checks are not worth it")
    void stopsAtTheFirstReason() {
        var compiler = new RuleCompiler<>(bookingRules());
        var leo = new Booking("Leo", 16, 3, "US", false);

        var result = Policies.firstFailureOf("booking-can-be-confirmed", List.of(
                MUST_BE_ACTIVE,
                eligibilityPolicy(compiler, 18)
        )).evaluate(leo);

        assertEquals(List.of("BOOKING_INACTIVE"), result.codes());
    }

    @Test
    @DisplayName("fails at the boundary when the operation cannot continue")
    void failsAtTheBoundary() {
        var leo = new Booking("Leo", 16, 3, "US", false);

        var exception = assertThrows(
                PolicyViolationException.class,
                () -> MUST_BE_ACTIVE.enforce(leo)
        );

        assertEquals(List.of("BOOKING_INACTIVE"), exception.codes());
    }
}
