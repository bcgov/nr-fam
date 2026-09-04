import { Search as SearchIcon } from "@carbon/icons-react";
import { Button, Select, SelectItem, TextInput } from "@carbon/react";
import { UserType } from "fam-api/model";
import {
    useEffect,
    useMemo,
    useRef,
    useState,
    type FC,
    type ReactNode,
} from "react";
import { InlineSpinner } from "@/components/InlineSpinner";
import {
    DIFFERENT_ORG_GRANT_PROHIBITED,
    PERMISSION_REQUIRED_FOR_OPERATION,
} from "@/constants/ApiErrorCodes";
import { IDP_CLAIM } from "@/enum/IdpEnum";
import { useAuth } from "@/context/auth/useAuth";
import { useUserSearch } from "@/hooks/useUserSearch";
import type { SelectedUser } from "@/types/SelectUserType";
import type { UserSearchType } from "@/types/UserSearchTypes";
import { UserSearchResultsModal } from "./UserSearchResultsModal";
import { UserSearchSelectedTable } from "./UserSearchSelectedTable";
import "./UserSearch.css";

/**
 * Find people in IDIR or BCeID and choose them for whatever the form does next.
 *
 * Owns the search fields, the results modal and the running selection; the form
 * around it is told the selection through `onSelectionChange` and never reaches
 * into it.
 */

const MAX_SEARCH_TEXT_LENGTH = 35; // The API allows 50; this is deliberately less.

const SELF_SELECTION_ERROR = "You cannot grant permissions to yourself.";

/**
 * A Business BCeID caller searching somebody at another business.
 *
 * <p>Says nothing about whether the account exists. The backend refuses before
 * it returns the person, so there is no name, email or organisation here to
 * leak - and a message that distinguished "no such user" from "not yours to
 * see" would report whether an account exists at another business, which is
 * what the rule exists to prevent.
 */
const OTHER_BUSINESS_ERROR = "User is not resident to your business";

type DomainChangeRequest = {
    currentDomain: UserType;
    nextDomain: UserType;
    /** Already chosen, and about to be discarded if the change goes ahead. */
    selectedUsersCount: number;
    approveChange: () => void;
    cancelChange: () => void;
};

type Props = {
    environment: string;
    /** Several people at once, or exactly one. */
    multiUserMode: boolean;
    availableDomains?: UserType[];
    disabled?: boolean;
    searchButtonLabel?: string;
    helperText?: string;
    /**
     * Lets the signed-in user choose themselves.
     *
     * Off by default: granting yourself access is the thing an administrator
     * must not quietly do. On for the cases the backend permits, such as an
     * application admin self-granting on a dev or test application.
     */
    allowSelfSelection?: boolean;
    onSelectionChange: (users: SelectedUser[]) => void;
    onDomainChange?: (domain: UserType) => void;
    /**
     * A chance to stop a domain change before the selection is discarded.
     *
     * Without it the change is applied at once. With it, nothing happens until
     * the form calls approveChange - which is how the grant screens get to ask
     * "you have three people chosen, discard them?" first.
     */
    onBeforeDomainChange?: (request: DomainChangeRequest) => void;
    /** Errors from the form itself, shown alongside the search's own. */
    formError?: ReactNode;
};

const domainLabel = (domain: UserType) =>
    domain === UserType.Idir ? "IDIR" : "BCeID";

/** BCeID can only be looked up by exact username; IDIR admits a name search. */
const searchTypesFor = (
    domain: UserType
): Array<{ label: string; value: UserSearchType }> =>
    domain === UserType.BceidBus
        ? [{ label: "Username", value: "username" }]
        : [
              { label: "Username", value: "username" },
              { label: "First Name", value: "firstName" },
              { label: "Last Name", value: "lastName" },
          ];

