/*
 * Copyright 2014-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.common;

import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.net.Uri;
import android.text.format.DateUtils;

import com.google.common.base.Strings;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.MonetaryFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dash.wallet.common.R;

/**
 * @author Andreas Schildbach
 */
public class Configuration {
    public final int lastVersionCode;

    private final SharedPreferences prefs;
    private final Resources res;

    public static final String PREFS_KEY_BTC_PRECISION = "btc_precision";
    public static final String PREFS_KEY_OWN_NAME = "own_name";
    public static final String PREFS_KEY_SEND_COINS_AUTOCLOSE = "send_coins_autoclose";
    public static final String PREFS_KEY_CONNECTIVITY_NOTIFICATION = "connectivity_notification";
    public static final String PREFS_KEY_EXCHANGE_CURRENCY = "exchange_currency";
    public static final String PREFS_KEY_EXCHANGE_CURRENCY_DETECTED = "exchange_currency_detected";
    public static final String PREFS_KEY_TRUSTED_PEER = "trusted_peer";
    public static final String PREFS_KEY_TRUSTED_PEER_ONLY = "trusted_peer_only";
    public static final String PREFS_KEY_BLOCK_EXPLORER = "block_explorer";
    public static final String PREFS_KEY_DATA_USAGE = "data_usage";
    public static final String PREFS_KEY_REMIND_BALANCE = "remind_balance";
    public static final String PREFS_KEY_DISCLAIMER = "disclaimer";
    private static final String PREFS_KEY_LABS_QR_PAYMENT_REQUEST = "labs_qr_payment_request";
    private static final String PREFS_KEY_LOOK_UP_WALLET_NAMES = "look_up_wallet_names";
    private static final String PREFS_KEY_PREVIOUS_VERSION = "previous_version";

    private static final String PREFS_KEY_LAST_VERSION = "last_version";
    private static final String PREFS_KEY_LAST_USED = "last_used";
    private static final String PREFS_KEY_BEST_CHAIN_HEIGHT_EVER = "best_chain_height_ever";
    private static final String PREFS_KEY_LAST_EXCHANGE_DIRECTION = "last_exchange_direction";
    private static final String PREFS_KEY_CHANGE_LOG_VERSION = "change_log_version";
    public static final String PREFS_KEY_REMIND_BACKUP = "remind_backup";
    private static final String PREFS_KEY_LAST_BACKUP = "last_backup";
    public static final String PREFS_KEY_REMIND_BACKUP_SEED = "remind_backup_seed";
    private static final String PREFS_KEY_LAST_BACKUP_SEED = "last_backup_seed";
    public static final String PREFS_KEY_INSTANTX_ENABLED = "labs_instantx_enabled";
    public static final String PREFS_KEY_LITE_MODE = "labs_lite_mode";
    private static final String PREFS_REMIND_ENABLE_FINGERPRINT = "remind_enable_fingerprint";

    private static final int PREFS_DEFAULT_BTC_SHIFT = 0;
    private static final int PREFS_DEFAULT_BTC_PRECISION = 4;

    private static final Logger log = LoggerFactory.getLogger(Configuration.class);

    public Configuration(final SharedPreferences prefs, final Resources res) {
        this.prefs = prefs;
        this.res = res;

        this.lastVersionCode = prefs.getInt(PREFS_KEY_LAST_VERSION, 0);
    }

    private int getBtcPrecision() {
        final String precision = prefs.getString(PREFS_KEY_BTC_PRECISION, null);
        if (precision != null)
            return precision.charAt(0) - '0';
        else
            return PREFS_DEFAULT_BTC_PRECISION;
    }

    public int getBtcShift() {
        final String precision = prefs.getString(PREFS_KEY_BTC_PRECISION, null);
        if (precision != null)
            return precision.length() == 3 ? precision.charAt(2) - '0' : 0;
        else
            return PREFS_DEFAULT_BTC_SHIFT;
    }

    public Coin getBtcBase() {
        final int shift = getBtcShift();
        if (shift == 0)
            return Coin.COIN;
        else if (shift == 3)
            return Coin.MILLICOIN;
        else if (shift == 6)
            return Coin.MICROCOIN;
        else
            throw new IllegalStateException("cannot handle shift: " + shift);
    }

