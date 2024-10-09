package com.kmwllc.lucille.indexer;

import com.kmwllc.lucille.core.Document;
import com.kmwllc.lucille.core.Indexer;
import com.kmwllc.lucille.core.IndexerException;
import com.kmwllc.lucille.core.KafkaDocument;
import com.kmwllc.lucille.message.IndexerMessenger;
import com.kmwllc.lucille.util.OpenSearchUtils;
import com.typesafe.config.Config;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.BulkIndexByScrollFailure;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.VersionType;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.opensearch.client.opensearch.core.DeleteByQueryResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class OpenSearchIndexer extends Indexer {

  private static final Logger log = LoggerFactory.getLogger(OpenSearchIndexer.class);

  private final OpenSearchClient client;
  private final String index;

  private final String routingField;

  private final VersionType versionType;

  //flag for using partial update API when sending documents to opensearch
  private final boolean update;

  public OpenSearchIndexer(Config config, IndexerMessenger messenger, OpenSearchClient client, String metricsPrefix) {
    super(config, messenger, metricsPrefix);
    if (this.indexOverrideField != null) {
      throw new IllegalArgumentException(
          "Cannot create OpenSearchIndexer. Config setting 'indexer.indexOverrideField' is not supported by OpenSearchIndexer.");
    }
    this.client = client;
    this.index = OpenSearchUtils.getOpenSearchIndex(config);
    this.routingField = config.hasPath("indexer.routingField") ? config.getString("indexer.routingField") : null;
    this.update = config.hasPath("opensearch.update") ? config.getBoolean("opensearch.update") : false;
    this.versionType =
        config.hasPath("indexer.versionType") ? VersionType.valueOf(config.getString("indexer.versionType")) : null;
  }

  public OpenSearchIndexer(Config config, IndexerMessenger messenger, boolean bypass, String metricsPrefix) {
    this(config, messenger, getClient(config, bypass), metricsPrefix);
  }

  private static OpenSearchClient getClient(Config config, boolean bypass) {
    return bypass ? null : OpenSearchUtils.getOpenSearchRestClient(config);
  }

  @Override
  public boolean validateConnection() {
    if (client == null) {
      return true;
    }
    boolean response;
    try {
      response = client.ping().value();
    } catch (Exception e) {
      log.error("Couldn't ping OpenSearch ", e);
      return false;
    }
    if (!response) {
      log.error("Non true response when pinging OpenSearch: " + response);
      return false;
    }
    return true;
  }

  @Override
  public void closeConnection() {
    if (client != null && client._transport() != null) {
      try {
        client._transport().close();
      } catch (Exception e) {
        log.error("Error closing Opensearchclient", e);
      }
    }
  }

  @Override
  protected void sendToIndex(List<Document> documents) throws Exception {
    // skip indexing if there is no indexer client
    if (client == null) {
      return;
    }

    Map<String, Document> documentsToUpload = new LinkedHashMap<>();
    Set<String> idsToDelete = new LinkedHashSet<>();
    Map<String, List<String>> termsToDeleteByQuery = new LinkedHashMap<>();

    // populate which collection each document belongs to
    // if document is marked for deletion ONLY, then only add to idsToDelete
    // if document is marked for deletion AND contains deleteByFieldField and deleteByFieldValue, only add to termsToDeleteByQuery
    // else then add to upload
    for (Document doc : documents) {
      String id = doc.getId();
      if (isMarkedForDeletion(doc)) {
        documentsToUpload.remove(id);
        if (isMarkedForDeletionByField(doc)) {
          String field = doc.getString(deleteByFieldField);
          if (!termsToDeleteByQuery.containsKey(field)) {
            termsToDeleteByQuery.put(field, new ArrayList<>());
          }
          termsToDeleteByQuery.get(field).add(doc.getString(deleteByFieldValue));
        } else {
          idsToDelete.add(id);
        }
      } else {
        idsToDelete.remove(id);
        documentsToUpload.put(id, doc);
      }
    }


    uploadDocuments(new ArrayList<>(documentsToUpload.values()));
    deleteById(new ArrayList<>(idsToDelete));
    deleteByQuery(termsToDeleteByQuery);
  }

  private void deleteById(List<String> idsToDelete) throws Exception {
    if (idsToDelete.isEmpty()) {
      return;
    }

    BulkRequest.Builder br = new BulkRequest.Builder();
    for (String id : idsToDelete) {
      br.operations(op -> op
          .delete(d -> d
              .index(index)
              .id(id)
          )
      );
    }

    BulkResponse response =  client.bulk(br.build());
    if (response.errors()) {
      for (BulkResponseItem item : response.items()) {
        if (item.error() != null) {
          log.debug("Error while deleting id: {} because: {}", item.id(), item.error().reason());
        }
      }
      throw new IndexerException("encountered errors while deleting documents");
    }
  }

  private void deleteByQuery(Map<String, List<String>> termsToDeleteByQuery) throws Exception {
    if (termsToDeleteByQuery.isEmpty()) {
      return;
    }
    // termsToDeleteByQuery is never empty
    BoolQuery.Builder boolQuery = new BoolQuery.Builder();
    for (Map.Entry<String, List<String>> entry : termsToDeleteByQuery.entrySet()) {
      String field = entry.getKey();
      List<String> values = entry.getValue();
      boolQuery.filter(f -> f
        .terms(t -> t
          .field(field)
          .terms(tt -> tt.value(values.stream()
              .map(FieldValue::of)
              .collect(Collectors.toList()))
          )
        )
      );
    }

    DeleteByQueryRequest deleteByQueryRequest = new DeleteByQueryRequest.Builder()
        .index(index)
        .query(q -> q.bool(boolQuery.build()))
        .build();
    DeleteByQueryResponse response = client.deleteByQuery(deleteByQueryRequest);

    if (!response.failures().isEmpty()) {
      for (BulkIndexByScrollFailure failure : response.failures()) {
        log.debug("Error while deleting by query: {}, because of: {}", failure.cause().reason(), failure.cause());
      }
      throw new IndexerException("encountered errors while deleting by query");
    }
  }

  private void uploadDocuments(List<Document> documentsToUpload) throws IOException, IndexerException {
    if (documentsToUpload.isEmpty()) {
      return;
    }

    BulkRequest.Builder br = new BulkRequest.Builder();
    for (Document doc : documentsToUpload) {

      // removing the fields mentioned in the ignoreFields setting in configurations
      Map<String, Object> indexerDoc = getIndexerDoc(doc);

      // remove children documents field from indexer doc (processed from doc by addChildren method call below)
      indexerDoc.remove(Document.CHILDREN_FIELD);

      // if a doc id override value exists, make sure it is used instead of pre-existing doc id
      String docId = Optional.ofNullable(getDocIdOverride(doc)).orElse(doc.getId());

      // This condition below avoids adding id if ignoreFields contains it and edge cases:
      // - Case 1: id and idOverride in ignoreFields -> idOverride used by Indexer, both removed from Document (tested in testIgnoreFieldsWithOverride)
      // - Case 2: id in ignoreFields, idOverride exists -> idOverride used by Indexer, only id field removed from Document (tested in testIgnoreFieldsWithOverride2)
      // - Case 3: id in ignoreFields, idOverride null -> id used by Indexer, id also removed from Document (tested in testRouting)
      // - Case 4: ignoreFields null, idOverride exists -> idOverride used by Indexer, id and idOverride field exist in Document (tested in testOverride)
      // - Case 5: ignoreFields null, idOverride null -> document id remains and used by Indexer (Default case & tested)
      if (ignoreFields == null || !ignoreFields.contains(Document.ID_FIELD)) {
        indexerDoc.put(Document.ID_FIELD, docId);
      }

      // handle special operations required to add children documents
      addChildren(doc, indexerDoc);
      Long versionNum = (versionType == VersionType.External || versionType == VersionType.ExternalGte)
          ? ((KafkaDocument) doc).getOffset()
          : null;

      if (update) {
        br.operations(op -> op
            .update((up) -> {
              up.index(index).id(docId);
              if (routingField != null) {
                up.routing(doc.getString(routingField));
              }
              if (versionNum != null) {
                up.versionType(versionType).version(versionNum);
              }
              return up.document(indexerDoc);
            }));
      } else {
        br.operations(op -> op
            .index((up) -> {
              up.index(index).id(docId);
              if (routingField != null) {
                up.routing(doc.getString(routingField));
              }
              if (versionNum != null) {
                up.versionType(versionType).version(versionNum);
              }
              return up.document(indexerDoc);
            }));
      }
    }
    BulkResponse response = client.bulk(br.build());
    // We're choosing not to check response.errors(), instead iterating to be sure whether errors exist
    if (response != null) {
      for (BulkResponseItem item : response.items()) {
        if (item.error() != null) {
          throw new IndexerException(item.error().reason());
        }
      }
    }
  }

  private void addChildren(Document doc, Map<String, Object> indexerDoc) {
    List<Document> children = doc.getChildren();
    if (children == null || children.isEmpty()) {
      return;
    }
    for (Document child : children) {
      Map<String, Object> map = child.asMap();
      Map<String, Object> indexerChildDoc = new HashMap<>();
      for (String key : map.keySet()) {
        // we don't support children that contain nested children
        if (Document.CHILDREN_FIELD.equals(key)) {
          continue;
        }
        Object value = map.get(key);
        indexerChildDoc.put(key, value);
      }
      // TODO: Do nothing for now, add support for child docs like SolrIndexer does in future (_childDocuments_)
    }
  }

  private boolean isMarkedForDeletion(Document doc) {
    return deletionMarkerField != null
        && deletionMarkerFieldValue != null
        && doc.hasNonNull(deletionMarkerField)
        && doc.getString(deletionMarkerField).equals(deletionMarkerFieldValue);
  }

  private boolean isMarkedForDeletionByField(Document doc) {
    return deleteByFieldField != null
        && doc.has(deleteByFieldField)
        && deleteByFieldValue != null
        && doc.has(deleteByFieldValue);
  }
}
