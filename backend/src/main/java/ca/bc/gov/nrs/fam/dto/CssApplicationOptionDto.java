package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One selectable application, derived from a CSS integration by fanning out its
 * {@code environments} array.
 *
 * <p>A CSS integration spans environments, where a FAM application has always
 * been per-environment. So an integration named "Forest and Range Evaluation
 * Program" with environments {@code [dev, test]} yields two options here.
 *
 * <p>The pair {@code (integrationId, environment)} is the identifier - there is
 * no single id, which is why callers pass both on every downstream call.
 */
public record CssApplicationOptionDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer integrationId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String environment,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
    String status,

    /**
     * Whether this option is FAM's own CSS integration.
     *
     * <p>FAM administers itself through the same picker every other application
     * uses, but it is not an ordinary application: its roles are
     * {@code FAM_ADMIN} plus the {@code APP_ADMIN_<id>_<ENV>} and
     * {@code DELEGATED_ADMIN_...} roles FAM generates to record who administers
     * everything else. Screens that would treat those as application roles have
     * to know to leave them alone, and this is how they tell.
     *
     * <p>True for every environment of that integration - dev, test and prod are
     * all the same integration.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean famApplication) {}
