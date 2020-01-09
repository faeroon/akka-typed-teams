package org.example.teams

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, DispatcherSelector}
import akka.cluster.sharding.typed.HashCodeNoEnvelopeMessageExtractor
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.cluster.typed.Cluster
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route}
import akka.stream.Materializer
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._
import org.example.teams.TeamJsonCoders._
import org.example.teams.cassandra.CassandraSchemaManager
import org.example.teams.request.CreateTeamRequest
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * @author Denis Pakhomov.
 */
object WebApp extends Directives {

  object RootBehavior {
    def apply(): Behavior[Nothing] = Behaviors.empty
  }

  val config: Config = ConfigFactory.load()

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

  def serverRoutes(shardRegion: ActorRef[TeamBehavior.Command]): Route = concat(
    path("teams") {
      post {
        entity(as[CreateTeamRequest]) { request =>

          val result = shardRegion.ask[TeamBehavior.Response](
            ref => TeamBehavior.CreateTeam(TeamId(request.teamName), request.leader, ref)
          )

          onComplete(result) {
            case Success(TeamBehavior.TeamCreated) => complete(StatusCodes.Created)
            case Success(TeamBehavior.InvalidState) => complete(StatusCodes.Forbidden)
            case Success(_) => complete(StatusCodes.BadRequest)
            case Failure(exception) =>
              system.log.info("create team exception", exception)
              complete(StatusCodes.BadRequest)
          }
        }
      }
    },
    path("teams" / Segment / "info") { teamName =>
      get {

        val result = shardRegion.ask[TeamBehavior.Response](ref => TeamBehavior.GetState(TeamId(teamName), ref))

        onComplete(result) {

          case Success(TeamBehavior.TeamState(team)) =>
            complete(HttpResponse(
              status = StatusCodes.OK,
              headers = List(`Content-Type`(`application/json`)),
              entity = team.asJson.toString()
            ))

          case Success(TeamBehavior.InvalidState) =>
            complete(StatusCodes.NotFound)

          case Failure(exception) =>
            system.log.info("get team exception", exception)
            complete(StatusCodes.BadRequest)

          case _ => complete(StatusCodes.NotImplemented)

        }

      }
    }
  )

  def startServer(): Unit = {

    val logger = LoggerFactory.getLogger(WebApp.super.getClass)

    logger.info("artery host: {}", config.getString("akka.remote.artery.canonical.hostname"))
    logger.info("artery port: {}", config.getInt("akka.remote.artery.canonical.port"))

    logger.info("web host: {}", config.getString("web.host"))
    logger.info("web port: {}", config.getInt("web.port"))

    implicit val classicSystem: akka.actor.ActorSystem = system.toClassic
    implicit val materializer: Materializer = Materializer.matFromSystem(system)


    val cassandraManager = new CassandraSchemaManager(
      host = config.getString("cassandra.host"),
      port = config.getInt("cassandra.port")
    )

    val bindingFuture = for {
      session <- cassandraManager.startSessionWithRetries
      _ <- cassandraManager.initSchema(session).recover(_ => ())

      teamsRepository = new CassandraTeamsRepository(session)

      shardRegion: ActorRef[TeamBehavior.Command] = sharding.init(
        Entity(TeamBehaviorTypeKey) { context => TeamBehavior(TeamId(context.entityId), teamsRepository) }
          .withMessageExtractor(TeamBehaviorMessageExtractor)
          .withStopMessage(TeamBehavior.GratefulStop)
      )

      routes: Route = serverRoutes(shardRegion)

      binding <- Http().bindAndHandle(
        handler = routes,
        interface = config.getString("web.host"),
        port = config.getInt("web.port")
      )

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
