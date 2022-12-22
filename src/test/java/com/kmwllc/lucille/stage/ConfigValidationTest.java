package com.kmwllc.lucille.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kmwllc.lucille.core.*;
import com.kmwllc.lucille.message.PersistingLocalMessageManager;
import com.typesafe.config.ConfigException;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ConfigValidationTest {

  @Test
  public void testConditions() {

    testException(NoopStage.class, "conditions-field-missing.conf");
    testException(NoopStage.class, "conditions-field-renamed.conf");

    testException(NoopStage.class, "conditions-values-missing.conf");
    testException(NoopStage.class, "conditions-values-renamed.conf");

    testException(NoopStage.class, "conditions-optional-unknown.conf");
    testException(NoopStage.class, "conditions-optional-renamed.conf");
  }

  @Test
  public void testConcatenate() {
    testException(Concatenate.class, "concatenate-dest-missing.conf");
    testException(Concatenate.class, "concatenate-format-missing.conf");
    testException(Concatenate.class, "concatenate-invalid-parent.conf");
    testException(Concatenate.class, "concatenate-unknown-property.conf");
  }


  private static void testException(Class<? extends Stage> stageClass, String config) {
    try {
      Stage stage = StageFactory.of(stageClass).get(addPath(config));
      stage.validateConfigWithConditions();
      fail();
    } catch (StageException e) {
      // expected
    }
  }

  // todo add examples of failures based on the different type of properties

  @Test
  public void testApplyRegex() throws DocumentException, JsonProcessingException {

    Document doc = Document.createFromJson("{\"id\":\"id\",\"true\": \"boolean\"}");

    testMessage(ApplyRegex.class, "apply-regex-invalid-type-1.conf", doc,
      "source has type BOOLEAN rather than LIST");

    testMessage(ApplyRegex.class, "apply-regex-invalid-type-2.conf", doc,
      "regex has type LIST rather than STRING");

    testMessage(ApplyRegex.class, "apply-regex-invalid-type-3.conf", doc,
      "class java.lang.String cannot be cast to class java.lang.Boolean " +
        "(java.lang.String and java.lang.Boolean are in module java.base of loader 'bootstrap')");
  }

  private static void processDoc(Class<? extends Stage> stageClass, String config, Document doc)
    throws StageException {
    Stage s = StageFactory.of(stageClass).get(addPath(config));
    s.start();
    s.processDocument(doc);
  }

  private static String addPath(String config) {
    return "ConfigValidationTest/" + config;
  }

  private static void assertContains(String string, String substring) {
    if (!string.contains(substring)) {
      fail();
    }
  }

  private static void testMessage(Class<? extends Stage> stageClass, String config, Document doc, String message) {
    try {
      processDoc(stageClass, config, doc);
    } catch (StageException e) {

      // e.printStackTrace();
      Throwable cause = e.getCause().getCause();
      assertContains(cause.getMessage(), message);
    }
  }

  @Test
  public void testTestModeException() throws Exception {
    Map<String, PersistingLocalMessageManager> exceptions = Runner.runInTestMode(addPath("pipeline.conf"));
    assertEquals(1, exceptions.size());

    // todo not sure how to add validation in test mode
  }

  @Test
  public void testValidationModeException() throws Exception {
    Map<String, List<Exception>> exceptions = Runner.runInValidationMode(addPath("pipeline.conf"));
    assertEquals(2, exceptions.size());

    List<Exception> exceptions1 = exceptions.get("connector1");
    assertEquals(2, exceptions1.size());

    List<Exception> exceptions2 = exceptions.get("connector2");
    assertEquals(2, exceptions2.size());

    testException(exceptions1.get(0), StageException.class, "com.kmwllc.lucille.stage.NoopStage: " +
      "Stage config contains unknown property invalid_property");

    // TODO note that for the following two exceptions, the fields are retrieved before
    //  the config validation is called
    testException(exceptions1.get(1), ConfigException.Missing.class,
      "No configuration setting found for key 'fields'");

    testException(exceptions2.get(0), ConfigException.Missing.class,
      "No configuration setting found for key 'dest'");

    testException(exceptions2.get(1), StageException.class, "com.kmwllc.lucille.stage.Concatenate: " +
      "Stage config contains unknown property default_inputs3");
  }

  private static void testException(Exception e, Class<? extends Exception> clazz, String message) {
    assertEquals(e.getClass(), clazz);
    assertContains(e.getMessage(), message);
  }
}
