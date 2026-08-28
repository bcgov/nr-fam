package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An application whose roles the caller may define, as the Manage roles picker
 * offers it.
 *
 * <p>Separate from {@link CssApplicationOptionDto}, which answers a different
 * question: that list is filtered by who may manage <em>access</em>, and a DevOps
 * administrator may manage none. Asking one list to serve both would either hide
 * applications from somebody entitled to them or offer applications whose access
 * guards would refuse - see {@code AuthorizationService.canAdminister}, which
 * exists precisely so the access list and the access guards cannot diverge.
 */
public record CssRoleManagementApplicationDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "6538")
    Integer integrationId,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "dev")
    String environment,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "FREP (DEV)")
    String description,

    /**
     * Whether the caller may define roles in <em>every</em> environment this
     * integration has.
     *
     * <p>Answered here because only the backend can: the list itself carries only
     * the environments this caller may manage, so a DevOps administrator holding
     * DEV alone would otherwise see one environment and conclude they held the
     * integration. It is what decides whether the "create in all environments"
     * button is worth offering - that call writes to all of them.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    boolean everyEnvironment) {}
