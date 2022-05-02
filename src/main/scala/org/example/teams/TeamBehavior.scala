package org.example.teams

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, LogOptions}
import org.example.teams.TeamBehavior._
import org.slf4j.event.Level
import cats.implicits._

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

/**
 * @author Denis Pakhomov.
 */
class TeamBehavior(
  id: TeamId,
  context: ActorContext[TeamBehavior.Command],
  buffer: StashBuffer[TeamBehavior.Command],
  timer: TimerScheduler[TeamBehavior.Command],
  repository: TeamsRepository
) {

  private def loading(): Behavior[TeamBehavior.Command] = {

    context.pipeToSelf(repository.findById(id)) {
      case Success(Some(team))  => TeamLoadedFromDb(id, team)
      case Success(None)        => TeamNotFoundInDb(id)
      case Failure(exception)   => TeamDbError(id, exception.getMessage)
    }

    Behaviors.receiveMessage {

      case TeamLoadedFromDb(_, team)  => buffer.unstashAll(existing(team))
      case TeamNotFoundInDb(_)        => buffer.unstashAll(notExisted())

      case TeamDbError(_, errorMsg)   =>
        timer.startSingleTimer(Reload, FiniteDuration(5, TimeUnit.SECONDS))
        buffer.unstashAll(failed(errorMsg))

      case other =>
        buffer.stash(other)
        Behaviors.same
    }
  }

  private def failed(errorMessage: String): Behavior[TeamBehavior.Command] = Behaviors.receiveMessage {

    case message: ReplyCommand =>
      message.replyTo ! DbError(errorMessage)
      Behaviors.same

    case Reload => buffer.unstashAll(loading())

    case GratefulStop => Behaviors.stopped

    case _ => Behaviors.same
  }

  private def teamUpdate[TCommand <: Command](
    team: Team,
    command: TCommand
  )(action: (Team, TCommand) => Either[Throwable, Team]): Behavior[Command] = {
    action(team, command) match {

      case Right(updatedTeam) if team != updatedTeam =>

        context.pipeToSelf(repository.update(updatedTeam)) {
          case Success(_)         => TeamUpdatedInDb(team.id)
          case Failure(exception) => TeamDbError(team.id, exception.getMessage)
        }

        updating(updatedTeam, command)

      case Right(updatedTeam) =>
        command.reply.foreach(ref => ref ! TeamUpdated)
        existing(updatedTeam)

      case Left(exception) =>
        command.reply.foreach(ref => ref ! InvalidState(exception.getMessage))
        existing(team)
    }
  }

  private def updating(updatedTeam: Team, command: Command): Behavior[Command] = Behaviors.receiveMessage {

    case TeamUpdatedInDb(_) =>
      command.reply.foreach(ref => ref ! TeamUpdated)
      buffer.unstashAll(existing(updatedTeam))

    case TeamDbError(_, message) =>
      timer.startSingleTimer(Reload, FiniteDuration(5, TimeUnit.SECONDS))
      buffer.unstashAll(failed(message))

    case c: Command =>
      buffer.stash(c)
      Behaviors.same
  }

  private def existing(team: Team): Behavior[TeamBehavior.Command] = Behaviors.receiveMessage {

    case GetState(_, replyTo) =>
      replyTo ! TeamState(team)
      Behaviors.same

    case command: AddTeamMember   => teamUpdate(team, command) { (team, cmd) => team.addMember(cmd.member) }
    case command: KickTeamMember  => teamUpdate(team, command) { (team, cmd) => team.kickMember(cmd.member) }
    case command: StartMatch    => teamUpdate(team, command) { (team, _) => team.startMatch() }
    case command: FinishMatch   => teamUpdate(team, command) { (team, _) => team.finishMatch().asRight }

    case GratefulStop => Behaviors.stopped

    case c: ReplyCommand =>
      c.replyTo ! InvalidState("invalid state for command")
      Behaviors.same

    case _ => Behaviors.same

  }

  private def creating(team: Team, replyTo: ActorRef[TeamBehavior.Response]): Behavior[TeamBehavior.Command] =
    Behaviors.receiveMessage {

      case TeamCreatedInDb(_) =>
        replyTo ! TeamCreated
        buffer.unstashAll(existing(team))

      case TeamExistsInDb(teamId) =>
        replyTo ! InvalidState(s"team ${teamId.value} already created")
        Behaviors.stopped

      case TeamDbError(_, errorMsg) =>
        replyTo ! DbError(errorMsg)
        buffer.unstashAll(notExisted())

      case other =>
        buffer.stash(other)
        Behaviors.same

    }

  private def notExisted(): Behavior[TeamBehavior.Command] = Behaviors.receiveMessage {

    case CreateTeam(_, leader, replyTo) =>

      val team = Team(id, leader)

      context.pipeToSelf(repository.insert(team)) {
        case Success(true)      => TeamCreatedInDb(id)
        case Success(false)     => TeamExistsInDb(id)
        case Failure(exception) => TeamDbError(id, exception.getMessage)
      }

      creating(team, replyTo)

    case GratefulStop => Behaviors.stopped

    case c: ReplyCommand =>
      c.replyTo ! TeamNotFound(c.teamId)
      Behaviors.same

    case _ => Behaviors.same
  }

}

