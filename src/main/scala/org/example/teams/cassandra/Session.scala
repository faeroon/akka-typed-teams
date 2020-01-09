package org.example.teams.cassandra

import com.datastax.driver.core.{ResultSet, Statement, Session => CassandraSession}
import ListenableFutureSyntax._

import scala.concurrent.Future

/**
 * @author Denis Pakhomov.
 */
class Session(session: CassandraSession) {

  def executeAsync(statement: Statement): Future[ResultSet] = session.executeAsync(statement).toScalaFuture

  def executeAsync(query: String): Future[ResultSet] = session.executeAsync(query).toScalaFuture

  def execute(statement: Statement): ResultSet = session.execute(statement)

  def execute(query: String): ResultSet = session.execute(query)

  def close(): Unit = session.close()

}
