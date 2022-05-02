package org.example.teams

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, DispatcherSelector}
import akka.cluster.sharding.typed.HashCodeNoEnvelopeMessageExtractor
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.cluster.typed.Cluster
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route, StandardRoute}
import akka.stream.Materializer
import akka.util.Timeout
import com.datastax.oss.driver.api.core.CqlSession
import com.typesafe.config.{Config, ConfigFactory}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._
import org.example.teams.TeamJsonCoders._
import org.example.teams.request.{AddTeamMemberRequest, CreateTeamRequest}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.jdk.CollectionConverters._
import scala.compat.java8.FutureConverters._

/**
 * @author Denis Pakhomov.
 */
object WebApp extends Directives {

  object RootBehavior {
    def apply(): Behavior[Nothing] = Behaviors.empty
  }

  val config: Config = ConfigFactory.load()
  val appConfig: AppConfig = AppConfig.fromHocon(config)

  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](RootBehavior(), "WebApp", config)

  implicit val executionContext: ExecutionContext = system.dispatchers.lookup(DispatcherSelector.default())
  implicit val timeout: Timeout = 3.seconds

  final val TEAM_SIZE: Int = 3

  val TeamBehaviorTypeKey: EntityTypeKey[TeamBehavior.Command] = EntityTypeKey[TeamBehavior.Command]("TeamBehavior2")

  val TeamBehaviorMessageExtractor: HashCodeNoEnvelopeMessageExtractor[TeamBehavior.Command] =
    new HashCodeNoEnvelopeMessageExtractor[TeamBehavior.Command](numberOfShards = 10) {
      override def entityId(message: TeamBehavior.Command): String = message.teamId.value
  }

  val cluster: Cluster = Cluster(system)

  val sharding: ClusterSharding = ClusterSharding(system)

  private def mapResponse(result: Try[TeamBehavior.Response]): StandardRoute = result match {

    case Success(TeamBehavior.TeamCreated) => complete(StatusCodes.Created)

    case Success(TeamBehavior.TeamUpdated) => complete(StatusCodes.OK)

    case Success(TeamBehavior.TeamState(team)) => complete(
      HttpResponse(
        status = StatusCodes.OK,
        entity = HttpEntity(
          contentType = ContentTypes.`application/json`,
          string = team.asJson.toString()
        )
      )
    )

    case Success(TeamBehavior.InvalidState(message)) => complete(
      HttpResponse(
        status = StatusCodes.Conflict,
        entity = HttpEntity(
          contentType = ContentTypes.`application/json`,
          string = ErrorResponse(
            code = "INVALID_STATE",
            message = message
          )
            .asJson
            .toString()
        )
      )
    )

    case Success(TeamBehavior.TeamNotFound(teamId)) => complete(
      HttpResponse(
        status = StatusCodes.NotFound,
        entity = HttpEntity(
          contentType = ContentTypes.`application/json`,
          string = ErrorResponse(
            code = "NOT_FOUND",
            message = s"team with id=${teamId.value} is not found in DB"
          )
            .asJson
            .toString()
        )
      )
    )

    case Success(TeamBehavior.DbError(message)) => complete(
      HttpResponse(
        status = StatusCodes.Conflict,
        entity = HttpEntity(
          contentType = ContentTypes.`application/json`,
          string = ErrorResponse(
            code = "DB_ERROR",
            message = message
          )
            .asJson
            .toString()
        )
      )
    )

    case Failure(exception) => complete(
      HttpResponse(
        status = StatusCodes.InternalServerError,
        entity = HttpEntity(
          contentType = ContentTypes.`application/json`,
          string = ErrorResponse(
            code = "CRITICAL_ERROR",
            message = exception.getMessage
          )
            .asJson
            .toString()
        )
      )
    )
  }

  def serverRoutes(shardRegion: ActorRef[TeamBehavior.Command]): Route = concat(
    path("teams") {
      post {
        entity(as[CreateTeamRequest]) { request =>

          val result = shardRegion.ask[TeamBehavior.Response](ref =>
            TeamBehavior.CreateTeam(TeamId(request.teamId), request.leader, ref)
          )

          onComplete(result)(mapResponse)
        }
      }
    },
    path("teams" / Segment / "info") { teamId =>
      get {

        val result = shardRegion.ask[TeamBehavior.Response](ref => TeamBehavior.GetState(TeamId(teamId), ref))

        onComplete(result)(mapResponse)
      }
    },
    path("teams" / Segment / "members" / "add") { teamId =>
      put {
        entity(as[AddTeamMemberRequest]) { request =>

          val result = shardRegion.ask[TeamBehavior.Response](ref =>
            TeamBehavior.AddTeamMember(TeamId(teamId), request.memberId, ref)
          )

          onComplete(result)(mapResponse)
        }
      }
    },
    path("teams" / Segment / "members" / "kick") { teamId =>
      put {
        entity(as[AddTeamMemberRequest]) { request =>

          val result = shardRegion.ask[TeamBehavior.Response](ref =>
            TeamBehavior.KickTeamMember(TeamId(teamId), request.memberId, ref)
          )

          onComplete(result)(mapResponse)
        }
      }
    },
    path("teams" / Segment / "start-match") { teamId =>
      put {
        val result = shardRegion.ask[TeamBehavior.Response](ref => TeamBehavior.StartMatch(TeamId(teamId), ref))
        onComplete(result)(mapResponse)
      }
    },
    path("teams" / Segment / "finish-match") { teamId =>
      val result = shardRegion.ask[TeamBehavior.Response](ref => TeamBehavior.FinishMatch(TeamId(teamId), ref))
      onComplete(result)(mapResponse)
    },
    path("teams" / Segment) { teamId =>
      delete {
        val result = shardRegion.ask[TeamBehavior.Response](ref => TeamBehavior.Remove(TeamId(teamId), ref))
        onComplete(result)(mapResponse)
      }
    }
  )

  def startServer(): Unit = {

    val logger = LoggerFactory.getLogger(WebApp.super.getClass)

    logger.info("artery host: {}", config.getString("akka.remote.artery.canonical.hostname"))
    logger.info("artery port: {}", config.getInt("akka.remote.artery.canonical.port"))

    logger.info("web host: {}", appConfig.web.host)
    logger.info("web port: {}", appConfig.web.port)

    implicit val classicSystem: akka.actor.ActorSystem = system.toClassic
    implicit val materializer: Materializer = Materializer.matFromSystem(system)

    val bindingFuture = for {

      session <- CqlSession.builder().addContactPoints(appConfig.cassandra.nodes.asJava).buildAsync().toScala
      teamsRepository <- CassandraTeamRepository.prepare(session)

      shardRegion: ActorRef[TeamBehavior.Command] = sharding.init(
        Entity(TeamBehaviorTypeKey) { context => TeamBehavior(TeamId(context.entityId), teamsRepository) }
          .withMessageExtractor(TeamBehaviorMessageExtractor)
          .withStopMessage(TeamBehavior.GratefulStop)
      )

      routes: Route = serverRoutes(shardRegion)

      binding <- Http().bindAndHandle(handler = routes, interface = appConfig.web.host, port = appConfig.web.port)

    } yield binding

    bindingFuture.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info("Server is online at http://{}:{}", address.getHostString, address.getPort)
      case Failure(exception) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", exception)
        system.terminate()
    }


  }

  def main(args: Array[String]): Unit = {
    startServer()
  }

}