object TeamBehavior {

  def apply(teamId: TeamId, repo: TeamsRepository): Behavior[Command] = Behaviors.logMessages(
    logOptions = LogOptions().withLevel(Level.DEBUG),
    behavior = Behaviors.withStash(100) { buffer =>
      Behaviors.withTimers { timer =>
        Behaviors.setup { context =>
          new TeamBehavior(teamId, context, buffer, timer, repo).loading()
        }
      }
    }
  )


  sealed trait Command extends CborSerializable {
    def teamId: TeamId
    def reply: Option[ActorRef[Response]] = None
  }

  sealed trait ReplyCommand extends Command {
    def replyTo: ActorRef[Response]
    override def reply: Option[ActorRef[Response]] = Some(replyTo)
  }

  case object GratefulStop extends Command {
    override def teamId: TeamId = TeamId("")
  }

  case object Reload extends Command {
    override def teamId: TeamId = TeamId("")
  }

  case class CreateTeam(teamId: TeamId, leader: UUID, replyTo: ActorRef[Response]) extends ReplyCommand
  case class AddTeamMember(teamId: TeamId, member: UUID, replyTo: ActorRef[Response]) extends ReplyCommand
  case class KickTeamMember(teamId: TeamId, member: UUID, replyTo: ActorRef[Response]) extends ReplyCommand
  case class StartMatch(teamId: TeamId, replyTo: ActorRef[Response]) extends ReplyCommand
  case class FinishMatch(teamId: TeamId, replyTo: ActorRef[Response]) extends ReplyCommand
  case class Remove(teamId: TeamId, replyTo: ActorRef[Response]) extends ReplyCommand
  case class GetState(teamId: TeamId, replyTo: ActorRef[Response]) extends ReplyCommand

  case class TeamLoadedFromDb(teamId: TeamId, team: Team) extends Command
  case class TeamCreatedInDb(teamId: TeamId) extends Command
  case class TeamExistsInDb(teamId: TeamId) extends Command
  case class TeamUpdatedInDb(teamId: TeamId) extends Command
  case class TeamNotFoundInDb(teamId: TeamId) extends Command
  case class TeamDbError(teamId: TeamId, errorMsg: String) extends Command

  sealed trait Response extends CborSerializable

  case class TeamNotFound(teamId: TeamId) extends Response
  case class InvalidState(message: String) extends Response
  case class DbError(message: String) extends Response

  case object TeamCreated extends Response
  case object TeamUpdated extends Response
  case class TeamState(team: Team) extends Response

}
