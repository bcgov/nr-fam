import { UserType } from "fam-api/model";
// Searched user type for Granting user
export interface SelectedUser {
    userId: string;
    guid?: string | null;
    firstName?: string | null;
    lastName?: string | null;
    email?: string | null;
    businessLegalName?: string | null;
    businessGuid?: string | null;
    fullName?: string | null;
    sourceDomain?: UserType;
}
