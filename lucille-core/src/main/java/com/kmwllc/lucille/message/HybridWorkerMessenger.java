package com.kmwllc.lucille.message;

import com.kmwllc.lucille.core.Document;
import com.kmwllc.lucille.core.Event;
import com.kmwllc.lucille.core.KafkaDocument;
import com.kmwllc.lucille.util.DeduplicationQueue;
import com.typesafe.config.Config;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import net.jodah.expiringmap.ExpiringMap;
import org.apache.commons.collections.map.SingletonMap;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

public class HybridWorkerMessenger implements WorkerMessenger {

  private static final Logger log = LoggerFactory.getLogger(KafkaWorkerMessenger.class);
  private final Consumer<String, KafkaDocument> sourceConsumer;
  private final KafkaProducer<String, String> kafkaEventProducer;
  private final LinkedBlockingQueue<Document> pipelineDest;
  private final LinkedBlockingQueue<Map<TopicPartition, OffsetAndMetadata>> offsets;

  private final DeduplicationQueue deduplicationQueue;

  private final Config config;
  private final String pipelineName;

  public HybridWorkerMessenger(Config config, String pipelineName,
      LinkedBlockingQueue<Document> pipelineDest,
      KafkaConsumer sourceConsumer) {
    this.config = config;
    this.pipelineName = pipelineName;
    this.pipelineDest = pipelineDest;
    this.sourceConsumer = sourceConsumer;
    this.kafkaEventProducer = KafkaUtils.createEventProducer(config);

    this.deduplicationQueue = DeduplicationQueue.getInstance();
    this.offsets = this.deduplicationQueue.getOffsets();
  }

  public HybridWorkerMessenger(Config config, String pipelineName,
      LinkedBlockingQueue<Document> pipelineDest) {
    this(config, pipelineName, pipelineDest, createSourceConsumer(config, pipelineName));
  }

  private static KafkaConsumer createSourceConsumer(Config config, String pipelineName) {
    // append random string to kafka client ID to prevent kafka from issuing a warning when multiple consumers
    // with the same client ID are started in separate worker threads
    String kafkaClientId = "com.kmwllc.lucille-worker-" + pipelineName + "-" + RandomStringUtils.randomAlphanumeric(8);
    KafkaConsumer consumer = KafkaUtils.createDocumentConsumer(config, kafkaClientId);
    consumer.subscribe(Pattern.compile(KafkaUtils.getSourceTopicName(pipelineName, config)));

    return consumer;
  }

  /**
   * Polls for a document that is waiting to be processed by the pipeline.
   *
   * Does not commit offsets.
   */
  @Override
  public KafkaDocument pollDocToProcess() throws Exception {
    ConsumerRecords<String, KafkaDocument> consumerRecords = sourceConsumer.poll(KafkaUtils.POLL_INTERVAL);
    KafkaUtils.validateAtMostOneRecord(consumerRecords);
    if (consumerRecords.count() > 0) {
      ConsumerRecord<String, KafkaDocument> record = consumerRecords.iterator().next();
      KafkaDocument doc = record.value();
      doc.setKafkaMetadata(record);

      deduplicationQueue.addToExpiryQueue(doc);
    }

    return deduplicationQueue.pollExpiredDocuments();
  }

  @Override
  public void commitPendingDocOffsets() throws Exception {
    Map<TopicPartition, OffsetAndMetadata> batchOffsets = null;
    while ((batchOffsets = offsets.poll()) != null) {
      sourceConsumer.commitSync(batchOffsets);
    }
  }

  /**
   * Sends a processed document to the appropriate destination for documents waiting to be indexed.
   *
   */
  @Override
  public void sendForIndexing(Document document) throws Exception {
    pipelineDest.put(document);
  }

  @Override
  public void sendFailed(Document document) throws Exception {
  }

  @Override
  public void sendEvent(Event event) throws Exception {
    if (kafkaEventProducer == null) {
      return;
    }
    String confirmationTopicName = KafkaUtils.getEventTopicName(pipelineName, event.getRunId());
    RecordMetadata result = kafkaEventProducer.send(
        new ProducerRecord<>(confirmationTopicName, event.getDocumentId(), event.toString())).get();
    kafkaEventProducer.flush();
  }

  @Override
  public void sendEvent(Document document, String message, Event.Type type) throws Exception {
    if (kafkaEventProducer == null) {
      return;
    }
    Event event = new Event(document, message, type);
    sendEvent(event);
  }

  @Override
  public void close() throws Exception {
    if (sourceConsumer != null) {
      sourceConsumer.close();
    }
    if (kafkaEventProducer != null) {
      kafkaEventProducer.close();
    }
  }

}

