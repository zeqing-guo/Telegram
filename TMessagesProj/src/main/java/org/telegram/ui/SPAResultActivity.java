package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Toast;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SPAConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.volley.Request;
import org.telegram.messenger.volley.RequestQueue;
import org.telegram.messenger.volley.Response;
import org.telegram.messenger.volley.VolleyError;
import org.telegram.messenger.volley.toolbox.StringRequest;
import org.telegram.messenger.volley.toolbox.Volley;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Adapters.BaseFragmentAdapter;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Created by zqguo on 16-12-1.
 */

public class SPAResultActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private ListAdapter listAdapter;
    int listSize = 0;
    ArrayList<String> settings = new ArrayList<>(4);
    ArrayList<String> settingsNames = new ArrayList<>(4);
    String settingsString = "setttings:";
    String users;
    final String[] LAST_SEEN_SETTING = {"Everybody", "My Contacts", "Nobody"};
    final String[] PASSCODE_LOCK_SETTING = {"on", "off"};
    int averagePolicy = 0;
    int maxMinPolicy = 0;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();

        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(SPAConfig.SPA_PREFERENCE, Activity.MODE_PRIVATE);
        if (preferences.contains("last_seen_setting_result")) {
            int index = preferences.getInt("last_seen_setting_result", 0);
            String setting = LAST_SEEN_SETTING[index];
            settings.add(setting);
            settingsNames.add("Last Seen");
            settingsString += "last_seen" + " " + index;
            listSize += 1;
        }
        if (preferences.contains("passcode_lock_setting_result")) {
            int index = preferences.getInt("passcode_lock_setting_result", 0);
            String setting = PASSCODE_LOCK_SETTING[index];
            settings.add(setting);
            settingsNames.add("Passcode Lock");
            settingsString += "passcode_lock" + " " + index;
            listSize += 1;
        }
        if (preferences.contains("average_result")) {
            averagePolicy = preferences.getInt("average_result", 0);
            settings.add("" + averagePolicy);
            settingsNames.add("Average");
            settingsString += "average" + " " + averagePolicy;
            listSize += 1;
        }
        if (preferences.contains("maximum_minimum_policy_result")) {
            maxMinPolicy = preferences.getInt("maximum_minimum_policy_result", 0);
            settings.add("" + maxMinPolicy);
            settingsNames.add("Maximum or Minimum");
            settingsString += "max_min" + " " + maxMinPolicy;
            listSize += 1;
        }
        users = preferences.getString("spa_respondents", "");

        NotificationCenter.getInstance().addObserver(this, NotificationCenter.spaSettings);

        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.spaSettings);
    }

    @Override
    public void didReceivedNotification(int id, Object... args) {

        if (id == NotificationCenter.spaSettings) {
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        }
    }

    @Override
    public View createView(final Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString("ReceivedSPARequest", R.string.SPAReceivedRequest));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listAdapter = new ListAdapter(context);

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(0xfff0f0f0);

        ListView listView = new ListView(context);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setVerticalScrollBarEnabled(false);
        listView.setDrawSelectorOnTop(true);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, final int i, long l) {
                if (i < listSize) {
                    Log.v("SPA", settingsNames.get(i) + ": " + settings.get(i));
                } else {
                    // response format: "ok: last_seen,1 passcode_lock,1"
                    StringRequest stringRequest = new StringRequest(
                            Request.Method.POST,
                            SPAConfig.sendSPAResult,
                            new Response.Listener<String>() {
                                @Override
                                public void onResponse(String response) {
                                    if (response.startsWith("ok")) {
                                        CharSequence text = "Success";
                                        int duration = Toast.LENGTH_SHORT;
                                        Toast toast = Toast.makeText(context, text, duration);
                                        toast.show();
                                    } else {
                                        CharSequence text = "Invalid response";
                                        int duration = Toast.LENGTH_SHORT;
                                        Toast toast = Toast.makeText(context, text, duration);
                                        toast.show();
                                    }
                                }
                            },
                            new Response.ErrorListener() {
                                @Override
                                public void onErrorResponse(VolleyError error) {
                                    Log.v("SPA", "SPA friend list activity cannot connect keymanager!");
                                }
                            }) {
                        protected Map<String, String> getParams() {
                            Map<String, String> params = new HashMap<>();
                            params.put("content", settingsString);
                            params.put("ids", users);
                            TLRPC.User user = UserConfig.getCurrentUser();
                            String value;
                            if (user != null && user.phone != null && user.phone.length() != 0) {
                                value = user.phone;
                            } else {
                                value = LocaleController.getString("NumberUnknown", R.string.NumberUnknown);
                            }
                            params.put("requester", value);
                            return params;
                        }
                    };
                    RequestQueue queue = Volley.newRequestQueue(context);
                    queue.add(stringRequest);
                }
            }
        });

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private class ListAdapter extends BaseFragmentAdapter {
        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int i) {
            return i <= listSize;
        }

        @Override
        public int getCount() {
            return listSize;
        }

        @Override
        public Object getItem(int i) {
            return null;
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            if (view == null) {
                view = new TextSettingsCell(mContext);
                view.setBackgroundColor(0xffffffff);
            }
            TextSettingsCell textCell = (TextSettingsCell) view;

            if (i < listSize) {
                textCell.setText(settingsNames.get(i) + ": " + settings.get(i), true);
            } else {
                textCell.setText("Send SPA Result", true);
            }

            return view;
        }

        @Override
        public int getItemViewType(int position) {
            return 0;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }
    }
}
