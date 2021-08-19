package com.kmwllc.lucille.callback;

import com.kmwllc.lucille.core.ConfigAccessor;
import com.kmwllc.lucille.core.Document;
import com.kmwllc.lucille.core.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * TODO: reconcile with com.kmwllc.lucille.core.PipelineWorker
 */
class Worker implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(Worker.class);
  
  private final WorkerMessageManager manager;

  private final Pipeline pipeline;

  private volatile boolean running = true;

  public void terminate() {
    log.info("terminate");
    running = false;
  }

  public Worker(boolean localMode) throws Exception {
    this.manager = localMode ? LocalMessageManager.getInstance() : new KafkaWorkerMessageManager();
    this.pipeline = Pipeline.fromConfig(ConfigAccessor.loadConfig());
  }

  @Override
  public void run() {
    while (running) {
      Document doc;
      try {
        log.info("polling");
        doc = manager.pollDocToProcess();
      } catch (Exception e) {
        log.info("interrupted " + e);
        terminate();

        return;
      }

      if (doc == null) {
        log.info("WORKER: received 0 docs");
        continue;
      }

      try {
        // TODO: update child document handling to support huge numbers of child documents, too many to fit in memory
        List<Document> results = pipeline.processDocument(doc);

        for (Document result : results) {
          log.info("processed " + result);
          manager.sendCompleted(result);

          // create an open receipt for child documents
          String runId = doc.getString("run_id");
          if (!doc.getId().equals(result.getId())) {
            manager.sendEvent(new Event(result.getId(),
              runId, null, Event.Type.CREATE, Event.Status.SUCCESS));
          }

        }

      } catch (Exception e) {

        /*
        try {
          manager.submitReceipt(new Receipt(doc.getId(), runId, e.getMessage()));
        } catch (Exception e2) {
          e2.printStackTrace();
        }
        */

        e.printStackTrace();
      }
    }
    try {
      manager.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
    log.info("exit");
  }

}
