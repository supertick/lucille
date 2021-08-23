package com.kmwllc.lucille.stage;

import com.kmwllc.lucille.core.Document;
import com.kmwllc.lucille.core.Stage;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static junit.framework.TestCase.*;


@RunWith(JUnit4.class)
public class MatchQueryTest extends BaseStageTest {

  @Test
  public void testMatchQueryStage() throws Exception {
    Stage matchQueryStage = loadConfig("MatchQueryTest/config.conf");
    
    assertEquals("MatchQuery stage should be part of the configuration.", matchQueryStage.getClass(), MatchQuery.class);
    String matchedQueriesField = getConfig().getConfigList("stages").get(0).getString("matchedQueriesField");

    Document d1 = new Document("d1");
    d1.setField("content", "giraffe");
    matchQueryStage.processDocument(d1);
    assertTrue("One query should have matched document d1. ", d1.has(matchedQueriesField) && d1.getStringList(matchedQueriesField).size() == 1);
    assertTrue("d1 should match only query2", d1.getStringList(matchedQueriesField).contains("query2"));

    Document d2 = new Document("d2");
    d2.setField("content", "giraffe test foo");
    matchQueryStage.processDocument(d2);
    assertTrue("Two queries should have matched document d2. ", d2.has(matchedQueriesField) && d2.getStringList(matchedQueriesField).size() == 2);
    assertTrue("d2 should match query1", d2.getStringList(matchedQueriesField).contains("query1"));
    assertTrue("d2 should match query2", d2.getStringList(matchedQueriesField).contains("query2"));

    Document d3 = new Document("d3");
    d3.setField("content", "test");
    d3.setField("table", "geotrans");
    matchQueryStage.processDocument(d3);
    assertTrue("Two queries should have matched document d3. ", d3.has(matchedQueriesField) && d3.getStringList(matchedQueriesField).size() == 2);
    assertTrue("d3 should match query2", d3.getStringList(matchedQueriesField).contains("query2"));
    assertTrue("d3 should match not_asia", d3.getStringList(matchedQueriesField).contains("not_asia"));

    Document d4 = new Document("d4");
    d4.setField("content", "foobar");
    matchQueryStage.processDocument(d4);
    assertFalse("No queries should have matched document d4. ", d4.has(matchedQueriesField));

    Document d5 = new Document("d5");
    d5.setField("table", "geotrans");
    d5.setField("country", "japan");
    matchQueryStage.processDocument(d5);
    assertTrue("One query should have matched document d5. ", d5.has(matchedQueriesField) && d5.getStringList(matchedQueriesField).size() == 1);
    assertTrue("d5 should match japan", d5.getStringList(matchedQueriesField).contains("japan"));
  }

}
