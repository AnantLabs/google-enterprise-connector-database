// Copyright 2011 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.db.diffing;

import com.google.enterprise.connector.db.TestUtils;
import com.google.enterprise.connector.spi.SpiConstants;

import java.util.Map;
import java.util.logging.Logger;

public class MetadataDocumentBuilderTest extends DocumentBuilderFixture {
  private static final Logger LOG =
      Logger.getLogger(MetadataDocumentBuilderTest.class.getName());

  /**
   * Test for converting DB row to DB Doc.
   */
  public final void testRowToDoc() throws Exception {
    Map<String, Object> rowMap = TestUtils.getStandardDBRow();
    JsonDocument doc =
        new MetadataDocumentBuilder(getMinimalDbContext()).fromRow(rowMap);
    for (String propName : doc.getPropertyNames()) {
      LOG.info(propName + ":    " + getProperty(doc, propName));
    }
    assertEquals("MSxsYXN0XzAx", getProperty(doc, SpiConstants.PROPNAME_DOCID));
    String content = getProperty(doc, SpiConstants.PROPNAME_CONTENT);
    assertNotNull(content);
    assertTrue(content.contains("id=1"));
    assertTrue(content.contains("lastName=last_01"));
    assertEquals("text/html", getProperty(doc, SpiConstants.PROPNAME_MIMETYPE));

    // Checksum should be hidden as a public property.
    assertNull(doc.findProperty(DocumentBuilder.ROW_CHECKSUM));

    // But the checksum should be included in the snapshot string.
    String expected = "{\"google:docid\":\"MSxsYXN0XzAx\","
        + "\"google:sum\":\"7ffd1d7efaf0d1ee260c646d827020651519e7b0\"}";
    assertEquals(expected, doc.toJson());
  }
}
