package com.kmwllc.lucille.stage;

import com.kmwllc.lucille.core.Document;
import com.kmwllc.lucille.core.Stage;
import com.kmwllc.lucille.core.StageException;
import com.kmwllc.lucille.core.UpdateMode;
import com.typesafe.config.Config;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SetStaticValues extends Stage {

  private final Map<String, String> staticValues;
  private final UpdateMode updateMode;

  public SetStaticValues(Config config) {
    super(config);
    staticValues = config.getConfig("static_values").entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, entry -> (String) entry.getValue().unwrapped()));
    updateMode = UpdateMode.fromConfig(config);
  }

  @Override
  public List<Document> processDocument(Document doc) throws StageException {
    staticValues.forEach((fieldName, staticValue) -> doc.update(fieldName, updateMode, staticValue));
    return null;
  }
}