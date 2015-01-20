/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.lib.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.base.Preconditions;
import com.streamsets.pipeline.lib.io.CountingReader;
import com.streamsets.pipeline.lib.io.OverrunException;
import com.streamsets.pipeline.lib.io.OverrunReader;
import com.streamsets.pipeline.lib.util.ExceptionUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Caps amount the size of of JSON objects being parsed, discarding the ones that exceed the limit and fast forwarding
 * to the next.
 * <p/>
 * If the max object length is exceed, the readMap(), readList() method will throw a JsonObjectLengthException.
 * After a JsonObjectLengthException exception the parser is still usable, the subsequent read will be positioned for
 * the next object.
 * <p/>
 * The underlying InputStream is wrapped with an OverrunInputStream to prevent an overrun due to an extremely large
 * field name or string value. The default limit is 100K and it is configurable via a JVM property,
 * {@link com.streamsets.pipeline.lib.io.OverrunReader#READ_LIMIT_SYS_PROP}, as it is not expected user will need
 * to change this. If an OverrunException is thrown the parser is not usable anymore.
 */
public class OverrunStreamingJsonParser extends StreamingJsonParser {

  public static class EnforcerMap extends LinkedHashMap {

    @Override
    @SuppressWarnings("unchecked")
    public Object put(Object key, Object value) {
      try {
        return super.put(key, value);
      } finally {
        checkIfLengthExceededForObjectRead(this);
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public String toString() {
      StringBuilder sb = new StringBuilder("{");
      String separator = "";
      for (Map.Entry entry : (Set<Map.Entry>) entrySet()) {
        sb.append(separator).append(entry.getKey()).append("=").append(entry.getValue());
        if (sb.length() > 100) {
          sb.append(", ...");
          break;
        }
        separator = ", ";
      }
      sb.append("}");
      return sb.toString();
    }

  }

  public static class EnforcerList extends ArrayList {

    @Override
    @SuppressWarnings("unchecked")
    public boolean add(Object o) {
      try {
        return super.add(o);
      } finally {
        checkIfLengthExceededForObjectRead(this);
      }
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder("[");
      String separator = "";
      for (Object value : this) {
        sb.append(separator).append(value);
        if (sb.length() > 100) {
          sb.append(", ...");
          break;
        }
        separator = ", ";
      }
      sb.append("]");
      return sb.toString();
    }
  }

  private static class MapDeserializer extends JsonDeserializer<Map> {

    @Override
    public Map deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException {
      return jp.readValueAs(EnforcerMap.class);
    }

  }

  private static class ListDeserializer extends JsonDeserializer<List> {

    @Override
    public List deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException {
      return jp.readValueAs(EnforcerList.class);
    }

  }

  private static final ThreadLocal<OverrunStreamingJsonParser> TL = new ThreadLocal<>();

  private final CountingReader countingReader;
  private final int maxObjectLen;
  private long limit;
  private boolean overrun;

  public OverrunStreamingJsonParser(CountingReader reader, Mode mode, int maxObjectLen) throws IOException {
    this(reader, 0, mode, maxObjectLen);
  }

  public OverrunStreamingJsonParser(CountingReader reader, long initialPosition, Mode mode, int maxObjectLen)
      throws IOException {
    super(new OverrunReader(reader, OverrunReader.getDefaultReadLimit()), initialPosition, mode);
    countingReader = (CountingReader) getReader();
    this.maxObjectLen = maxObjectLen;
  }

  @Override
  protected void fastForwardLeaseReader() {
    ((CountingReader) getReader()).resetCount();
  }

  @Override
  protected ObjectMapper getObjectMapper() {
    ObjectMapper objectMapper = super.getObjectMapper();
    SimpleModule module = new SimpleModule();
    module.addDeserializer(Map.class, new MapDeserializer());
    module.addDeserializer(List.class, new ListDeserializer());
    objectMapper.registerModule(module);
    return objectMapper;
  }

  @Override
  protected Object readObjectFromArray() throws IOException {
    Preconditions.checkState(!overrun, "The underlying input stream had an overrun, the parser is not usable anymore");
    countingReader.resetCount();
    limit = getJsonParser().getCurrentLocation().getCharOffset() + maxObjectLen;
    try {
      TL.set(this);
      return super.readObjectFromArray();
    } catch (Exception ex) {
      JsonObjectLengthException olex = ExceptionUtils.findSpecificCause(ex, JsonObjectLengthException.class);
      if (olex != null) {
        JsonParser parser = getJsonParser();
        JsonToken token = parser.getCurrentToken();
        if (token == null) {
          token = parser.nextToken();
        }
        while (token != null && parser.getParsingContext() != getRootContext()) {
          token = parser.nextToken();
        }
        throw olex;
      } else {
        OverrunException oex = ExceptionUtils.findSpecificCause(ex, OverrunException.class);
        if (oex != null) {
          overrun = true;
          throw oex;
        }
        throw ex;
      }
    } finally {
      TL.remove();
    }
  }

  @Override
  protected Object readObjectFromStream() throws IOException {
    Preconditions.checkState(!overrun, "The underlying input stream had an overrun, the parser is not usable anymore");
    countingReader.resetCount();
    limit = getJsonParser().getCurrentLocation().getCharOffset() + maxObjectLen;
    try {
      TL.set(this);
      return super.readObjectFromStream();
    } catch (Exception ex) {
      JsonObjectLengthException olex = ExceptionUtils.findSpecificCause(ex, JsonObjectLengthException.class);
      if (olex != null) {
        fastForwardToNextRootObject();
        throw olex;
      } else {
        OverrunException oex = ExceptionUtils.findSpecificCause(ex, OverrunException.class);
        if (oex != null) {
          overrun = true;
          throw oex;
        }
        throw ex;
      }
    } finally {
      TL.remove();
    }
  }

  public static class JsonObjectLengthException extends IOException {
    private String objectSnippet;

    public JsonObjectLengthException(String message, Object json) {
      super(message);
      objectSnippet = json.toString();
    }

    public String getJsonSnippet() {
      return objectSnippet;
    }

  }

  private static void checkIfLengthExceededForObjectRead(Object json) {
    OverrunStreamingJsonParser enforcer = TL.get();
    if (enforcer.getJsonParser().getCurrentLocation().getCharOffset() > enforcer.limit) {
      ExceptionUtils.throwUndeclared(new JsonObjectLengthException("Json Object exceeds max length", json));
    }
  }

}