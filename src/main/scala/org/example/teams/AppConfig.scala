package org.example.teams

import com.typesafe.config.Config
import java.net.InetSocketAddress

/**
 * @author Denis Pakhomov.
 */
case class AppConfig(cassandra: CassandraConfig, web: HostConfig)

object AppConfig {
  def fromHocon(config: Config): AppConfig = AppConfig(
    cassandra = CassandraConfig.fromHocon(config.getConfig("cassandra")),
    web       = HostConfig.fromHocon(config.getConfig("web"))
  )
}

case class CassandraConfig(nodes: Vector[InetSocketAddress], keyspace: String)

object CassandraConfig {
  def fromHocon(config: Config): CassandraConfig = CassandraConfig(
    nodes = config.getString("nodes").split(',')
      .map { point =>
        val parts = point.split(':')
        new InetSocketAddress(parts(0), parts(1).toInt) }
      .toVector,
    keyspace = "teams"
  )
}

case class HostConfig(host: String, port: Int)

object HostConfig {
  def fromHocon(config: Config): HostConfig = HostConfig(
    host = config.getString("host"),
    port = config.getInt("port")
  )
}