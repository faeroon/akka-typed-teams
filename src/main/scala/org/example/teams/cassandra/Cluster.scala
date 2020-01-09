package org.example.teams.cassandra

import com.datastax.driver.core.{Cluster => CassandraCluster, Session => CassandraSession}
import ListenableFutureSyntax._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * @author Denis Pakhomov.
 */
class Cluster(contactPoint: String, port: Int) {

  private val cluster: CassandraCluster =
    CassandraCluster.builder()
      .addContactPoint(contactPoint)
      .withPort(port)
      .build()

  def connectAsync(implicit ec: ExecutionContext): Future[Session] =
    for {
      initializedCluster <- Future.fromTry(Try{ cluster.init() })
      session <- initializedCluster.connectAsync().toScalaFuture
    } yield new Session(session)

//  def connectAsync(implicit ec: ExecutionContext): Future[Session] =
//    Future.fromTry(
//      Try {
//        cluster.init()
//        cluster
//      }).flatMap()
//      .connectAsync()
//      .toScalaFuture
//      .map(new Session(_))

  def connect(): Session = new Session(cluster.connect())

}
