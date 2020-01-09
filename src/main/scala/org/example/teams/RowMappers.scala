package org.example.teams

import cats.implicits._
import com.datastax.driver.core.Row
import io.circe.parser._
import TeamJsonCoders._
import org.example.teams.cassandra.{DbMappingException, RowMapper}

/**
 * @author Denis Pakhomov.
 */
object RowMappers {
  implicit val teamRowMapper: RowMapper[Team] = (row: Row) => {
    for {
      json <- Either.fromOption(Option(row.getString("state")), DbMappingException("state is undefined"))
      team <- decode[Team](json).leftMap(error => DbMappingException("decoding exception", cause = error))
    } yield team
  }
}
