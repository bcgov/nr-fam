import {
    ComboBox,
    Table,
    TableBody,
    TableCell,
    TableContainer,
    TableHead,
    TableHeader,
    TableRow,
} from "@carbon/react";
import { HttpStatusCode } from "axios";
import type { FamForestClientDto } from "fam-api";
import { useCallback, useEffect, useRef, useState, type FC } from "react";
import { Chip } from "@/components/Chip";
import { SubsectionTitle } from "@/components/SubsectionTitle";
import { FOREST_CLIENT_SEARCH_MIN_LENGTH } from "@/constants/constants";
import { useErrorToast } from "@/context/notification/useErrorToast";
import { AppActlApiService } from "@/services/ApiServiceFactory";
import { getAxiosErrorStatus } from "@/utils/ApiUtils";
import "./ScopeSelectTable.css";
import { RemoveButton } from "@/components/RemoveButton";

/**
 * Organisation picker as a search, for a caller who may grant any of them.
 *
 * Typed either way: an eight-digit client number, or a name. The backend
 * classifies the term and searches the right column - the Forest Client API ANDs
 * its criteria, so a name and a number cannot be sent together and one has to be
 * chosen.
 */
type Props = {
    environment: string;
    selected: FamForestClientDto[];
    onChange: (clients: FamForestClientDto[]) => void;
    /**
     * The only client numbers this caller may grant for, or null when they are
     * not restricted.
     *
     * The search still runs against the whole Forest Client API - it is how a
     * number becomes a name - but anything outside the delegation is dropped
     * from the suggestions. Offering it would be offering a grant the backend
     * refuses.
     */
    allowed?: string[] | null;
    title?: string;
    subtitle?: string;
    errorMessage?: string;
};

/** Long enough that a search fires on a word rather than on a letter. */
const SEARCH_DEBOUNCE_MS = 300;

