package com.kmwllc.lucille.core;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import com.kmwllc.lucille.message.*;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;

import java.util.*;

/**
 * Responsible for managing a "run." A run is a sequential execution of one or more Connectors.
 * During a run, all the work generated by one Connector must be complete before the next Connector begins.
 * A Runner should only stop once the entire run is complete.
 *
 * Runner instances are not meant to be shared across runs; a new Runner instance should be created for each run.
 *
 * In a distributed deployment, there will be one or more Worker processes and one or more Indexer processes
 * that stay alive indefinitely and constantly poll for work. A Runner can then be launched to execute
 * a sequence of connectors which pump work into the system. The work from this particular "run" should
 * then be handled by the existing Worker and Indexer. The Runner will terminate when all the work
 * it generated was complete, while the Worker and Indexer would stay alive.
 *
 * For development and testing purposes it is useful to have a way to execute an end-to-end run of a system
 * without requiring that the Worker and Indexer have been started as separate processes.
 * Runner provides a way to launch the Worker and Indexer as threads, then execute the run, and then
 * stop the Worker and Indexer threads. This can be done in a fully local mode, where all message traffic
 * is stored in in-memory queues, or in Kafka mode, where message traffic flows through an external
 * deployment of Kafka.
 *
 * A local end-to-end run involves a total of 4 threads:
 *
 * 1) a Worker thread polls for documents to process and runs them through the pipeline
 * 2) an Indexer thread polls for processed documents and indexes them
 * 3) a Connector thread reads data from a source, generates documents, and publishes them;
 *    Connectors are run sequentially so there will be at most one Connector thread at any time
 * 4) the main thread launches the other threads, and then uses the Publisher to poll for Events
 *    and wait for completion of the run
 */
public class Runner {

  public static final int DEFAULT_CONNECTOR_TIMEOUT = 1000 * 60 * 60 * 24;

  private static final Logger log = LoggerFactory.getLogger(Runner.class);

  private final String runId;
  private final int connectorTimeout;

  private static MetricRegistry metrics = new MetricRegistry();

  /**
   * Runs the configured connectors.
   *
   * no args: pipelines and indexers will be executed in separate threads within the same JVM; communication
   * between components will take place in memory and Kafka will not be used
   *
   * -usekafka: connectors will be run, sending documents and receiving events via Kafka. Pipeline workers
   * and indexers will not be run. The assumption is that these have been deployed as separate processes.
   *
   * -local: modifies -usekafka so that workers and indexers are started as separate threads within the same JVM;
   * kafka is still used for communication between them.
   *
   */
  public static void main(String[] args) throws Exception {
    SharedMetricRegistries.setDefault("default", metrics);
    Timer timer = metrics.timer("runner.timer");
    Timer.Context context = timer.time();

    Options cliOptions = new Options()
      .addOption(Option.builder("usekafka").hasArg(false)
        .desc("Use Kafka for inter-component communication and don't execute pipelines locally").build())
      .addOption(Option.builder("local").hasArg(false)
        .desc("Modifies usekafka mode to execute pipelines locally").build());

    CommandLine cli = null;
    try {
      cli = new DefaultParser().parse(cliOptions, args);
    } catch (UnrecognizedOptionException | MissingOptionException e) {
      try (StringWriter writer = new StringWriter();
           PrintWriter printer = new PrintWriter(writer)) {

        String header = "Run a sequence of connectors";
        new HelpFormatter().printHelp(printer, 256, "Runner", header, cliOptions,
          2, 10, "", true);
        log.info(writer.toString());
      }
      System.exit(1);
    }

    if (cli.hasOption("usekafka")) {
      runWithKafka(cli.hasOption("local"));
    } else {
      runLocal();
    }

    context.stop();
    log.info(String.format("Entire run took %.2f seconds to run.", timer.getSnapshot().getValues()[0] / Math.pow(10, 9)));
  }

  public Runner(Config config) throws Exception {
    // generate a unique ID for this run
    this.runId = UUID.randomUUID().toString();
    log.info("runId=" + runId);
    this.connectorTimeout =
      config.hasPath("runner.connectorTimeout") ? config.getInt("runner.connectorTimeout") : DEFAULT_CONNECTOR_TIMEOUT;
  }

  /**
   * Returns the ID for the current run.
   */
  public String getRunId() {
    return runId;
  }

  /**
   * Runs the designated Connector. Returns true only when 1) the Connector has finished generating documents, and
   * 2) all of the documents (and any generated children) have reached an end state in the workflow:
   * either being indexed or erroring-out. Returns false if the connector execution fails (i.e. if the connector
   * throws an exception).
   */
  public boolean runConnector(Connector connector, Publisher publisher) throws Exception {
    log.info("Running connector: " + connector.getName());
    Timer timer = metrics.timer("runner.connector.timer");
    Timer.Context context = timer.time();

    try {
      connector.preExecute(runId);
    } catch (ConnectorException e) {
      log.error("Connector failed to perform pre execution actions.", e);
      return false;
    }

    ConnectorThread connectorThread = new ConnectorThread(connector, publisher);
    connectorThread.start();

    boolean result = false;

    // the publisher could be null if we are running a connector that has no associated pipeline and therefore
    // there's nothing to publish to
    if (publisher!=null) {
      result = publisher.waitForCompletion(connectorThread, connectorTimeout);
      publisher.close();
    }

    try {
      connector.postExecute(runId);
    } catch (ConnectorException e) {
      log.error("Connector failed to perform post execution actions.", e);
      return false;
    }

    context.stop();
    log.info(String.format("Connector %s is complete. It took %.2f seconds to run.", connector.getName(), timer.getSnapshot().getValues()[0] / Math.pow(10, 9)));

    return result;
  }

