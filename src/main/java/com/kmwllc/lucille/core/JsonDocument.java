package com.kmwllc.lucille.core;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * A record from a source system to be passed through a Pipeline, enriched,
 * and sent to a destination system.
 *
 */
public class JsonDocument implements Document {

  protected static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> TYPE = new TypeReference<Map<String, Object>>(){};
  private static final Logger log = LoggerFactory.getLogger(JsonDocument.class);

  @JsonValue
  protected final ObjectNode data;

  public JsonDocument(Document document) throws DocumentException {
    ObjectNode node = document.getData();
    verifyJsonNode(node);
    this.data = node.deepCopy();
  }

  public JsonDocument(ObjectNode data) throws DocumentException {
    verifyJsonNode(data);
    this.data = data;
  }

  public JsonDocument(String id) {
    if (id==null) {
      throw new NullPointerException("ID cannot be null");
    }
    this.data = MAPPER.createObjectNode();
    this.data.put(ID_FIELD, id);
  }

  public JsonDocument(String id, String runId) {
    this(id);
    this.data.put(RUNID_FIELD, runId);
  }

  public static JsonDocument fromJsonString(String json) throws DocumentException, JsonProcessingException {
    return new JsonDocument((ObjectNode)MAPPER.readTree(json));
  }

  public static JsonDocument fromJsonString(String json, UnaryOperator<String> idUpdater) throws DocumentException, JsonProcessingException {
    JsonDocument doc = fromJsonString(json);
    doc.data.put(ID_FIELD, idUpdater.apply(doc.getId()));
    return doc;
  }

  private static void verifyJsonNode(JsonNode node) throws DocumentException {
    if (! (node instanceof ObjectNode)) {
      throw new DocumentException("node must be an object node");
    }

    ObjectNode oNode = (ObjectNode) node;
    if (!oNode.hasNonNull(ID_FIELD)) {
      throw new DocumentException("id is missing");
    }

    JsonNode id = oNode.get(ID_FIELD);
    if (!id.isTextual() || id.asText().isEmpty()) {
      throw new DocumentException("id is present but null or empty or not a string");
    }
  }

  @Override
  public void removeField(String name) {
    validateNotReservedField(name);
    data.remove(name);
  }

  @Override
  public void removeFromArray(String name, int index) {
    validateNotReservedField(name);
    data.withArray(name).remove(index);
  }

  @Override
  public void update(String name, UpdateMode mode, String... values) {
    update(name, mode, (v)->{setField(name,(String)v);}, (v)->{setOrAdd(name,(String)v);}, values);
  }

  @Override
  public void update(String name, UpdateMode mode, Long... values) {
    update(name, mode, (v)->{setField(name,(Long)v);}, (v)->{setOrAdd(name,(Long)v);}, values);
  }

  @Override
  public void update(String name, UpdateMode mode, Integer... values) {
    update(name, mode, (v)->{setField(name,(Integer)v);}, (v)->{setOrAdd(name,(Integer)v);}, values);
  }

  @Override
  public void update(String name, UpdateMode mode, Boolean... values) {
    update(name, mode, (v)->{setField(name,(Boolean)v);}, (v)->{setOrAdd(name,(Boolean)v);}, values);
  }

  @Override
  public void update(String name, UpdateMode mode, Double... values) {
    update(name, mode, (v)->{setField(name,(Double)v);}, (v)->{setOrAdd(name,(Double)v);}, values);
  }

  @Override
  public void update(String name, UpdateMode mode, Instant... values) {
    update(name, mode, (v)->{setField(name,(Instant)v);}, (v)->{setOrAdd(name,(Instant)v);}, values);
  }

  /**
   * Private helper method used by different public versions of the overloaded update method.
   *
   * Expects two Consumers that invoke setField and addToField respectively on the named field, passing in
   * a provided value.
   *
   * The Consumer / Lambda Expression approach is used here to avoid code duplication between the various
   * update methods. It is not possible to make update() a generic method because ultimately it would need to call
   * one of the specific setField or addToField methods which in turn call data.put(String, String),
   * data.put(String, Long), data.put(String Boolean)
   */
  private void update(String name, UpdateMode mode, Consumer setter, Consumer adder, Object... values) {

    validateNotReservedField(name);

    if (values.length == 0)
      return;

    if (has(name) && mode.equals(UpdateMode.SKIP)) {
      return;
    }

    int i = 0;
    if (mode.equals(UpdateMode.OVERWRITE)) {
      setter.accept(values[0]);
      i = 1;
    }
    for (; i < values.length; i++) {
      adder.accept(values[i]);
    }
  }

