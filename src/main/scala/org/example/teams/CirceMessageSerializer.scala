package org.example.teams

import java.nio.charset.StandardCharsets

import akka.actor.ExtendedActorSystem
import akka.actor.typed.ActorRefResolver
import akka.serialization.SerializerWithStringManifest
import akka.actor.typed.scaladsl.adapter._
import io.circe.syntax._
import io.circe.parser._
import cats.implicits._
import io.circe.DecodingFailure

import org.example.teams.TeamJsonCoders._

/**
 * @author Denis Pakhomov.
 */
class CirceMessageSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {

  private implicit val actorRefResolver: ActorRefResolver = ActorRefResolver(system.toTyped)

  private val TeamBehaviorCommandManifest = "TEAM_BEHAVIOR_COMMAND"
  private val TeamBehaviorResponseManifest = "TEAM_BEHAVIOR_RESPONSE"

  private val charset = StandardCharsets.UTF_8.name()

  override def identifier: Int = 42

  override def manifest(msg: AnyRef): String = msg match {
    case _: TeamBehavior.Command  => TeamBehaviorCommandManifest
    case _: TeamBehavior.Response => TeamBehaviorResponseManifest

    case _ => throw new IllegalArgumentException(s"can't find manifest for ${msg.getClass}")
  }

  override def toBinary(msg: AnyRef): Array[Byte] = msg match {

    case c: TeamBehavior.Command => c.asJson.noSpaces.getBytes(charset)
    case r: TeamBehavior.Response => r.asJson.noSpaces.getBytes(charset)

    case _ => throw new IllegalArgumentException(s"can't serialize with Circe ${msg.getClass}")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {

    val rawJson = new String(bytes, charset)

    val decodeResult = manifest match {

      case TeamBehaviorCommandManifest  => decode[TeamBehavior.Command](rawJson)
      case TeamBehaviorResponseManifest => decode[TeamBehavior.Response](rawJson)

      case _ => Either.left(DecodingFailure(s"can't find decoder for manifest $manifest", List()))
    }

    decodeResult match {
      case Right(value) => value
      case Left(exception) => throw exception
    }

  }
}
