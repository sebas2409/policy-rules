package io.github.sebas2409.policyrules.rule.definition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Translates between {@link RuleDefinition} trees and plain {@code Map} documents.
 *
 * <p>The library deliberately knows nothing about databases or serialization
 * frameworks, but virtually every one of them can hand over a
 * {@code Map<String, Object>}: a JSON parser, a YAML parser, a document
 * database driver, an HTTP client. This codec is the single adapter between
 * that shape and the typed model, so an application only writes the few lines
 * that load the map:</p>
 *
 * {@snippet lang = "java":
 * Map<String, Object> document = ruleCollection.findById("customer-eligibility");
 * RuleDefinition definition = RuleDefinitionCodec.read(document);
 * Rule<Customer> rule = compiler.compile(definition);
 *}
 *
 * <h2>Document format</h2>
 * <p>A node is either <em>composite</em>, when it carries an
 * {@value #OPERATOR_KEY} entry, or <em>atomic</em>, when it carries a
 * {@value #TYPE_KEY} entry. A node carrying both, or neither, is rejected.</p>
 *
 * <pre>{@code
 * {
 *   "operator": "and",
 *   "rules": [
 *     { "type": "minimum-age", "parameters": { "minimum": 18 } },
 *     { "operator": "not",
 *       "rule": { "type": "blocked" } },
 *     { "operator": "or",
 *       "rules": [
 *         { "type": "country-is", "parameters": { "expected": "ES" } },
 *         { "type": "country-is", "parameters": { "expected": "PT" } }
 *       ] }
 *   ]
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@value #OPERATOR_KEY} accepts {@value #AND_OPERATOR},
 *       {@value #OR_OPERATOR} and {@value #NOT_OPERATOR}, ignoring case.</li>
 *   <li>{@value #AND_OPERATOR} and {@value #OR_OPERATOR} read their children
 *       from {@value #RULES_KEY}, which must hold at least one node.</li>
 *   <li>{@value #NOT_OPERATOR} reads its single child from {@value #RULE_KEY},
 *       or from a {@value #RULES_KEY} list holding exactly one node.</li>
 *   <li>{@value #TYPE_KEY} is the name registered in the
 *       {@link RuleRegistry}; {@value #PARAMETERS_KEY} is optional and defaults
 *       to an empty map.</li>
 * </ul>
 *
 * <p>{@link #write(RuleDefinition)} produces exactly this shape, so a document
 * survives a read-write round trip unchanged.</p>
 *
 * <h2>Untrusted input</h2>
 * <p>Reading is strict: every unexpected shape raises a
 * {@link RuleDefinitionFormatException} whose message points at the offending
 * node (for example {@code $.rules[1].rule}), and nesting deeper than
 * {@link #MAX_DEPTH} is rejected rather than allowed to exhaust the stack.
 * Parameter <em>values</em> are not interpreted here; they are validated by the
 * {@link RuleFactory} that consumes them.</p>
 */
public final class RuleDefinitionCodec {

    /** Key that marks a composite node and names its boolean operator. */
    public static final String OPERATOR_KEY = "operator";

    /** Key holding the children of an {@code and} or {@code or} node. */
    public static final String RULES_KEY = "rules";

    /** Key holding the single child of a {@code not} node. */
    public static final String RULE_KEY = "rule";

    /** Key that marks an atomic node and names its registered rule type. */
    public static final String TYPE_KEY = "type";

    /** Key holding the parameters of an atomic node. */
    public static final String PARAMETERS_KEY = "parameters";

    /** Operator value of a conjunction. */
    public static final String AND_OPERATOR = "and";

    /** Operator value of a disjunction. */
    public static final String OR_OPERATOR = "or";

    /** Operator value of a negation. */
    public static final String NOT_OPERATOR = "not";

    /**
     * Maximum accepted nesting depth of a document, counting the root as one.
     *
     * <p>The limit protects the recursive reader from a document, possibly
     * hand-edited or received over the network, that is nested deeply enough to
     * exhaust the stack.</p>
     */
    public static final int MAX_DEPTH = 50;

    private RuleDefinitionCodec() {
    }

    /**
     * Reads a definition tree from a raw document.
     *
     * @param document map produced by a parser or database driver
     * @return the parsed definition
     * @throws NullPointerException          if {@code document} is null
     * @throws RuleDefinitionFormatException if the document does not follow the
     *                                       documented format
     */
    public static RuleDefinition read(Map<String, Object> document) {
        Objects.requireNonNull(document, "document must not be null");
        return readNode(document, "$", 1);
    }

    /**
     * Writes a definition tree as a raw document.
     *
     * <p>The returned maps are mutable and safe to hand over to a serializer or
     * a database driver, and keys are written in a stable order.</p>
     *
     * @param definition definition to serialize
     * @return the document describing the definition
     * @throws NullPointerException if {@code definition} is null
     */
    public static Map<String, Object> write(RuleDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        var document = new LinkedHashMap<String, Object>();
        switch (definition) {
            case AtomicRuleDefinition atomic -> {
                document.put(TYPE_KEY, atomic.type());
                if (!atomic.parameters().isEmpty()) {
                    document.put(PARAMETERS_KEY, new LinkedHashMap<>(atomic.parameters()));
                }
            }
            case AndRuleDefinition and -> {
                document.put(OPERATOR_KEY, AND_OPERATOR);
                document.put(RULES_KEY, writeAll(and.children()));
            }
            case OrRuleDefinition or -> {
                document.put(OPERATOR_KEY, OR_OPERATOR);
                document.put(RULES_KEY, writeAll(or.children()));
            }
            case NotRuleDefinition not -> {
                document.put(OPERATOR_KEY, NOT_OPERATOR);
                document.put(RULE_KEY, write(not.child()));
            }
        }
        return document;
    }

    private static List<Map<String, Object>> writeAll(List<RuleDefinition> children) {
        var documents = new ArrayList<Map<String, Object>>(children.size());
        for (var child : children) {
            documents.add(write(child));
        }
        return documents;
    }

    private static RuleDefinition readNode(Map<String, Object> node, String path, int depth) {
        if (depth > MAX_DEPTH) {
            throw new RuleDefinitionFormatException(
                    "Rule definition at " + path + " is nested deeper than " + MAX_DEPTH + " levels"
            );
        }

        var operator = optionalText(node.get(OPERATOR_KEY), path, OPERATOR_KEY);
        var type = optionalText(node.get(TYPE_KEY), path, TYPE_KEY);

        if (operator != null && type != null) {
            throw new RuleDefinitionFormatException(
                    "Rule definition at " + path + " declares both '" + OPERATOR_KEY
                            + "' and '" + TYPE_KEY + "'; a node is either composite or atomic"
            );
        }
        if (operator != null) {
            return readComposite(node, path, depth, operator);
        }
        if (type != null) {
            return readAtomic(node, path, type);
        }
        throw new RuleDefinitionFormatException(
                "Rule definition at " + path + " must declare either '" + OPERATOR_KEY
                        + "' or '" + TYPE_KEY + "', but has keys " + node.keySet()
        );
    }

    private static RuleDefinition readComposite(
            Map<String, Object> node,
            String path,
            int depth,
            String operator
    ) {
        return switch (operator.toLowerCase(Locale.ROOT)) {
            case AND_OPERATOR -> new AndRuleDefinition(readChildren(node, path, depth));
            case OR_OPERATOR -> new OrRuleDefinition(readChildren(node, path, depth));
            case NOT_OPERATOR -> new NotRuleDefinition(readNegated(node, path, depth));
            default -> throw new RuleDefinitionFormatException(
                    "Unknown operator '" + operator + "' at " + path + "; expected one of ["
                            + AND_OPERATOR + ", " + OR_OPERATOR + ", " + NOT_OPERATOR + "]"
            );
        };
    }

    private static RuleDefinition readAtomic(Map<String, Object> node, String path, String type) {
        var rawParameters = node.get(PARAMETERS_KEY);
        if (rawParameters == null) {
            return new AtomicRuleDefinition(type);
        }
        if (!(rawParameters instanceof Map<?, ?> map)) {
            throw new RuleDefinitionFormatException(
                    "'" + PARAMETERS_KEY + "' at " + path + " must be a map, but was "
                            + rawParameters.getClass().getSimpleName()
            );
        }
        var parameters = new LinkedHashMap<String, Object>(map.size());
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new RuleDefinitionFormatException(
                        "'" + PARAMETERS_KEY + "' at " + path + " must only use text keys"
                );
            }
            if (entry.getValue() == null) {
                throw new RuleDefinitionFormatException(
                        "Parameter '" + key + "' at " + path + " must not be null"
                );
            }
            parameters.put(key, entry.getValue());
        }
        return new AtomicRuleDefinition(type, parameters);
    }

    private static List<RuleDefinition> readChildren(
            Map<String, Object> node,
            String path,
            int depth
    ) {
        var raw = node.get(RULES_KEY);
        if (!(raw instanceof List<?> elements)) {
            throw new RuleDefinitionFormatException(
                    "'" + RULES_KEY + "' at " + path + " must be a non-empty list of rules, but was "
                            + describe(raw)
            );
        }
        if (elements.isEmpty()) {
            throw new RuleDefinitionFormatException(
                    "'" + RULES_KEY + "' at " + path + " must contain at least one rule"
            );
        }
        var children = new ArrayList<RuleDefinition>(elements.size());
        for (var index = 0; index < elements.size(); index++) {
            children.add(readChild(
                    elements.get(index),
                    path + "." + RULES_KEY + "[" + index + "]",
                    depth
            ));
        }
        return children;
    }

    private static RuleDefinition readNegated(Map<String, Object> node, String path, int depth) {
        var raw = node.get(RULE_KEY);
        if (raw != null) {
            return readChild(raw, path + "." + RULE_KEY, depth);
        }
        var children = readChildren(node, path, depth);
        if (children.size() != 1) {
            throw new RuleDefinitionFormatException(
                    "Operator '" + NOT_OPERATOR + "' at " + path + " must negate exactly one rule,"
                            + " but " + children.size() + " were supplied"
            );
        }
        return children.getFirst();
    }

    private static RuleDefinition readChild(Object raw, String path, int depth) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new RuleDefinitionFormatException(
                    "Rule definition at " + path + " must be a map, but was " + describe(raw)
            );
        }
        var child = new LinkedHashMap<String, Object>(map.size());
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new RuleDefinitionFormatException(
                        "Rule definition at " + path + " must only use text keys"
                );
            }
            child.put(key, entry.getValue());
        }
        return readNode(child, path, depth + 1);
    }

    private static String optionalText(Object raw, String path, String key) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof CharSequence text) {
            var value = text.toString().trim();
            if (value.isEmpty()) {
                throw new RuleDefinitionFormatException(
                        "'" + key + "' at " + path + " must not be blank"
                );
            }
            return value;
        }
        throw new RuleDefinitionFormatException(
                "'" + key + "' at " + path + " must be text, but was " + describe(raw)
        );
    }

    private static String describe(Object raw) {
        return raw == null ? "missing" : raw.getClass().getSimpleName() + ": " + raw;
    }
}
