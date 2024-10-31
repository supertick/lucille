package com.kmwllc.lucille.connector.cloudstorageclients;

import com.kmwllc.lucille.core.Publisher;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public interface CloudStorageClient {

  static CloudStorageClient getClient(URI pathToStorage, Publisher publisher, String docIdPrefix, List<Pattern> excludes, List<Pattern> includes,
      Map<String, Object> cloudOptions) {
    String activeClient = pathToStorage.getScheme();
    switch (activeClient) {
      case "gcs" -> {
        return new GoogleStorageClient(pathToStorage, publisher, docIdPrefix, excludes, includes, cloudOptions);
      }
      case "s3" -> {
        return new S3StorageClient(pathToStorage, publisher, docIdPrefix, excludes, includes, cloudOptions);
      }
      case "azb" -> {
        return new AzureStorageClient(pathToStorage, publisher, docIdPrefix, excludes, includes, cloudOptions);
      }
      default -> throw new RuntimeException("Unsupported client type: " + activeClient);
    }
  }

  void init();

  void shutdown() throws Exception;

  void publishFiles();
}
