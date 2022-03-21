package com.kmwllc.lucille.connector;

import com.kmwllc.lucille.connector.xml.XMLConnector;
import com.kmwllc.lucille.core.*;
import com.kmwllc.lucille.message.PersistingLocalMessageManager;
import com.kmwllc.lucille.util.FileUtils;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class XMLConnectorTest {

  @Test
  public void testStaff() throws Exception {
    Config config = ConfigFactory.parseReader(FileUtils.getReader("classpath:XMLConnectorTest/staff.conf"));
    PersistingLocalMessageManager manager = new PersistingLocalMessageManager();
    Publisher publisher = new PublisherImpl(config, manager, "run1", "pipeline1");
    Connector connector = new XMLConnector(config);
    connector.execute(publisher);

    List<Document> docs = manager.getSavedDocumentsSentForProcessing();

    assertEquals(2, docs.size());

    assertTrue(docs.get(0).has("xml"));
  }

  @Test
  public void testNestedStaff() throws Exception {
    Config config = ConfigFactory.parseReader(FileUtils.getReader("classpath:XMLConnectorTest/nestedstaff.conf"));
    PersistingLocalMessageManager manager = new PersistingLocalMessageManager();
    Publisher publisher = new PublisherImpl(config, manager, "run1", "pipeline1");
    Connector connector = new XMLConnector(config);
    connector.execute(publisher);

    List<Document> docs = manager.getSavedDocumentsSentForProcessing();

    // ensure that in a nested scenario, the nested tag does not get included
    assertEquals(2, docs.size());

    assertTrue(docs.get(0).has("xml"));
  }

  @Test
  public void testDifferentEncoding() throws Exception {
    Config config = ConfigFactory.parseReader(FileUtils.getReader("classpath:XMLConnectorTest/non-utf8.conf"));
    PersistingLocalMessageManager manager = new PersistingLocalMessageManager();
    Publisher publisher = new PublisherImpl(config, manager, "run1", "pipeline1");
    Connector connector = new XMLConnector(config);
    connector.execute(publisher);

    List<Document> docs = manager.getSavedDocumentsSentForProcessing();

    assertEquals(2, docs.size());

    assertTrue(docs.get(0).has("xml"));
  }
}
