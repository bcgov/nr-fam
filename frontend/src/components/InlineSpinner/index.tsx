import { Loading } from "@carbon/react";
import type { FC } from "react";

/**
 * Carbon's spinner, sized to sit in a Button's icon slot.
 *
 * Used as a Button's `renderIcon` while the action behind it is in flight, so a
 * button that is doing something looks busy rather than merely dead. Every
 * action in FAM crosses CSS - often several times, one call per user per role -
 * and those are slow enough that a disabled button with no other change reads
 * as a broken screen.
 *
 * `withOverlay={false}` stops Loading dimming the whole page, which is its
 * default. Swapping it in for the button's existing icon rather than adding it
 * alongside keeps the button the same width, so nothing shifts mid-action.
 */
export const InlineSpinner: FC = () => (
    <Loading small withOverlay={false} description="Working" />
);

export default InlineSpinner;
