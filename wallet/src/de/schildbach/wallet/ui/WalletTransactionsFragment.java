/*
 * Copyright 2011-2015 the original author or authors.
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

package de.schildbach.wallet.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.CoinDefinition;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Transaction.Purpose;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.AddressBookProvider;
import de.schildbach.wallet.data.WalletLock;
import de.schildbach.wallet.ui.TransactionsAdapter.Warning;
import de.schildbach.wallet.ui.send.RaiseFeeDialogFragment;
import de.schildbach.wallet.util.BitmapFragment;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.FingerprintHelper;
import de.schildbach.wallet.util.Qr;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.support.annotation.RequiresApi;
import android.support.v4.content.ContextCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.os.CancellationSignal;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.PopupMenu.OnMenuItemClickListener;
import android.widget.TextView;

/**
 * @author Andreas Schildbach
 */
public class WalletTransactionsFragment extends Fragment implements LoaderManager.LoaderCallbacks<List<Transaction>>,
        TransactionsAdapter.OnClickListener, OnSharedPreferenceChangeListener, WalletLock.OnLockChangeListener {


    public enum Direction {
        RECEIVED, SENT;
    }
    private AbstractWalletActivity activity;

    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private ContentResolver resolver;
    private LoaderManager loaderManager;
    private DevicePolicyManager devicePolicyManager;

    private TextView emptyView;
    private View loading;
    private View transactionListContainer;
    private View fingerprintGroup;
    private ImageView fingerprintIcon;
    private TextView fingerprintText;
    private RecyclerView recyclerView;
    private View backupDisclaimerView;
    private TextView backupDisclaimerTitle;
    private TransactionsAdapter adapter;
    private MenuItem filterMenuItem;
    @Nullable
    private Direction direction;

    private FingerprintHelper fingerprintHelper;
    private CancellationSignal fingerprintCancellationSignal;

    private final Handler handler = new Handler();

    private static final int ID_TRANSACTION_LOADER = 0;

    private static final String ARG_DIRECTION = "direction";
    private static final long THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;

    private static final Uri KEY_ROTATION_URI = Uri.parse("https://bitcoin.org/en/alert/2013-08-11-android");
    private static final int SHOW_QR_THRESHOLD_BYTES = 2500;
    private static final Logger log = LoggerFactory.getLogger(WalletTransactionsFragment.class);

    private final ContentObserver addressBookObserver = new ContentObserver(handler) {
        @Override
        public void onChange(final boolean selfChange) {
            adapter.clearCacheAndNotifyDataSetChanged();
        }
    };

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (AbstractWalletActivity) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        this.wallet = application.getWallet();
        this.resolver = activity.getContentResolver();
        this.loaderManager = getLoaderManager();
        this.devicePolicyManager = (DevicePolicyManager) application.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(true);
        setHasOptionsMenu(true);

        adapter = new TransactionsAdapter(activity, wallet, application.maxConnectedPeers(), this);
        adapter.setShowTransactionRowMenu(true);

        this.direction = null;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        recyclerView.setAdapter(null);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.wallet_transactions_fragment, container, false);

        emptyView = view.findViewById(R.id.wallet_transactions_empty);
        fingerprintGroup = view.findViewById(R.id.fingerprint_group);
        fingerprintIcon = fingerprintGroup.findViewById(R.id.fingerprint_icon);
        fingerprintText = fingerprintGroup.findViewById(R.id.fingerprint_text);
        loading = view.findViewById(R.id.loading);
        transactionListContainer = view.findViewById(R.id.transaction_list_container);

        backupDisclaimerView = view.findViewById(R.id.backup_wallet_disclaimer);
        backupDisclaimerTitle = (TextView) view.findViewById(R.id.backup_warning_title);

        view.findViewById(R.id.backup_now).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackupNowClicked();
            }
        });
        view.findViewById(R.id.remind_later).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WalletApplication.getInstance().setBackupDisclaimerDismissed(true);
                backupDisclaimerView.setVisibility(View.GONE);
            }
        });

        recyclerView = (RecyclerView) view.findViewById(R.id.wallet_transactions_list);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setAdapter(adapter);
        recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            private final int PADDING = 2
                    * activity.getResources().getDimensionPixelOffset(R.dimen.card_padding_vertical);

            @Override
            public void getItemOffsets(final Rect outRect, final View view, final RecyclerView parent,
                    final RecyclerView.State state) {
                super.getItemOffsets(outRect, view, parent, state);

                final int position = parent.getChildAdapterPosition(view);
                if (position == 0)
                    outRect.top += PADDING;
                else if (position == parent.getAdapter().getItemCount() - 1)
                    outRect.bottom += PADDING;
            }
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        resolver.registerContentObserver(AddressBookProvider.contentUri(activity.getPackageName()), true,
                addressBookObserver);

        config.registerOnSharedPreferenceChangeListener(this);

        final Bundle args = new Bundle();
        args.putSerializable(ARG_DIRECTION, direction);
        loaderManager.initLoader(ID_TRANSACTION_LOADER, args, this);

        wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, transactionChangeListener);
        wallet.addCoinsSentEventListener(Threading.SAME_THREAD, transactionChangeListener);
        wallet.addChangeEventListener(Threading.SAME_THREAD, transactionChangeListener);
        wallet.addTransactionConfidenceEventListener(Threading.SAME_THREAD, transactionChangeListener);

        updateView();

        WalletLock.getInstance().addListener(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            initFingerprintHelper();
        }
    }

    @Override
    public void onPause() {
        wallet.removeTransactionConfidenceEventListener(transactionChangeListener);
        wallet.removeChangeEventListener(transactionChangeListener);
        wallet.removeCoinsSentEventListener(transactionChangeListener);
        wallet.removeCoinsReceivedEventListener(transactionChangeListener);
        transactionChangeListener.removeCallbacks();

        loaderManager.destroyLoader(ID_TRANSACTION_LOADER);

        config.unregisterOnSharedPreferenceChangeListener(this);

        resolver.unregisterContentObserver(addressBookObserver);

        WalletLock.getInstance().removeListener(this);

        if (fingerprintCancellationSignal != null) {
            fingerprintCancellationSignal.cancel();
        }
        super.onPause();
    }

    @Override
    public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
        inflater.inflate(R.menu.wallet_transactions_fragment_options, menu);
        filterMenuItem = menu.findItem(R.id.wallet_transactions_options_filter);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onPrepareOptionsMenu(final Menu menu) {
        if (direction == null) {
            menu.findItem(R.id.wallet_transactions_options_filter_all).setChecked(true);
            maybeSetFilterMenuItemIcon(R.drawable.ic_filter_list_white_24dp);
        } else if (direction == Direction.RECEIVED) {
            menu.findItem(R.id.wallet_transactions_options_filter_received).setChecked(true);
            maybeSetFilterMenuItemIcon(R.drawable.transactions_list_filter_received);
        } else if (direction == Direction.SENT) {
            menu.findItem(R.id.wallet_transactions_options_filter_sent).setChecked(true);
            maybeSetFilterMenuItemIcon(R.drawable.transactions_list_filter_sent);
        }
        super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == R.id.wallet_transactions_options_filter_all) {
            direction = null;
            maybeSetFilterMenuItemIcon(R.drawable.ic_filter_list_white_24dp);
        } else if (itemId == R.id.wallet_transactions_options_filter_received) {
            direction = Direction.RECEIVED;
            maybeSetFilterMenuItemIcon(R.drawable.transactions_list_filter_received);
        } else if (itemId == R.id.wallet_transactions_options_filter_sent) {
            direction = Direction.SENT;
            maybeSetFilterMenuItemIcon(R.drawable.transactions_list_filter_sent);
        } else {
            return false;
        }
        item.setChecked(true);

        reloadTransactions();
        return true;
    }

    private void reloadTransactions() {
        final Bundle args = new Bundle();
        args.putSerializable(ARG_DIRECTION, direction);
        loaderManager.restartLoader(ID_TRANSACTION_LOADER, args, this);
    }

    private void maybeSetFilterMenuItemIcon(final int iconResId) {
        // Older Android versions can't deal with width and height in XML layer-list items.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            filterMenuItem.setIcon(iconResId);
    }

    @Override
    public void onTransactionMenuClick(final View view, final Transaction tx) {
        final boolean txSent = tx.getValue(wallet).signum() < 0;
        final Address txAddress = txSent ? WalletUtils.getToAddressOfSent(tx, wallet)
                : WalletUtils.getWalletAddressOfReceived(tx, wallet);
        final byte[] txSerialized = tx.unsafeBitcoinSerialize();
        final boolean txRotation = tx.getPurpose() == Purpose.KEY_ROTATION;

        Context wrapper = new ContextThemeWrapper(activity, R.style.My_PopupOverlay);
        final PopupMenu popupMenu = new PopupMenu(wrapper, view);
        popupMenu.inflate(R.menu.wallet_transactions_context);
        final MenuItem editAddressMenuItem = popupMenu.getMenu()
                .findItem(R.id.wallet_transactions_context_edit_address);
        if (!txRotation && txAddress != null) {
            editAddressMenuItem.setVisible(true);
            final boolean isAdd = AddressBookProvider.resolveLabel(activity, txAddress.toBase58()) == null;
            final boolean isOwn = wallet.isPubKeyHashMine(txAddress.getHash160());

            if (isOwn)
                editAddressMenuItem.setTitle(isAdd ? R.string.edit_address_book_entry_dialog_title_add_receive
                        : R.string.edit_address_book_entry_dialog_title_edit_receive);
            else
                editAddressMenuItem.setTitle(isAdd ? R.string.edit_address_book_entry_dialog_title_add
                        : R.string.edit_address_book_entry_dialog_title_edit);
        } else {
            editAddressMenuItem.setVisible(false);
        }

        popupMenu.getMenu().findItem(R.id.wallet_transactions_context_show_qr)
                .setVisible(!txRotation && txSerialized.length < SHOW_QR_THRESHOLD_BYTES);
        popupMenu.getMenu().findItem(R.id.wallet_transactions_context_raise_fee)
                .setVisible(RaiseFeeDialogFragment.feeCanLikelyBeRaised(wallet, tx) && CoinDefinition.feeCanBeRaised);
        popupMenu.setOnMenuItemClickListener(new OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(final MenuItem item) {
                switch (item.getItemId()) {
                case R.id.wallet_transactions_context_edit_address:
                    handleEditAddress(tx);
                    return true;

                case R.id.wallet_transactions_context_show_qr:
                    handleShowQr();
                    return true;

                case R.id.wallet_transactions_context_raise_fee:
                    RaiseFeeDialogFragment.show(getFragmentManager(), tx);
                    return true;

                case R.id.wallet_transactions_context_report_issue:
                    handleReportIssue(tx);
                    return true;

                case R.id.wallet_transactions_context_browse:
                    if (!txRotation)
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.withAppendedPath(config.getBlockExplorer(), "tx/" + tx.getHashAsString())));
                    else
                        startActivity(new Intent(Intent.ACTION_VIEW, KEY_ROTATION_URI));
                    return true;
                }

                return false;
            }

            private void handleEditAddress(final Transaction tx) {
                EditAddressBookEntryFragment.edit(getFragmentManager(), txAddress);
            }

            private void handleShowQr() {
                final Bitmap qrCodeBitmap = Qr.bitmap(Qr.encodeCompressBinary(txSerialized));
                BitmapFragment.show(getFragmentManager(), qrCodeBitmap);
            }

            private void handleReportIssue(final Transaction tx) {
                final ReportIssueDialogBuilder dialog = new ReportIssueDialogBuilder(activity,
                        R.string.report_issue_dialog_title_transaction, R.string.report_issue_dialog_message_issue) {
                    @Override
                    protected CharSequence subject() {
                        return Constants.REPORT_SUBJECT_ISSUE + " " + application.packageInfo().versionName;
                    }

                    @Override
                    protected CharSequence collectApplicationInfo() throws IOException {
                        final StringBuilder applicationInfo = new StringBuilder();
                        CrashReporter.appendApplicationInfo(applicationInfo, application);
                        return applicationInfo;
                    }

                    @Override
                    protected CharSequence collectDeviceInfo() throws IOException {
                        final StringBuilder deviceInfo = new StringBuilder();
                        CrashReporter.appendDeviceInfo(deviceInfo, activity);
                        return deviceInfo;
                    }

                    @Override
                    protected CharSequence collectContextualData() {
                        final StringBuilder contextualData = new StringBuilder();
                        try {
                            contextualData.append(tx.getValue(wallet).toFriendlyString()).append(" total value");
                        } catch (final ScriptException x) {
                            contextualData.append(x.getMessage());
                        }
                        contextualData.append('\n');
                        if (tx.hasConfidence())
                            contextualData.append("  confidence: ").append(tx.getConfidence()).append('\n');
                        contextualData.append(tx.toString());
                        return contextualData;
                    }

                    @Override
                    protected CharSequence collectWalletDump() {
                        return application.getWallet().toString(false, true, true, null);
                    }
                };
                dialog.show();
            }
        });
        popupMenu.show();
    }

    private void onBackupNowClicked() {
        if (config.remindBackupSeed()) {
            ((WalletActivity) activity).handleBackupWalletToSeed();
        } else if (Constants.SUPPORT_BOTH_BACKUP_WARNINGS && config.remindBackup()) {
            ((WalletActivity) activity).handleBackupWallet();
        }
    }

    @Override
    public void onWarningClick() {
        switch (warning()) {
        case STORAGE_ENCRYPTION:
            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            break;
        }
    }

    @Override
    public Loader<List<Transaction>> onCreateLoader(final int id, final Bundle args) {
        return new TransactionsLoader(activity, wallet, (Direction) args.getSerializable(ARG_DIRECTION));
    }

    @Override
    public void onLoadFinished(final Loader<List<Transaction>> loader, final List<Transaction> transactions) {
        final Direction direction = ((TransactionsLoader) loader).getDirection();

        loading.setVisibility(View.GONE);
        adapter.replace(transactions);
        updateView();

        if (WalletLock.getInstance().isWalletLocked(wallet)) {
            showLockedView();
        } else if (transactions.isEmpty()) {
            showEmptyTransactions(direction);
        } else {
            showTransactionList();
        }
    }

    private void showTransactionList() {
        fingerprintGroup.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);
        transactionListContainer.setVisibility(View.VISIBLE);
    }

    private void showEmptyView() {
        emptyView.setVisibility(View.VISIBLE);
        fingerprintGroup.setVisibility(View.GONE);
        transactionListContainer.setVisibility(View.GONE);
    }

    private void showLockedView() {
        showEmptyView();

        final SpannableStringBuilder lockedWalletText = new SpannableStringBuilder(
                getString(R.string.wallet_lock_unlock_to_see_txs_title));
        int titleLength = lockedWalletText.length();

        lockedWalletText.setSpan(new StyleSpan(Typeface.BOLD), 0, titleLength,
                SpannableStringBuilder.SPAN_POINT_MARK);
        lockedWalletText.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.darkest_blue)),
                0, titleLength, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        String unlockToSeeTxt = getString(R.string.wallet_lock_unlock_to_see_txs_txt);
        lockedWalletText.append("\n\n").append(unlockToSeeTxt);
        lockedWalletText.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.medium_gray)),
                titleLength, lockedWalletText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        Matcher matcher = Pattern.compile("\n\n").matcher(lockedWalletText);
        while (matcher.find()) {
            lockedWalletText.setSpan(new AbsoluteSizeSpan(10, true),
                    matcher.start() + 1, matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        emptyView.setText(lockedWalletText);
        fingerprintGroup.setVisibility(fingerprintHelper != null &&
                fingerprintHelper.isFingerprintEnabled() ? View.VISIBLE : View.GONE);
    }

    private void showEmptyTransactions(Direction direction) {
        showEmptyView();

        final SpannableStringBuilder emptyText = new SpannableStringBuilder(
                getString(direction == Direction.SENT ? R.string.wallet_transactions_fragment_empty_text_sent
                        : R.string.wallet_transactions_fragment_empty_text_received));
        int titleLength = emptyText.length();
        emptyText.setSpan(new StyleSpan(Typeface.BOLD), 0, titleLength,
                SpannableStringBuilder.SPAN_POINT_MARK);
        emptyText.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.darkest_blue)),
                0, titleLength, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (direction != Direction.SENT) {
            emptyText.append("\n\n").append(getString(R.string.wallet_transactions_fragment_empty_text_howto));
            emptyText.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.medium_gray)),
                    titleLength, emptyText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        Matcher matcher = Pattern.compile("\n\n").matcher(emptyText);
        while (matcher.find()) {
            emptyText.setSpan(new AbsoluteSizeSpan(10, true),
                    matcher.start() + 1, matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        emptyView.setText(emptyText);
    }

    @Override
    public void onLoaderReset(final Loader<List<Transaction>> loader) {
        // don't clear the adapter, because it will confuse users
    }

    private final ThrottlingWalletChangeListener transactionChangeListener = new ThrottlingWalletChangeListener(
            THROTTLE_MS) {
        @Override
        public void onThrottledWalletChanged() {
            adapter.notifyDataSetChanged();
        }
    };

    private static class TransactionsLoader extends AsyncTaskLoader<List<Transaction>> {

        private LocalBroadcastManager broadcastManager;
        private final Wallet wallet;
        @Nullable
        private final Direction direction;
        private TransactionsLoader(final Context context, final Wallet wallet, @Nullable final Direction direction) {
            super(context);

            this.broadcastManager = LocalBroadcastManager.getInstance(context.getApplicationContext());
            this.wallet = wallet;
            this.direction = direction;
        }

        public @Nullable Direction getDirection() {
            return direction;
        }

        @Override
        protected void onStartLoading() {
            super.onStartLoading();

            wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, transactionAddRemoveListener);
            wallet.addCoinsSentEventListener(Threading.SAME_THREAD, transactionAddRemoveListener);
            wallet.addChangeEventListener(Threading.SAME_THREAD, transactionAddRemoveListener);
            broadcastManager.registerReceiver(walletChangeReceiver,
                    new IntentFilter(WalletApplication.ACTION_WALLET_REFERENCE_CHANGED));
            transactionAddRemoveListener.onReorganize(null); // trigger at least one reload

            safeForceLoad();
        }

        @Override
        protected void onStopLoading() {
            broadcastManager.unregisterReceiver(walletChangeReceiver);
            wallet.removeChangeEventListener(transactionAddRemoveListener);
            wallet.removeCoinsSentEventListener(transactionAddRemoveListener);
            wallet.removeCoinsReceivedEventListener(transactionAddRemoveListener);
            transactionAddRemoveListener.removeCallbacks();

            super.onStopLoading();
        }

        @Override
        protected void onReset() {
            broadcastManager.unregisterReceiver(walletChangeReceiver);
            wallet.removeChangeEventListener(transactionAddRemoveListener);
            wallet.removeCoinsSentEventListener(transactionAddRemoveListener);
            wallet.removeCoinsReceivedEventListener(transactionAddRemoveListener);
            transactionAddRemoveListener.removeCallbacks();

            super.onReset();
        }

        @Override
        public List<Transaction> loadInBackground() {
            org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

            final Set<Transaction> transactions = wallet.getTransactions(true);
            final List<Transaction> filteredTransactions = new ArrayList<Transaction>(transactions.size());

            for (final Transaction tx : transactions) {
                final boolean sent = tx.getValue(wallet).signum() < 0;
                final boolean isInternal = tx.getPurpose() == Purpose.KEY_ROTATION;

                if ((direction == Direction.RECEIVED && !sent && !isInternal) || direction == null
                        || (direction == Direction.SENT && sent && !isInternal))
                    filteredTransactions.add(tx);
            }

            Collections.sort(filteredTransactions, TRANSACTION_COMPARATOR);

            return filteredTransactions;
        }

        private final ThrottlingWalletChangeListener transactionAddRemoveListener = new ThrottlingWalletChangeListener(
                THROTTLE_MS, true, true, false) {
            @Override
            public void onThrottledWalletChanged() {
                safeForceLoad();
            }
        };

        private final BroadcastReceiver walletChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                safeForceLoad();
            }
        };

        private void safeForceLoad() {
            try {
                forceLoad();
            } catch (final RejectedExecutionException x) {
                log.info("rejected execution: " + TransactionsLoader.this.toString());
            }
        }

        private static final Comparator<Transaction> TRANSACTION_COMPARATOR = new Comparator<Transaction>() {
            @Override
            public int compare(final Transaction tx1, final Transaction tx2) {
                final boolean pending1 = tx1.getConfidence().getConfidenceType() == ConfidenceType.PENDING;
                final boolean pending2 = tx2.getConfidence().getConfidenceType() == ConfidenceType.PENDING;

                if (pending1 != pending2)
                    return pending1 ? -1 : 1;

                final Date updateTime1 = tx1.getUpdateTime();
                final long time1 = updateTime1 != null ? updateTime1.getTime() : 0;
                final Date updateTime2 = tx2.getUpdateTime();
                final long time2 = updateTime2 != null ? updateTime2.getTime() : 0;

                if (time1 != time2)
                    return time1 > time2 ? -1 : 1;

                return tx1.getHash().compareTo(tx2.getHash());
            }
        };

    }
    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (Configuration.PREFS_KEY_BTC_PRECISION.equals(key) || Configuration.PREFS_KEY_REMIND_BACKUP.equals(key) ||
                Configuration.PREFS_KEY_REMIND_BACKUP_SEED.equals(key))
            updateView();
    }

    private void updateView() {
        adapter.setFormat(config.getFormat());
        adapter.setWarning(warning());

        boolean showBackupDisclaimer = (config.remindBackupSeed()
                || (Constants.SUPPORT_BOTH_BACKUP_WARNINGS && config.remindBackup()))
                && !WalletApplication.getInstance().isBackupDisclaimerDismissed();

        if (showBackupDisclaimer) {
            backupDisclaimerView.setVisibility(View.VISIBLE);
            if (adapter.getTransactionsCount() == 1) {
                backupDisclaimerTitle.setText(R.string.wallet_transactions_row_warning_backup);
            } else {
                backupDisclaimerTitle.setText(R.string.wallet_disclaimer_fragment_remind_backup);
            }
        } else {
            backupDisclaimerView.setVisibility(View.GONE);
        }
    }

    private Warning warning() {
        final int storageEncryptionStatus = devicePolicyManager.getStorageEncryptionStatus();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && (storageEncryptionStatus == DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE
                        || storageEncryptionStatus == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY))
            return Warning.STORAGE_ENCRYPTION;
        else
            return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    public void initFingerprintHelper() {
        if (fingerprintHelper == null) {
            fingerprintHelper = new FingerprintHelper(getActivity());
        }
        if (fingerprintHelper.init() && fingerprintHelper.isFingerprintEnabled()) {
            fingerprintGroup.setVisibility(View.VISIBLE);
            hideFingerprintError();
            fingerprintCancellationSignal = new CancellationSignal();
            fingerprintHelper.getPassword(fingerprintCancellationSignal, new FingerprintHelper.Callback() {
                @Override
                public void onSuccess(String savedPass) {
                    hideFingerprintError();
                    WalletLock.getInstance().setWalletLocked(false);
                }

                @Override
                public void onFailure(String message, boolean canceled, boolean exceededMaxAttempts) {
                    if (!canceled) {
                        showFingerprintError(exceededMaxAttempts);
                    }
                }

                @Override
                public void onHelp(int helpCode, String helpString) {
                    showFingerprintError(false);
                }
            });
        }
    }

    public void hideFingerprintError() {
        fingerprintIcon.setColorFilter(ContextCompat.getColor(getContext(),
                android.R.color.transparent));
        fingerprintText.setText(R.string.or_unlock_with_fingerprint);
    }

    public void showFingerprintError(boolean exceededMaxAttempts) {
        Animation shakeAnimation = AnimationUtils.loadAnimation(getActivity(), R.anim.shake);
        fingerprintIcon.startAnimation(shakeAnimation);
        fingerprintIcon.setColorFilter(ContextCompat.getColor(getActivity(), R.color.fg_error));
        if (exceededMaxAttempts) {
            fingerprintText.setText(R.string.unlock_with_fingerprint_error_max_attempts);
        } else {
            fingerprintText.setText(R.string.unlock_with_fingerprint_error);
        }
    }

    @Override
    public void onLockChanged(boolean locked) {
        if (locked) {
            showLockedView();
        } else {
            reloadTransactions();
        }
    }

}
