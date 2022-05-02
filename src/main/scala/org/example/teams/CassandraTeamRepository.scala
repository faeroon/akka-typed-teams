package org.example.teams
import cats.MonadError
import cats.data.OptionT
import com.datastax.oss.driver.api.core.{ConsistencyLevel, CqlSession}
import com.datastax.oss.driver.api.core.cql.{PreparedStatement, Row, SimpleStatement}
import com.datastax.oss.driver.api.querybuilder.QueryBuilder
import org.example.teams.CassandraTeamRepository.{ID_FIELD, STATE_FIELD, mapToTeam}
import cats.implicits._
import org.example.teams.TeamJsonCoders._
import io.circe.parser._
import io.circe.syntax._

import scala.concurrent.{ExecutionContext, Future}
import scala.compat.java8.FutureConverters._

/**
 * @author Denis Pakhomov.
 */
class CassandraTeamRepository(
  private val session: CqlSession,
  private val selectQuery: PreparedStatement,
  private val insertQuery: PreparedStatement,
  private val updateQuery: PreparedStatement,
  private val deleteQuery: PreparedStatement
)(implicit ec: ExecutionContext) extends TeamsRepository {
  override def findById(id: TeamId): Future[Option[Team]] = {

    val bound = selectQuery.boundStatementBuilder()
      .setString(ID_FIELD, id.value)
      .build()

    val result = for {
      row   <- OptionT(session.executeAsync(bound).toScala.map(res => Option(res.one())))
      team  <- OptionT(MonadError[Future, Throwable].fromEither(mapToTeam(row)).map(_.some))
    } yield team

    result.value
  }

  override def insert(team: Team): Future[Boolean] = {

    val bound = insertQuery.boundStatementBuilder()
      .setString(ID_FIELD, team.id.value)
      .setString(STATE_FIELD, team.asJson.noSpaces)
      .build()

    session.executeAsync(bound).toScala.map(_.wasApplied())
  }

  override def update(team: Team): Future[Unit] = {

    val bound = updateQuery.boundStatementBuilder()
      .setString(ID_FIELD, team.id.value)
      .setString(STATE_FIELD, team.asJson.noSpaces)
      .build()

    session.executeAsync(bound).toScala.map(_ => ())
  }

  override def delete(id: TeamId): Future[Unit] = {
    val bound = deleteQuery.boundStatementBuilder().setString(ID_FIELD, id.value).build()
    session.executeAsync(bound).toScala.map(_ => ())
  }
}

object CassandraTeamRepository {

  private val KEYSPACE: String = "teams"
  private val TABLE: String = "teams"

  private val ID_FIELD: String = "id"
  private val STATE_FIELD: String = "state"

  private def mapToTeam(row: Row): Either[Exception, Team] = for {
    stateJson <- Either.fromOption(Option(row.getString(STATE_FIELD)), new Exception("team state is undefined"))
    team <- decode[Team](stateJson)
  } yield team

  private val SELECT_QUERY: SimpleStatement = SimpleStatement.builder(
    QueryBuilder.selectFrom(KEYSPACE, TABLE)
      .columns(ID_FIELD, STATE_FIELD)
      .whereColumn(ID_FIELD).isEqualTo(QueryBuilder.bindMarker(ID_FIELD))
      .build()
  ).setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    .build()

  private val INSERT_QUERY: SimpleStatement = SimpleStatement.builder(
    QueryBuilder.insertInto(KEYSPACE, TABLE)
      .value(ID_FIELD, QueryBuilder.bindMarker(ID_FIELD))
      .value(STATE_FIELD, QueryBuilder.bindMarker(STATE_FIELD))
      .ifNotExists()
      .build()
  ).setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    .build()

  private val UPDATE_QUERY: SimpleStatement = SimpleStatement.builder(
    QueryBuilder.update(KEYSPACE, TABLE)
      .setColumn(STATE_FIELD, QueryBuilder.bindMarker(STATE_FIELD))
      .whereColumn(ID_FIELD).isEqualTo(QueryBuilder.bindMarker(ID_FIELD))
      .build()
  ).setConsistencyLevel(ConsistencyLevel.QUORUM)
    .build()

  private val DELETE_QUERY: SimpleStatement = SimpleStatement.builder(
    QueryBuilder.deleteFrom(KEYSPACE, TABLE)
      .whereColumn(ID_FIELD).isEqualTo(QueryBuilder.bindMarker(ID_FIELD))
      .build()
  ).setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
    .build()

  def prepare(session: CqlSession)(implicit ec: ExecutionContext): Future[CassandraTeamRepository] = (
    session.prepareAsync(SELECT_QUERY).toScala,
    session.prepareAsync(INSERT_QUERY).toScala,
    session.prepareAsync(UPDATE_QUERY).toScala,
    session.prepareAsync(DELETE_QUERY).toScala,
  ).mapN { (select, insert, update, delete) => new CassandraTeamRepository(session, select, insert, update, delete) }
}
