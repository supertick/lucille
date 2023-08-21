package com.kmwllc.lucille.connector.jdbc;

import com.kmwllc.lucille.connector.AbstractConnector;
import com.kmwllc.lucille.core.ConnectorException;
import com.kmwllc.lucille.core.Document;
import com.kmwllc.lucille.core.Publisher;
import com.typesafe.config.Config;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Database Connector - This connector can run a select statement and return the rows 
 * from the database as documents which are published to a topic for processing.
 * If "otherSQLs" are set, the sql and otherSQLs must all be ordered by their join key
 * and the otherJoinFields must be populated.  If those parameters are populated
 * this connector will run the otherSQL statements in parallel and flatten the rows from
 * the otherSQL statements onto the Document as a child document
 * 
 * Note: currently this connector with otherSQL statements only supports integers as a 
 * join key.
 *
 * @author kwatters
 *
 */
public class DatabaseConnector extends AbstractConnector {

  private static final Logger log = LogManager.getLogger(DatabaseConnector.class);

  private String driver;
  private String connectionString;
  private String jdbcUser;
  private String jdbcPassword;
  private Integer fetchSize = null;
  private String preSql;
  private String sql;
  private String postSql;
  private String idField;
  private List<String> otherSQLs = new ArrayList<String>();
  private List<String> otherJoinFields;
  private List<Connection> connections = new ArrayList<Connection>();
  // TODO: consider moving this down to the base connector class.
  private ConnectorState state = null;

  // The constructor that takes the config.
  public DatabaseConnector(Config config) {
    super(config);

    driver = config.getString("driver");
    connectionString = config.getString("connectionString");
    jdbcUser = config.getString("jdbcUser");
    jdbcPassword = config.getString("jdbcPassword");
    sql = config.getString("sql");
    idField = config.getString("idField");
    // For MYSQL this should be set to Integer.MIN_VALUE to avoid buffering the full resultset in memory.
    // The behavior of this parameter varies from driver to driver, often it defaults to 0.
    if (config.hasPath("fetchSize"))
      fetchSize = config.getInt("fetchSize");
    if (config.hasPath("preSql")) {
      preSql = config.getString("preSql");
    }
    if (config.hasPath("postSql")) {
      postSql = config.getString("postSql");
    }
    if (config.hasPath("otherSQLs")) {
      otherSQLs = config.getStringList("otherSQLs");
      otherJoinFields = config.getStringList("otherJoinFields");
    }
  }

  // create a jdbc connection
  private Connection createConnection() throws ClassNotFoundException, SQLException {
    Connection connection = null;
    try {
      Class.forName(driver);
    } catch (ClassNotFoundException e) {
      log.error("Driver not found {} check classpath to make sure the jdbc driver jar file is there.", driver);
      setState(ConnectorState.ERROR);
      throw (e);
    }
    try {
      connection = DriverManager.getConnection(connectionString, jdbcUser, jdbcPassword);
    } catch (SQLException e) {
      log.error("Unable to connect to database {} user:{}", connectionString, jdbcUser);
      setState(ConnectorState.ERROR);
      throw (e);
    }
    connections.add(connection);
    return connection;
  }

  private void setState(ConnectorState newState) {
    this.state = newState;
  }

