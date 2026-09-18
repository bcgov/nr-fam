/**
 * Builds the how-to guides: Markdown in, PDF out.
 *
 * The guides used to be Word documents kept on somebody's drive, exported to
 * PDF and dropped into the app. That is why they went stale: nothing about a
 * change to a screen brought the document along with it, and only one person
 * could edit it. The text now lives in docs/guides beside the code, the
 * screenshots are captured from the running app (see e2e/guides.capture.spec.ts),
 * and this turns both into the PDFs the How-to guide menu item serves.
 *
 * Chromium does the rendering rather than a PDF library, because Playwright is
 * already a dependency for the end-to-end tests - so this adds a Markdown
 * parser and nothing else.
 *
 *   npm run guides:build
 *
 * The built PDFs are committed. Building them needs no running FAM, so anybody
 * can correct a sentence and rebuild; only new screenshots need the app.
 */
import { chromium } from "@playwright/test";
import MarkdownIt from "markdown-it";
import { access, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const guidesDir = resolve(here, "../../docs/guides");
const publicDir = resolve(here, "../public");

/**
 * Each guide, and where it is served from.
 *
 * The output names are what routePaths.HOW_TO_GUIDES points at - change one and
 * you change the other.
 */
const GUIDES = [
    {
        source: "app-admin-guide.md",
        output: "fam-app-admin-guide.pdf",
        title: "How to use FAM - a guide for application administrators",
    },
    {
        source: "delegated-admin-guide.md",
        output: "fam-delegated-admin-guide.pdf",
        title: "How to use FAM - a guide for delegated administrators",
    },
];

const markdown = new MarkdownIt({ html: true, linkify: true, typographer: true });

/**
 * The page furniture.
 *
 * <p>No base href: the page is written into the guides directory and opened
 * from there, so `screenshots/01-sign-in.png` resolves as it does in the
 * Markdown. Chromium refuses to load `file://` images into a page set with
 * setContent - it has no origin to resolve them against - and the first build
 * produced PDFs carrying fourteen broken-image icons at 14x16 pixels, which is
 * exactly what an unread proof looks like.
 */
const html = async (title, body) => {
    const css = await readFile(resolve(guidesDir, "guide.css"), "utf8");
    return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>${title}</title>
<style>${css}</style>
</head>
<body>${body}</body>
</html>`;
};

/**
 * Warns about screenshots a guide asks for and has not got.
 *
 * Not an error: the prose can be corrected and rebuilt long before somebody has
 * a chance to run the capture against a live FAM, and blocking that would push
 * people back to editing the PDF. A broken image in a built guide is obvious
 * enough on its own - this just says which, without opening it.
 */
const warnAboutMissingImages = async (source, body) => {
    const referenced = [...body.matchAll(/<img[^>]+src="([^"]+)"/g)].map(
        (match) => match[1]
    );
    for (const image of referenced) {
        try {
            await access(resolve(guidesDir, image));
        } catch {
            console.warn(`  missing screenshot: ${image} (in ${source})`);
        }
    }
};

const build = async () => {
    const browser = await chromium.launch();
    try {
        for (const guide of GUIDES) {
            const source = await readFile(resolve(guidesDir, guide.source), "utf8");
            const body = markdown.render(source);
            await warnAboutMissingImages(guide.source, body);

            // Written beside the screenshots so their relative paths resolve,
            // and removed again once the PDF is out.
            const scratch = resolve(guidesDir, `.${guide.output}.html`);
            await writeFile(scratch, await html(guide.title, body), "utf8");

            const page = await browser.newPage();
            await page.goto(`file://${scratch}`, { waitUntil: "networkidle" });
            const pdf = await page.pdf({
                format: "Letter",
                printBackground: true,
                margin: { top: "18mm", bottom: "18mm", left: "16mm", right: "16mm" },
                displayHeaderFooter: true,
                headerTemplate: "<div></div>",
                // The page number, so somebody reading a printout can say where
                // they are. Styled here because the footer template is rendered
                // outside the document and inherits none of its stylesheet.
                footerTemplate: `<div style="width:100%;font-size:8pt;color:#525252;
                    font-family:sans-serif;padding:0 16mm;display:flex;
                    justify-content:space-between;">
                    <span>${guide.title}</span>
                    <span class="pageNumber"></span>
                </div>`,
            });
            await writeFile(resolve(publicDir, guide.output), pdf);
            await page.close();
            await rm(scratch, { force: true });
            console.log(`Built ${guide.output} (${(pdf.length / 1024).toFixed(0)} KB)`);
        }
    } finally {
        await browser.close();
    }
};

await build();
