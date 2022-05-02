package org.example.teams

import java.util.UUID
import akka.actor.typed.{ActorRef, ActorRefResolver}
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import org.example.teams.TeamBehavior.Response
import org.example.teams.request.{AddTeamMemberRequest, CreateTeamRequest}

import scala.util.Try

/**
 * @author Denis Pakhomov.
 */
object TeamJsonCoders {

  implicit val customConfiguration: Configuration = Configuration.default.withDefaults.withDiscriminator("_type")

  // UUID
  implicit val uuidDecoder: Decoder[UUID] = Decoder.decodeString.emapTry(str => Try { UUID.fromString(str) })
  implicit val uuidEncoder: Encoder[UUID] = Encoder.encodeString.contramap(_.toString)

  // TeamId
  implicit val teamIdEncoder: Encoder[TeamId] = Encoder.encodeString.contramap(_.value)
  implicit val teamIdDecoder: Decoder[TeamId] = Decoder.decodeString.map(TeamId)

  // Team
  implicit val teamCodec: Codec[Team] = deriveConfiguredCodec[Team]

  // ActorRef
  implicit def actorRefEncoder[P](implicit resolver: ActorRefResolver): Encoder[ActorRef[P]] =
    Encoder.encodeString.contramap(resolver.toSerializationFormat(_))

  implicit def actorRefDecoder[P](implicit resolver: ActorRefResolver): Decoder[ActorRef[P]] =
    Decoder.decodeString.map(resolver.resolveActorRef[P])

  implicit def actorRefCodec[P](implicit resolver: ActorRefResolver): Codec[ActorRef[P]] =
    Codec.from(actorRefDecoder,  actorRefEncoder)

  // TeamBehavior.Command
  implicit def teamBehaviorCommandCodec(implicit codec: Codec[ActorRef[Response]]): Codec[TeamBehavior.Command] =
    deriveConfiguredCodec[TeamBehavior.Command]

  // TeamBehavior.Response
  implicit val teamBehaviorResponseCodec: Codec[TeamBehavior.Response] = deriveConfiguredCodec[TeamBehavior.Response]

  // requests
  implicit val createTeamRequestDecoder: Decoder[CreateTeamRequest] = deriveConfiguredDecoder[CreateTeamRequest]
  implicit val addTeamMemberDecoder: Decoder[AddTeamMemberRequest] = deriveConfiguredDecoder[AddTeamMemberRequest]

  //responses
  implicit val errorResponseEncoder: Encoder[ErrorResponse] = deriveConfiguredEncoder[ErrorResponse]

}