  @Override
  public void execute(Publisher publisher) throws ConnectorException {

    try {
      setState(ConnectorState.RUNNING);
      // connect to the database.
      Connection connection = createConnection();
      // run the pre-sql (if specified)
      runSql(connection, preSql);
      // TODO: make sure we cleanup result set/statement/connections properly.
      ResultSet rs = null;
      log.info("Running primary sql");
      try {
        Statement state = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        if (fetchSize != null) {
          state.setFetchSize(fetchSize);
        }
        rs = state.executeQuery(sql);
      } catch (SQLException e) {
        setState(ConnectorState.ERROR);
        throw(e);
      }

      log.info("Describing primary set...");
      String[] columns = getColumnNames(rs);
      int idColumn = -1;
      for (int i = 0; i < columns.length; i++) {
        if (columns[i].equalsIgnoreCase(idField)) {
          idColumn = i + 1;
          break;
        }
      }

      ArrayList<ResultSet> otherResults = new ArrayList<ResultSet>();
      ArrayList<String[]> otherColumns = new ArrayList<String[]>();
      for (String otherSQL : otherSQLs) {
        log.info("Describing other result set... {}", otherSQL );
        // prepare the other sql query

        // TODO: run all sql statements in parallel.
        ResultSet rs2 = runJoinSQL(otherSQL);
        String[] columns2 = getColumnNames(rs2);
        otherResults.add(rs2);
        otherColumns.add(columns2);
      }
      log.info("Processing rows...");
      while (rs.next()) {
        // Need the ID column from the RS.
        String id = createDocId(rs.getString(idColumn));
        Document doc = Document.create(id);
        // Add each column / field name to the doc
        for (int i = 1; i <= columns.length; i++) {
          // TODO: how do we normalize our column names?  (lowercase is probably ok and likely desirable as 
          // sometimes databases return columns in upper/lower case depending on which db you talk to.)
          String fieldName = columns[i-1].toLowerCase();
          if (i == idColumn && Document.ID_FIELD.equals(fieldName)) {
            // we already have this column because it's the id column.
            continue;
          }
          // log.info("Add Field {} Value {} -- Doc {}", columns[i-1].toLowerCase(), rs.getString(i), doc);
          String fieldValue = rs.getString(i);
          if (fieldValue != null) {
            doc.setOrAdd(fieldName, fieldValue);
          }
        }
        if (!otherResults.isEmpty()) {
          // this is the primary key that the result set is ordered by.
          Integer joinId = rs.getInt(idField);
          int childId = -1;
          int j = 0;
          for (ResultSet otherResult : otherResults) {
            iterateOtherSQL(otherResult, otherColumns.get(j), doc, joinId, childId, otherJoinFields.get(j));
            j++;
          }
        }
        // feed the accumulated document.
        publisher.publish(doc);
      }

      // close all results
      rs.close();
      for (ResultSet ors : otherResults) {
        ors.close();
      }
      // the post sql.
      runSql(connection, postSql);
      flush();
      setState(ConnectorState.STOPPED);
    } catch (Exception e) {
      setState(ConnectorState.ERROR);
      throw new ConnectorException("Exception caught during connector execution", e);
    }
  }

  private void flush() {
    // TODO: possibly move to base class / interface
    // lifecycle to be called after the last doc is processed..
    // in case the connector is doing some batching to make sure it flushes the last batch.
    // System.err.println("No Op flush for now.");
  }

  private void iterateOtherSQL(ResultSet rs2, String[] columns2, Document doc, Integer joinId, int childId, String joinField) throws SQLException {
    // Test if we need to advance or if we should read the current row ...
    if (rs2.isBeforeFirst()) {
      // we need to at least advance to the first row.
      rs2.next();    
    }
    // Test if this resultset is alread exhausted.
    if (rs2.isAfterLast()) {
      // um.. why is this getting called?  if it is?
      return;
    }
      
    do {
      // Convert to do-while i think we can avoid the rs2.previous() call.

      // TODO: support non INT primary key
      Integer otherJoinId = rs2.getInt(joinField);
      if (otherJoinId < joinId) {
        // advance until we get to the id on the right side that we want.
        rs2.next();
        continue;
      }

      if (otherJoinId > joinId) {
        // we've gone too far.. lets back up and break out , move forward the primary result set.
        // we should leave the cursor here so we can test again when the primary result set is advanced.
        return;
      }
      
      // here we have a match for the join keys.. let's create the child doc for this joined row.
      childId++;
      Document child = Document.create(Integer.toString(childId));
      for (String c : columns2) {
        String fieldName = c.trim().toLowerCase();
        String fieldValue = rs2.getString(c);
        if (fieldValue != null) {
          child.setOrAdd(fieldName, fieldValue);
        }
      }
      // add the accumulated child doc.

      // add the accumulated rows to the document.
      doc.addChild(child);
      // Ok.. so now we need to advance this cursor and see if there is another row to collapse into a child.
    } while (rs2.next());

  }

