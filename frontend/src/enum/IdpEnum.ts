export enum IdpProvider {
    IDIR = 'IDIR',
    BCEIDBUSINESS = 'Business BCeID',
}

/**
 * The `identity_provider` claim, as AuthProvider stores it - lower-cased.
 *
 * Distinct from IdpProvider above, which is how a provider is *labelled* on
 * screen. These are the values compared against, and the two must not be
 * confused: "Business BCeID" is what a person reads, "bceidbusiness" is what the
 * token says.
 */
export const IDP_CLAIM = {
    IDIR: "idir",
    BUSINESS_BCEID: "bceidbusiness",
} as const;
