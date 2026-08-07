package ca.bc.gov.nrs.fam.dto;

import java.util.List;

/**
 * Who the caller is and what they can do, as returned by the login-bootstrap
 * endpoint.
 *
 * <p>Replaces the information the frontend used to read straight out of the
 * Cognito access token. {@code accessRoles} in particular used to be the
 * {@code cognito:groups} claim; it is now resolved from the database, so it is
 * current rather than fixed at login.
 *
 * <p>Deliberately excludes {@code userGuid} and {@code businessGuid}: the frontend
 * has no use for them and they are identifiers worth not shipping to the browser.
 */
public record SelfDto(
    Long userId,
    String userName,
    String userTypeCode,
    String firstName,
    String lastName,
    String email,
    List<String> accessRoles,
    boolean isDelegatedAdmin,
    boolean requiresAcceptTc) {}
