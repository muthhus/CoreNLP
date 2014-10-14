package edu.stanford.nlp.patterns.surface;

import edu.stanford.nlp.util.Execution;
import edu.stanford.nlp.util.Execution.Option;
import edu.stanford.nlp.util.concurrent.ConcurrentHashIndex;

import java.io.*;
import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by sonalg on 10/8/14.
 */
public class PatternsForEachToken {

  @Option(name = "useDB")
  boolean useDB = false;

  @Option(name = "createTable")
  boolean createTable = false;

  @Option(name = "deleteExisting")
  boolean deleteExisting = false;

  @Option(name = "tableName")
  String tableName = null;

  private Map<String, Map<Integer, Set<Integer>>> patternsForEachToken = null;

  Connection conn;

  public PatternsForEachToken(Properties props, Map<String, Map<Integer, Set<Integer>>> pats) throws SQLException, ClassNotFoundException, IOException {
    Execution.fillOptions(this, props);

    if (useDB) {
      Execution.fillOptions(SQLConnection.class, props);
      conn = SQLConnection.getConnection();
      Execution.fillOptions(SQLConnection.class, props);
      assert tableName != null;
      tableName = tableName.toLowerCase();
      if (createTable && !deleteExisting)
        throw new RuntimeException("Cannot have createTable as true and deleteExisting as false!");
      if (createTable)
        createTable();
    }else
      patternsForEachToken = new ConcurrentHashMap<>();
    if(pats != null)
      addPatterns(pats);
  }

  public PatternsForEachToken(Properties props) throws SQLException, IOException, ClassNotFoundException {
    this(props, null);
  }

  void createTable() throws SQLException, ClassNotFoundException {
    DatabaseMetaData dbm = conn.getMetaData();
    ResultSet tables = dbm.getTables(null, null, tableName, null);
    if (tables.next()) {
      System.out.println("Found table " + tableName);
      if (deleteExisting) {
        System.out.println("deleting table " + tableName);
        Statement stmt = conn.createStatement();
        stmt.executeUpdate("DELETE FROM " + tableName);
      }
    } else {
      Statement stmt = conn.createStatement();
      String query = "create table  IF NOT EXISTS " + tableName + " (\"sentid\" text, \"tokenid\" int, \"patterns\" bytea); ";
      stmt.execute(query);
    }
  }


  public void addPatterns(Map<String, Map<Integer, Set<Integer>>> pats) throws IOException, SQLException {

    PreparedStatement pstmt = null;

    if(useDB) {
     pstmt =getPreparedStmt();
    }

    for (Map.Entry<String, Map<Integer, Set<Integer>>> en : pats.entrySet()) {
      for (Map.Entry<Integer, Set<Integer>> en2 : en.getValue().entrySet()) {
        addPattern(en.getKey(), en2.getKey(), en2.getValue(), pstmt);
        if(useDB)
          pstmt.addBatch();
      }
    }

    if(useDB){
      pstmt.executeUpdate();
      pstmt.close();
    }
  }

  PreparedStatement getPreparedStmt() throws SQLException {
  return conn.prepareStatement("UPDATE " + tableName + " SET patterns = ? WHERE sentid = ? and tokenid = ?; " +
    "INSERT INTO " + tableName + " (sentid, tokenid, patterns) (SELECT ?,?,? WHERE NOT EXISTS (SELECT sentid FROM " + tableName + " WHERE sentid  =? and tokenid=?));");
  }

  public void addPattern(String sentId, int tokenId, Set<Integer> patterns) throws SQLException, IOException {
    PreparedStatement pstmt = null;
    if(useDB)
      pstmt = getPreparedStmt();

    addPattern(sentId, tokenId, patterns, pstmt);

    if(useDB){
      pstmt.executeUpdate();
      pstmt.close();
    }
  }


  public void addPattern(String sentId, int tokenId, Set<Integer> patterns, PreparedStatement pstmt) throws SQLException, IOException {

      if(pstmt != null){
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(patterns);
        byte[] patsAsBytes = baos.toByteArray();
        ByteArrayInputStream bais = new ByteArrayInputStream(patsAsBytes);
        pstmt.setBinaryStream(1, bais, patsAsBytes.length);
        pstmt.setObject(2,sentId);
        pstmt.setInt(3, tokenId);
        pstmt.setString(4,sentId);
        pstmt.setInt(5, tokenId);
        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        ObjectOutputStream oos2 = new ObjectOutputStream(baos2);
        oos2.writeObject(patterns);
        byte[] patsAsBytes2 = baos2.toByteArray();
        ByteArrayInputStream bais2 = new ByteArrayInputStream(patsAsBytes2);
        pstmt.setBinaryStream(6, bais2, patsAsBytes2.length);
        pstmt.setString(7,sentId);
        pstmt.setInt(8, tokenId);
      } else{
        if(!patternsForEachToken.containsKey(sentId))
          patternsForEachToken.put(sentId, new ConcurrentHashMap<Integer, Set<Integer>>());
        patternsForEachToken.get(sentId).put(tokenId, patterns);
      }


  }

  Set<Integer> getPatterns(String sentId, Integer tokenId) throws SQLException, IOException, ClassNotFoundException {
    if(useDB){
      String query = "Select patterns from " + tableName + " where sentid=\'" + sentId + "\' and tokenid = " + tokenId;
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery(query);
      if(rs.next()){
        byte[] st = (byte[]) rs.getObject(1);
        ByteArrayInputStream baip = new ByteArrayInputStream(st);
        ObjectInputStream ois = new ObjectInputStream(baip);
        Set<Integer> pats = (Set<Integer>) ois.readObject();
        return pats;
      }
      else
        return null;
    }
    else
      return patternsForEachToken.get(sentId).get(tokenId);
  }


  Map<Integer, Set<Integer>> getPatternsForAllTokens(String sentId) throws SQLException, IOException, ClassNotFoundException {
    if(useDB){
      Map<Integer, Set<Integer>> pats = new ConcurrentHashMap<Integer, Set<Integer>>();
      String query = "Select tokenid, patterns from " + tableName + " where sentid=\"" + sentId + "\"";
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery(query);
      while(rs.next()){
        byte[] st = (byte[]) rs.getObject(1);
        ByteArrayInputStream baip = new ByteArrayInputStream(st);
        ObjectInputStream ois = new ObjectInputStream(baip);
        Set<Integer> patsToken = (Set<Integer>) ois.readObject();
        pats.put(rs.getInt("tokenid"), patsToken);
      }
      return pats;
    }
    else
      return patternsForEachToken.get(sentId);
  }

  public void writeIndex(ConcurrentHashIndex<SurfacePattern> index){

  }



}
