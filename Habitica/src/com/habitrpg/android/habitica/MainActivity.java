package com.habitrpg.android.habitica;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDoneException;
import android.databinding.DataBindingUtil;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.Snackbar;
import android.support.design.widget.TabLayout;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;
import com.habitrpg.android.habitica.callbacks.HabitRPGUserCallback;
import com.habitrpg.android.habitica.callbacks.TaskScoringCallback;
import com.habitrpg.android.habitica.databinding.ValueBarBinding;
import com.habitrpg.android.habitica.events.TaskRemovedEvent;
import com.habitrpg.android.habitica.events.ToggledInnStateEvent;
import com.habitrpg.android.habitica.events.commands.BuyRewardCommand;
import com.habitrpg.android.habitica.events.commands.DeleteTaskCommand;
import com.habitrpg.android.habitica.events.commands.OpenGemPurchaseFragmentCommand;
import com.habitrpg.android.habitica.prefs.PrefsActivity;
import com.habitrpg.android.habitica.ui.AvatarWithBarsViewModel;
import com.habitrpg.android.habitica.ui.MainDrawerBuilder;
import com.habitrpg.android.habitica.ui.fragments.BaseFragment;
import com.habitrpg.android.habitica.ui.fragments.GemsPurchaseFragment;
import com.habitrpg.android.habitica.userpicture.UserPicture;
import com.habitrpg.android.habitica.userpicture.UserPictureRunnable;
import com.magicmicky.habitrpgwrapper.lib.models.HabitRPGUser;
import com.magicmicky.habitrpgwrapper.lib.models.SuppressedModals;
import com.magicmicky.habitrpgwrapper.lib.models.TaskDirection;
import com.magicmicky.habitrpgwrapper.lib.models.TaskDirectionData;
import com.magicmicky.habitrpgwrapper.lib.models.tasks.ChecklistItem;
import com.magicmicky.habitrpgwrapper.lib.models.tasks.Days;
import com.magicmicky.habitrpgwrapper.lib.models.tasks.Task;
import com.magicmicky.habitrpgwrapper.lib.models.tasks.TaskTag;
import com.mikepenz.materialdrawer.AccountHeader;
import com.mikepenz.materialdrawer.Drawer;
import com.mikepenz.materialdrawer.model.PrimaryDrawerItem;
import com.mikepenz.materialdrawer.model.interfaces.IDrawerItem;
import com.mikepenz.materialdrawer.model.interfaces.IProfile;
import com.raizlabs.android.dbflow.runtime.transaction.BaseTransaction;
import com.raizlabs.android.dbflow.runtime.transaction.TransactionListener;
import com.raizlabs.android.dbflow.sql.builder.Condition;
import com.raizlabs.android.dbflow.sql.language.Delete;
import com.raizlabs.android.dbflow.sql.language.From;
import com.raizlabs.android.dbflow.sql.language.Select;
import com.raizlabs.android.dbflow.sql.language.Where;

import org.solovyev.android.checkout.ActivityCheckout;
import org.solovyev.android.checkout.Checkout;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import butterknife.Bind;
import butterknife.ButterKnife;
import de.greenrobot.event.EventBus;
import io.fabric.sdk.android.Fabric;
import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.Response;

