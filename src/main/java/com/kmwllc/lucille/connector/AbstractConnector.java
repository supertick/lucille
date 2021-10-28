package com.kmwllc.lucille.connector;

import com.kmwllc.lucille.core.Connector;
import com.kmwllc.lucille.core.ConnectorException;
import com.typesafe.config.Config;

public abstract class AbstractConnector implements Connector {

  private String name;
  private String pipelineName;
  private String docIdPrefix;
  private final String docIdFormat;

  public AbstractConnector(Config config) {
    this.name = config.getString("name");
    this.pipelineName = config.hasPath("pipeline") ? config.getString("pipeline") : null;
    this.docIdPrefix = config.hasPath("docIdPrefix") ? config.getString("docIdPrefix") : "";
    this.docIdFormat = config.hasPath("docIdFormat") ? config.getString("docIdFormat") : null;
  }

  public String getName() {
    return name;
  }

  public String getPipelineName() {
    return pipelineName;
  }
  
  public String getDocIdPrefix() {
    return docIdPrefix;
  }

  /**
   * Creates an extended doc ID by adding a prefix (and possibly in the future, a suffix) to the
   * given id.
   */
  public String createDocId(String id) {
    return docIdPrefix + id;
  }

  public void postExecute(String runId) throws ConnectorException {
    // no-op
  }

  public void preExecute(String runId) throws ConnectorException {
    // no-op
  }

}
