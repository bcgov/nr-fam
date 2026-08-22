import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import StepContainer from "./StepContainer.vue";

/**
 * The step wrapper, and specifically the hook its spacing hangs off.
 *
 * Divider sets its own margins from a scoped block, so StepContainer's rule has
 * to out-weigh `hr.solid[data-v-...]` rather than merely follow it. It does that
 * with `hr.solid.step-divider`, which only works while the class actually
 * reaches the element - it arrives by attribute fallthrough, which is silent
 * when it breaks.
 */
describe("StepContainer", () => {
    it("puts the spacing hook on the rule", () => {
        const wrapper = mount(StepContainer, {
            props: { title: "Select a user", divider: true },
        });

        const rule = wrapper.find("hr");
        expect(rule.exists()).toBe(true);
        // Losing either class drops the selector's weight below Divider's own,
        // and the rule silently goes back to 2.5rem a side.
        expect(rule.classes()).toContain("solid");
        expect(rule.classes()).toContain("step-divider");
    });

    it("is a direct child of the container, which the selector requires", () => {
        const wrapper = mount(StepContainer, {
            props: { title: "Select a user", divider: true },
        });

        // The rule is `.step-container > hr`. Wrapping the divider in anything
        // would break it without breaking the markup.
        expect(wrapper.find(".step-container > hr.step-divider").exists()).toBe(
            true
        );
    });

    it("draws no rule when it was not asked for", () => {
        const wrapper = mount(StepContainer, { props: { title: "Only step" } });

        expect(wrapper.find("hr").exists()).toBe(false);
    });
});
