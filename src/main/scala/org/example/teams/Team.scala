package org.example.teams

import java.util.UUID


/**
 * @author Denis Pakhomov.
 */
case class Team(id: TeamId, leader: Member, members: Set[Member] = Set.empty)
case class TeamId(value: String) extends AnyVal

case class Member(id: UUID, name: String)