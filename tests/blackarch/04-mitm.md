# 04 — MITM / TLS interception

**Threat class**: man-in-the-middle, TLS interception
**Tools**: mitmproxy, sslstrip, bettercap, Burp Suite proxy mode.

**passgen defense**: passgen has zero `INTERNET` permission. The OS
itself prevents network calls. Even if it did make calls,
`network_security_config.xml` denies cleartext for every domain and
declines to trust any custom CA.

## Setup

On your laptop:

```bash
pip install mitmproxy
mitmproxy --listen-port 8080
```

On the phone:
1. Install mitmproxy's CA certificate (Settings → Security → Encryption
   & credentials → Install a certificate → CA certificate → from
   `http://mitm.it/cert/cer`)
2. Settings → Wi-Fi → tap your network → Advanced → Proxy → Manual →
   `<your laptop IP>:8080`

## Test 1: traffic capture during all passgen flows

With mitmproxy running and the phone proxied, exercise every passgen
feature: open the app, generate a password to clipboard, set up the
vault, unlock it, view an entry, switch to the IME and generate.

In mitmproxy's flow view: **zero entries should appear from
`com.understory.passgen`.**

To confirm what processes ARE making calls (sanity check that
mitmproxy is working): leave the phone proxied for a minute and watch
unrelated apps' traffic appear.

## Test 2: dpkg-grep style verification

Even simpler: `aapt2 dump permissions android/dist/passgen.apk` should
not include `android.permission.INTERNET`. Already covered by test 11
(permissions) but worth noting here as the load-bearing defense.

## Pass/fail

✅ mitmproxy logs zero requests from passgen during any feature use
✅ APK declares no INTERNET permission
