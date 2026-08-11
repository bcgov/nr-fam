package ca.bc.gov.nrs.fam.dto;

import ca.bc.gov.nrs.fam.constants.ScopeType;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * A role held by a user, as the external API reports it.
 *
 * <p>Client-scoped child roles are collapsed onto their parent: the caller sees
 * one role with a list of forest client numbers in {@code value}, not one entry
 * per client.
 *
 * <p>camelCase, overriding the application-wide snake_case strategy. The external
 * API is a published contract for downstream applications and was camelCase
 * under FastAPI; the internal API is snake_case.
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ExtRoleWithScopeDto(
    String applicationName,
    String roleName,
    String roleDisplayName,
    /** Null for an unscoped role. FAM only has FOREST_CLIENT scoping today. */
    ScopeType scopeType,
    /** Forest client numbers for a scoped role; empty for an unscoped one. */
    List<String> value) {}
