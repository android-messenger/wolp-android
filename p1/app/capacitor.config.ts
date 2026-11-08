import type { CapacitorConfig } from '@capacitor/cli';

// S1 §2.1, variant A. Host is the local P1 stand (p1/stand), see p1/README.md.
const host = process.env.WOLP_HOST ?? 'wolp.test';

const config: CapacitorConfig = {
  appId: 'org.wolpmessenger',
  appName: 'Wolp P1',
  webDir: 'stub',
  server: {
    // P1-b: an origin rule must not contain a path. With url = "https://app.<host>/messenger-ui/" Capacitor's
    // addWebMessageListener rejects it and silently falls back to addJavascriptInterface (no origin check at all).
    url: `https://app.${host}`,
    appStartPath: '/messenger-ui/',
    androidScheme: 'https',
    cleartext: false,
    // enroll is NOT listed: allowNavigation hosts become allowed bridge origins. Navigation to enroll is
    // permitted by WolpWebViewClient.shouldOverrideUrlLoading instead.
    allowNavigation: [],
  },
  android: {
    allowMixedContent: false,
    webContentsDebuggingEnabled: true, // prototype = debug only
  },
};

export default config;