  /**
   * Run Lucille end-to-end using the sequence of Connectors and the pipeline defined in the given
   * config file. Stop the run if any of the connectors fails.
   *
   * Run in a local mode where message traffic is kept in memory and there is no
   * communication with Kafka. Documents are sent to Solr as expected; sending to Solr is NOT bypassed.
   */
  public static void runLocal() throws Exception {

    Config config = ConfigFactory.load();
    Runner runner = new Runner(config);
    List<Connector> connectors = Connector.fromConfig(config);

    for (Connector connector : connectors) {
      LocalMessageManager manager = new LocalMessageManager(config);
      WorkerMessageManagerFactory workerMessageManagerFactory = WorkerMessageManagerFactory.getConstantFactory(manager);
      IndexerMessageManagerFactory indexerMessageManagerFactory = IndexerMessageManagerFactory.getConstantFactory(manager);
      PublisherMessageManagerFactory publisherMessageManagerFactory = PublisherMessageManagerFactory.getConstantFactory(manager);

      boolean result = run(config, runner, connector,
        workerMessageManagerFactory, indexerMessageManagerFactory, publisherMessageManagerFactory,
        true, false);
      if (!result) {
        break;
      }
    }

  }

  /**
   * Run Lucille end-to-end using the sequence of Connectors and the pipeline defined in the given
   * config file. Stop the run if any of the connectors fails.
   *
   * Run in a local mode where message traffic is kept in memory and there is no
   * communication with external systems like Kafka. Sending to solr is bypassed.
   *
   * Return a PersistingLocalMessageManager that
   * can be used to review the messages that were sent between various components during the run.
   */
  public static Map<String, PersistingLocalMessageManager> runInTestMode(String configName) throws Exception {

    Config config = ConfigFactory.load(configName);
    Runner runner = new Runner(config);
    List<Connector> connectors = Connector.fromConfig(config);
    Map<String,PersistingLocalMessageManager> map = new HashMap<>();
    for (Connector connector : connectors) {
      // create a persisting message manager that saves all message traffic so we can review it
      // even after various simulated topics have been fully consumed/cleared;
      // this implements the Worker/Indexer/PublisherMessageManager interfaces so it can be passed
      // to all three components; indeed, the same instance must be passed to the various components
      // so those components will see each other's messages
      PersistingLocalMessageManager manager = new PersistingLocalMessageManager();
      WorkerMessageManagerFactory workerMessageManagerFactory = WorkerMessageManagerFactory.getConstantFactory(manager);
      IndexerMessageManagerFactory indexerMessageManagerFactory = IndexerMessageManagerFactory.getConstantFactory(manager);
      PublisherMessageManagerFactory publisherMessageManagerFactory = PublisherMessageManagerFactory.getConstantFactory(manager);
      map.put(connector.getName(), manager);
      boolean result = run(config, runner, connector,
        workerMessageManagerFactory, indexerMessageManagerFactory, publisherMessageManagerFactory,
        true, true);
      if (!result) {
        break;
      }
    }

    return map;
  }

  /**
   * Run Lucille end-to-end using the sequence of Connectors and the pipeline defined in the given
   * config file. Stop the run if any of the connectors fails.
   *
   * Run in Kafka mode where message traffic flows through the Kafka deployment defined
   * in the config.
   */
  public static void runWithKafka(boolean startWorkerAndIndexer) throws Exception {
    Config config = ConfigFactory.load();
    Runner runner = new Runner(config);
    List<Connector> connectors = Connector.fromConfig(config);
    for (Connector connector : connectors) {
      String pipelineName = connector.getPipelineName();
      WorkerMessageManagerFactory workerMessageManagerFactory =
        WorkerMessageManagerFactory.getKafkaFactory(config, pipelineName);
      IndexerMessageManagerFactory indexerMessageManagerFactory =
        IndexerMessageManagerFactory.getKafkaFactory(config, pipelineName);
      PublisherMessageManagerFactory publisherMessageManagerFactory =
        PublisherMessageManagerFactory.getKafkaFactory(config);

      boolean result = run(config, runner, connector, workerMessageManagerFactory,
        indexerMessageManagerFactory, publisherMessageManagerFactory, startWorkerAndIndexer, false);
      if (!result) {
        break;
      }
    }
  }

  private static boolean run(Config config,
                             Runner runner,
                             Connector connector,
                             WorkerMessageManagerFactory workerMessageManagerFactory,
                             IndexerMessageManagerFactory indexerMessageManagerFactory,
                             PublisherMessageManagerFactory publisherMessageManagerFactory,
                             boolean startWorkerAndIndexer,
                             boolean bypassSolr) throws Exception {

    // Does not take into account connector creation time
    String pipelineName = connector.getPipelineName();
    Meter indexerMeter = metrics.meter("indexer.meter");
    Meter publisherMeter = metrics.meter("publisher.meter");
    indexerMeter.mark(0);
    publisherMeter.mark(0);

    WorkerPool workerPool = null;
    Indexer indexer = null;
    Publisher publisher = null;

    if (startWorkerAndIndexer && connector.getPipelineName() != null) {
      workerPool = new WorkerPool(config, pipelineName, workerMessageManagerFactory);
      workerPool.start();
      IndexerMessageManager indexerMessageManager = indexerMessageManagerFactory.create();
      indexer = Indexer.startThread(config, indexerMessageManager, bypassSolr);
    }

    if (connector.getPipelineName() != null) {
      PublisherMessageManager publisherMessageManager = publisherMessageManagerFactory.create();
      publisher = new PublisherImpl(publisherMessageManager, runner.getRunId(), connector.getPipelineName());
    }

    boolean result = runner.runConnector(connector, publisher);
    if (workerPool != null) {
      workerPool.stop();
    }
    if (indexer != null) {
      indexer.terminate();
    }

    return result;
  }

}