export const UserSearch: FC<Props> = ({
    environment,
    multiUserMode,
    availableDomains = [UserType.Idir, UserType.BceidBus],
    disabled = false,
    searchButtonLabel = "Search",
    helperText = "",
    allowSelfSelection = false,
    onSelectionChange,
    onDomainChange,
    onBeforeDomainChange,
    formError,
}) => {
    const { authState } = useAuth();

    /*
        What this caller may search.

        A Business BCeID administrator may only grant to Business BCeID users -
        the backend refuses anything else outright, in TargetOrganizationGuard -
        so offering an IDIR search to one is offering a search whose every result
        is unusable. The same reasoning the application-admin screens already
        apply when they hard-code IDIR.

        Presentation only, as everywhere else here: the guard decides, this only
        stops asking a question with no good answer.

        Falls back to what the screen asked for if the intersection is empty,
        rather than rendering a selector with nothing in it. That is a screen a
        BCeID administrator cannot reach - appointing an application admin is
        IDIR-only and needs a tier they do not hold - and an empty control would
        be a worse way to say so than the refusal they would get.
    */
    const domains = useMemo(() => {
        if (authState.famLoginUser?.idpProvider !== IDP_CLAIM.BUSINESS_BCEID) {
            return availableDomains;
        }
        const allowed = availableDomains.filter(
            (option) => option === UserType.BceidBus
        );
        return allowed.length > 0 ? allowed : availableDomains;
    }, [availableDomains, authState.famLoginUser?.idpProvider]);

    const [domain, setDomain] = useState<UserType>(domains[0]);
    const [searchType, setSearchType] = useState<UserSearchType>("username");
    const [searchText, setSearchText] = useState("");
    const [searchTextError, setSearchTextError] = useState("");
    const [resultMessage, setResultMessage] = useState("");
    const [selected, setSelected] = useState<SelectedUser[]>([]);
    const [isResultsOpen, setResultsOpen] = useState(false);

    const { searchUsers, isPending, searchResults, isSuccess, searchError, reset } =
        useUserSearch();

    const currentUsername = (
        authState.famLoginUser?.username ?? ""
    ).toLowerCase();

    /*
        Tell the form which directory is actually selected, including on first
        render.

        onDomainChange fires only when somebody changes the selector, so a form
        that initialises its own `domain` to IDIR stayed on IDIR while the
        selector beside it showed Business BCeID - and submitted a BCeID GUID
        labelled as an IDIR user. The backend refuses that, correctly, with a
        message about the wrong thing.
    */
    useEffect(() => {
        onDomainChange?.(domain);
        // onDomainChange is a prop the callers redefine each render; depending on
        // it would fire this on every one.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [domain]);

    const searchTypes = useMemo(() => searchTypesFor(domain), [domain]);
    const isUsernameSearch = searchType === "username";

    /**
     * What is wrong with this text, or "" if nothing is.
     *
     * No spaces ever - no directory identifier contains one - and no digits on a
     * name search, where they only ever come from somebody typing a username
     * into the wrong field.
     */
    const invalidTextError = (value: string): string => {
        if (/\s/.test(value)) {
            return "Search text cannot contain spaces";
        }
        if (!isUsernameSearch && /\d/.test(value)) {
            return "Search text cannot contain numbers";
        }
        return "";
    };

    const validate = (): boolean => {
        const trimmed = searchText.trim();
        if (!trimmed) {
            setSearchTextError("Search text is required");
            return false;
        }
        if (trimmed.length > MAX_SEARCH_TEXT_LENGTH) {
            setSearchTextError(
                `Search text must be ${MAX_SEARCH_TEXT_LENGTH} characters or less`
            );
            return false;
        }
        const invalid = invalidTextError(trimmed);
        setSearchTextError(invalid);
        return !invalid;
    };

    const applyDomainChange = (next: UserType) => {
        setDomain(next);
        setSearchType(searchTypesFor(next)[0].value);
        setSearchText("");
        setSearchTextError("");
        setResultMessage("");
        // The people already chosen came from the other directory, so they go.
        setSelected([]);
        onSelectionChange([]);
        reset();
        // Not reported here: the effect above watches `domain` and covers both
        // this and the first render, so the form cannot be told twice or - as it
        // was - not at all until somebody touched the selector.
    };

    const handleDomainSelection = (next: UserType) => {
        if (next === domain) {
            return;
        }
        if (!onBeforeDomainChange) {
            applyDomainChange(next);
            return;
        }
        onBeforeDomainChange({
            currentDomain: domain,
            nextDomain: next,
            selectedUsersCount: selected.length,
            approveChange: () => applyDomainChange(next),
            // Nothing to undo: the Select is driven by `domain`, which has not
            // moved. The Vue version had to force a re-render here to drag
            // PrimeVue's own internal value back.
            cancelChange: () => {},
        });
    };

    const handleTextChange = (value: string) => {
        // Refuses the character rather than accepting it and complaining: the
        // field cannot hold a space or (on a name search) a digit at all.
        const sanitized = isUsernameSearch
            ? value.replace(/\s/g, "")
            : value.replace(/[\s\d]/g, "");

        if (sanitized !== value) {
            setSearchTextError(invalidTextError(value));
        } else if (searchTextError) {
            setSearchTextError(invalidTextError(sanitized));
        }
        setSearchText(sanitized);
    };

    const handleSearch = () => {
        if (!validate()) {
            return;
        }
        // Caught before the request rather than filtered out of the results: the
        // person typed their own username, and telling them so is clearer than
        // an empty result set.
        if (
            !allowSelfSelection &&
            isUsernameSearch &&
            searchText.trim().toLowerCase() === currentUsername
        ) {
            setSearchTextError(SELF_SELECTION_ERROR);
            return;
        }
        setResultMessage("");
        searchUsers({
            domain,
            searchType,
            searchText: searchText.trim(),
            environment,
        });
    };

    /**
     * Opens the results once per search.
     *
     * The ref is what makes it once: `isSuccess` stays true afterwards, so
     * reacting to the flag alone reopened the modal on every later render -
     * including the one caused by closing it.
     */
    const handledResults = useRef<SelectedUser[] | null>(null);
    useEffect(() => {
        if (!isSuccess || !searchResults || handledResults.current === searchResults) {
            return;
        }
        handledResults.current = searchResults;
        if (searchResults.length === 0) {
            setResultMessage(
                "No search result found. Check the spelling or try another search."
            );
            return;
        }
        setResultMessage("");
        setResultsOpen(true);
    }, [isSuccess, searchResults]);

    useEffect(() => {
        if (!searchError) {
            return;
        }
        /*
            Against the field, not below the whole search block.

            This one is about what was typed - a username belonging to another
            business - so it belongs where the typing happened, in red, the same
            way a too-long or malformed username is reported. It also carries
            nothing about the person searched for: the backend refuses before
            returning them, so FAM never holds a name, an email or an
            organisation to leak, and the message deliberately does not hint at
            whether the account exists.
        */
        if (searchError.code === DIFFERENT_ORG_GRANT_PROHIBITED) {
            setSearchTextError(OTHER_BUSINESS_ERROR);
            return;
        }
        if (searchError.code === PERMISSION_REQUIRED_FOR_OPERATION) {
            /*
                The organisation is appended only when there is one to name.

                It is there because a BCeID administrator refused for reaching
                outside their own business is helped by seeing which business
                that is. When the session carries no organisation - every IDIR
                caller, and any refusal that has nothing to do with one - it used
                to append "Org name: Unknown organization", which answers a
                question nobody asked and made every unrelated refusal read as an
                organisation problem.
            */
            const orgName = authState.famLoginUser?.organization;
            const reason = searchError.description ?? searchError.message;
            setResultMessage(orgName ? `${reason}. Org name: ${orgName}` : reason);
            return;
        }
        setResultMessage(searchError.message);
    }, [searchError, authState.famLoginUser?.organization]);

    const confirmSelection = (chosen: SelectedUser[]) => {
        setResultsOpen(false);
        if (chosen.length === 0) {
            return;
        }

        const admissible =
            currentUsername && !allowSelfSelection
                ? chosen.filter(
                      (user) => user.userId.toLowerCase() !== currentUsername
                  )
                : chosen;

        if (currentUsername && admissible.length !== chosen.length) {
            setResultMessage(SELF_SELECTION_ERROR);
        }

        const next = multiUserMode
            ? // Merged with what was already chosen, deduplicated on user and
              // directory together - the same id can exist in both.
              [...selected, ...admissible].filter(
                  (user, index, all) =>
                      index ===
                      all.findIndex(
                          (other) =>
                              other.userId.toLowerCase() ===
                                  user.userId.toLowerCase() &&
                              other.sourceDomain === user.sourceDomain
                      )
              )
            : admissible.slice(0, 1);

        setSelected(next);
        onSelectionChange(next);
    };

    const removeUser = (userId: string) => {
        const next = selected.filter(
            (user) => user.userId.toLowerCase() !== userId.toLowerCase()
        );
        setSelected(next);
        onSelectionChange(next);
    };

    return (
        <div className="user-search-container">
            <div className="search-fields-row">
                <Select
                    id="user-domain"
                    className="field-domain"
                    labelText="User domain"
                    value={domain}
                    onChange={(event) =>
                        handleDomainSelection(event.target.value as UserType)
                    }
                    disabled={disabled || domains.length === 1 || isPending}
                >
                    {domains.map((option) => (
                        <SelectItem
                            key={option}
                            value={option}
                            text={domainLabel(option)}
                        />
                    ))}
                </Select>

                <Select
                    id="search-type"
                    className="field-type"
                    labelText="Type"
                    value={searchType}
                    onChange={(event) => {
                        setSearchType(event.target.value as UserSearchType);
                        // The text may be legal for one type and not the other,
                        // so the complaint goes rather than being re-evaluated
                        // against a rule the user has not typed under yet.
                        setSearchTextError("");
                        setResultMessage("");
                    }}
                    disabled={disabled || isPending}
                >
                    {searchTypes.map((option) => (
                        <SelectItem
                            key={option.value}
                            value={option.value}
                            text={option.label}
                        />
                    ))}
                </Select>

                <div className="field-search-input">
                    <TextInput
                        id="user-search-input"
                        labelText="Search text"
                        hideLabel
                        placeholder="Please input search text"
                        maxLength={MAX_SEARCH_TEXT_LENGTH}
                        value={searchText}
                        onChange={(event) => handleTextChange(event.target.value)}
                        onKeyDown={(event) => {
                            if (event.key === "Enter") {
                                event.preventDefault();
                                handleSearch();
                            }
                        }}
                        invalid={Boolean(searchTextError)}
                        invalidText={searchTextError}
                        disabled={disabled || isPending}
                    />
                </div>

                <div className="field-search-button">
                    <Button
                        kind="tertiary"
                        // Carbon buttons default to `lg` (48px) while its inputs
                        // default to `md` (40px), so an unsized button stands a
                        // full 8px taller than the field beside it.
                        size="md"
                        name="searchUsers"
                        aria-label="Search users"
                        renderIcon={isPending ? InlineSpinner : SearchIcon}
                        disabled={disabled || isPending}
                        onClick={handleSearch}
                    >
                        {searchButtonLabel}
                    </Button>
                </div>
            </div>

            <div className="search-error-row">
                {helperText ? (
                    <p className="user-search__helper">{helperText}</p>
                ) : null}
                {resultMessage ? (
                    <p className="user-search__error" role="alert">
                        {resultMessage}
                    </p>
                ) : null}
                {formError}
            </div>

            <UserSearchSelectedTable
                users={selected}
                multiUserMode={multiUserMode}
                onDelete={removeUser}
            />

            <UserSearchResultsModal
                open={isResultsOpen}
                rows={searchResults ?? []}
                multiUserMode={multiUserMode}
                onConfirm={confirmSelection}
                onCancel={() => setResultsOpen(false)}
            />
        </div>
    );
};

export default UserSearch;
