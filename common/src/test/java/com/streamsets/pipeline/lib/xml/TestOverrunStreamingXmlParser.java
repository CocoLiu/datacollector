/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.lib.xml;

import com.google.common.base.Strings;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.lib.io.OverrunException;
import com.streamsets.pipeline.lib.io.OverrunReader;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

public class TestOverrunStreamingXmlParser {

  @Before
  public void setUp() {
    System.getProperties().remove(OverrunReader.READ_LIMIT_SYS_PROP);
  }

  @After
  public void cleanUp() {
    setUp();
  }

  public void testStreamLevelOverrunArray(boolean attemptNextRead) throws Exception {
    System.setProperty(OverrunReader.READ_LIMIT_SYS_PROP, "10000");
    String xml = "<root><record/><record>" + Strings.repeat("a", 20000) + "</record></root>";
    StreamingXmlParser parser = new OverrunStreamingXmlParser(new StringReader(xml), "record", 0, 100);
    Assert.assertNotNull(parser.read());
    if (!attemptNextRead) {
      parser.read();
    } else {
      try {
        parser.read();
      } catch (OverrunException ex) {
        //NOP
      }
      parser.read();
    }
  }

  @Test(expected = OverrunException.class)
  public void testStreamLevelOverrunArray() throws Exception {
    testStreamLevelOverrunArray(false);
  }

  @Test(expected = IllegalStateException.class)
  public void testStreamLevelOverrunArrayAttemptNextRead() throws Exception {
    testStreamLevelOverrunArray(true);
  }

  private Reader getXml(String name) throws Exception {
    return new InputStreamReader(Thread.currentThread().getContextClassLoader().getResourceAsStream(name));
  }

  @Test
  public void testXmlObjectOverrun() throws Exception {
    StreamingXmlParser parser = new OverrunStreamingXmlParser(getXml("TestStreamingXmlParser-records.xml"), "record", 0,
                                                              50);

    Field f = parser.read();
    Assert.assertNotNull(f);
    try {
      f = parser.read();
      Assert.fail();
    } catch (OverrunStreamingXmlParser.XmlObjectLengthException ex) {
    }
    f = parser.read();
    Assert.assertNotNull(f);
    Assert.assertEquals("r2", f.getValueAsMap().get("text").getValue());

    parser.close();
  }

}