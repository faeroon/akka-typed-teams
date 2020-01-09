package org.example.teams
import java.util.UUID

import scala.concurrent.Future
import scala.collection.mutable.{Map => MutableMap}

/**
 * @author Denis Pakhomov.
 */
class StabTeamsRepository extends TeamsRepository {

  private val map: MutableMap[TeamId, Team] = MutableMap(
    TeamId("test") -> Team(id = TeamId("test"), leader = Member(UUID.randomUUID(), name = "test_leader"))
  )

  override def findById(id: TeamId): Future[Option[Team]] = Future.successful(map.get(id))

  override def insert(team: Team): Future[Unit] = Future.successful(map.put(team.id, team))

  override def update(team: Team): Future[Unit] = Future.successful(map.put(team.id, team))

}
