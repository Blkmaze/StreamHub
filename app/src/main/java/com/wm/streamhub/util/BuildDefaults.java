package com.wm.streamhub.util;

/**
 * Ship-time defaults. Change these before you build for your customers and the app
 * comes preconfigured — no typing on a TV remote required.
 *
 * Everything here is also editable at runtime in Settings.
 */
public final class BuildDefaults {

    private BuildDefaults() {
    }

    /** Supabase project REST URL, e.g. https://xxxx.supabase.co  (leave blank to disable chat) */
    public static final String CHAT_BASE_URL = "";

    /** Supabase anon/publishable key. Safe to ship: RLS restricts rows to the device. */
    public static final String CHAT_API_KEY = "";

    /** Fallback contact channels shown on the Support screen. */
    public static final String SUPPORT_WHATSAPP = "";   // e.g. 15551234567
    public static final String SUPPORT_TELEGRAM = "";   // e.g. yourhandle
    public static final String SUPPORT_EMAIL = "";      // e.g. support@yourdomain.com

    /**
     * Optional fixed account code for this build. Leave blank and the app derives the
     * account from the customer's own line (username@host), which groups every stick
     * in one household into a single conversation automatically.
     *
     * Set it when you build a per-customer APK and want your own reference number
     * (invoice ID, CRM ID) to be what shows up in the support console.
     */
    public static final String ACCOUNT_CODE = "";

    /** Optional: URL returning JSON {"message":"...","updatedAt":1699999999} shown as a banner. */
    public static final String NOTICE_URL = "";

    /**
     * Optional preloaded server. Leave blank for a clean install where the
     * customer adds their own line.
     */
    public static final String PRESET_NAME = "";
    public static final String PRESET_HOST = "";
    public static final String PRESET_USER = "";
    public static final String PRESET_PASS = "";
    public static final String PRESET_M3U = "";
}
