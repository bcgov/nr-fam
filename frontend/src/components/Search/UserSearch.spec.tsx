import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { UserType } from "fam-api/model";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "@/context/auth/AuthContext";
import type { AuthState } from "@/types/AuthTypes";

/**
 * Finding people, and the rules about who may be found.
 *
 * The one worth guarding hardest is self-selection: granting yourself access is
 * the thing an administrator must not quietly do, and it is refused in two
 * places - before the search, and again over whatever the search returned.
 */

const searchIdirUsers = vi.fn();
const bceidLookup = vi.fn();

vi.mock("@/services/ApiServiceFactory", () => ({
    AppActlApiService: {
        idirBceidProxyApi: {
            searchIdirUsers: (
                firstName?: string,
                lastName?: string,
                userId?: string,
                _pageSize?: number,
                environment?: string
            ) => searchIdirUsers(firstName, lastName, userId, environment),
            bceidLookup: (userId: string, environment?: string) =>
                bceidLookup(userId, environment),
        },
    },
    AdminMgmtApiService: {},
}));

const { UserSearch } = await import("./UserSearch");

const IDIR_RESULT = {
    items: [
        {
            userId: "JSMITH",
            guid: "AAAA1111",
            firstName: "Jane",
            lastName: "Smith",
            email: "jane.smith@gov.bc.ca",
        },
        {
            userId: "JSMYTHE",
            guid: "BBBB2222",
            firstName: "John",
            lastName: "Smythe",
            email: "john.smythe@gov.bc.ca",
        },
    ],
};

const renderSearch = (
    props: Partial<Parameters<typeof UserSearch>[0]> = {},
    signedInAs = "ADMINUSER",
    idpProvider?: string
) => {
    const onSelectionChange = props.onSelectionChange ?? vi.fn();
    const authState: AuthState = {
        isAuthenticated: true,
        famLoginUser: {
            username: signedInAs,
            organization: "Timber Co",
            idpProvider,
        },
        isAuthRestored: true,
        accessRoles: ["FAM_ADMIN"],
    };
    const auth: AuthContextValue = {
        authState,
        login: async () => {},
        logout: async () => {},
        ensureFreshToken: async () => {},
        forceRefreshSession: async () => {},
    };
    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const { container } = render(
        <QueryClientProvider client={queryClient}>
            <AuthContext.Provider value={auth}>
                <UserSearch
                    environment="dev"
                    multiUserMode
                    {...props}
                    onSelectionChange={onSelectionChange}
                />
            </AuthContext.Provider>
        </QueryClientProvider>
    );
    return { onSelectionChange, container };
};

/**
 * Carbon's Modal stays mounted when closed - only `is-visible` comes and goes -
 * so `queryByRole("dialog")` finds it either way and would pass on a modal that
 * never opened.
 */
const isResultsModalOpen = () =>
    document.querySelector(".cds--modal.is-visible") !== null;

/** The confirmed selection, as distinct from the same names inside the modal. */
const selectedTable = () =>
    document.querySelector(".user-id-card-table") as HTMLElement;

const search = async (text: string) => {
    await userEvent.type(screen.getByRole("textbox"), text);
    await userEvent.click(screen.getByRole("button", { name: "Search users" }));
};

/**
 * The results modal, once it has actually opened.
 *
 * `findByRole("dialog")` is not enough on its own: Carbon keeps the modal
 * mounted and only toggles `is-visible`, so it resolves the moment the results
 * render - which can be a commit before the modal opens. Asserting the
 * selection in that window saw the rows present and nothing ticked yet.
 */
const openResults = async () => {
    await waitFor(() => expect(isResultsModalOpen()).toBe(true));
    return screen.getByRole("dialog");
};

