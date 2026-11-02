package io.github.almeida96.runharbor;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

final class Json {
  static final ObjectMapper MAPPER =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(40)
                          .maxStringLength(262144)
                          .maxNumberLength(128)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(
              DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
              DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  private Json() {}

  static ObjectNode obj(Object... pairs) {
    var node = MAPPER.createObjectNode();
    for (int i = 0; i < pairs.length; i += 2)
      node.set((String) pairs[i], MAPPER.valueToTree(pairs[i + 1]));
    return node;
  }

  static ArrayNode array() {
    return MAPPER.createArrayNode();
  }

  static JsonNode parse(byte[] value) throws IOException {
    JsonNode node = MAPPER.readTree(value);
    require(node != null && !node.isMissingNode(), "Empty JSON document");
    return node;
  }

  static JsonNode parse(String value) {
    try {
      return parse(value.getBytes(UTF_8));
    } catch (IOException e) {
      throw new Invalid("Invalid JSON document");
    }
  }

  static void fields(JsonNode node, Set<String> required, Set<String> optional) {
    require(node != null && node.isObject(), "Expected an object");
    required.forEach(key -> require(node.has(key), "Required field is missing"));
    node.fieldNames()
        .forEachRemaining(
            key -> require(required.contains(key) || optional.contains(key), "Unknown field"));
  }

  static String text(JsonNode node, int min, int max) {
    require(
        node != null
            && node.isTextual()
            && node.textValue().length() >= min
            && node.textValue().length() <= max,
        "Invalid text field");
    return node.textValue();
  }

  static int integer(JsonNode node, int min, int max) {
    require(
        node != null
            && node.isIntegralNumber()
            && node.canConvertToInt()
            && node.intValue() >= min
            && node.intValue() <= max,
        "Invalid integer field");
    return node.intValue();
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new Invalid(message);
  }

  static final class Invalid extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    Invalid(String message) {
      super(message);
    }
  }

  static final class Conflict extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    Conflict(String message) {
      super(message);
    }
  }
}
