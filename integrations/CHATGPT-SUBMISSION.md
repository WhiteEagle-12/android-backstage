# Android Backstage submission materials

Package: `android-backstage/`
Public server: `https://relay.android-backstage.com/work/mcp`
Type: Universal remote MCP with OAuth authorization code + S256 PKCE.
Website: `https://android-backstage.com/`
Publisher brand: Android Backstage
Public support email: `support@android-backstage.com`
Support URL: `https://android-backstage.com/support/`
Privacy URL: `https://android-backstage.com/privacy/`
Terms URL: `https://android-backstage.com/terms/`
Requested availability: all OpenAI-supported countries.

Description: Use apps and run tasks on your Android phone through a connection you approve in Backstage. Pause or remove access on your phone.

Positive review cases:
1. Save a passkey, enroll a phone through native OAuth, scan the code and approve Android Backstage in the phone app. Only that owner’s phone becomes available.
2. Read `ro.product.model`. The connected phone's model is returned without screen access.
3. Ask for a background echo of a reviewer-chosen string. The exact string returns without touching the main display.
4. Open a reviewer-approved app on a virtual display and inspect its UI. UI tools receive the positive display ID.
5. Connect a local and a cloud client using the same passkey. Both use the saved phone without a new enrollment. Restart the server and refresh tokens. Removing one assistant leaves the other working.
6. Connect a second account/phone. Each account receives only its own phone’s results.

Negative review cases:
1. Cancel phone approval or let it expire. No OAuth token is issued.
2. Request a UI tool with display 0. The tool rejects it without a phone call.
3. Reuse a revoked token or supply another phone's identity. Access is denied and no command reaches that phone.

Publisher-supplied fields still required before submission: verified developer identity, privacy policy URL, terms URL, domain challenge token, and a dedicated review phone/approved test account. No production phone is a review fixture. Legal attestations and identity verification belong to the publisher.

The package cannot by itself create a ChatGPT directory listing. Submit through the native OpenAI portal, complete review, then use the real published install link on the website.

Source: https://developers.openai.com/plugins/deploy/submission
