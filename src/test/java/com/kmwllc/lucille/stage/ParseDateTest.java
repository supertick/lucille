package com.kmwllc.lucille.stage;

import com.kmwllc.lucille.core.Document;
import com.kmwllc.lucille.core.Stage;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.Test;
import static org.junit.Assert.*;

public class ParseDateTest {

  @Test
  public void testParseDate() throws Exception {
    Config config = ConfigFactory.load("ParseDateTest/config.conf");
    Stage stage = new ParseDate(config);
    stage.start();

    // Ensure that dates are correctly extracted
    Document doc = new Document("doc");
    doc.setField("date1", "February 2, 2021");
    stage.processDocument(doc);
    assertEquals("2021-02-02T00:00:00Z", doc.getStringList("output1").get(0));

    // Ensure that several dates can be extracted in one pass, in different formats.
    Document doc2 = new Document("doc2");
    doc2.setField("date1", "2003|10|25");
    doc2.setField("date2", "2020-2050");
    stage.processDocument(doc2);
    assertEquals("2003-10-25T00:00:00Z", doc2.getStringList("output1").get(0));
    assertEquals("2020-01-01T00:00:00Z", doc2.getStringList("output2").get(0));

    // Test parsing dates from a format String
    Document doc3 = new Document("doc3");
    doc3.setField("date1", "90/Jul/17");
    doc3.setField("date2", "2023-06-21");
    stage.processDocument(doc3);
    assertEquals("1990-07-17T00:00:00Z", doc3.getStringList("output1").get(0));
    assertEquals("2023-06-21T00:00:00Z", doc3.getStringList("output2").get(0));
  }

}