  @Override
  public void initializeRunId(String value) {
    if (data.has(RUNID_FIELD)) {
      throw new IllegalStateException();
    }
    data.put(RUNID_FIELD, value);
  }

  @Override
  public void clearRunId() {
    if (data.has(RUNID_FIELD)) {
      data.remove(RUNID_FIELD);
    }
  }

  @Override
  public void setField(String name, String value) {
    validateNotReservedField(name);
    data.put(name, value);
  }

  @Override
  public void setField(String name, Long value) {
    validateNotReservedField(name);
    data.put(name, value);
  }

  @Override
  public void setField(String name, Integer value) {
    validateNotReservedField(name);
    data.put(name, value);
  }

  @Override
  public void setField(String name, Boolean value) {
    validateNotReservedField(name);
    data.put(name, value);
  }

  @Override
  public void setField(String name, Double value) {
    validateNotReservedField(name);
    data.put(name, value);
  }

  @Override
  public void setField(String name, JsonNode value) {
    validateNotReservedField(name);
    data.set(name, value);
  }

  @Override
  public void setField(String name, Instant value) {
    validateNotReservedField(name);
    String instantStr = DateTimeFormatter.ISO_INSTANT.format(value);
    data.put(name, instantStr);
  }

  @Override
  public void renameField(String oldName, String newName, UpdateMode mode) {
    validateNotReservedField(oldName);
    validateNotReservedField(newName);
    JsonNode oldValues = data.get(oldName);
    data.remove(oldName);

    if (has(newName)) {
      if (mode.equals(UpdateMode.SKIP)) {
        return;
      } else if (mode.equals(UpdateMode.APPEND)) {
        convertToList(newName);

        if (oldValues.getNodeType() == JsonNodeType.ARRAY) {
          data.withArray(newName).addAll((ArrayNode) oldValues);
        } else {
          data.withArray(newName).add(oldValues);
        }
        return;
      }
    }

    data.set(newName,oldValues);
  }

  @Override
  public ObjectNode getData() {
    return data;
  }

  @Override
  public String getString(String name) {
    if (!data.has(name)) {
      return null;
    }

    JsonNode node = getSingleNode(name);

    return node.isNull() ? null : node.asText();
  }

  @Override
  public List<String> getStringList(String name) {
    if (!data.has(name)) {
      return null;
    }

    if (!isMultiValued(name)) {
      return Collections.singletonList(getString(name));
    }

    ArrayNode array = data.withArray(name);
    List<String> result = new ArrayList<>();
    for (JsonNode node : array) {
      result.add(node.isNull() ? null : node.asText());
    }
    return result;
  }

  @Override
  public Integer getInt(String name) {
    if (!data.has(name)) {
      return null;
    }

    JsonNode node = getSingleNode(name);

    return node.isNull() ? null : node.asInt();
  }

  @Override
  public List<Integer> getIntList(String name) {
    if (!data.has(name)) {
      return null;
    }

    if (!isMultiValued(name)) {
      return Collections.singletonList(getInt(name));
    }

    ArrayNode array = data.withArray(name);
    List<Integer> result = new ArrayList<>();
    for (JsonNode node : array) {
      result.add(node.isNull() ? null : node.asInt());
    }
    return result;
  }

  @Override
  public Double getDouble(String name) {
    if (!data.has(name)) {
      return null;
    }

    JsonNode node = getSingleNode(name);

    return node.isNull() ? null : node.asDouble();
  }

  @Override
  public List<Double> getDoubleList(String name) {
    if (!data.has(name)) {
      return null;
    }

    if (!isMultiValued(name)) {
      return Collections.singletonList(getDouble(name));
    }

    ArrayNode array = data.withArray(name);
    List<Double> result = new ArrayList<>();
    for (JsonNode node : array) {
      result.add(node.isNull() ? null : node.asDouble());
    }
    return result;
  }

  @Override
  public Boolean getBoolean(String name) {
    if (!data.has(name)) {
      return null;
    }

    JsonNode node = getSingleNode(name);

    return node.isNull() ? null : node.asBoolean();
  }

  @Override
  public List<Boolean> getBooleanList(String name) {
    if (!data.has(name)) {
      return null;
    }

    if (!isMultiValued(name)) {
      return Collections.singletonList(getBoolean(name));
    }

    ArrayNode array = data.withArray(name);
    List<Boolean> result = new ArrayList<>();
    for (JsonNode node : array) {
      result.add(node.isNull() ? null : node.asBoolean());
    }
    return result;
  }

