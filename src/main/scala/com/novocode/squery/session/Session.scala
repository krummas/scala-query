package com.novocode.squery.session

import java.sql.{PreparedStatement, Connection, ResultSet, DatabaseMetaData}
import com.novocode.squery.SQueryException

/**
 * A database session which opens a connection and transaction on demand.
 */
trait Session extends java.io.Closeable { self =>

  def conn: Connection
  def metaData: DatabaseMetaData

  def resultSetType: ResultSetType = ResultSetType.Auto
  def resultSetConcurrency: ResultSetConcurrency = ResultSetConcurrency.Auto
  def resultSetHoldability: ResultSetHoldability = ResultSetHoldability.Auto

  final def prepareStatement(sql: String,
             defaultType: ResultSetType = ResultSetType.ForwardOnly,
             defaultConcurrency: ResultSetConcurrency = ResultSetConcurrency.ReadOnly,
             defaultHoldability: ResultSetHoldability = ResultSetHoldability.Default): PreparedStatement = {
    resultSetHoldability.withDefault(defaultHoldability) match {
      case ResultSetHoldability.Default =>
        conn.prepareStatement(sql, resultSetType.withDefault(defaultType).intValue,
          resultSetConcurrency.withDefault(defaultConcurrency).intValue)
      case h =>
        conn.prepareStatement(sql, resultSetType.withDefault(defaultType).intValue,
          resultSetConcurrency.withDefault(defaultConcurrency).intValue,
          h.intValue)
    }
  }

  final def withPreparedStatement[T](sql: String,
              defaultType: ResultSetType = ResultSetType.ForwardOnly,
              defaultConcurrency: ResultSetConcurrency = ResultSetConcurrency.ReadOnly,
              defaultHoldability: ResultSetHoldability = ResultSetHoldability.Default)(f: (PreparedStatement => T)): T = {
    val st = prepareStatement(sql, defaultType, defaultConcurrency, defaultHoldability)
    try f(st) finally st.close()
  }

  def close(): Unit

  /**
   * Call this method within a <em>withTransaction</em> call to roll back the current
   * transaction after <em>withTransaction</em> returns.
   */
  def rollback(): Unit

  /**
   * Run the supplied function within a transaction. If the function throws an Exception
   * or the session's rollback() method is called, the transaction is rolled back,
   * otherwise it is commited when the function returns.
   */
  def withTransaction[T](f: => T): T

  def forParameters(rsType: ResultSetType = resultSetType, rsConcurrency: ResultSetConcurrency = resultSetConcurrency,
                    rsHoldability: ResultSetHoldability = resultSetHoldability): Session = new Session {
    override def resultSetType = rsType
    override def resultSetConcurrency = rsConcurrency
    override def resultSetHoldability = rsHoldability
    def conn = self.conn
    def metaData = self.metaData
    def close() = self.close()
    def rollback() = self.rollback()
    def withTransaction[T](f: => T) = self.withTransaction(f)
  }
}

class BaseSession private[session] (db: Database) extends Session {

  var open = false
  var doRollback = false
  var inTransaction = false

  lazy val conn = { open = true; db.createConnection() }
  lazy val metaData = conn.getMetaData()

  def close() {
    if(open) conn.close()
  }

  def rollback() {
    if(conn.getAutoCommit) throw new SQueryException("Cannot roll back session in auto-commit mode")
    doRollback = true
  }

  def withTransaction[T](f: => T): T = if(inTransaction) f else {
    inTransaction = true
    conn.setAutoCommit(false)
    try {
      var done = false
      try {
        doRollback = false
        val res = f
        if(doRollback) conn.rollback()
        else conn.commit()
        done = true
        res
      } finally if(!done) conn.rollback()
    } finally {
      inTransaction = false
      conn.setAutoCommit(true)
    }
  }
}
