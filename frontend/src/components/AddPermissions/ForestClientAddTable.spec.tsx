import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { NotificationProvider } from "@/context/notification/NotificationProvider";

const autocompleteForestClients = vi.fn();
vi.mock("@/services/ApiServiceFactory", () => ({
    AppActlApiService: {
        forestClientsApi: {
            autocompleteForestClients: () => autocompleteForestClients(),
        },
    },
    AdminMgmtApiService: {},
}));

const { ForestClientAddTable } = await import(
    "@/components/AddPermissions/ForestClientAddTable"
);

/**
 * The organisation search, and the two things the e2e suite drives it by.
 *
 * Both were lost in the port and restored: Carbon's ComboBox draws nothing at
 * all for an empty list, so a search that found nothing looked exactly like one
 * that had not run, and the client number arrived glued to the name with no
 * styling of its own.
 */
describe("ForestClientAddTable", () => {
    it("offers each match as an option with the number set apart", async () => {
        autocompleteForestClients.mockResolvedValue({
            data: [
                {
                    forest_client_number: "00001012",
                    client_name: "Timber Co",
                    status: { status_code: "A", description: "Active" },
                },
            ],
        });
        render(
            <QueryClientProvider client={new QueryClient()}>
                <NotificationProvider>
                <ForestClientAddTable
                    environment="dev"
                    selected={[]}
                    onChange={() => {}}
                />
                </NotificationProvider>
            </QueryClientProvider>
        );

        await userEvent.type(
            screen.getByRole("combobox", { name: "Organization" }),
            "timber"
        );

        await waitFor(() => expect(screen.getAllByRole("option")).toHaveLength(1));
        const option = screen.getByRole("option");
        expect(option.querySelector(".option-number")?.textContent).toBe(
            " - 00001012"
        );
        expect(option.textContent).toContain("Timber Co");
    });

    it("says so when a search found nothing", async () => {
        autocompleteForestClients.mockResolvedValue({ data: [] });
        render(
            <QueryClientProvider client={new QueryClient()}>
                <NotificationProvider>
                <ForestClientAddTable
                    environment="dev"
                    selected={[]}
                    onChange={() => {}}
                />
                </NotificationProvider>
            </QueryClientProvider>
        );

        await userEvent.type(
            screen.getByRole("combobox", { name: "Organization" }),
            "zzzzqqq"
        );

        expect(
            await screen.findByText("No organization found")
        ).toBeInTheDocument();
    });
});
