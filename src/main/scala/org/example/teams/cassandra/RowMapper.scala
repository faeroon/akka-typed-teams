package org.example.teams.cassandra

import com.datastax.driver.core.Row

/**
 * @author Denis Pakhomov.
 */
trait RowMapper[A] {

  def toEntity(row: Row): Either[DbMappingException, A]

}

object RowMapper {

  def toEntity[A](row: Row)(implicit rowMapper: RowMapper[A]): Either[DbMappingException, A] =
    rowMapper.toEntity(row)

}

case class DbMappingException(private val message: String = "", private val cause: Throwable = None.orNull)
  extends Exception(message, cause)
