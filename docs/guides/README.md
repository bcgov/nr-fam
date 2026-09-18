# The how-to guides

The two documents behind the **How-to guide** item in FAM's left-hand
navigation: one for application administrators, one for delegated
administrators. Which one somebody is offered depends on what they administer -
see `howToGuideFor` in `frontend/src/routes/routePaths.ts`.

## Why they live here

They used to be Word documents on a drive, exported to PDF and copied into the
app. That is why they went stale: nothing about changing a screen brought the
document along with it, only one person could edit it, and every screenshot was
taken by hand against whatever data happened to be in the environment that day -
including real people's names and email addresses.

Now the text is Markdown beside the code, the screenshots are captured from the
running app, and the PDFs are built from both.

## Files

| Path | What it is |
| --- | --- |
| `app-admin-guide.md` | The application administrator's guide |
| `delegated-admin-guide.md` | The delegated administrator's guide |
| `guide.css` | Print styles for both |
| `screenshots/` | PNGs, captured from the app - not edited by hand |

The built PDFs land in `frontend/public/` and are committed, so serving them
needs no build step at deploy time.

## Changing the words

Edit the Markdown, then:

```sh
cd frontend
npm run guides:build
```

That needs no running FAM and no credentials - it renders the Markdown with the
screenshots already on disk. It warns about any screenshot a guide asks for and
has not got, then builds anyway, so a broken image is visible rather than
blocking.

Commit the Markdown and the rebuilt PDF together.

## Changing the screenshots

The screenshots are captured by an end-to-end test that walks the real app:

```sh
cd frontend
npm run e2e:login      # once, to store a session
npm run guides:capture # writes docs/guides/screenshots/*.png
npm run guides:build
```

Rules the capture holds to:

- **A fixed viewport**, so images are a consistent size and a re-shoot produces
  a comparable picture rather than a differently cropped one.
- **Test accounts only.** The old screenshots showed real staff names and
  addresses in a document that gets emailed around. The capture uses the
  fixtures the end-to-end suite already uses.
- **Named for the step they illustrate**, not for the order they were taken in,
  so a guide can be reordered without renaming files.

Add a screenshot by adding a step to `frontend/e2e/guides.capture.spec.ts` and
referencing the new file from the Markdown.

## What the app serves

The **How-to guide** menu item serves `fam-app-admin-guide.pdf` and
`fam-delegated-admin-guide.pdf` from `frontend/public/`, built from here. The two
Word-authored PDFs they replaced have been removed.

One screenshot cannot be captured yet: the terms of use are shown only to a
Business BCeID delegated administrator who has not accepted the current version,
and the stored end-to-end session is IDIR. That test is skipped with a note until
there is a BCeID test account.

The delegated administrator's guide describes that dialog in words and shows no
picture of it, rather than carrying a broken image. When the screenshot exists,
add it back under "Accepting the terms of use" as
`![The terms of use dialog](screenshots/02-terms-of-use.png)`.

## When a screen changes

Both files are in the same repository as the screen. A change that alters what a
guide describes should update the guide in the same pull request - that is the
whole point of them being here.
