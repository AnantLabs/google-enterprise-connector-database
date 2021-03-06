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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.enterprise.connector.db.DBConnectorType;
import com.google.enterprise.connector.db.DBContext;
import com.google.enterprise.connector.db.DBException;
import com.google.enterprise.connector.db.DocIdUtil;
import com.google.enterprise.connector.db.Util;
import com.google.enterprise.connector.db.XmlUtils;
import com.google.enterprise.connector.db.diffing.UrlDocumentBuilder.UrlType;
import com.google.enterprise.connector.spi.SpiConstants;
import com.google.enterprise.connector.spi.TraversalContext;
import com.google.enterprise.connector.util.diffing.DocumentHandle;
import com.google.enterprise.connector.util.diffing.DocumentSnapshot;

import org.json.JSONException;
import org.json.JSONObject;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Class for constructing a {@code DocumentSnapshot} and
 * {@code DocumentHandle} from a database row. This class combines the
 * Director and Builder classes of the Builder pattern using the
 * Template Method pattern. This class has two template methods,
 * {@code getDocumentSnapshot} and {@code getDocumentHandle}, that
 * construct the corresponding objects in a consistent fashion,
 * delegating pieces of the construction to the subclasses. Subclasses
 * must implement the {@code getContentHolder} and
 * {@code getJsonDocument} methods.
 */
abstract class DocumentBuilder {
  private static final Logger LOG =
      Logger.getLogger(DocumentBuilder.class.getName());

  public static final String ROW_CHECKSUM = "google:sum";

  /**
   * Factory method that creates the configured DocumentBuilder
   * instance. This method uses the same logic as
   * DBConnectorType.getExtMetadataType and should be kept in sync.
   */
  public static DocumentBuilder getInstance(DBContext dbContext,
      TraversalContext traversalContext) {
    String extMetaType = dbContext.getExtMetadataType();
    if (!Util.isNullOrWhitespace(extMetaType)
        && !extMetaType.equals(DBConnectorType.NO_EXT_METADATA)) {
      if (extMetaType.equalsIgnoreCase(DBConnectorType.COMPLETE_URL)
          && dbContext.getDocumentURLField() != null) {
        LOG.info("DB Connector is running in External Metadata feed mode with "
            + "complete document URL");
        return new UrlDocumentBuilder(dbContext, UrlType.COMPLETE_URL);
      } else if (extMetaType.equalsIgnoreCase(DBConnectorType.DOC_ID)
          && dbContext.getDocumentIdField() != null) {
        LOG.info("DB Connector is running in External Metadata feed mode with "
            + "Base URL and document ID");
        return new UrlDocumentBuilder(dbContext, UrlType.BASE_URL);
      } else if (extMetaType.equalsIgnoreCase(DBConnectorType.BLOB_CLOB)
          && dbContext.getLobField() != null) {
        LOG.info(
            "DB Connector is running in Content Feed Mode for BLOB/CLOB data");
        return new LobDocumentBuilder(dbContext, traversalContext);
      }
    }

    // No matches found above.
    // Explicitly change the mode of execution as user may switch from
    // "External Metadata Feed" mode to "Content Feed(for text data)" mode.
    dbContext.setExtMetadataType(DBConnectorType.NO_EXT_METADATA);
    LOG.info("DB Connector is running in content feed mode for text data");
    return new MetadataDocumentBuilder(dbContext);
  }

  protected final DBContext dbContext;
  protected final String connectorName;

  protected DocumentBuilder(DBContext dbContext) {
    this.dbContext = dbContext;
    this.connectorName = dbContext.getConnectorName();
  }

  // PUBLIC TEMPLATE METHODS
  //
  // These methods are not final so that they can be mocked.

  /**
   * Constructs a {@code DocumentSnapshot} for the given database row.
   *
   * @return a snapshot representing the database row
   * @throw DBException if an error occurs retrieving or processing the row
   */
  public DocumentSnapshot getDocumentSnapshot(Map<String, Object> row)
      throws DBException {
    ImmutableList<String> primaryKey =
        dbContext.getPrimaryKeyColumns(row.keySet());
    String docId = getDocId(row, primaryKey);
    ContentHolder contentHolder = getContentHolder(row, primaryKey, docId);
    DocumentHolder docHolder =
        getDocumentHolder(row, primaryKey, docId, contentHolder);
    String jsonString = getJsonString(docId, contentHolder.getChecksum());
    return new DBSnapshot(dbContext, docId, jsonString, docHolder);
  }

  /**
   * Constructs a {@code DocumentHandle} for the given {@code DocumentHolder},
   * which maintains the partially constructed document state.
   */
  public DocumentHandle getDocumentHandle(DocumentHolder docHolder)
      throws DBException {
    return new DBHandle(getJsonDocument(docHolder));
  }

  // ABSTRACT CONSTRUCTION METHODS IMPLEMENTED BY THE SUBCLASSES

  /**
   * Gets the content and associated content metadata. This will be passed
   * back to the subclass in the {@code DocumentHolder} to produce the
   * content properties for the {@code Document}.
   *
   * @return a non-null holder for the document content
   */
  protected abstract ContentHolder getContentHolder(Map<String, Object> row,
      List<String> primaryKey, String docId) throws DBException;

