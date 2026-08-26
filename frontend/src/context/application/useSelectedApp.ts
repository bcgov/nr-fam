import { useContext } from "react";
import { SelectedAppContext } from "./SelectedAppContext";

export const useSelectedApp = () => {
    const context = useContext(SelectedAppContext);
    if (!context) {
        throw new Error(
            "useSelectedApp must be used inside a SelectedAppProvider."
        );
    }
    return context;
};