  @Override
  public Long getLong(String name) {
    if (!data.has(name)) {
      return null;
    }

    JsonNode node = getSingleNode(name);

    return node.isNull() ? null : node.asLong();
  }

  @Override
  public List<Long> getLongList(String name) {
    if (!data.has(name)) {
      return null;
    }

    if (!isMultiValued(name)) {
      return Collections.singletonList(getLong(name));
    }

    ArrayNode array = data.withArray(name);
    List<Long> result = new ArrayList<>();
    for (JsonNode node : array) {
      result.add(node.isNull() ? null : node.asLong());
    }
    return result;
  }

  @Override
  public Instant getInstant(String name) {
    if (!data.has(name)) {
      return null;
    }

    JsonNode node = getSingleNode(name);

    String dateStr = node.asText();
    Instant dateInstant = Instant.from(DateTimeFormatter.ISO_INSTANT.parse(dateStr));
    return node.isNull() ? null : dateInstant;
  }

  @Override
  public List<Instant> getInstantList(String name) {
    if (!data.has(name)) {
      return null;
    }

    if (!isMultiValued(name)) {
      return Collections.singletonList(getInstant(name));
    }

    ArrayNode array = data.withArray(name);
    List<Instant> result = new ArrayList<>();
    for (JsonNode node : array) {
      String instantStr = node.asText();
      Instant instant = Instant.from(DateTimeFormatter.ISO_INSTANT.parse(instantStr));
      result.add(node.isNull() ? null : instant);
    }
    return result;
  }

  private JsonNode getSingleNode(String name) {
    return isMultiValued(name) ? data.withArray(name).get(0) : data.get(name);
  }

  @Override
  public int length(String name) {
    if (!has(name)) {
      return 0;
    } else if (!isMultiValued(name)) {
      return 1;
    } else {
      return data.get(name).size();
    }
  }

  @Override
  public String getId() {
    return getString(ID_FIELD);
  }

  @Override
  public String getRunId() {
    return getString(RUNID_FIELD);
  }

  @Override
  public boolean has(String name) {
    return data.has(name);
  }

  @Override
  public boolean hasNonNull(String name) {
    return data.hasNonNull(name);
  }

  @Override
  public boolean isMultiValued(String name) {
    return data.has(name) && JsonNodeType.ARRAY.equals(data.get(name).getNodeType());
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof JsonDocument) {
      return data.equals(((JsonDocument)other).data);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return data.hashCode();
  }

  private void convertToList(String name) {
    if (!data.has(name)) {
      data.set(name, MAPPER.createArrayNode());
      return;
    }
    JsonNode field = data.get(name);
    if (field.isArray()) {
      return;
    }
    ArrayNode array = MAPPER.createArrayNode();
    array.add(field);
    data.set(name, array);
  }

  @Override
  public void addToField(String name, String value) {
    validateNotReservedField(name);
    convertToList(name);
    ArrayNode array = data.withArray(name);
    array.add(value);
  }

  @Override
  public void addToField(String name, Long value) {
    validateNotReservedField(name);
    convertToList(name);
    ArrayNode array = data.withArray(name);
    array.add(value);
  }

  @Override
  public void addToField(String name, Integer value) {
    validateNotReservedField(name);
    convertToList(name);
    ArrayNode array = data.withArray(name);
    array.add(value);
  }

  @Override
  public void addToField(String name, Boolean value) {
    validateNotReservedField(name);
    convertToList(name);
    ArrayNode array = data.withArray(name);
    array.add(value);
  }

  @Override
  public void addToField(String name, Double value) {
    validateNotReservedField(name);
    convertToList(name);
    ArrayNode array = data.withArray(name);
    array.add(value);
  }

  @Override
  public void addToField(String name, Instant value) {
    validateNotReservedField(name);
    convertToList(name);
    ArrayNode array = data.withArray(name);
    String dateStr = DateTimeFormatter.ISO_INSTANT.format(value);
    array.add(dateStr);
  }

  @Override
  public void setOrAdd(String name, String value) {
    if (has(name)) {
      addToField(name, value);
    } else {
      setField(name, value);
    }
  }

  @Override
  public void setOrAdd(String name, Long value) {
    if (has(name)) {
      addToField(name, value);
    } else {
      setField(name, value);
    }
  }