  /** Constructs the SPI document with its required properties. */
  /*
   * TODO(jlacey): getJsonDocument could be further refactored, moving
   * more of the steps into the DocumentBuilder.getDocumentHandle method.
   */
  protected abstract JsonDocument getJsonDocument(DocumentHolder docHolder)
      throws DBException;

  // PUBLIC CONSTRUCTION HELPER CLASSES

  /**
   * Links the DBSnapshot and DBHandle, by providing access to the builder
   * and the data it had, in order to produce the SPI {@code Document} in
   * the {@code DocumentHandle}.
   */
  /*
   * TODO(jlacey): An alternative design would be to have a parallel
   * hierarchy of factories and builders, and hold this data in the
   * builder itself. That is more code but possibly cleaner. It would be
   * nice to avoid introducing state in the builder (e.g., requiring
   * getDocumentSnapshot to be called before getDocumentHandle).
   */
  public static class DocumentHolder {
    private final DocumentBuilder builder;

    public final Map<String, Object> row;
    public final ImmutableList<String> primaryKey;
    public final String docId;
    public final ContentHolder contentHolder;

    public DocumentHolder(DocumentBuilder builder, Map<String, Object> row,
        ImmutableList<String> primaryKey, String docId,
        ContentHolder contentHolder) {
      this.builder = builder;

      this.row = row;
      this.primaryKey = primaryKey;
      this.docId = docId;
      this.contentHolder = contentHolder;
    }

    public DocumentHandle getDocumentHandle() throws DBException {
      return builder.getDocumentHandle(this);
    }
  }

  // UTILITY METHODS FOR THE SUBCLASSES

  protected final String getChecksum(Map<String, Object> row,
      List<String> primaryKey, String xslt) throws DBException {
    // TODO: Look into which encoding/charset to use for getBytes().
    return Util.getChecksum(getXmlDoc(row, primaryKey, xslt).getBytes());
  }

  /** Get XML representation of document (exclude the LOB column). */
  protected final String getXmlDoc(Map<String, Object> row,
      List<String> primaryKey, String xslt) throws DBException {
    return XmlUtils.getXMLRow(connectorName, row, primaryKey, xslt, dbContext,
        true);
  }

  protected final String getDisplayUrl(String docId) {
    return String.format("dbconnector://%s.localhost/%s", connectorName, docId);
  }

  /**
   * Extract the columns for Last Modified date
   * and add in list of skip columns.
   *
   * @param skipColumns list of columns to be skipped as metadata
   * @param dbContext
   */
  protected final void skipLastModified(List<String> skipColumns,
      DBContext dbContext) {
    String lastModColumn = dbContext.getLastModifiedDate();
    if (lastModColumn != null) {
      skipColumns.add(lastModColumn);
    }
  }

  /**
   * Sets the value for last modified date.
   *
   * @param row Map representing database row
   */
  protected final void setLastModified(Map<String, Object> row,
      JsonObjectUtil jsonObjectUtil, DBContext dbContext) {
    if (dbContext == null || dbContext.getLastModifiedDate() == null) {
      return;
    }
    Object lastModified = row.get(dbContext.getLastModifiedDate());
    if (lastModified != null && (lastModified instanceof Timestamp)) {
      jsonObjectUtil.setLastModifiedDate(SpiConstants.PROPNAME_LASTMODIFIED,
                                         (Timestamp) lastModified);
    }
  }

  /**
   * Adds the value of each column as metadata to Database document
   * except the values of columns in skipColumns list.
   */
  protected final void setMetaInfo(JsonObjectUtil jsonObjectUtil,
      Map<String, Object> row, List<String> skipColumns) {
    Set<String> keySet = row.keySet();
    for (String key : keySet) {
      if (!skipColumns.contains(key)) {
        Object value = row.get(key);
        if (value != null) {
          jsonObjectUtil.setProperty(key, value.toString());
        }
      } else {
        LOG.info("Skipping metadata indexing of column " + key);
      }
    }
  }

  // CONCRETE CONSTRUCTION METHODS USED BY THIS CLASS

  private String getDocId(Map<String, Object> row, List<String> primaryKey) {
    return DocIdUtil.generateDocId(primaryKey, row);
  }

  @VisibleForTesting
  final String getJsonString(String docId, String checksum) {
    JSONObject jsonObject = new JSONObject();
    try {
      jsonObject.put(SpiConstants.PROPNAME_DOCID, docId);
      jsonObject.put(ROW_CHECKSUM, checksum);
    } catch (JSONException impossible) {
      // This exception is thrown on null keys and out-of-range numbers.
      throw new AssertionError(impossible);
    }
    return jsonObject.toString();
  }

  private DocumentHolder getDocumentHolder(Map<String, Object> row,
      ImmutableList<String> primaryKey, String docId,
      ContentHolder contentHolder) {
    return new DocumentHolder(this, row, primaryKey, docId, contentHolder);
  }
}
