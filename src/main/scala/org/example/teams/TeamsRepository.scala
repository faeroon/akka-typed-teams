package org.example.teams

import scala.concurrent.Future

/**
 * @author Denis Pakhomov.
 */
trait TeamsRepository {

  def findById(id: TeamId): Future[Option[Team]]

  def insert(team: Team): Future[Boolean]

  def update(team: Team): Future[Unit]

  def delete(id: TeamId): Future[Unit]

}
