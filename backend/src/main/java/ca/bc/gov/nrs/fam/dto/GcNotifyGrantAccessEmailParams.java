package ca.bc.gov.nrs.fam.dto;

import java.util.List;

/**
 * Inputs for the "access granted" notification.
 *
 * @param applicationDescription shown to the recipient as the application name.
 *     The template variable is called {@code application_name}, but upstream
 *     deliberately supplies the description because it is the human-readable one.
 * @param organizationList forest clients the role was scoped to, or null when the
 *     role has no scope. Null and empty mean different things here - see
 *     {@code GcNotifyEmailService}.
 */
public record GcNotifyGrantAccessEmailParams(
    String userName,
    String firstName,
    String lastName,
    String applicationDescription,
    String roleDisplayName,
    List<FamForestClientDto> organizationList,
    String applicationTeamContactEmail,
    String sendToEmail) {}
