package org.example.teams.cassandra


import cats.implicits._
import org.slf4j.{Logger, LoggerFactory}
import retry.RetryDetails.{GivingUp, WillDelayAndRetry}
import retry.RetryPolicies._
import retry.{RetryDetails, retryingOnAllErrors}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source

/**
 * @author Denis Pakhomov.
 */
class CassandraSchemaManager(host: String, port: Int)(implicit ec: ExecutionContext) {

  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  private def logError(err: Throwable, details: RetryDetails): Future[Unit] = Future.successful {
    details match {
      case WillDelayAndRetry(_, retries, _) => logger.warn(s"waiting for cassandra, retries: $retries")
      case GivingUp(_, _)                   => logger.error("failed to create cassandra session")
    }
  }

  def startSessionWithRetries: Future[Session] = {
    val retryPolicy = limitRetriesByDelay[Future](120.second, constantDelay(5.seconds))
    retryingOnAllErrors[Session](
      policy = retryPolicy,
      onError = logError
    )(startSession)
  }

  def initSchema(session: Session): Future[Unit] = {

    val statements = Source.fromInputStream(
      getClass.getClassLoader.getResourceAsStream("schema.cql"))
      .getLines()
      .mkString("\n")
      .split(";")
      .toList

    statements.foldLeft(Future.unit)((acc, statement) => acc.flatMap(_ => session.executeAsync(statement).map(_ => ())))

  }

  def startSession: Future[Session] = new Cluster(host, port).connectAsync

}
