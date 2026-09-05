package io.github.sebas2409.policyrules.rule.definition;

/**
 * Signals that a raw map does not describe a valid rule definition.
 *
 * <p>Thrown by {@link RuleDefinitionCodec#read(java.util.Map)} when the
 * document read from the external store does not follow the documented format:
 * an unknown operator, a missing {@code type}, a node that is neither atomic nor
 * composite, or a nesting depth beyond
 * {@link RuleDefinitionCodec#MAX_DEPTH}.</p>
 */
@SuppressWarnings("serial")
public final class RuleDefinitionFormatException extends RuleConfigurationException {

    /**
     * Creates the exception.
     *
     * @param message description of the format problem, including the path of
     *                the offending node when it is known
     */
    public RuleDefinitionFormatException(String message) {
        super(message);
    }

    /**
     * Creates the exception with an underlying cause.
     *
     * @param message description of the format problem
     * @param cause   failure raised while reading the node
     */
    public RuleDefinitionFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