export const ForestClientAddTable: FC<Props> = ({
    environment,
    selected,
    onChange,
    allowed = null,
    title = "Restrict access by organizations",
    subtitle = "Add one or more organizations for this user to have access to",
    errorMessage,
}) => {
    const [suggestions, setSuggestions] = useState<FamForestClientDto[]>([]);
    const [inputValue, setInputValue] = useState("");
    const [clearCount, setClearCount] = useState(0);
    const [searchError, setSearchError] = useState("");
    const [isServiceDown, setServiceDown] = useState(false);

    /*
        A dependency being down is not this field's fault and not something
        retyping fixes, so it is said once from the corner rather than sitting
        above the search box for as long as the outage lasts. It waits to be
        dismissed - see NotificationProvider - and the helper text below keeps
        saying nothing was found while it is down.
    */
    useErrorToast({
        when: isServiceDown,
        kind: "warning",
        title: "Forest Client Service is unavailable",
        subtitle: "Role with forest client cannot be added.",
    });

    // Guards against an earlier, slower search overwriting a later one's
    // results - which shows suggestions for a term the user has moved on from.
    const latestSearch = useRef(0);

    const runSearch = useCallback(
        async (term: string) => {
            const searchId = ++latestSearch.current;
            setSearchError("");
            setServiceDown(false);

            // The backend enforces this too; checking here avoids a request that
            // can only come back as a validation error.
            if (term.length < FOREST_CLIENT_SEARCH_MIN_LENGTH) {
                setSuggestions([]);
                return;
            }

            try {
                const { data } =
                    await AppActlApiService.forestClientsApi.autocompleteForestClients(
                        term,
                        environment
                    );
                if (searchId !== latestSearch.current) {
                    return;
                }

                // Already-added organisations are dropped rather than offered
                // and then refused on selection.
                const chosen = new Set(
                    selected.map((client) => client.forest_client_number)
                );
                // And anything outside the delegation, for the same reason: the
                // grant path compares the scoped role name, so a client the
                // delegation does not name is refused however it was chosen.
                const permitted = allowed == null ? null : new Set(allowed);

                setSuggestions(
                    data.filter(
                        (client) =>
                            !chosen.has(client.forest_client_number) &&
                            (permitted === null ||
                                permitted.has(client.forest_client_number))
                    )
                );
            } catch (error) {
                if (searchId !== latestSearch.current) {
                    return;
                }
                setSuggestions([]);
                if (getAxiosErrorStatus(error) === HttpStatusCode.GatewayTimeout) {
                    setServiceDown(true);
                } else {
                    setSearchError(
                        "The organization search failed. Please try again"
                    );
                }
            }
        },
        [allowed, environment, selected]
    );

    useEffect(() => {
        const timer = setTimeout(() => void runSearch(inputValue.trim()), SEARCH_DEBOUNCE_MS);
        return () => clearTimeout(timer);
    }, [inputValue, runSearch]);

    /**
     * Adds the chosen organisation.
     *
     * The status check stays even though the search returns only active
     * organisations: it is a backstop against a suggestion that has gone stale
     * between the search and the click, not the reason inactive ones are hidden.
     */
    const add = (client: FamForestClientDto | null | undefined) => {
        if (!client) {
            return;
        }
        if (client.status?.status_code !== "A") {
            setSearchError("This organization can't be added due to its status");
        } else {
            onChange([...selected, client]);
            setSearchError("");
        }
        // Cleared either way: the field is a search box, not a value.
        setInputValue("");
        setSuggestions([]);
        setClearCount((count) => count + 1);
    };

    const hasSearched = inputValue.trim().length >= FOREST_CLIENT_SEARCH_MIN_LENGTH;

    /**
     * Said out loud, because Carbon draws nothing at all for an empty list - so
     * a search that found nothing looks exactly like one that has not run.
     */
    const helperText = (() => {
        if (hasSearched && suggestions.length === 0 && !isServiceDown) {
            return "No organization found";
        }
        return allowed != null
            ? `You may grant this role for ${allowed.length} organization(s). Search to find them.`
            : "Type an organization name or client number, then choose from the list";
    })();

    return (
        <div className="scope-select-table-container forest-client-add-table">
            <SubsectionTitle title={title} subtitle={subtitle} />

            <ComboBox
                id="forest-client-number-input"
                className="forest-client-autocomplete"
                titleText="Organization"
                placeholder="Search by organization name or client number"
                items={suggestions}
                // A number alone is not something anybody recognises, so the
                // list shows both. itemToString is what lands in the box on
                // selection; itemToElement is what the list draws, which is
                // where the number gets its own styling.
                itemToString={(item: FamForestClientDto | null) =>
                    item
                        ? `${item.client_name ?? ""} - ${item.forest_client_number}`
                        : ""
                }
                itemToElement={(item: FamForestClientDto) => (
                    <>
                        <span className="option-name">{item.client_name}</span>
                        {/*
                            The separator is real text rather than a ::before so
                            it is copied and announced along with the option.
                        */}
                        <span className="option-number">
                            {` - ${item.forest_client_number}`}
                        </span>
                    </>
                )}
                // Carbon's ComboBox owns its own input text - there is no
                // controlled `inputValue` - so the box is remounted to clear it.
                // Otherwise the chosen organisation's name stays sitting in what
                // is a search field, reading as a value that was kept.
                key={clearCount}
                selectedItem={null}
                onInputChange={(value) => setInputValue(value ?? "")}
                onChange={({ selectedItem }) => add(selectedItem)}
                /*
                    Three things can be wrong with this field and all of them
                    are the field's own: the search failed, the chosen
                    organisation was refused, or nothing was chosen at all.
                    Ranked, because Carbon shows one - the newest complaint
                    first, then the one the form is blocked on.
                */
                invalid={Boolean(searchError || errorMessage)}
                invalidText={searchError || errorMessage}
                helperText={searchError || errorMessage ? undefined : helperText}
            />

            <div className="fam-table">
                <TableContainer>
                    <Table size="md" useZebraStyles>
                        <TableHead>
                            <TableRow>
                                <TableHeader>Client number</TableHeader>
                                <TableHeader>Name</TableHeader>
                                <TableHeader>Status</TableHeader>
                                <TableHeader>Action</TableHeader>
                            </TableRow>
                        </TableHead>
                        <TableBody>
                            {selected.length === 0 ? (
                                <TableRow>
                                    <TableCell colSpan={4}>
                                        No organization added yet
                                    </TableCell>
                                </TableRow>
                            ) : (
                                selected.map((client) => (
                                    <TableRow key={client.forest_client_number}>
                                        <TableCell>
                                            {client.forest_client_number}
                                        </TableCell>
                                        <TableCell>{client.client_name}</TableCell>
                                        <TableCell>
                                            {client.status ? (
                                                <Chip
                                                    color="green"
                                                    // Both are optional on
                                                    // the DTO; the code is a
                                                    // poor label but a better
                                                    // one than an empty pill.
                                                    label={
                                                        client.status
                                                            .description ??
                                                        client.status
                                                            .status_code ??
                                                        ""
                                                    }
                                                />
                                            ) : null}
                                        </TableCell>
                                        <TableCell>
                                            <RemoveButton
                                                accessible={`Remove ${client.forest_client_number}`}
                                                onClick={() =>
                                                    onChange(
                                                        selected.filter(
                                                            (chosen) =>
                                                                chosen.forest_client_number !==
                                                                client.forest_client_number
                                                        )
                                                    )
                                                }
                                            />
                                        </TableCell>
                                    </TableRow>
                                ))
                            )}
                        </TableBody>
                    </Table>
                </TableContainer>
            </div>
        </div>
    );
};

export default ForestClientAddTable;
