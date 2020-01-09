package org.example.teams.request

import org.example.teams.Member

/**
 * @author Denis Pakhomov.
 */
case class CreateTeamRequest(teamName: String, leader: Member)
