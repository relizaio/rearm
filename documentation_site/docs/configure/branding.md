---
sidebarDepth: 2
---

# Branding Presets (ReARM Pro)

::: warning ReARM Pro only
:::

A **branding preset** gives a ReARM Pro deployment another product name, logo
and contact details. Presets are bundled with ReARM Pro; a deployment selects
one by name with a single setting, and everything a preset does not set keeps
the default (Reliza) branding.

## What a preset changes

| Preset field | Where it shows |
|---|---|
| Product name | browser tab title, footer, sign-up and support messages, email subjects and the email sender name, the Keycloak login page title |
| Organization | the hover text of the footer's Legal link ("... is offered by ...") |
| Support name and address | the footer's Support link and every "contact support" message |
| Documentation, terms of service, privacy policy and legal links | the footer's Documentation and Legal links, the sign-up page's Terms Of Service and Privacy Policy links |
| Marketing consent | whether the sign-up page shows the news and promotions checkbox (and the matching option on the profile page), and its text |
| Logo | the navigation bar and the Keycloak login page |
| Favicon | the browser tab icon and the Keycloak login page |
| Sign-up background | the background of the sign-up, email verification and join-organization pages. A preset without one gets a plain background (a flat grey, no image); the Reliza beach background is shown on the default branding only |
| Login background | the background of the Keycloak login page. A preset without one gets Keycloak's own background; the Reliza background is shown on the default branding only |

With a preset selected, the footer shows the product name, a "Built on ReARM"
line and Documentation, Support and Legal links. In the navigation bar, a wide
logo (at least twice as wide as it is tall) is shown alone; a stacked or square
logo is shown as a mark with the product name beside it.

## Bundled presets

| Name | Product |
|---|---|
| `medware` | MedWare Cyber Dossier |

Presets are bundled with ReARM Pro: a new preset is a content change shipped
with a release. To request one, contact Reliza at
[info@reliza.io](mailto:info@reliza.io).

A preset declares its sign-up background as `signUpBackground: <file>` under
`assets` in its `preset.yaml` (a `jpg`, `jpeg`, `png` or `webp` file beside
it). The login page background is a separate file in the preset's Keycloak
login-page overlay, so a picture wanted on both pages is shipped twice.

## Selecting a preset

The preset is selected by name; an empty value or `default` means the Reliza
branding.

**Helm (ReARM Pro chart).** Set `brandingPreset` in your values file:

```yaml
brandingPreset: medware
```

The chart passes it to both the backend and the Keycloak pods, which restart
on the change.

**Docker Compose (ReARM Pro).** Set `BRANDING_PRESET` in your `.env` file:

```
BRANDING_PRESET=medware
```

The backend reads it as `RELIZAPROP_BRANDING_PRESET` and Keycloak as
`REARM_BRANDING_PRESET`. Restart both containers after changing it.

The UI needs no setting: it asks the backend for the branding when it starts.

## Fallback

A field a preset leaves out takes the default (Reliza) value. This applies
per field, per asset (logo, favicon) and per file of the login page. The two
backgrounds are the exception: a preset that provides no sign-up background
gets a plain one, and one that provides no login background shows Keycloak's
own; the Reliza backgrounds appear on the default branding only. The `medware`
preset sets the product name, organization, logo and favicon, and hides the
marketing consent checkbox; it leaves the support contact, documentation, terms
of service, privacy policy and legal links to the Reliza defaults; its sign-up
pages have the plain background and its login page has Keycloak's own
background.

## What is never branded

- The text of Slack, Microsoft Teams and GitHub notifications.
- Tool metadata in exported BOM, VEX and PDF documents.
- Protocol identifiers: commit trailers, the Keycloak realm name and similar.
- The `rearm` CLI and its name in the UI.
- Product names in help text, field labels and placeholders.
- The static error pages served while the backend is unavailable.

## Community Edition

ReARM CE always shows the default branding. A preset set on a CE deployment
is ignored, with a warning in the backend log.

## Troubleshooting

- **The UI shows the Reliza branding although a preset is set.** Check the
  backend log at startup. `Branding preset in use: <name>` confirms the
  preset; an unknown or invalid name logs
  `Branding preset '<name>' cannot be used (...); using default`, and the
  deployment runs with the default branding.
- **The login page is not branded.** Check the Keycloak log: a preset without
  a login-page overlay logs `branding preset "<name>" has no Keycloak overlay ...`
  and keeps the default login page. Keycloak reads the preset at startup, so
  restart it after a change.
- **The login page shows the Reliza beach background with a preset set.**
  Keycloak is still running without the preset (see the bullet above); with a
  preset applied the login page never shows it.
- **The sign-up page shows the Reliza beach background with a preset set.**
  Open `/api/branding/v1/settings`: with a preset applied,
  `signUpBackgroundUrl` is `null` or the preset's asset URL. If it is
  `/reliza_in_sand_right_corner.jpg`, the backend is running without the
  preset (see the first bullet).
- **The browser tab shows ReARM for a moment.** This is expected: the tab
  title and icon switch to the preset's once the UI has read the branding
  from the backend.

See also [Installation](/installation/) and [Configure ReARM](/configure/).