    public MonetaryFormat getFormat() {
        final int shift = getBtcShift();
        final int minPrecision = shift <= 3 ? 2 : 0;
        final int decimalRepetitions = (getBtcPrecision() - minPrecision) / 2;
        return new MonetaryFormat().shift(shift).minDecimals(minPrecision).repeatOptionalDecimals(2,
                decimalRepetitions);
    }

    public MonetaryFormat getMaxPrecisionFormat() {
        final int shift = getBtcShift();
        if (shift == 0)
            return new MonetaryFormat().shift(0).minDecimals(2).optionalDecimals(2, 2, 2);
        else if (shift == 3)
            return new MonetaryFormat().shift(3).minDecimals(2).optionalDecimals(2, 1);
        else
            return new MonetaryFormat().shift(6).minDecimals(0).optionalDecimals(2);
    }

    public String getOwnName() {
        return Strings.emptyToNull(prefs.getString(PREFS_KEY_OWN_NAME, "").trim());
    }

    public boolean getSendCoinsAutoclose() {
        return prefs.getBoolean(PREFS_KEY_SEND_COINS_AUTOCLOSE, true);
    }

    public boolean getConnectivityNotificationEnabled() {
        return prefs.getBoolean(PREFS_KEY_CONNECTIVITY_NOTIFICATION, false);
    }

    public String getTrustedPeerHost() {
        return Strings.emptyToNull(prefs.getString(PREFS_KEY_TRUSTED_PEER, "").trim());
    }

    public boolean getTrustedPeerOnly() {
        return prefs.getBoolean(PREFS_KEY_TRUSTED_PEER_ONLY, false);
    }

    public Uri getBlockExplorer() {
        return Uri.parse(prefs.getString(PREFS_KEY_BLOCK_EXPLORER,
                res.getStringArray(R.array.preferences_block_explorer_values)[0]));
    }

    public boolean remindBalance() {
        return prefs.getBoolean(PREFS_KEY_REMIND_BALANCE, true);
    }

    public void setRemindBalance(final boolean remindBalance) {
        prefs.edit().putBoolean(PREFS_KEY_REMIND_BALANCE, remindBalance).apply();
    }

    public boolean remindBackup() {
        return prefs.getBoolean(PREFS_KEY_REMIND_BACKUP, true);
    }
    public boolean remindBackupSeed() {
        return prefs.getBoolean(PREFS_KEY_REMIND_BACKUP_SEED, true);
    }
    public long getLastBackupTime() {
        return prefs.getLong(PREFS_KEY_LAST_BACKUP, 0);
    }

    public void armBackupReminder() {
        prefs.edit().putBoolean(PREFS_KEY_REMIND_BACKUP, true).apply();
    }

    public void disarmBackupReminder() {
        prefs.edit().putBoolean(PREFS_KEY_REMIND_BACKUP, false)
                .putLong(PREFS_KEY_LAST_BACKUP, System.currentTimeMillis()).apply();
    }

    public long getLastBackupSeedTime() {
        return prefs.getLong(PREFS_KEY_LAST_BACKUP_SEED, 0);
    }

    public void armBackupSeedReminder() {
        prefs.edit().putBoolean(PREFS_KEY_REMIND_BACKUP_SEED, true).apply();
    }

    public void disarmBackupSeedReminder() {
        prefs.edit().putBoolean(PREFS_KEY_REMIND_BACKUP_SEED, false)
                .putLong(PREFS_KEY_LAST_BACKUP_SEED, System.currentTimeMillis()).apply();
    }

    public boolean getDisclaimerEnabled() {
        return prefs.getBoolean(PREFS_KEY_DISCLAIMER, true);
    }