  @Override
  public void setOrAdd(String name, Integer value) {
    if (has(name)) {
      addToField(name, value);
    } else {
      setField(name, value);
    }
  }

  @Override
  public void setOrAdd(String name, Boolean value) {
    if (has(name)) {
      addToField(name, value);
    } else {
      setField(name, value);
    }
  }

  @Override
  public void setOrAdd(String name, Double value) {
    if (has(name)) {
      addToField(name, value);
    } else {
      setField(name, value);
    }
  }

  @Override
  public void setOrAdd(String name, Instant value) {
    if (has(name)) {
      addToField(name, value);
    } else {
      setField(name, value);
    }
  }

  @Override
  public void setOrAdd(String name, Document other) throws IllegalArgumentException {
    validateNotReservedField(name);

    if (!has(name)) {

      if (!other.has(name)) {
        return;
      } else {
        data.set(name, other.getData().get(name));
        return;
      }

    } else {

      convertToList(name);
      ArrayNode currentValues = (ArrayNode) data.get(name);
      JsonNode otherValue = other.getData().get(name);

      if (otherValue.getNodeType() == JsonNodeType.ARRAY) {
        currentValues.addAll((ArrayNode) otherValue);
      } else {
        currentValues.add(otherValue);
      }

    }
  }

  @Override
  public void setOrAddAll(Document other) {
    for (Iterator<String> it = other.getData().fieldNames(); it.hasNext(); ) {
      String name = it.next();
      if (RESERVED_FIELDS.contains(name)) {
        continue;
      }
      setOrAdd(name, other);
    }
  }

  @Override
  public void logError(String description) {
    convertToList(ERROR_FIELD);
    ArrayNode array = data.withArray(ERROR_FIELD);
    array.add(description);
  }

  @Override
  public Map<String,Object> asMap() {
    Map<String, Object> result = MAPPER.convertValue(data, TYPE);
    return result;
  }

  @Override
  public void addChild(Document document) {
    ArrayNode node = data.withArray(CHILDREN_FIELD);
    node.add(document.getData());
  }

  @Override
  public boolean hasChildren() {
    if (!data.has(CHILDREN_FIELD)) {
      return false;
    }
    if (getChildren().isEmpty()) {
      return false;
    }
    return true;
  }

  @Override
  public List<Document> getChildren() {
    if (!data.has(CHILDREN_FIELD)) {
      return new ArrayList();
    }
    ArrayNode node = data.withArray(CHILDREN_FIELD);
    ArrayList<Document> children = new ArrayList();
    for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
      JsonNode element = it.next();
      try {
        // todo check that it is fine to cast / why did the need to do it arose?
//        children.add(new JsonDocument(element.deepCopy()));
        children.add(new JsonDocument((ObjectNode) element.deepCopy()));
      } catch (DocumentException e) {
        log.error("Unable to instantiate child Document", e);
      }
    }
    return children;
  }

  @Override
  public String toString() {
    return data.toString();
  }

  @Override
  public Document deepCopy() {
    try {
      return new JsonDocument(this);
    } catch (DocumentException e) {
      throw new IllegalStateException("Document not copyable", e);
    }
  }

  private void validateNotReservedField(String name) throws IllegalArgumentException {
    if (RESERVED_FIELDS.contains(name)) {
      throw new IllegalArgumentException();
    }
  }

  @Override
  public Set<String> getFieldNames() {
    Set<String> fieldNames = new HashSet<String>();
    Iterator<String> it = data.fieldNames();
    while (it.hasNext()) {
      String fieldName = it.next();
      fieldNames.add(fieldName);
    }
    return fieldNames;
  }

  @Override
  public void removeDuplicateValues(String fieldName, String targetFieldName) {
    if (!isMultiValued(fieldName)) {
      return;
    }

    ArrayNode arrayNode = data.withArray(fieldName);
    LinkedHashSet<JsonNode> set = new LinkedHashSet<>();
    int length = 0;
    for (JsonNode jsonNode : arrayNode) {
      length++;
      set.add(jsonNode);
    }

    if (targetFieldName == null || fieldName.equals(targetFieldName)) {
      if (set.size() == length) {
        return;
      }
      data.remove(fieldName);
      arrayNode = data.withArray(fieldName);
      for (JsonNode jsonNode : set) {
        arrayNode.add(jsonNode);
      }
    } else {
      arrayNode = data.withArray(targetFieldName);
      for (JsonNode jsonNode : set) {
        arrayNode.add(jsonNode);
      }
    }
  }
}
