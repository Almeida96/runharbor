package io.github.almeida96.runharbor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

interface Provider extends AutoCloseable {
  record Reply(ObjectNode message, long tokens) {}

  Reply next(ArrayNode messages) throws IOException, InterruptedException;

  String key();

  JsonNode description();

  @Override
  default void close() {}
}
