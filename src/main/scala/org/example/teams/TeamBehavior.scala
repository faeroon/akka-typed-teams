package org.example.teams

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.{ActorRef, Behavior, LogOptions}
import org.example.teams.TeamBehavior._
import org.slf4j.event.Level

import scala.util.{Failure, Success}

/**
 * @author Denis Pakhomov.
 */
class TeamBehavior(
  id: TeamId,
  context: ActorContext[TeamBehavior.Command],
  buffer: StashBuffer[TeamBehavior.Command],
  repository: TeamsRepository
) {

  private def loading(): Behavior[TeamBehavior.Command] = {

    context.pipeToSelf(repository.findById(id)) {
      case Success(Some(team))  => TeamLoadedFromDb(id, team)
      case Success(None)        => TeamNotFoundInDb(id)
      case Failure(exception)   => DbError(id, exception.getMessage)
    }

    Behaviors.receiveMessage {

      case TeamLoadedFromDb(_, team)  => buffer.unstashAll(existing(team))
      case TeamNotFoundInDb(_)        => buffer.unstashAll(notExisted())
      case DbError(_, errorMsg)      => throw new Exception(errorMsg)

      case other =>
        buffer.stash(other)
        Behaviors.same
    }

  }

  private def existing(team: Team): Behavior[TeamBehavior.Command] = Behaviors.receiveMessage {

    case GetState(_, replyTo) =>
      replyTo ! TeamState(team)
      Behaviors.same

    case GratefulStop => Behaviors.stopped

    case c: ReplyCommand =>
      c.replyTo ! InvalidState
      Behaviors.same

    case _ => Behaviors.same

  }

  private def creating(team: Team, replyTo: ActorRef[TeamBehavior.Response]): Behavior[TeamBehavior.Command] =
    Behaviors.receiveMessage {

      case TeamCreatedInDb(_) =>
        replyTo ! TeamCreated
        buffer.unstashAll(existing(team))

      case DbError(_, errorMsg) => throw new Exception(errorMsg)

      case other =>
        buffer.stash(other)
        Behaviors.same

    }

  private def notExisted(): Behavior[TeamBehavior.Command] = Behaviors.receiveMessage {

    case CreateTeam(_, leader, replyTo) =>

      val team = Team(id, leader)

      context.pipeToSelf(repository.insert(team)) {
        case Success(_) => TeamCreatedInDb(id)
        case Failure(exception) => DbError(id, exception.getMessage)
      }

      creating(team, replyTo)

    case GratefulStop => Behaviors.stopped

    case c: ReplyCommand =>
      c.replyTo ! InvalidState
      Behaviors.same

    case _ => Behaviors.same
  }

}

object TeamBehavior {

  def apply(teamId: TeamId, repo: TeamsRepository): Behavior[Command] = Behaviors.logMessages(
    logOptions = LogOptions().withLevel(Level.DEBUG),
    behavior = Behaviors.withStash(100) { buffer =>
      Behaviors.setup { context =>
        new TeamBehavior(teamId, context, buffer, repo).loading()
      }
    }
  )


  sealed trait Command extends CborSerializable {
    def teamId: TeamId
  }

  sealed trait ReplyCommand extends Command {
    def replyTo: ActorRef[Response]
  }

  case object GratefulStop extends Command {
    override def teamId: TeamId = TeamId("")
  }

  case class CreateTeam(teamId: TeamId, leader: Member, replyTo: ActorRef[Response]) extends ReplyCommand
  case class GetState(teamId: TeamId, replyTo: ActorRef[Response]) extends ReplyCommand

  case class TeamLoadedFromDb(teamId: TeamId, team: Team) extends Command
  case class TeamCreatedInDb(teamId: TeamId) extends Command
  case class TeamUpdatedInDb(teamId: TeamId) extends Command
  case class TeamNotFoundInDb(teamId: TeamId) extends Command
  case class DbError(teamId: TeamId, errorMsg: String) extends Command

  sealed trait Response extends CborSerializable

  sealed trait ErrorResponse extends Response
  case object InvalidState extends ErrorResponse

  case object TeamCreated extends Response
  case class TeamState(team: Team) extends Response

}