describe("UserSearch", () => {
    beforeEach(() => {
        searchIdirUsers.mockReset().mockResolvedValue({ data: IDIR_RESULT });
        bceidLookup.mockReset().mockResolvedValue({ data: { found: false } });
    });

    it("searches IDIR by username and offers what came back", async () => {
        renderSearch();

        await search("smith");

        const dialog = await openResults();
        expect(within(dialog).getByText("JSMITH")).toBeInTheDocument();
        expect(within(dialog).getByText("JSMYTHE")).toBeInTheDocument();
        // The term goes in the userId argument for a username search - not the
        // name arguments, which would search the wrong directory column.
        expect(searchIdirUsers).toHaveBeenCalledWith(
            undefined,
            undefined,
            "smith",
            "dev"
        );
    });

    it("searches by first name when that is the chosen type", async () => {
        renderSearch();

        await userEvent.selectOptions(
            screen.getByLabelText("Type"),
            "firstName"
        );
        await search("jane");

        expect(searchIdirUsers).toHaveBeenCalledWith("jane", undefined, undefined, "dev");
    });

    it("reports the chosen people to the form", async () => {
        const { onSelectionChange } = renderSearch();
        await search("smith");

        const dialog = await openResults();
        await userEvent.click(within(dialog).getByLabelText("Select JSMITH"));
        await userEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));

        await waitFor(() =>
            expect(onSelectionChange).toHaveBeenLastCalledWith([
                expect.objectContaining({ userId: "JSMITH", guid: "AAAA1111" }),
            ])
        );
    });

    it("keeps only one person in single-user mode", async () => {
        const { onSelectionChange } = renderSearch({ multiUserMode: false });
        await search("smith");

        const dialog = await openResults();
        // Radios, not checkboxes: that is what makes choosing the second
        // replace the first rather than add to it. Asserted directly because
        // the outcome alone cannot tell the two apart - the parent also slices
        // the confirmed list to one, so a checkbox modal would still report a
        // single user here and the test would pass on a broken dialog.
        expect(
            within(dialog).getByLabelText("Select JSMITH")
        ).toHaveAttribute("type", "radio");

        await userEvent.click(within(dialog).getByLabelText("Select JSMITH"));
        await userEvent.click(within(dialog).getByLabelText("Select JSMYTHE"));
        await userEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));

        await waitFor(() =>
            expect(onSelectionChange).toHaveBeenLastCalledWith([
                expect.objectContaining({ userId: "JSMYTHE" }),
            ])
        );
    });

    it("arrives with a lone result already selected", async () => {
        // One result is not a choice. Making somebody tick it before confirming
        // is a step that can only go one way.
        searchIdirUsers.mockResolvedValue({
            data: { items: [IDIR_RESULT.items[0]] },
        });
        const { onSelectionChange } = renderSearch();

        await search("smith");

        const dialog = await openResults();
        // Waited for, not asserted on the first frame: the tick is applied by an
        // effect, which React runs after the commit that opens the modal. There
        // is a real one-commit window where it is open and unticked.
        await waitFor(() =>
            expect(within(dialog).getByLabelText("Select JSMITH")).toBeChecked()
        );
        await userEvent.click(
            within(dialog).getByRole("button", { name: "Confirm" })
        );

        await waitFor(() =>
            expect(onSelectionChange).toHaveBeenLastCalledWith([
                expect.objectContaining({ userId: "JSMITH" }),
            ])
        );
    });

    it("arrives with nothing selected when there is a choice to make", async () => {
        renderSearch();

        await search("smith");

        const dialog = await openResults();
        expect(within(dialog).getByLabelText("Select JSMITH")).not.toBeChecked();
        expect(
            within(dialog).getByRole("button", { name: "Confirm" })
        ).toBeDisabled();
    });

    it("offers checkboxes in multi-user mode", async () => {
        renderSearch({ multiUserMode: true });
        await search("smith");

        const dialog = await openResults();
        expect(
            within(dialog).getByLabelText("Select JSMITH")
        ).toHaveAttribute("type", "checkbox");
    });

    it("keeps the row in line while a validation message is showing", async () => {
        /*
            Carbon renders invalidText inside the input's wrapper, and this row
            aligns on flex-end - so the message used to grow the wrapper and push
            the field a message's height above the two selects and the button
            beside it. The message hangs below the field instead; the rule is in
            UserSearch.css and this holds the markup it depends on.
        */
        renderSearch({}, "JSMITH");
        await search("jsmith");
        await screen.findByText("You cannot grant permissions to yourself.");

        const message = screen.getByText("You cannot grant permissions to yourself.");
        // Inside the wrapper the rule targets, so it is taken out of the flow.
        expect(message.closest(".field-search-input")).not.toBeNull();
        expect(message).toHaveClass("cds--form-requirement");
    });

    it("refuses to search for the signed-in user", async () => {
        // Caught before the request: the person typed their own username, and
        // saying so is clearer than an empty result set.
        renderSearch({}, "JSMITH");

        await search("jsmith");

        expect(
            await screen.findByText("You cannot grant permissions to yourself.")
        ).toBeInTheDocument();
        expect(searchIdirUsers).not.toHaveBeenCalled();
    });

    it("drops the signed-in user out of a name search's results", async () => {
        // The username guard cannot catch this one - the search was by surname,
        // and the administrator is simply among the matches.
        const { onSelectionChange } = renderSearch({}, "JSMITH");

        await userEvent.selectOptions(screen.getByLabelText("Type"), "lastName");
        await search("smith");

        const dialog = await openResults();
        await userEvent.click(within(dialog).getByLabelText("Select JSMITH"));
        await userEvent.click(within(dialog).getByLabelText("Select JSMYTHE"));
        await userEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));

        await waitFor(() =>
            expect(onSelectionChange).toHaveBeenLastCalledWith([
                expect.objectContaining({ userId: "JSMYTHE" }),
            ])
        );
        expect(
            screen.getByText("You cannot grant permissions to yourself.")
        ).toBeInTheDocument();
    });

    it("admits the signed-in user when self-selection is allowed", async () => {
        // Application admins self-granting on a dev or test application, which
        // the backend permits.
        const { onSelectionChange } = renderSearch(
            { allowSelfSelection: true },
            "JSMITH"
        );

        await search("jsmith");

        const dialog = await openResults();
        await userEvent.click(within(dialog).getByLabelText("Select JSMITH"));
        await userEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));

        await waitFor(() =>
            expect(onSelectionChange).toHaveBeenLastCalledWith([
                expect.objectContaining({ userId: "JSMITH" }),
            ])
        );
    });

    it("refuses a blank search", async () => {
        renderSearch();

        await userEvent.click(
            screen.getByRole("button", { name: "Search users" })
        );

        expect(await screen.findByText("Search text is required")).toBeInTheDocument();
        expect(searchIdirUsers).not.toHaveBeenCalled();
    });

    it("will not let a space be typed", async () => {
        // No directory identifier contains one, so the field refuses the
        // character rather than accepting it and complaining later.
        renderSearch();

        await userEvent.type(screen.getByRole("textbox"), "van der berg");

        expect(screen.getByRole("textbox")).toHaveValue("vanderberg");
    });

    it("will not let a digit be typed into a name search", async () => {
        renderSearch();
        await userEvent.selectOptions(screen.getByLabelText("Type"), "lastName");

        await userEvent.type(screen.getByRole("textbox"), "smith2");

        expect(screen.getByRole("textbox")).toHaveValue("smith");
        expect(
            screen.getByText("Search text cannot contain numbers")
        ).toBeInTheDocument();
    });

    it("allows a digit in a username search", async () => {
        // Usernames carry them; only a name search rejects them.
        renderSearch();

        await userEvent.type(screen.getByRole("textbox"), "smith2");

        expect(screen.getByRole("textbox")).toHaveValue("smith2");
    });

    it("shows a spinner on the search button while the lookup runs", async () => {
        // A directory search can take seconds. Without this the button simply
        // goes dead and the screen looks broken rather than busy.
        searchIdirUsers.mockReturnValue(new Promise(() => {}));
        const { container } = renderSearch();

        await search("smith");

        await waitFor(() =>
            expect(container.querySelector(".cds--loading")).not.toBeNull()
        );
        expect(
            screen.getByRole("button", { name: "Search users" })
        ).toBeDisabled();
    });

    it("shows no spinner when idle", async () => {
        const { container } = renderSearch();

        expect(container.querySelector(".cds--loading")).toBeNull();
        expect(
            screen.getByRole("button", { name: "Search users" })
        ).toBeEnabled();
    });

    it("says so when the directory found nobody", async () => {
        searchIdirUsers.mockResolvedValue({ data: { items: [] } });
        renderSearch();

        await search("nobody");

        expect(
            await screen.findByText(/No search result found/)
        ).toBeInTheDocument();
        expect(isResultsModalOpen()).toBe(false);
    });

    it("offers only a username search for BCeID", async () => {
        // BCeID can only be looked up by exact username; a name search has no
        // equivalent there.
        renderSearch();

        await userEvent.selectOptions(
            screen.getByLabelText("User domain"),
            UserType.BceidBus
        );

        const types = within(screen.getByLabelText("Type")).getAllByRole("option");
        expect(types.map((option) => option.textContent)).toEqual(["Username"]);
    });

    it("looks BCeID up by exact username", async () => {
        bceidLookup.mockResolvedValue({
            data: {
                found: true,
                userId: "CONTRACTOR",
                guid: "CCCC3333",
                firstName: "Sam",
                lastName: "Doe",
                businessLegalName: "Cariboo Logging Ltd.",
                businessGuid: "BBBB2222",
            },
        });
        renderSearch();

        await userEvent.selectOptions(
            screen.getByLabelText("User domain"),
            UserType.BceidBus
        );
        await search("contractor");

        await waitFor(() =>
            expect(bceidLookup).toHaveBeenCalledWith("contractor", "dev")
        );
        const dialog = await openResults();
        expect(within(dialog).getByText("CONTRACTOR")).toBeInTheDocument();
        /*
            Which organisation the account belongs to. Two BCeID accounts can
            carry the same person's name at different businesses, and the result
            is somebody about to be granted access - the org is part of knowing
            the right person was found.
        */
        expect(within(dialog).getByText("Business")).toBeInTheDocument();
        expect(
            within(dialog).getByText("Cariboo Logging Ltd.")
        ).toBeInTheDocument();
    });

    it("offers a BCeID administrator only the BCeID directory", async () => {
        /*
            They may only grant to Business BCeID users - TargetOrganizationGuard
            refuses anything else - so an IDIR search is one whose every result
            is unusable. Presentation only; the guard is what decides.
        */
        renderSearch({}, "ADMINUSER", "bceidbusiness");

        const domain = screen.getByLabelText("User domain") as HTMLSelectElement;
        expect(within(domain).getAllByRole("option")).toHaveLength(1);
        expect(within(domain).getByRole("option")).toHaveValue(
            UserType.BceidBus
        );
        // One option is not a choice.
        expect(domain).toBeDisabled();
    });

    it("tells the form the directory it starts on", async () => {
        /*
            The form keeps its own `domain` and only heard about changes, so a
            BCeID administrator's form sat on IDIR while the selector beside it
            read Business BCeID - and would have submitted a BCeID GUID labelled
            as an IDIR user.
        */
        const onDomainChange = vi.fn();
        renderSearch({ onDomainChange }, "ADMINUSER", "bceidbusiness");

        await waitFor(() =>
            expect(onDomainChange).toHaveBeenCalledWith(UserType.BceidBus)
        );
    });

    it("still offers both directories to an IDIR administrator", async () => {
        renderSearch({}, "ADMINUSER", "idir");

        const domain = screen.getByLabelText("User domain") as HTMLSelectElement;
        expect(within(domain).getAllByRole("option")).toHaveLength(2);
        expect(domain).not.toBeDisabled();
    });

    it("says so against the field when the user is at another business", async () => {
        /*
            A Business BCeID administrator may only see people at their own
            business. The backend refuses before returning the person, so
            nothing about them reaches the screen - and the message belongs
            against the field, since it is about what was typed.
        */
        bceidLookup.mockRejectedValue({
            isAxiosError: true,
            response: {
                status: 403,
                data: {
                    detail: {
                        code: "different_org_grant_prohibited",
                        description:
                            "Operation requires business bceid users to be within the same organization",
                    },
                },
            },
        });
        renderSearch({}, "ADMINUSER", "bceidbusiness");
        await search("outsider");

        const field = await screen.findByRole("textbox");
        await waitFor(() =>
            expect(
                screen.getByText("User is not resident to your business")
            ).toBeInTheDocument()
        );
        expect(field).toBeInvalid();

        // Nothing about the person: not their name, not their organisation, and
        // no hint as to whether the account exists at all.
        expect(screen.queryByText(/Org name/)).not.toBeInTheDocument();
    });

    it("leaves the business column out of an IDIR search", async () => {
        // Only a Business BCeID account belongs to one, and IDIR is the common
        // search - a permanent column would be empty on almost every one.
        renderSearch();
        await search("smith");

        const dialog = await openResults();
        expect(within(dialog).queryByText("Business")).not.toBeInTheDocument();
    });

    it("clears the selection when the domain changes", async () => {
        // The people already chosen came from the other directory.
        const { onSelectionChange } = renderSearch();
        await search("smith");

        const dialog = await openResults();
        await userEvent.click(within(dialog).getByLabelText("Select JSMITH"));
        await userEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));
        await waitFor(() =>
            expect(within(selectedTable()).getByText("JSMITH")).toBeInTheDocument()
        );

        await userEvent.selectOptions(
            screen.getByLabelText("User domain"),
            UserType.BceidBus
        );

        expect(onSelectionChange).toHaveBeenLastCalledWith([]);
    });

    it("asks the form first when the form wants to be asked", async () => {
        // The grant screens use this to warn before discarding a selection.
        const onBeforeDomainChange = vi.fn();
        renderSearch({ onBeforeDomainChange });

        await userEvent.selectOptions(
            screen.getByLabelText("User domain"),
            UserType.BceidBus
        );

        expect(onBeforeDomainChange).toHaveBeenCalledWith(
            expect.objectContaining({
                currentDomain: UserType.Idir,
                nextDomain: UserType.BceidBus,
            })
        );
        // And nothing has changed yet: the search types still say IDIR's three.
        const types = within(screen.getByLabelText("Type")).getAllByRole("option");
        expect(types).toHaveLength(3);
    });

    it("applies the change once the form approves", async () => {
        let approve = () => {};
        renderSearch({
            onBeforeDomainChange: (request) => {
                approve = request.approveChange;
            },
        });

        await userEvent.selectOptions(
            screen.getByLabelText("User domain"),
            UserType.BceidBus
        );
        approve();

        await waitFor(() => {
            const types = within(screen.getByLabelText("Type")).getAllByRole(
                "option"
            );
            expect(types).toHaveLength(1);
        });
    });

    it("names the organization when a BCeID admin reaches outside it", async () => {
        // Otherwise indistinguishable from a general refusal.
        searchIdirUsers.mockRejectedValue({
            isAxiosError: true,
            response: {
                status: 403,
                data: {
                    detail: {
                        code: "permission_required_for_operation",
                        description: "You may only search your own organization",
                    },
                },
            },
        });
        renderSearch();

        await search("someone");

        // The search retries once before giving up, and TanStack's first retry
        // waits a second - which is exactly the default findBy timeout, so this
        // passed or failed depending on machine load.
        expect(
            await screen.findByText(/Org name: Timber Co/, undefined, {
                timeout: 5000,
            })
        ).toBeInTheDocument();
    });

    it("removes a chosen person from the list", async () => {
        const { onSelectionChange } = renderSearch();
        await search("smith");

        const dialog = await openResults();
        await userEvent.click(within(dialog).getByLabelText("Select JSMITH"));
        await userEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));
        await waitFor(() =>
            expect(within(selectedTable()).getByText("JSMITH")).toBeInTheDocument()
        );

        await userEvent.click(
            screen.getByRole("button", { name: "Remove JSMITH" })
        );

        expect(onSelectionChange).toHaveBeenLastCalledWith([]);
    });
});