public class MainActivity extends AppCompatActivity implements HabitRPGUserCallback.OnUserReceived,
                                                               TaskScoringCallback.OnTaskScored,
                                                               GemsPurchaseFragment.Listener {

    public enum SnackbarDisplayType {
        NORMAL, FAILURE, FAILURE_BLUE, DROP
    }

    BaseFragment activeFragment;

    @Bind(R.id.floating_menu_wrapper)
    FrameLayout floatingMenuWrapper;

    @Bind(R.id.toolbar)
    Toolbar toolbar;

    @Bind(R.id.detail_tabs)
    TabLayout detail_tabs;

    @Bind(R.id.avatar_with_bars)
    View avatar_with_bars;

    AccountHeader accountHeader;
    public Drawer drawer;

    protected HostConfig hostConfig;
    protected HabitRPGUser user;

    AvatarWithBarsViewModel avatarInHeader;

    APIHelper mAPIHelper;

    private MaterialDialog faintDialog;

    // Checkout needs to be in the Activity..
    public ActivityCheckout checkout = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Inject Controls
        ButterKnife.bind(this);

        // Initialize Crashlytics
        Crashlytics crashlytics = new Crashlytics.Builder()
                .core(new CrashlyticsCore.Builder().disabled(BuildConfig.DEBUG).build())
                .build();
        Fabric.with(this, crashlytics);

        this.hostConfig = PrefsActivity.fromContext(this);
        if(!HabiticaApplication.checkUserAuthentication(this, hostConfig))
            return;

        HabiticaApplication.ApiHelper = this.mAPIHelper = new APIHelper(hostConfig);

        new Select().from(HabitRPGUser.class).where(Condition.column("id").eq(hostConfig.getUser())).async().querySingle(userTransactionListener);

        if (toolbar != null) {
            setSupportActionBar(toolbar);

            ActionBar actionBar = getSupportActionBar();

            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(true);
                actionBar.setDisplayShowHomeEnabled(false);
                actionBar.setDisplayShowTitleEnabled(true);
                actionBar.setDisplayUseLogoEnabled(false);
                actionBar.setHomeButtonEnabled(false);
            }

        }

        avatarInHeader = new AvatarWithBarsViewModel(this, avatar_with_bars);
        accountHeader = MainDrawerBuilder.CreateDefaultAccountHeader(this).build();
        drawer = MainDrawerBuilder.CreateDefaultBuilderSettings(this, toolbar, accountHeader)
                .build();

        drawer.setSelectionAtPosition(1);

        // Create Checkout

        checkout = Checkout.forActivity(this, HabiticaApplication.Instance.getCheckout());

        checkout.start();

        EventBus.getDefault().register(this);

        mAPIHelper.retrieveUser(new HabitRPGUserCallback(this));
    }

    @Override
    public ActivityCheckout getActivityCheckout() {
        return checkout;
    }

    private void saveLoginInformation() {
        HabiticaApplication.User = user;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();
        boolean ans = editor.putString(getString(R.string.SP_username), user.getAuthentication().getLocalAuthentication().getUsername())
                .putString(getString(R.string.SP_email), user.getAuthentication().getLocalAuthentication().getEmail())
                .commit();

        if (!ans) {
            Log.e("SHARED PREFERENCES", "Shared Preferences Username and Email error");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    public void displayFragment(BaseFragment fragment) {
        fragment.setArguments(getIntent().getExtras());
        fragment.mAPIHelper = mAPIHelper;
        fragment.setUser(user);
        fragment.setActivity(this);
        fragment.setTabLayout(detail_tabs);
        fragment.setFloatingMenuWrapper(floatingMenuWrapper);

        if (getSupportFragmentManager().getFragments() == null) {
            getSupportFragmentManager().beginTransaction().add(R.id.fragment_container, fragment).commit();
        } else {
            getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, fragment).addToBackStack(null).commit();
        }
    }

    private TransactionListener<HabitRPGUser> userTransactionListener = new TransactionListener<HabitRPGUser>() {
        @Override
        public void onResultReceived(HabitRPGUser habitRPGUser) {
            user = habitRPGUser;
            setUserData(true);
        }

        @Override
        public boolean onReady(BaseTransaction<HabitRPGUser> baseTransaction) {
            return true;
        }

        @Override
        public boolean hasResult(BaseTransaction<HabitRPGUser> baseTransaction, HabitRPGUser habitRPGUser) {
            return true;
        }
    };

    private void setUserData(boolean fromLocalDb) {
        if (user != null) {
            Calendar mCalendar = new GregorianCalendar();
            TimeZone mTimeZone = mCalendar.getTimeZone();
            long offset = -TimeUnit.MINUTES.convert(mTimeZone.getRawOffset(), TimeUnit.MILLISECONDS);
            if (offset != user.getPreferences().getTimezoneOffset()) {
                Map<String, String> updateData = new HashMap<String, String>();
                updateData.put("preferences.timezoneOffset", String.valueOf(offset));
                mAPIHelper.apiService.updateUser(updateData, new HabitRPGUserCallback(this));
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateHeader();
                    updateSidebar();
                    saveLoginInformation();
                    if (activeFragment != null) {
                        activeFragment.updateUserData(user);
                    }
                }
            });

            if (!fromLocalDb) {
                // Update the oldEntries
                new Thread(new Runnable() {
                    public void run() {

                        // multiple crashes because user is null
                        if(user != null) {
                            ArrayList<Task> allTasks = new ArrayList<>();
                            allTasks.addAll(user.getDailys());
                            allTasks.addAll(user.getTodos());
                            allTasks.addAll(user.getHabits());
                            allTasks.addAll(user.getRewards());

                            loadAndRemoveOldTasks(user.getId(), allTasks);

                            ArrayList<ChecklistItem> allChecklistItems = new ArrayList<>();

                            for (Task t : allTasks) {
                                if (t.checklist != null) {
                                    allChecklistItems.addAll(t.checklist);
                                }
                            }

                            loadAndRemoveOldChecklists(allChecklistItems);
                        }
                    }
                }).start();
            }else{
                displayDeathDialogIfNeeded();
            }
        }
    }

    private void loadAndRemoveOldTasks(String userId, final List<Task> onlineEntries) {
        final ArrayList<String> onlineTaskIdList = new ArrayList<>();

        for (Task oTask : onlineEntries) {
            onlineTaskIdList.add(oTask.getId());
        }

        Where<Task> query = new Select().from(Task.class).where(Condition.column("user_id").eq(userId));
        try {
            if (query.count() != onlineEntries.size()) {

                // Load Database Tasks
                query.async().queryList(new TransactionListener<List<Task>>() {
                    @Override
                    public void onResultReceived(List<Task> tasks) {

                        ArrayList<Task> tasksToDelete = new ArrayList<Task>();

                        for (Task dbTask : tasks) {
                            if (!onlineTaskIdList.contains(dbTask.getId())) {
                                tasksToDelete.add(dbTask);
                            }
                        }

                        for (Task delTask : tasksToDelete) {
                            // TaskTag
                            new Delete().from(TaskTag.class).where(Condition.column("task_id").eq(delTask.getId())).async().execute();

                            // ChecklistItem
                            new Delete().from(ChecklistItem.class).where(Condition.column("task_id").eq(delTask.getId())).async().execute();

                            // Days
                            new Delete().from(Days.class).where(Condition.column("task_id").eq(delTask.getId())).async().execute();

                            // TASK
                            delTask.async().delete();

                            EventBus.getDefault().post(new TaskRemovedEvent(delTask.getId()));
                        }
                    }

                    @Override
                    public boolean onReady(BaseTransaction<List<Task>> baseTransaction) {
                        return false;
                    }

                    @Override
                    public boolean hasResult(BaseTransaction<List<Task>> baseTransaction, List<Task> tasks) {
                        return tasks != null && tasks.size() > 0;
                    }
                });
            }
        } catch (SQLiteDoneException e) {}
    }

    private void loadAndRemoveOldChecklists(final List<ChecklistItem> onlineEntries) {
        final ArrayList<String> onlineChecklistItemIdList = new ArrayList<>();

        for (ChecklistItem item : onlineEntries) {
            onlineChecklistItemIdList.add(item.getId());
        }

        From<ChecklistItem> query = new Select().from(ChecklistItem.class);
        try {
            if (query.count() != onlineEntries.size()) {

                // Load Database Checklist items
                query.async().queryList(new TransactionListener<List<ChecklistItem>>() {
                    @Override
                    public void onResultReceived(List<ChecklistItem> items) {

                        ArrayList<ChecklistItem> checkListItemsToDelete = new ArrayList<>();

                        for (ChecklistItem chItem : items) {
                            if (!onlineChecklistItemIdList.contains(chItem.getId())) {
                                checkListItemsToDelete.add(chItem);
                            }
                        }

                        for (ChecklistItem chItem : checkListItemsToDelete) {
                            chItem.async().delete();
                        }
                    }

                    @Override
                    public boolean onReady(BaseTransaction<List<ChecklistItem>> baseTransaction) {
                        return false;
                    }

                    @Override
                    public boolean hasResult(BaseTransaction<List<ChecklistItem>> baseTransaction, List<ChecklistItem> items) {
                        return items != null && items.size() > 0;
                    }
                });
            }
        } catch (SQLiteDoneException e) {}

    }

    private void updateUserAvatars() {
        avatarInHeader.updateData(user);
    }

    private void updateHeader() {
        updateUserAvatars();
        setTitle(user.getProfile().getName());

        android.support.v7.app.ActionBarDrawerToggle actionBarDrawerToggle = drawer.getActionBarDrawerToggle();

        if (actionBarDrawerToggle != null) {
            actionBarDrawerToggle.setDrawerIndicatorEnabled(true);
        }
    }

    public void updateSidebar() {
        final IProfile profile = accountHeader.getProfiles().get(0);
        if (user.getAuthentication() != null) {
            if (user.getAuthentication().getLocalAuthentication() != null) {
                profile.withEmail(user.getAuthentication().getLocalAuthentication().getEmail());
            }
        }
        profile.withName(user.getProfile().getName());
        new UserPicture(user, this, true, false).setPictureWithRunnable(new UserPictureRunnable() {
            public void run(Bitmap avatar) {
                profile.withIcon(avatar);
                accountHeader.updateProfile(profile);
            }
        });
        accountHeader.updateProfile(profile);

        IDrawerItem item = drawer.getDrawerItem(MainDrawerBuilder.SIDEBAR_SKILLS);
        if (user.getStats().getLvl() < 11 && item.isEnabled()) {
            IDrawerItem newItem = new PrimaryDrawerItem()
                    .withName(this.getString(R.string.sidebar_skills))
                    .withEnabled(false)
                    .withBadge(this.getString(R.string.unlock_lvl_10))
                    .withIdentifier(MainDrawerBuilder.SIDEBAR_SKILLS);
            drawer.updateItem(newItem);
        } else if (user.getStats().getLvl() >= 11 && !item.isEnabled()) {
            IDrawerItem newItem = new PrimaryDrawerItem()
                    .withName(this.getString(R.string.sidebar_skills))
                    .withIdentifier(MainDrawerBuilder.SIDEBAR_SKILLS);
            drawer.updateItem(newItem);
        }
    }

    @Override
    public void onUserReceived(HabitRPGUser user) {
        this.user = user;
        MainActivity.this.setUserData(false);
    }

    @Override
    public void onUserFail() {
    }

    public void setActiveFragment(BaseFragment fragment) {
        this.activeFragment = fragment;
        this.drawer.setSelectionAtPosition(this.activeFragment.fragmentSidebarPosition, false);
    }

    public void onBackPressed() {
        if (drawer.isDrawerOpen()) {
            drawer.closeDrawer();
        } else if (drawer.getDrawerLayout().isDrawerOpen(Gravity.RIGHT)) {
            drawer.getDrawerLayout().closeDrawer(Gravity.RIGHT);
        } else {
            super.onBackPressed();
        }
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        checkout.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onDestroy() {
        if (checkout != null)
            checkout.stop();

        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }

    public void showSnackbar(String content) {
        showSnackbar(content, SnackbarDisplayType.NORMAL);
    }

    public void showSnackbar(String content, SnackbarDisplayType displayType) {
        if (this.isFinishing()) {
            return;
        }
            Snackbar snackbar = Snackbar.make(floatingMenuWrapper, content, Snackbar.LENGTH_LONG);
        View snackbarView = snackbar.getView();

        if (displayType == SnackbarDisplayType.FAILURE) {
            //change Snackbar's background color;
            snackbarView.setBackgroundColor(ContextCompat.getColor(this, R.color.worse_10));
        } else if(displayType == SnackbarDisplayType.FAILURE_BLUE) {
            snackbarView.setBackgroundColor(ContextCompat.getColor(this, R.color.best_100));
        } else if (displayType == SnackbarDisplayType.DROP) {
            TextView tv = (TextView) snackbarView.findViewById(android.support.design.R.id.snackbar_text);
            tv.setMaxLines(5);
            snackbarView.setBackgroundColor(ContextCompat.getColor(this, R.color.best_10));
        }
        snackbar.show();
    }

    // region Events

    public void onEvent(ToggledInnStateEvent evt) {
        avatarInHeader.updateData(user);
    }

    public void onEvent(final BuyRewardCommand event) {
        final String rewardKey = event.Reward.getId();

        if (user.getStats().getGp() < event.Reward.getValue()) {
            this.showSnackbar("Not enough Gold", MainActivity.SnackbarDisplayType.FAILURE);
            return;
        }

        double newGp = user.getStats().getGp() - event.Reward.getValue();
        user.getStats().setGp(newGp);

        if (rewardKey.equals("potion")) {
            int currentHp = user.getStats().getHp().intValue();
            int maxHp = user.getStats().getMaxHealth();

            if (currentHp == maxHp) {
                this.showSnackbar("You don't need to buy an health potion", SnackbarDisplayType.FAILURE_BLUE);
                return;
            }
            double newHp = Math.min(user.getStats().getMaxHealth(), user.getStats().getHp() + 15);
            user.getStats().setHp(newHp);
        }

        if (event.Reward.specialTag != null && event.Reward.specialTag.equals("item")) {
            mAPIHelper.apiService.buyItem(event.Reward.getId(), new Callback<Void>() {

                @Override
                public void success(Void aVoid, Response response) {
                    if (!event.Reward.getId().equals("potion")) {
                        EventBus.getDefault().post(new TaskRemovedEvent(event.Reward.getId()));
                    } else {
                        // TODO Update gears in avatar
                    }

                    user.async().save();
                    MainActivity.this.setUserData(true);

                    showSnackbar(event.Reward.getText() + " successfully purchased!");
                }

                @Override
                public void failure(RetrofitError error) {
                    double newGp = user.getStats().getGp() + event.Reward.getValue();
                    user.getStats().setGp(newGp);
                    switch (rewardKey) {
                        case "potion":
                            double newHp = Math.max(0, user.getStats().getHp() - 15);
                            user.getStats().setHp(newHp);

                            break;
                        default:
                            break;
                    }

                    avatarInHeader.updateData(user);
                    user.async().save();

                    showSnackbar("Buy Reward Error " + event.Reward.getText(), MainActivity.SnackbarDisplayType.FAILURE);
                }
            });
        } else {
            // user created Rewards
            mAPIHelper.updateTaskDirection(rewardKey, TaskDirection.down, new TaskScoringCallback(this, rewardKey));
        }

        avatarInHeader.updateData(user);
        user.async().save();
    }


    public void onEvent(final DeleteTaskCommand cmd) {
        mAPIHelper.apiService.deleteTask(cmd.TaskIdToDelete, new Callback<Void>() {
            @Override
            public void success(Void aVoid, Response response) {
                EventBus.getDefault().post(new TaskRemovedEvent(cmd.TaskIdToDelete));
            }

            @Override
            public void failure(RetrofitError error) {

            }
        });
    }

    public void onEvent(OpenGemPurchaseFragmentCommand cmd) {
        drawer.setSelection(MainDrawerBuilder.SIDEBAR_PURCHASE);
    }

    // endregion

    @Override
    public void onTaskDataReceived(TaskDirectionData data, Task task) {
        if (task.type.equals("reward")) {

            showSnackbar(task.getText() + " successfully purchased!");

        } else {

            if(user != null){
                notifyUser(data.getExp(), data.getHp(), data.getGp(), data.getLvl(), data.getDelta());
            }

            showSnackBarForDataReceived(data);
        }
    }

    private void showSnackBarForDataReceived(TaskDirectionData data) {
        if (data.get_tmp() != null) {
            if (data.get_tmp().getDrop() != null) {
                this.showSnackbar(data.get_tmp().getDrop().getDialog(), SnackbarDisplayType.DROP);
            }
        }
    }

    private void notifyUser(double xp, double hp, double gold,
                            int lvl, double delta) {
        StringBuilder message = new StringBuilder();
        MainActivity.SnackbarDisplayType displayType = MainActivity.SnackbarDisplayType.NORMAL;
        if (lvl > user.getStats().getLvl()) {
            displayLevelUpDialog(lvl);

            this.mAPIHelper.retrieveUser(new HabitRPGUserCallback(this));
            user.getStats().setLvl((int) lvl);

            this.showSnackbar(message.toString());
        } else {
            com.magicmicky.habitrpgwrapper.lib.models.Stats stats = user.getStats();

            if (xp > stats.getExp()) {
                message.append(" + ").append(round(xp - stats.getExp(), 2)).append(" XP");
                user.getStats().setExp(xp);
            }
            if (hp != stats.getHp()) {
                displayType = MainActivity.SnackbarDisplayType.FAILURE;
                message.append(" - ").append(round(stats.getHp() - hp, 2)).append(" HP");
                user.getStats().setHp(hp);
            }
            if (gold > stats.getGp()) {
                message.append(" + ").append(round(gold - stats.getGp(), 2)).append(" GP");
                stats.setGp(gold);
            } else if (gold < stats.getGp()) {
                displayType = MainActivity.SnackbarDisplayType.FAILURE;
                message.append(" - ").append(round(stats.getGp() - gold, 2)).append(" GP");
                stats.setGp(gold);
            }
            this.showSnackbar(message.toString(), displayType);
        }
        setUserData(true);
    }

    @Override
    public void onTaskScoringFailed() {

    }

    static public Double round(Double value, int n) {
        return (Math.round(value * Math.pow(10, n))) / (Math.pow(10, n));
    }

    private void displayDeathDialogIfNeeded() {

        if (user.getStats().getHp() > 0) {
            return;
        }

        if (this.faintDialog == null) {
            this.faintDialog = new MaterialDialog.Builder(this)
                    .title(R.string.faint_header)
                    .customView(R.layout.faint_dialog, true)
                    .positiveText(R.string.faint_button)
                    .positiveColorRes(R.color.worse_100)
                    .dismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            faintDialog = null;
                            mAPIHelper.reviveUser(new HabitRPGUserCallback(MainActivity.this));
                        }
                    })
                    .cancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            faintDialog = null;
                        }
                    })
                    .build();

            View customView = this.faintDialog.getCustomView();
            if (customView != null) {
                View hpBarView = customView.findViewById(R.id.hpBar);

                ValueBarBinding hpBar = DataBindingUtil.bind(hpBarView);
                hpBar.setPartyMembers(true);
                AvatarWithBarsViewModel.setHpBarData(hpBar, user.getStats(), this);

                ImageView avatarView = (ImageView) customView.findViewById(R.id.avatarView);
                UserPicture userPicture = new UserPicture(user, this, false, false);
                userPicture.setPictureOn(avatarView);
            }

            this.faintDialog.show();
        }
    }

    private void displayLevelUpDialog(int level) {
        SuppressedModals suppressedModals = user.getPreferences().getSuppressModals();
        if (suppressedModals != null) {
            if (suppressedModals.getLevelUp()) {
                return;
            }
        }

        MaterialDialog dialog = new MaterialDialog.Builder(this)
                .title(R.string.levelup_header)
                .customView(R.layout.levelup_dialog, true)
                .positiveText(R.string.levelup_button)
                .positiveColorRes(R.color.brand_100)
                .build();

        View customView = dialog.getCustomView();
        if (customView != null) {
            TextView detailView = (TextView) customView.findViewById(R.id.levelupDetail);
            detailView.setText(this.getString(R.string.levelup_detail, level));
            ImageView avatarView = (ImageView) customView.findViewById(R.id.avatarView);
            UserPicture userPicture = new UserPicture(user, this, false, false);
            userPicture.setPictureOn(avatarView);
        }

        dialog.show();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if(keyCode == KeyEvent.KEYCODE_MENU && drawer != null){
            drawer.openDrawer();
            return true;
        }

        return super.onKeyUp(keyCode, event);
    }
}
