package org.example.teams.request

import java.util.UUID

/**
 * @author Denis Pakhomov.
 */
case class CreateTeamRequest(teamId: String, leader: UUID)
