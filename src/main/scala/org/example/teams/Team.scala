package org.example.teams

import java.util.UUID
import cats.implicits._
import org.example.teams.Team.MAX_SIZE


/**
 * @author Denis Pakhomov.
 */
case class Team(id: TeamId, leader: UUID, playing: Boolean = false, members: Set[UUID] = Set.empty) {

  def allMembers(): Set[UUID] = members + leader

  def size(): Int = members.size + 1

  private def checkNotPlaying(action: => Either[TeamException, Team]): Either[TeamException, Team] =
    if (!playing) action else Either.left(new TeamException("team is playing"))

  def addMember(memberId: UUID): Either[TeamException, Team] = checkNotPlaying {
    if (!allMembers().contains(memberId)) {
      Either.cond(size() < MAX_SIZE, copy(members = members + memberId), new TeamException("team is full"))
    } else this.asRight
  }

  def kickMember(memberId: UUID): Either[TeamException, Team] = checkNotPlaying {
    copy(members = members - memberId).asRight
  }

  def startMatch(): Either[TeamException, Team] =
    Either.cond(size() == MAX_SIZE, copy(playing = true), new TeamException("team is not full"))

  def finishMatch(): Team = copy(playing = false)

}

object Team {
  private val MAX_SIZE: Int = 5
}

case class TeamId(value: String) extends AnyVal

class TeamException(message: String) extends Exception(message)