    public void setDisclaimerEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREFS_KEY_DISCLAIMER, enabled).apply();
    }

    public String getExchangeCurrencyCode() {
        return prefs.getString(PREFS_KEY_EXCHANGE_CURRENCY, null);
    }

    public void setExchangeCurrencyCode(final String exchangeCurrencyCode) {
        prefs.edit().putString(PREFS_KEY_EXCHANGE_CURRENCY, exchangeCurrencyCode).apply();
    }

    public boolean getExchangeCurrencyCodeDetected() {
        return prefs.getBoolean(PREFS_KEY_EXCHANGE_CURRENCY_DETECTED, false);
    }

    public void setExchangeCurrencyCodeDetected(boolean detected) {
        prefs.edit().putBoolean(PREFS_KEY_EXCHANGE_CURRENCY_DETECTED, detected).apply();
    }

    /**
     * @return whether the app was ever upgraded of if it's running on the first version in which
     * it was installed
     */
    public boolean wasUpgraded() {
        return prefs.getInt(PREFS_KEY_PREVIOUS_VERSION, 0) != 0;
    }

    public boolean getQrPaymentRequestEnabled() {
        return prefs.getBoolean(PREFS_KEY_LABS_QR_PAYMENT_REQUEST, false);
    }

    public boolean getLookUpWalletNames() {
        return prefs.getBoolean(PREFS_KEY_LOOK_UP_WALLET_NAMES, false);
    }

    public boolean versionCodeCrossed(final int currentVersionCode, final int triggeringVersionCode) {
        final boolean wasBelow = lastVersionCode < triggeringVersionCode;
        final boolean wasUsedBefore = lastVersionCode > 0;
        final boolean isNowAbove = currentVersionCode >= triggeringVersionCode;

        return wasUsedBefore && wasBelow && isNowAbove;
    }

    public void updateLastVersionCode(final int currentVersionCode) {
        Editor editor = prefs.edit();
        editor.putInt(PREFS_KEY_PREVIOUS_VERSION, prefs.getInt(PREFS_KEY_LAST_VERSION, 0));
        editor.putInt(PREFS_KEY_LAST_VERSION, currentVersionCode);
        editor.apply();

        if (currentVersionCode > lastVersionCode)
            log.info("detected app upgrade: " + lastVersionCode + " -> " + currentVersionCode);
        else if (currentVersionCode < lastVersionCode)
            log.warn("detected app downgrade: " + lastVersionCode + " -> " + currentVersionCode);
    }

    public boolean hasBeenUsed() {
        return prefs.contains(PREFS_KEY_LAST_USED);
    }

    public long getLastUsedAgo() {
        final long now = System.currentTimeMillis();

        return now - prefs.getLong(PREFS_KEY_LAST_USED, 0);
    }

    public void touchLastUsed() {
        final long prefsLastUsed = prefs.getLong(PREFS_KEY_LAST_USED, 0);
        final long now = System.currentTimeMillis();
        prefs.edit().putLong(PREFS_KEY_LAST_USED, now).apply();

        log.info("just being used - last used {} minutes ago", (now - prefsLastUsed) / DateUtils.MINUTE_IN_MILLIS);
    }

    public int getBestChainHeightEver() {
        return prefs.getInt(PREFS_KEY_BEST_CHAIN_HEIGHT_EVER, 0);
    }

    public void maybeIncrementBestChainHeightEver(final int bestChainHeightEver) {
        if (bestChainHeightEver > getBestChainHeightEver())
            prefs.edit().putInt(PREFS_KEY_BEST_CHAIN_HEIGHT_EVER, bestChainHeightEver).apply();
    }

        public boolean getLastExchangeDirection() {
        return prefs.getBoolean(PREFS_KEY_LAST_EXCHANGE_DIRECTION, true);
    }

    public void setLastExchangeDirection(final boolean exchangeDirection) {
        prefs.edit().putBoolean(PREFS_KEY_LAST_EXCHANGE_DIRECTION, exchangeDirection).apply();
    }

    public boolean changeLogVersionCodeCrossed(final int currentVersionCode, final int triggeringVersionCode) {
        final int changeLogVersion = prefs.getInt(PREFS_KEY_CHANGE_LOG_VERSION, 0);

        final boolean wasBelow = changeLogVersion < triggeringVersionCode;
        final boolean wasUsedBefore = changeLogVersion > 0;
        final boolean isNowAbove = currentVersionCode >= triggeringVersionCode;

        prefs.edit().putInt(PREFS_KEY_CHANGE_LOG_VERSION, currentVersionCode).apply();

        return /* wasUsedBefore && */wasBelow && isNowAbove;
    }

    public void registerOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    public boolean getInstantXEnabled() {
        return prefs.getBoolean(PREFS_KEY_INSTANTX_ENABLED, true);
    }

    public boolean getLiteMode() {
        return prefs.getBoolean(PREFS_KEY_LITE_MODE, true);
    }

    public boolean getRemindEnableFingerprint() {
        return prefs.getBoolean(PREFS_REMIND_ENABLE_FINGERPRINT, true);
    }

    public void setRemindEnableFingerprint(boolean remind) {
        prefs.edit().putBoolean(PREFS_REMIND_ENABLE_FINGERPRINT, remind).apply();
    }
}
