package org.example.teams

import cats.implicits._
import com.datastax.driver.core.ConsistencyLevel
import com.datastax.driver.core.querybuilder.QueryBuilder
import org.example.teams.RowMappers._
import org.example.teams.TeamJsonCoders._
import org.example.teams.cassandra.{RowMapper, Session}
import io.circe.syntax._

import scala.concurrent.{ExecutionContext, Future}

/**
 * @author Denis Pakhomov.
 */
class CassandraTeamsRepository(val session: Session)(implicit ec: ExecutionContext) extends TeamsRepository {

  private val KEYSPACE = "teams"
  private val TABLE = "state"



  override def findById(id: TeamId): Future[Option[Team]] = {

    val query = QueryBuilder.select()
      .from(KEYSPACE, TABLE)
      .where(QueryBuilder.eq("id", id.value))
      .limit(1)
      .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)

    session.executeAsync(query).flatMap { resultSet =>
      Option(resultSet.one()).traverse(row => RowMapper.toEntity[Team](row)) match {
        case Left(error) => Future.failed[Option[Team]](error)
        case Right(team) => Future.successful(team)
      }
    }

  }

  override def insert(team: Team): Future[Unit] = {

    val statement = QueryBuilder.insertInto(KEYSPACE, TABLE)
      .value("id", team.id.value)
      .value("state", team.asJson.noSpaces)
      .ifNotExists()
      .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)

    session.executeAsync(statement)
      .ensure(new Exception("team is already created"))(rs => rs.wasApplied())
      .map(_ => ())

  }

  override def update(team: Team): Future[Unit] = {

    val statement = QueryBuilder.update(KEYSPACE, TABLE)
      .`with`(QueryBuilder.set("state", team.asJson.noSpaces))
      .where(QueryBuilder.eq("id", team.id.value))
      .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)

    session.executeAsync(statement).map(_ => ())

  }

}
