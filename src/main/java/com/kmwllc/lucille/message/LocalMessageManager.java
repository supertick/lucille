package com.kmwllc.lucille.message;

import com.kmwllc.lucille.core.Event;
import com.kmwllc.lucille.core.Document;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class LocalMessageManager implements IndexerMessageManager, PublisherMessageManager, WorkerMessageManager {

  public static final Logger log = LoggerFactory.getLogger(LocalMessageManager.class);

  public static final int POLL_TIMEOUT_MS = 50;

  private final BlockingQueue<Event> pipelineEvents = new LinkedBlockingQueue<>();
  private final BlockingQueue<Document> pipelineSource;
  private final BlockingQueue<Document> pipelineDest = new LinkedBlockingQueue<>();

  public LocalMessageManager() {
    this.pipelineSource = new LinkedBlockingQueue<>();
  }

  public LocalMessageManager(int capacity) {
    this.pipelineSource = new LinkedBlockingQueue<>(capacity);
  }

  public LocalMessageManager(Config config) {
    this.pipelineSource = config.hasPath("worker.queueCapacity") ?
      new LinkedBlockingQueue<>(config.getInt("worker.queueCapacity")) :
      new LinkedBlockingQueue<>();
  }

  private String runId = null;
  private String pipelineName;

  @Override
  public Document pollCompleted() throws Exception {
    return pipelineDest.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  @Override
  public Document pollDocToProcess() throws Exception {
    return pipelineSource.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  @Override
  public void sendCompleted(Document document) throws Exception {
    pipelineDest.add(document);
  }

  @Override
  public void sendEvent(Event event) throws Exception {
    pipelineEvents.add(event);
  }

  @Override
  public Event pollEvent() throws Exception {
    return pipelineEvents.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  @Override
  public boolean hasEvents() throws Exception {
    return !pipelineEvents.isEmpty();
  }

  @Override
  public void initialize(String runId, String pipelineName) throws Exception {
    if (this.runId!=null) {
      throw new Exception("Already initialized.");
    }
    this.runId = runId;
    this.pipelineName = pipelineName;
  }

  @Override
  public String getRunId() {
    return runId;
  }

  @Override
  public void sendForProcessing(Document document) throws Exception {
    pipelineSource.put(document);
  }

  @Override
  public void close() {
  }
}