  // TODO: can we remove this method and just use runSql instead?
  private ResultSet runJoinSQL(String sql) throws SQLException {
    ResultSet rs2 = null;
    try {
      // Running the sql
      log.info("Running other sql");
      // create a new connection instead of re-using this one because we're using forward only resultsets
      Connection connection = null;
      try {
        // TODO: clean up this connection .. we need to hold onto a handle of it
        connection = createConnection();
      } catch (ClassNotFoundException e) {
        setState(ConnectorState.ERROR);
        log.error("Error creating connection.",e);
      }
      Statement state2 = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
      // Statement state2 = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
      // make sure we stream the results instead of buffering in memory.
      // TODO: this doesn't work for h2 db..  it does work for mysql..  *shrug* 
      // Mysql needs this hint so it the mysql driver doesn't try to buffer the entire resultset in memory.
      if (fetchSize != null) {
        state2.setFetchSize(fetchSize);
      }
      rs2 = state2.executeQuery(sql);
    } catch (SQLException e) {
      e.printStackTrace();
      setState(ConnectorState.ERROR);
      throw (e);
    }
    log.info("Other SQL Executed.");
    return rs2;
  }

  /**
   * Return an array of column names.
   */
  private String[] getColumnNames(ResultSet rs) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    String[] names = new String[meta.getColumnCount()];
    for (int i = 0; i < names.length; i++) {
      names[i] = meta.getColumnLabel(i + 1).toLowerCase();
      log.info("column {} ", names[i]);
    }
    return names;
  }

  private void runSql(Connection connection, String sql) {
    if (!StringUtils.isEmpty(sql)) {
      try (Statement state = connection.createStatement()) {
        state.executeUpdate(sql);
      } catch (SQLException e) {
        log.error("Error running Update SQL {}", sql, e);
        // TODO: maybe we should throw here?
      }
    }
  }

  //@Override
  public void stop() {
    // TODO: move this to a base class..
    setState(ConnectorState.STOPPED);
  }

  public String getDriver() {
    return driver;
  }

  public void setDriver(String driver) {
    this.driver = driver;
  }

  public String getConnectionString() {
    return connectionString;
  }

  public void setConnectionString(String connectionString) {
    this.connectionString = connectionString;
  }

  public String getJdbcUser() {
    return jdbcUser;
  }

  public void setJdbcUser(String jdbcUser) {
    this.jdbcUser = jdbcUser;
  }

  public String getJdbcPassword() {
    return jdbcPassword;
  }

  public void setJdbcPassword(String jdbcPassword) {
    this.jdbcPassword = jdbcPassword;
  }

  public String getPreSql() {
    return preSql;
  }

  public void setPreSql(String preSql) {
    this.preSql = preSql;
  }

  public String getSql() {
    return sql;
  }

  public void setSql(String sql) {
    this.sql = sql;
  }

  public String getPostSql() {
    return postSql;
  }

  public void setPostSql(String postSql) {
    this.postSql = postSql;
  }

  public String getIdField() {
    return idField;
  }

  public void setIdField(String idField) {
    this.idField = idField;
  }

  @Override
  public void close() throws ConnectorException {
    for (Connection connection : connections) {
      if (connection == null) {
        // no-op
        continue;
      }
      try {
        connection.close();
      } catch (SQLException e) {
        // We don't care if we can't close the connection.
        continue;
      }
    }
    // empty out the collections
    connections = new ArrayList<Connection>();
  }

  // for testing purposes
  // return true if any connection is open to the database
  public boolean isClosed() {
    if (connections.size() == 0) {
      return true;
    }
    for (Connection connection : connections) {
      try {
        if (!connection.isClosed()) {
          return false;
        }
      } catch (SQLException e) {
        log.error("Unable to check if connection was closed", e);
        return false;
      }
    }
    return true;
  }

}
