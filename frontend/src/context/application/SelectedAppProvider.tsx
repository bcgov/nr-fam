import { useMemo, useState, type ReactNode } from "react";
import type { CssApplicationOptionDto } from "fam-api";
import { SelectedAppContext } from "./SelectedAppContext";

export const SelectedAppProvider = ({ children }: { children: ReactNode }) => {
    const [selectedApp, setSelectedApp] = useState<
        CssApplicationOptionDto | undefined
    >();

    const value = useMemo(
        () => ({ selectedApp, setSelectedApp }),
        [selectedApp]
    );

    return (
        <SelectedAppContext.Provider value={value}>
            {children}
        </SelectedAppContext.Provider>
    );
};

export default SelectedAppProvider;
